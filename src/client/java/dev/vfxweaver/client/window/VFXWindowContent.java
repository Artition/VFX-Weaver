package dev.vfxweaver.client.window;

import com.mojang.blaze3d.platform.NativeImage;
import dev.vfxweaver.util.VFXLog;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The picture content of one aux window: a resource image decoded and uploaded once into the
 * window's own GL context, drawn one frame at a time.
 *
 * <p><b>Addressing, not pixels.</b> The source is addressed the way the {@code surface_pattern}
 * ground-image effect addresses it: an {@link Identifier} (a {@code .png} texture completed when the
 * suffix is missing) whose sheet is read row-major, frame 0 top-left, frame
 * {@code columns * rows - 1} bottom-right. Only the addressing is reused - the game's texture lives
 * in its own backend, so the pixels are decoded here and uploaded into this window's context. The
 * frame count comes from the caller; the sheet is a single horizontal strip, so {@code columns}
 * equals the frame count and {@code rows} is 1.
 *
 * <p><b>One upload per source.</b> {@link #load(Identifier, int)} and {@link #reload()} funnel
 * through one decode-and-upload path; {@link #drawFrame(int)} only binds the uploaded texture and
 * shifts its UV rect, so there is no per-frame readback or upload. A missing, undecodable or
 * too-small source leaves the last successfully uploaded frame in place and warns once - it never
 * throws.
 *
 * <p><b>Own context.</b> This window has its own GL context (see {@link VFXWindow}); every GL call
 * here saves the current context with {@code glfwGetCurrentContext}, makes the window's context
 * current, does its work, and restores the saved one, so the game's context is never leaked. The
 * texture dies with the context when the window is closed.
 *
 * <p><b>Render thread only.</b> Every method calls GLFW and/or GL, so every method must run on the
 * render thread; GLFW is not thread-safe and a context may only be current on one thread at a time.
 */
public final class VFXWindowContent {

	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/window");

	private final VFXWindow window;
	private Identifier sourceId;
	private int requestedFrames;
	private int frameCount;
	private int columns;
	private int rows;
	private int frameWidth;
	private int frameHeight;
	private int texture;

	/**
	 * Creates the content owner for one window. Must run on the render thread.
	 *
	 * @param window the window whose own GL context the decoded texture is uploaded into and drawn
	 *               from; never {@code null}
	 */
	public VFXWindowContent(final VFXWindow window) {
		this.window = window;
	}

	/**
	 * Loads a picture source and uploads it once into the window's GL context. Calling it again
	 * replaces the previous source and texture; a source that cannot be read or decoded leaves the
	 * previous texture untouched. Must run on the render thread.
	 *
	 * @param textureId the resource id of the image (a {@code .png} suffix is added when missing)
	 * @param frames    the number of frames in the horizontal strip; clamped to at least 1
	 */
	public void load(final Identifier textureId, final int frames) {
		this.sourceId = textureId;
		this.requestedFrames = Math.max(1, frames);
		uploadSource();
	}

	/**
	 * Re-reads and re-uploads the current source, for a resource reload ({@code F3+T}) that changed
	 * the image on disk. A no-op before the first {@link #load(Identifier, int)}; a failed re-read
	 * keeps the last uploaded frame. Must run on the render thread.
	 */
	public void reload() {
		if (this.sourceId == null) {
			return;
		}
		uploadSource();
	}

	/**
	 * Draws one frame of the picture, filling the window. The frame is wrapped into the frame count,
	 * so any index is safe. Nothing is decoded or uploaded here - only the UV rect shifts. Must run
	 * on the render thread.
	 *
	 * @param frameIndex the frame to draw; negative values wrap from the end
	 */
	public void drawFrame(final int frameIndex) {
		final long previous = GLFW.glfwGetCurrentContext();
		GLFW.glfwMakeContextCurrent(this.window.handle());
		try {
			drawCurrent(frameIndex);
		} finally {
			GLFW.glfwMakeContextCurrent(previous);
		}
	}

	private void drawCurrent(final int frameIndex) {
		final int[] width = new int[1];
		final int[] height = new int[1];
		GLFW.glfwGetFramebufferSize(this.window.handle(), width, height);
		GL11.glViewport(0, 0, Math.max(1, width[0]), Math.max(1, height[0]));
		GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
		if (this.texture == 0 || this.frameCount <= 0 || this.columns <= 0 || this.rows <= 0) {
			return;
		}
		final int frame = Math.floorMod(frameIndex, this.frameCount);
		final int column = frame % this.columns;
		final int row = frame / this.columns;
		final float u0 = (float) column / (float) this.columns;
		final float v0 = (float) row / (float) this.rows;
		final float u1 = (float) (column + 1) / (float) this.columns;
		final float v1 = (float) (row + 1) / (float) this.rows;
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.texture);
		GL11.glBegin(GL11.GL_QUADS);
		GL11.glTexCoord2f(u0, v1);
		GL11.glVertex2f(-1.0F, -1.0F);
		GL11.glTexCoord2f(u1, v1);
		GL11.glVertex2f(1.0F, -1.0F);
		GL11.glTexCoord2f(u1, v0);
		GL11.glVertex2f(1.0F, 1.0F);
		GL11.glTexCoord2f(u0, v0);
		GL11.glVertex2f(-1.0F, 1.0F);
		GL11.glEnd();
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glDisable(GL11.GL_BLEND);
	}

	private void uploadSource() {
		final Identifier pngId = withPng(this.sourceId);
		final Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(pngId);
		if (resource.isEmpty()) {
			VFXLog.warnOnce(LOGGER, "window:texture:" + pngId, "window texture '{}' could not be resolved; keeping the last frame", pngId);
			return;
		}
		final int[] pixels;
		final int imageWidth;
		final int imageHeight;
		try (InputStream input = resource.get().open(); NativeImage image = NativeImage.read(input)) {
			imageWidth = image.getWidth();
			imageHeight = image.getHeight();
			pixels = image.getPixels();
		} catch (final IOException | RuntimeException e) {
			VFXLog.warnOnce(LOGGER, "window:texture:" + pngId, "window texture '{}' could not be decoded ({}); keeping the last frame", pngId, e.getMessage());
			return;
		}
		final int frames = Math.max(1, this.requestedFrames);
		final int columns = frames;
		final int rows = 1;
		if (imageWidth <= 0 || imageHeight <= 0 || imageWidth < columns || imageHeight < rows) {
			VFXLog.warnOnce(LOGGER, "window:texture:" + pngId, "window texture '{}' is too small for {} frame(s); keeping the last frame", pngId, frames);
			return;
		}
		final int frameWidth = imageWidth / columns;
		final int frameHeight = imageHeight / rows;
		final ByteBuffer buffer = MemoryUtil.memAlloc(imageWidth * imageHeight * 4);
		final long previous = GLFW.glfwGetCurrentContext();
		GLFW.glfwMakeContextCurrent(this.window.handle());
		try {
			for (final int pixel : pixels) {
				final int alpha = (pixel >>> 24) & 0xFF;
				buffer.put((byte) (((pixel & 0xFF) * alpha + 127) / 255));
				buffer.put((byte) ((((pixel >>> 8) & 0xFF) * alpha + 127) / 255));
				buffer.put((byte) ((((pixel >>> 16) & 0xFF) * alpha + 127) / 255));
				buffer.put((byte) alpha);
			}
			buffer.flip();
			if (this.texture != 0) {
				GL11.glDeleteTextures(this.texture);
			}
			this.texture = GL11.glGenTextures();
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.texture);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, imageWidth, imageHeight, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
			this.frameCount = columns;
			this.columns = columns;
			this.rows = rows;
			this.frameWidth = frameWidth;
			this.frameHeight = frameHeight;
		} finally {
			MemoryUtil.memFree(buffer);
			GLFW.glfwMakeContextCurrent(previous);
		}
	}

	private static Identifier withPng(final Identifier id) {
		return id.getPath().endsWith(".png") ? id : id.withSuffix(".png");
	}
}

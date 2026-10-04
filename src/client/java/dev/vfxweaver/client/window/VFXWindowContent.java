package dev.vfxweaver.client.window;

import com.mojang.blaze3d.platform.NativeImage;
import dev.vfxweaver.client.render.VFXImageRegistry;
import dev.vfxweaver.util.VFXLog;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GLCapabilities;
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
 * in its own backend, so the pixels are decoded here and uploaded into this window's context.
 *
 * <p><b>Sheet layout.</b> The decoded picture is wrapped in a {@link VFXWindowFrames}: a caller
 * image and a pack PNG without a {@code .mcmeta} are one horizontal strip of {@code frames}
 * columns driven by {@code frame_time}, while a pack PNG with a sibling {@code .mcmeta} animates by
 * the vanilla format (multi-row sheet, per-frame times). The frame to draw is picked from elapsed
 * ticks, so this content never precomputes a frame index.
 *
 * <p><b>One upload per source.</b> {@link #load(Identifier, int)} and {@link #reload()} funnel
 * through one decode-and-upload path; {@link #drawFrame(long, float, float, float, float, float)}
 * only binds the uploaded texture and shifts its UV rect, so there is no per-frame readback or
 * upload. A missing, undecodable or too-small source leaves the last successfully uploaded frame in
 * place and warns once - it never throws.
 *
 * <p><b>Placement.</b> {@link #drawFrame(long, float, float, float, float, float)} draws the picture
 * into a sub-rectangle of the canvas instead of filling it: the four values are normalized window
 * coordinates in {@code [0, 1]} with the origin at the <b>bottom-left</b> ({@code x} from the left
 * edge, {@code y} from the bottom edge), which maps to NDC with {@code ndc = rect * 2 - 1}. The
 * caller (the controller) owns the work-area mapping and hands this method the already-mapped,
 * already-flipped rect.
 *
 * <p><b>Own context.</b> This window has its own GL context (see {@link VFXWindow}); every GL call
 * here saves the current context with {@code glfwGetCurrentContext}, makes the window's context
 * current, does its work, and restores the saved one, so the game's context is never leaked. The
 * texture dies with the context when the window is closed.
 *
 * <p><b>Closed window.</b> All three entry points are no-ops once {@link VFXWindow#closed()} is
 * true, so a stale draw or reload after the window's context was destroyed cannot call GLFW or GL on
 * a dead handle.
 *
 * <p><b>Render thread only.</b> Every method calls GLFW and/or GL, so every method must run on the
 * render thread; GLFW is not thread-safe and a context may only be current on one thread at a time.
 */
public final class VFXWindowContent {

	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/window");

	private final VFXWindow window;
	private Identifier sourceId;
	private int requestedFrames;
	private int frameWidth;
	private int frameHeight;
	private int texture;
	private VFXWindowFrames sheet;

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
		if (this.window.closed()) {
			return;
		}
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
		if (this.window.closed() || this.sourceId == null) {
			return;
		}
		uploadSource();
	}

	/**
	 * Draws one frame of the picture into a sub-rectangle of the canvas. The frame is picked from
	 * the elapsed ticks (a {@code .mcmeta} animation walks its table, a plain strip advances every
	 * {@code frameTime} ticks) and wrapped into the sheet's slot count, so any tick count is safe.
	 * Nothing is decoded or uploaded here - only the UV rect and the quad's rectangle change. Must
	 * run on the render thread.
	 *
	 * @param timeTicks elapsed ticks since the window opened, for a {@code .mcmeta} animation or a
	 *                  {@code frame_time} strip
	 * @param frameTime ticks per frame for a sheet without {@code .mcmeta}; {@code 0} holds frame 0
	 * @param rectX     the rectangle's left edge, a normalized {@code [0, 1]} window coordinate
	 * @param rectY     the rectangle's bottom edge, a normalized {@code [0, 1]} window coordinate
	 *                  (the origin is the bottom-left of the canvas)
	 * @param rectW     the rectangle's width, a normalized {@code [0, 1]} window coordinate
	 * @param rectH     the rectangle's height, a normalized {@code [0, 1]} window coordinate
	 */
	public void drawFrame(final long timeTicks, final float frameTime, final float rectX, final float rectY, final float rectW, final float rectH) {
		if (this.window.closed()) {
			return;
		}
		final long previous = GLFW.glfwGetCurrentContext();
		final GLCapabilities previousCaps = GL.getCapabilities();
		GLFW.glfwMakeContextCurrent(this.window.handle());
		GL.setCapabilities(this.window.caps());
		try {
			drawCurrent(timeTicks, frameTime, rectX, rectY, rectW, rectH);
		} finally {
			GLFW.glfwMakeContextCurrent(previous);
			GL.setCapabilities(previousCaps);
		}
	}

	private void drawCurrent(final long timeTicks, final float frameTime, final float rectX, final float rectY, final float rectW, final float rectH) {
		final int[] width = new int[1];
		final int[] height = new int[1];
		GLFW.glfwGetFramebufferSize(this.window.handle(), width, height);
		GL11.glViewport(0, 0, Math.max(1, width[0]), Math.max(1, height[0]));
		GL11.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
		GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
		if (this.texture == 0 || this.sheet == null || this.frameWidth <= 0 || this.frameHeight <= 0
			|| rectW <= 0.0F || rectH <= 0.0F) {
			return;
		}
		final int columns = this.sheet.columns();
		final int rows = this.sheet.rows();
		final int frame = this.sheet.animated()
			? this.sheet.frameAt(timeTicks)
			: (frameTime > 0.0F ? (int) (timeTicks / frameTime) : 0);
		// frame is a slot in the sheet grid (a .mcmeta frames list can repeat or reorder slots), so
		// wrap by the slot count, not by the playback-table length.
		final int wrapped = Math.floorMod(frame, columns * rows);
		final int column = wrapped % columns;
		final int row = wrapped / columns;
		final float u0 = (float) (column * this.frameWidth) / (float) this.sheet.imageWidth();
		final float v0 = (float) (row * this.frameHeight) / (float) this.sheet.imageHeight();
		final float u1 = (float) (column * this.frameWidth + this.frameWidth) / (float) this.sheet.imageWidth();
		final float v1 = (float) (row * this.frameHeight + this.frameHeight) / (float) this.sheet.imageHeight();
		// Fill the rect: the controller already sized it from size_w : size_h (the picture's own
		// proportions in one common unit), so any stretch is deliberate and the monitor's aspect
		// never distorts the shape.
		final float left = rectX * 2.0F - 1.0F;
		final float right = (rectX + rectW) * 2.0F - 1.0F;
		final float bottom = rectY * 2.0F - 1.0F;
		final float top = (rectY + rectH) * 2.0F - 1.0F;
		GL11.glEnable(GL11.GL_BLEND);
		GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GL11.glEnable(GL11.GL_TEXTURE_2D);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, this.texture);
		GL11.glBegin(GL11.GL_QUADS);
		GL11.glTexCoord2f(u0, v1);
		GL11.glVertex2f(left, bottom);
		GL11.glTexCoord2f(u1, v1);
		GL11.glVertex2f(right, bottom);
		GL11.glTexCoord2f(u1, v0);
		GL11.glVertex2f(right, top);
		GL11.glTexCoord2f(u0, v0);
		GL11.glVertex2f(left, top);
		GL11.glEnd();
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		GL11.glDisable(GL11.GL_BLEND);
	}

	private void uploadSource() {
		final Identifier pngId = withPng(this.sourceId);
		final int[] pixels;
		final int imageWidth;
		final int imageHeight;
		final Optional<Resource> resource;
		// A caller-supplied image (VFXAPI.registerImage) has no pack file: its pixels come from the
		// registry. It still uploads through this method's premultiply-and-upload path so a
		// registered image and a packed one are drawn identically.
		final VFXImageRegistry.Image registered = VFXImageRegistry.get().get(pngId);
		if (registered != null) {
			imageWidth = registered.width();
			imageHeight = registered.height();
			pixels = registered.argb();
			resource = Optional.empty();
		} else {
			resource = Minecraft.getInstance().getResourceManager().getResource(pngId);
			if (resource.isEmpty()) {
				VFXLog.warnOnce(LOGGER, "window:texture:" + pngId, "window texture '{}' could not be resolved; keeping the last frame", pngId);
				return;
			}
			try (InputStream input = resource.get().open(); NativeImage image = NativeImage.read(input)) {
				imageWidth = image.getWidth();
				imageHeight = image.getHeight();
				pixels = image.getPixels();
			} catch (final IOException | RuntimeException e) {
				VFXLog.warnOnce(LOGGER, "window:texture:" + pngId, "window texture '{}' could not be decoded ({}); keeping the last frame", pngId, e.getMessage());
				return;
			}
		}
		final VFXWindowFrames sheet;
		if (registered != null) {
			// A caller-supplied image has no .mcmeta: a still, or the strip hint if the caller split it.
			sheet = this.requestedFrames > 1
				? VFXWindowFrames.strip(imageWidth, imageHeight, this.requestedFrames)
				: VFXWindowFrames.still(imageWidth, imageHeight);
		} else {
			sheet = this.readPack(pngId, imageWidth, imageHeight, resource);
		}
		if (sheet.imageWidth() <= 0 || sheet.imageHeight() <= 0
			|| sheet.frameWidth() <= 0 || sheet.frameHeight() <= 0) {
			VFXLog.warnOnce(LOGGER, "window:texture:" + pngId, "window texture '{}' is too small; keeping the last frame", pngId);
			return;
		}
		final ByteBuffer buffer = MemoryUtil.memAlloc(imageWidth * imageHeight * 4);
		final long previous = GLFW.glfwGetCurrentContext();
		final GLCapabilities previousCaps = GL.getCapabilities();
		GLFW.glfwMakeContextCurrent(this.window.handle());
		GL.setCapabilities(this.window.caps());
		try {
			for (final int pixel : pixels) {
				// The source ints are ARGB (0xAARRGGBB): NativeImage.getPixels() returns that order
				// and VFXAPI.registerImage documents it. GL wants R,G,B,A bytes, so read R from bits
				// 16-23 - reading R from the low byte swapped red and blue (a blue-tinted picture).
				final int alpha = (pixel >>> 24) & 0xFF;
				buffer.put((byte) ((((pixel >>> 16) & 0xFF) * alpha + 127) / 255));
				buffer.put((byte) ((((pixel >>> 8) & 0xFF) * alpha + 127) / 255));
				buffer.put((byte) (((pixel & 0xFF) * alpha + 127) / 255));
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
			this.frameWidth = sheet.frameWidth();
			this.frameHeight = sheet.frameHeight();
			// The pixels now live only in GL; the draw path keeps the frame table, not the image.
			this.sheet = sheet;
		} finally {
			MemoryUtil.memFree(buffer);
			GLFW.glfwMakeContextCurrent(previous);
			GL.setCapabilities(previousCaps);
		}
	}

	/**
	 * Builds the frames for a pack texture: the vanilla {@code .mcmeta} sheet when the PNG has one,
	 * otherwise the datapack horizontal strip of {@link #requestedFrames} columns.
	 *
	 * @param pngId       the resolved source id, for the warning key
	 * @param imageWidth  the image width in pixels
	 * @param imageHeight the image height in pixels
	 * @param resource    the resolved pack resource, present for a pack texture
	 * @return the decoded frames, never {@code null}
	 */
	private VFXWindowFrames readPack(final Identifier pngId, final int imageWidth,
		final int imageHeight, final Optional<Resource> resource) {
		Optional<AnimationMetadataSection> meta = Optional.empty();
		if (resource.isPresent()) {
			try {
				meta = resource.get().metadata().getSection(AnimationMetadataSection.TYPE);
			} catch (final IOException e) {
				VFXLog.warnOnce(LOGGER, "window:meta:" + pngId, "window texture '{}' .mcmeta could not be read ({}); treating it as a still", pngId, e.getMessage());
			}
		}
		if (meta.isEmpty()) {
			return this.requestedFrames > 1
				? VFXWindowFrames.strip(imageWidth, imageHeight, this.requestedFrames)
				: VFXWindowFrames.still(imageWidth, imageHeight);
		}
		return VFXWindowFrames.fromMetadata(meta.get(), imageWidth, imageHeight, this.requestedFrames, pngId.toString());
	}

	private static Identifier withPng(final Identifier id) {
		return id.getPath().endsWith(".png") ? id : id.withSuffix(".png");
	}
}

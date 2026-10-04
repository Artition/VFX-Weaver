package dev.vfxweaver.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import dev.vfxweaver.util.VFXLog;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client registry of caller-supplied images ({@code VFXAPI.registerImage}): a mod renders an image
 * it cannot ship as a pack texture (an item icon, a live preview) and hands over ARGB pixels; this
 * makes that id usable everywhere a packed texture is.
 *
 * <p><b>Two consumers, one id.</b> The pixels are kept on the CPU and also pushed as a
 * {@link DynamicTexture} into the game's {@link net.minecraft.client.renderer.texture.TextureManager}.
 * The texture manager is what the post-processing pattern paths read
 * ({@code getTexture(id).getTextureView()}), so {@code surface_pattern}, {@code sky_pattern} and the
 * field textures resolve a registered id with no change of their own; an aux window cannot use that
 * texture (it has its own GL context and does not share with the game), so it reads the CPU copy
 * instead through {@link #get(Identifier)}.</p>
 *
 * <p><b>Render thread only.</b> Registering builds a {@code NativeImage} and uploads it, so every
 * method runs on the render thread; the public API queues off-thread calls here. Both layers are
 * bounded by {@link #MAX_IMAGES} so a caller cannot grow the registry without limit.</p>
 */
public final class VFXImageRegistry {

	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/image");
	private static final VFXImageRegistry INSTANCE = new VFXImageRegistry();

	/** Cap on registered images; a caller that exceeds it loses the oldest registration. */
	public static final int MAX_IMAGES = 256;

	/** A stored image: the pixels a window uploads itself, in the ARGB order it reads. */
	public record Image(int width, int height, int[] argb) {
	}

	private final Map<Identifier, Image> images = new HashMap<>();

	private VFXImageRegistry() {
	}

	public static VFXImageRegistry get() {
		return INSTANCE;
	}

	/**
	 * Registers (or replaces) an image and its texture. Must run on the render thread.
	 *
	 * @param id     the resource id to serve the image under
	 * @param width  width in pixels; a value below 1 is rejected
	 * @param height height in pixels; a value below 1 is rejected
	 * @param argb   {@code width * height} ARGB pixels, row-major, row 0 the top row; a short
	 *               array is rejected
	 * @return {@code true} when the image was stored and its texture registered
	 */
	public boolean register(final Identifier id, final int width, final int height, final int[] argb) {
		if (width < 1 || height < 1 || argb == null || argb.length < width * height) {
			VFXLog.warnOnce(LOGGER, "image:bad:" + id, "Ignoring image '{}': {}x{} needs {} pixels, got {}", id, width, height, width * height, argb == null ? 0 : argb.length);
			return false;
		}
		if (this.images.size() >= MAX_IMAGES && !this.images.containsKey(id)) {
			VFXLog.warnOnce(LOGGER, "image:full", "Image registry is full ({}); '{}' dropped", MAX_IMAGES, id);
			return false;
		}
		final int[] copy = new int[width * height];
		System.arraycopy(argb, 0, copy, 0, copy.length);

		final NativeImage nativeImage = new NativeImage(width, height, false);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				// setPixel takes ARGB (it converts to the image's ABGR storage itself); the caller's
				// pixels are ARGB too, so the game texture shows the colours the caller authored.
				nativeImage.setPixel(x, y, copy[y * width + x]);
			}
		}
		final DynamicTexture texture = new DynamicTexture(() -> "vfxweaver/image/" + id, nativeImage);
		texture.upload();

		final net.minecraft.client.renderer.texture.TextureManager manager = Minecraft.getInstance().getTextureManager();
		// register() only maps the id; a previous texture for the same id must be released first
		// or its GPU texture leaks.
		if (this.images.containsKey(id)) {
			manager.release(id);
		}
		manager.register(id, texture);
		this.images.put(id, new Image(width, height, copy));
		return true;
	}

	/**
	 * Removes an image and its texture ({@code /reload} does not touch a registered image, so this
	 * is the only way to drop one). Must run on the render thread.
	 *
	 * @param id the id passed to {@link #register(Identifier, int, int, int[])}
	 * @return {@code true} when an image with that id was registered
	 */
	public boolean unregister(final Identifier id) {
		if (this.images.remove(id) == null) {
			return false;
		}
		Minecraft.getInstance().getTextureManager().release(id);
		return true;
	}

	/**
	 * The stored image for an id, or {@code null} when nothing is registered (the caller then falls
	 * back to the resource pack). The pixels are the caller's to read, not to keep.
	 *
	 * @param id the id passed to {@link #register(Identifier, int, int, int[])}
	 * @return the stored image, or {@code null}
	 */
	public @Nullable Image get(final Identifier id) {
		return this.images.get(id);
	}
}

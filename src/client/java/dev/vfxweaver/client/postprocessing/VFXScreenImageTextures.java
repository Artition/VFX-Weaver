package dev.vfxweaver.client.postprocessing;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.vfxweaver.client.window.VFXWindowFrames;
import dev.vfxweaver.effect.VFXActiveEffect;
import dev.vfxweaver.effect.VFXDefinition;
import dev.vfxweaver.effect.VFXScreenImageSpec;
import dev.vfxweaver.resource.VFXDefinitionManager;
import dev.vfxweaver.util.VFXLog;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves a {@code screen_image} effect to the GPU texture to sample and the frame table to address.
 * The picture is looked up in the game's {@code TextureManager} (a resource-pack PNG with its
 * {@code .png} suffix completed, or a caller image already registered there by {@code VFXImageRegistry}),
 * so nothing is decoded on the CPU. The view is re-derived every call, because a resource reload
 * closes and re-uploads it; only the frame table is kept, memoized against the view it was read for
 * (see {@link Cached}).
 */
public final class VFXScreenImageTextures {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/screen_image");
	private static final VFXScreenImageTextures INSTANCE = new VFXScreenImageTextures();
	/** Safety cap on cached frame tables (the key is external datapack input, AGENTS.md). */
	private static final int MAX_CACHED_FRAMES = 128;

	/** The frame table per picture and requested frame count; see {@link #frames}. */
	private final Map<String, Cached> framesByImage = new HashMap<>();

	private VFXScreenImageTextures() {
	}

	/** The process-wide resolver. Must be used on the render thread. */
	public static VFXScreenImageTextures get() {
		return INSTANCE;
	}

	/**
	 * The resolved picture: the sampler view (or {@code null} when the texture did not resolve) and
	 * the frame table. A {@code null} view means the caller must draw a passthrough.
	 */
	public record Resolved(@Nullable GpuTextureView view, VFXWindowFrames frames) {
	}

	/**
	 * Resolves the effect's image and frame table. A missing definition, missing {@code texture} or
	 * an unloadable texture yields a {@code null} view and a one-cell still (the caller draws
	 * nothing) - it never throws into the post layer.
	 *
	 * @param effect the running {@code screen_image} effect
	 * @return the resolved view and frames, never {@code null}
	 */
	public Resolved resolve(final VFXActiveEffect effect) {
		final VFXDefinition definition = VFXDefinitionManager.get().get(effect.getId());
		final VFXScreenImageSpec spec = definition == null ? null : definition.getScreenImage();
		if (spec == null) {
			return new Resolved(null, VFXWindowFrames.still(1, 1));
		}
		final Identifier id = spec.texture();
		final Identifier pngId = withPng(id);
		try {
			final AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(pngId);
			final GpuTextureView view = texture.getTextureView();
			if (view == null) {
				VFXLog.warnOnce(LOGGER, "screen_image:texture:" + id,
					"screen_image '{}': texture '{}' did not upload a GPU view; drawing nothing", effect.getId(), id);
				return new Resolved(null, VFXWindowFrames.still(1, 1));
			}
			final GpuTexture gpu = texture.getTexture();
			final int imageWidth = Math.max(1, gpu == null ? 1 : gpu.getWidth(0));
			final int imageHeight = Math.max(1, gpu == null ? 1 : gpu.getHeight(0));
			final int requestedFrames = Math.max(1, (int) effect.getParam("frames", 1.0F));
			return new Resolved(view, this.frames(view, pngId, imageWidth, imageHeight, requestedFrames));
		} catch (final RuntimeException e) {
			VFXLog.warnOnce(LOGGER, "screen_image:texture:" + id,
				"screen_image '{}': texture '{}' could not be resolved ({}); drawing nothing", effect.getId(), id, e.getMessage());
			return new Resolved(null, VFXWindowFrames.still(1, 1));
		}
	}

	/**
	 * The frame table for one picture, read once per texture view instead of once per frame: a
	 * {@code .mcmeta} read re-opens and re-parses the pack file, which has no place on the render
	 * path. {@code VFXReloadSafeCache} cannot hold it (it re-derives on every read by design), so
	 * the value is memoized against the view identity, which a reload replaces - see {@link Cached}.
	 */
	private VFXWindowFrames frames(final @Nullable GpuTextureView view, final Identifier pngId,
		final int imageWidth, final int imageHeight, final int requestedFrames) {
		final String key = pngId + "#" + requestedFrames;
		final Cached cached = this.framesByImage.get(key);
		if (cached != null && cached.view() == view) {
			return cached.frames();
		}
		final VFXWindowFrames read = readFrames(pngId, imageWidth, imageHeight, requestedFrames);
		if (this.framesByImage.size() >= MAX_CACHED_FRAMES) {
			this.framesByImage.clear();
		}
		this.framesByImage.put(key, new Cached(view, read));
		return read;
	}

	private static VFXWindowFrames readFrames(final Identifier pngId, final int imageWidth,
		final int imageHeight, final int requestedFrames) {
		Optional<AnimationMetadataSection> meta = Optional.empty();
		final Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(pngId);
		if (resource.isPresent()) {
			try {
				meta = resource.get().metadata().getSection(AnimationMetadataSection.TYPE);
			} catch (final IOException e) {
				VFXLog.warnOnce(LOGGER, "screen_image:meta:" + pngId,
					"screen_image texture '{}' .mcmeta could not be read ({}); treating it as a still", pngId, e.getMessage());
			}
		}
		if (meta.isEmpty()) {
			return requestedFrames > 1
				? VFXWindowFrames.strip(imageWidth, imageHeight, requestedFrames)
				: VFXWindowFrames.still(imageWidth, imageHeight);
		}
		return VFXWindowFrames.fromMetadata(meta.get(), imageWidth, imageHeight, requestedFrames, pngId.toString());
	}

	private static Identifier withPng(final Identifier id) {
		return id.getPath().endsWith(".png") ? id : id.withSuffix(".png");
	}

	/**
	 * One memoized frame table and the texture view it was read for. A resource reload closes the
	 * view and uploads a new one, so a different view identity means the {@code .mcmeta} (and the
	 * image size) may have changed: the table is read again.
	 *
	 * @param view   the view the frames were read for; {@code null} while the texture has not uploaded
	 * @param frames the frame table, never {@code null}
	 */
	private record Cached(@Nullable GpuTextureView view, VFXWindowFrames frames) {
	}
}
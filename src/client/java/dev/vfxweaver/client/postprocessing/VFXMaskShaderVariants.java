package dev.vfxweaver.client.postprocessing;

import dev.vfxweaver.mask.VFXMaskShapeGlsl;
import dev.vfxweaver.mask.VFXShapeRegistry;
import dev.vfxweaver.util.VFXLog;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//? if <26.1 {
/*import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderManager;
*///?} else {
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderManager;
//?}

/**
 * Compiles and caches the bounded custom-shape GLSL variants (expanded design). A mask that
 * references plugin shapes needs the coverage shader with the neutral {@code vfx_shape_custom} stub
 * replaced by the registered plugin source(s); a mask with only built-in/composed leaves uses the
 * base {@code coverageProgram()}. Variants are keyed by the sorted plugin-id set, capped at
 * {@link #MAX_VARIANTS}, and a compile failure is isolated: the failed variant falls back to the
 * neutral stub (plugin leaves evaluate to no coverage) and the error is recorded, naming the shape,
 * so it surfaces through the parser/validator instead of breaking unrelated effects.
 *
 * <p>How a variant is compiled (26.1+): the coverage fragment source is taken from the live
 * {@code ShaderManager} (already preprocessed, so {@code #moj_import} is resolved), the marked
 * {@code vfx_mask_custom_inject} region is replaced by the concatenated plugin sources, and a
 * coverage pipeline under a distinct {@code post/mask_coverage_v<k>} fragment id is compiled with
 * that source through a per-variant {@code ShaderSource}. The variant is not registered as a static
 * pipeline (a shader reload would precompile it from the default source and fail); the device
 * pipeline cache is re-seeded on every use instead, which also survives a resource reload's
 * {@code clearPipelineCache}. On {@code <26.1} the shader-source hook does not exist, so a plugin
 * leaf keeps the neutral stub (additive: only plugin custom shapes are affected).
 */
public final class VFXMaskShaderVariants {
	/** The most compiled plugin variants that may exist at once. */
	public static final int MAX_VARIANTS = 4;

	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/mask");
	// The markers include the leading `// ` so the trailing marker stays a complete comment after
	// the marked region is replaced (only the stub between them is swapped for the plugin source).
	private static final String INJECT_BEGIN = "// >>> vfx_mask_custom_inject:begin";
	private static final String INJECT_END = "// <<< vfx_mask_custom_inject:end";

	private static final Map<String, VFXShaderPrograms.@Nullable ProgramInfo> VARIANTS = new HashMap<>();
	/** The injected fragment source per variant key, so the device cache can be re-seeded after a reload. */
	private static final Map<String, String> SOURCES = new HashMap<>();
	/** A monotonic variant number: never reused, so a recompiled key never collides with a stale pass/cache entry. */
	private static int nextVariantId;

	private VFXMaskShaderVariants() {
	}

	/**
	 * The coverage program for a mask that references the given plugin shapes.
	 *
	 * @param pluginIds the distinct plugin shape ids in first-use order (empty for none)
	 * @return the base coverage program when there are no plugins, else the cached variant; a
	 *         failed/over-cap variant returns the base program (neutral custom coverage)
	 */
	public static VFXShaderPrograms.@Nullable ProgramInfo variantFor(final List<String> pluginIds) {
		if (pluginIds.isEmpty()) {
			return VFXShaderPrograms.coverageProgram();
		}
		final String key = String.join(",", new TreeSet<>(pluginIds));
		if (VARIANTS.containsKey(key)) {
			// Re-seed the device pipeline cache: a resource reload clears it, after which the
			// consumer's setPipeline would otherwise recompile this pipeline from the default
			// shader source, which has no such fragment shader.
			reseed(key);
			final VFXShaderPrograms.@Nullable ProgramInfo cached = VARIANTS.get(key);
			return cached != null ? cached : VFXShaderPrograms.coverageProgram();
		}
		if (VARIANTS.size() >= MAX_VARIANTS) {
			fail(key, "the compiled custom-shape shader variant limit of " + MAX_VARIANTS + " is reached");
			VARIANTS.put(key, null);
			return VFXShaderPrograms.coverageProgram();
		}
		final VFXShaderPrograms.@Nullable ProgramInfo compiled = compileVariant(key);
		VARIANTS.put(key, compiled);
		return compiled != null ? compiled : VFXShaderPrograms.coverageProgram();
	}

	/** Clears all variants so the next use rebuilds the source and pipeline (a shape re-registration). */
	public static void invalidate() {
		VARIANTS.clear();
		SOURCES.clear();
	}

	/**
	 * Records a variant failure once (bounded key) and returns {@code null} so the caller falls back
	 * to neutral coverage. The message reads like a parse/validation error naming the shape(s) and is
	 * surfaced through {@link VFXLog#warnOnce}; no separate error accessor is kept (it had no caller).
	 */
	private static VFXShaderPrograms.@Nullable ProgramInfo fail(final String key, final String reason) {
		final String message = "mask: custom shape '" + key + "' cannot be rendered, falling back to neutral coverage: " + reason;
		VFXLog.warnOnce(LOGGER, "mask_shape_variant:" + key, message);
		return null;
	}

	/**
	 * Rebuilds the injected source from the live base shader and the live plugin sources, then
	 * re-seeds the device pipeline cache. A resource reload changing the base shader, or a
	 * re-registration changing a plugin's GLSL, is picked up here instead of leaving a stale variant.
	 */
	private static void reseed(final String key) {
		//? if <26.1 {
		/*return;
		*///?} else {
		final VFXShaderPrograms.@Nullable ProgramInfo info = VARIANTS.get(key);
		if (info == null) {
			return;
		}
		final String source = injectedSource(key);
		if (source == null) {
			return;
		}
		SOURCES.put(key, source);
		RenderSystem.getDevice().precompilePipeline(info.pipeline(), variantSource(info.pipeline().getFragmentShader(), source));
		//?}
	}

	/**
	 * Compiles a coverage variant: the live coverage source with the marked stub replaced by the
	 * registered plugin source(s), under a distinct fragment id so identical plugin sets share a
	 * variant and distinct sets do not collide in the device shader cache.
	 */
	private static VFXShaderPrograms.@Nullable ProgramInfo compileVariant(final String key) {
		//? if <26.1 {
		/*return null;
		*///?} else {
		final String injected = injectedSource(key);
		if (injected == null) {
			return null;
		}
		final Identifier variant = Identifier.fromNamespaceAndPath("vfxweaver", "post/mask_coverage_v" + nextVariantId++);
		final RenderPipeline pipeline = VFXShaderPrograms.buildCoveragePipeline(variant, variant);
		final CompiledRenderPipeline compiled = RenderSystem.getDevice().precompilePipeline(pipeline, variantSource(variant, injected));
		if (!compiled.isValid()) {
			return fail(key, "the GLSL plugin failed to compile");
		}
		SOURCES.put(key, injected);
		return new VFXShaderPrograms.ProgramInfo(pipeline, new String[0], VFXMaskUniforms.uboSize(), VFXShaderPrograms.PassRole.NORMAL, false, null, false, false);
		//?}
	}

	/**
	 * The live coverage source with the marked stub replaced by the live plugin source(s), or
	 * {@code null} after recording a failure (missing base source / markers / plugin).
	 */
	private static @Nullable String injectedSource(final String key) {
		//? if <26.1 {
		/*return null;
		*///?} else {
		final Minecraft minecraft = Minecraft.getInstance();
		final ShaderManager shaders = minecraft.getShaderManager();
		final Identifier coverageFragment = Identifier.fromNamespaceAndPath("vfxweaver", "post/mask_coverage");
		final String base = shaders.getShader(coverageFragment, ShaderType.FRAGMENT);
		if (base == null) {
			fail(key, "the coverage shader source is unavailable");
			return null;
		}
		final int begin = base.indexOf(INJECT_BEGIN);
		final int end = begin < 0 ? -1 : base.indexOf(INJECT_END, begin + INJECT_BEGIN.length());
		if (begin < 0 || end < 0) {
			fail(key, "the coverage shader is missing its custom-shape injection markers");
			return null;
		}
		final StringBuilder plugin = new StringBuilder();
		for (final String id : key.split(",")) {
			final @Nullable VFXMaskShapeGlsl shape = VFXShapeRegistry.get().plugin(id);
			if (shape == null) {
				fail(key, "shape '" + id + "' has no registered GLSL plugin");
				return null;
			}
			plugin.append("\n// mask custom shape '").append(id).append("'\n").append(shape.glsl()).append('\n');
		}
		return base.substring(0, begin + INJECT_BEGIN.length()) + plugin + base.substring(end);
		//?}
	}

	//? if <26.1 {
	/*private static ShaderSource variantSource(final Identifier variantFragment, final String fragmentSource) {
		throw new UnsupportedOperationException("no custom-shape shader variants before 26.1");
	}
	*///?} else {
	/** A shader source that serves {@code fragmentSource} for the variant fragment id and the live shader manager for everything else. */
	private static ShaderSource variantSource(final Identifier variantFragment, final String fragmentSource) {
		final ShaderManager shaders = Minecraft.getInstance().getShaderManager();
		return (location, type) -> type == ShaderType.FRAGMENT && location.equals(variantFragment)
			? fragmentSource
			: shaders.getShader(location, type);
	}
	//?}
}

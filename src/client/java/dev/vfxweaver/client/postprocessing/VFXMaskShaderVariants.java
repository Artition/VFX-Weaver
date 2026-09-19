package dev.vfxweaver.client.postprocessing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;

/**
 * Compiles and caches the bounded custom-shape GLSL variants (expanded design). A mask that
 * references plugin shapes needs the coverage shader with the neutral {@code vfx_shape_custom} stub
 * replaced by the registered plugin source(s); a mask with only built-in/composed leaves uses the
 * base {@code coverageProgram()}. Variants are keyed by the sorted plugin-id set, capped at
 * {@link #MAX_VARIANTS}, and a compile failure is isolated: the failed variant falls back to the
 * neutral stub (plugin leaves evaluate to no coverage) and the error is recorded, naming the shape,
 * so it surfaces through the parser/validator instead of breaking unrelated effects.
 *
 * <p>Deferred: the shader-source injection hook (generating a variant shader resource and
 * registering it for the next {@code ShaderManager} reload) does not exist in this codebase yet;
 * {@link #compileVariant} therefore returns {@code null} and every plugin leaf falls back to the
 * base shader's neutral stub. {@code ponytail:} plugin variant compilation deferred, neutral stub;
 * add the resource-pack hook when a plugin shape must render.
 */
public final class VFXMaskShaderVariants {
	/** The most compiled plugin variants that may exist at once. */
	public static final int MAX_VARIANTS = 4;

	private static final Map<String, VFXShaderPrograms.@Nullable ProgramInfo> VARIANTS = new HashMap<>();
	private static final Map<String, String> ERRORS = new HashMap<>();

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
			return VARIANTS.get(key);
		}
		if (VARIANTS.size() >= MAX_VARIANTS) {
			ERRORS.put(key, "mask custom-shape shader variants exceed the limit of " + MAX_VARIANTS + " (shapes: " + key + ")");
			VARIANTS.put(key, null);
			return VFXShaderPrograms.coverageProgram();
		}
		final VFXShaderPrograms.ProgramInfo compiled = compileVariant(key);
		VARIANTS.put(key, compiled);
		return compiled != null ? compiled : VFXShaderPrograms.coverageProgram();
	}

	/** The recorded compile/validation error for a variant key, or {@code null}. */
	public static @Nullable String error(final String key) {
		return ERRORS.get(key);
	}

	/** Clears all variants (called on resource reload). */
	public static void invalidate() {
		VARIANTS.clear();
		ERRORS.clear();
	}

	private static VFXShaderPrograms.@Nullable ProgramInfo compileVariant(final String key) {
		return null;
	}
}

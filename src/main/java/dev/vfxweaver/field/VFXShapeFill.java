package dev.vfxweaver.field;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * How a shape figure is filled. The ordinal is the shader {@code fill} value used by
 * {@code vfx_shape_coverage} in {@code assets/vfxweaver/shaders/include/shapes.glsl}
 * ({@code solid} = 0, {@code stroke} = 1).
 */
public enum VFXShapeFill {
	SOLID, STROKE;

	/**
	 * Resolves a fill mode from its datapack spelling.
	 *
	 * @param name raw string, e.g. {@code "stroke"}
	 * @return the matching fill, or {@code null} when unknown
	 */
	public static @Nullable VFXShapeFill fromString(final String name) {
		if (name == null) {
			return null;
		}
		final String normalized = name.trim().toLowerCase(Locale.ROOT);
		for (final VFXShapeFill fill : values()) {
			if (fill.name().toLowerCase(Locale.ROOT).equals(normalized)) {
				return fill;
			}
		}
		return null;
	}
}

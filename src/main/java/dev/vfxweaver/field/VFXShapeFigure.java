package dev.vfxweaver.field;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The closed set of 2D shape figures the shared shape library owns. The ordinal is the shader
 * {@code figure} value — the {@code primitive} argument of {@code vfx_shape_sdf} in
 * {@code assets/vfxweaver/shaders/include/shapes.glsl}: {@code circle} = 0, {@code ellipse} = 1,
 * {@code rect} = 2, {@code polygon} = 3.
 *
 * <p>A grid is not a figure: it is any figure with a {@code repeat} modifier. A ring is not a
 * figure: it is {@link #ELLIPSE} with {@link VFXShapeFill#STROKE}.
 */
public enum VFXShapeFigure {
	CIRCLE, ELLIPSE, RECT, POLYGON;

	/**
	 * Resolves a figure from its datapack spelling.
	 *
	 * @param name raw string, e.g. {@code "circle"}
	 * @return the matching figure, or {@code null} when unknown
	 */
	public static @Nullable VFXShapeFigure fromString(final String name) {
		if (name == null) {
			return null;
		}
		final String normalized = name.trim().toLowerCase(Locale.ROOT);
		for (final VFXShapeFigure figure : values()) {
			if (figure.name().toLowerCase(Locale.ROOT).equals(normalized)) {
				return figure;
			}
		}
		return null;
	}
}

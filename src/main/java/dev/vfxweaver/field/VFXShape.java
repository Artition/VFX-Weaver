package dev.vfxweaver.field;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The CPU half of the shared shape/field library: a parsed structural shape figure with its
 * figure-specific parameters, fill, {@code [1,64]} {@code repeat} tile modifier and optional
 * world {@code center}. It computes no distance — the signed-distance functions are the GPU
 * library's ({@code assets/vfxweaver/shaders/include/shapes.glsl}); this only carries the numbers
 * the shader needs and validates them.
 *
 * <p>Parameter names and defaults are the field library's {@link VFXFieldFn#SHAPE} — the single
 * source of truth shared with the per-pixel {@code shape} function — so a figure added there is
 * available here too.
 */
public final class VFXShape {
	/** Lower bound of a {@code repeat} component. */
	public static final float MIN_REPEAT = 1.0F;
	/** Upper bound of a {@code repeat} component. */
	public static final float MAX_REPEAT = 64.0F;

	private final VFXShapeFigure figure;
	private final VFXShapeFill fill;
	/** Parameter slots in {@link VFXFieldFn#SHAPE} order. */
	private final float[] values;
	private final @Nullable float[] center;

	private VFXShape(final VFXShapeFigure figure, final VFXShapeFill fill, final float[] values, final @Nullable float[] center) {
		this.figure = figure;
		this.fill = fill;
		this.values = values;
		this.center = center;
	}

	/**
	 * Parses and validates a structural shape block.
	 *
	 * @param json the {@code pattern} object (e.g. {@code {"figure": "circle", "radius": 0.4}})
	 * @return the parsed shape, never {@code null}
	 * @throws IllegalArgumentException on an unknown figure/fill/field or an out-of-range repeat
	 */
	public static VFXShape parse(final JsonObject json) {
		final String figureName = str(json, "figure", VFXShapeFigure.CIRCLE.name().toLowerCase(java.util.Locale.ROOT));
		final VFXShapeFigure figure = VFXShapeFigure.fromString(figureName);
		if (figure == null) {
			throw new IllegalArgumentException("pattern: unknown figure '" + figureName + "' (circle, ellipse, rect or polygon)");
		}
		final String fillName = str(json, "fill", VFXShapeFill.SOLID.name().toLowerCase(java.util.Locale.ROOT));
		final VFXShapeFill fill = VFXShapeFill.fromString(fillName);
		if (fill == null) {
			throw new IllegalArgumentException("pattern: unknown fill '" + fillName + "' (solid or stroke)");
		}

		final float[] values = new float[VFXFieldFn.SHAPE.paramNames().size()];
		for (int i = 0; i < values.length; i++) {
			values[i] = VFXFieldFn.SHAPE.defaultParam(VFXFieldFn.SHAPE.paramNames().get(i));
		}
		float[] center = null;
		final float[] repeat = {1.0F, 1.0F};

		for (final Map.Entry<String, JsonElement> entry : json.entrySet()) {
			final String key = entry.getKey();
			if ("figure".equals(key) || "fill".equals(key)) {
				continue;
			}
			final JsonElement value = entry.getValue();
			if ("center".equals(key)) {
				final JsonArray array = array(key, value, 3);
				center = new float[]{number(key, array.get(0)), number(key, array.get(1)), number(key, array.get(2))};
				continue;
			}
			if ("repeat".equals(key)) {
				final JsonArray array = array(key, value, 2);
				repeat[0] = number(key, array.get(0));
				repeat[1] = number(key, array.get(1));
				continue;
			}
			final int index = VFXFieldFn.SHAPE.paramIndex(key);
			if (index < 0) {
				throw new IllegalArgumentException("pattern: unknown field '" + key + "'");
			}
			values[index] = number(key, value);
		}

		if (repeat[0] < MIN_REPEAT || repeat[0] > MAX_REPEAT || repeat[1] < MIN_REPEAT || repeat[1] > MAX_REPEAT) {
			throw new IllegalArgumentException("pattern: repeat components must be within [" + (int) MIN_REPEAT + ", " + (int) MAX_REPEAT + "], got [" + repeat[0] + ", " + repeat[1] + "]");
		}
		values[VFXFieldFn.SHAPE.paramIndex("repeat_x")] = repeat[0];
		values[VFXFieldFn.SHAPE.paramIndex("repeat_y")] = repeat[1];

		if (figure == VFXShapeFigure.POLYGON && values[VFXFieldFn.SHAPE.paramIndex("sides")] < 3.0F) {
			throw new IllegalArgumentException("pattern: polygon needs at least 3 sides, got " + (int) values[VFXFieldFn.SHAPE.paramIndex("sides")]);
		}

		return new VFXShape(figure, fill, values, center);
	}

	/** The figure (the shader's {@code shape} ordinal source). */
	public VFXShapeFigure figure() {
		return this.figure;
	}

	/** The fill mode (the shader's {@code fill} ordinal source). */
	public VFXShapeFill fill() {
		return this.fill;
	}

	/** The literal structural centre {@code [x, y, z]}, or {@code null} when unset (anchor rule). */
	public @Nullable float[] center() {
		return this.center;
	}

	/** Rotation in degrees. */
	public float rotation() {
		return slot("rotation");
	}

	/** The structural stroke width in cell units. */
	public float strokeWidth() {
		return slot("stroke_width");
	}

	/** Edge softness in cell units. */
	public float softness() {
		return slot("softness");
	}

	/** {@code circle}/{@code polygon} radius. */
	public float radius() {
		return slot("radius");
	}

	/** {@code ellipse} X radius. */
	public float radiusX() {
		return slot("radius_x");
	}

	/** {@code ellipse} Y radius. */
	public float radiusY() {
		return slot("radius_y");
	}

	/** {@code rect} X half-extent. */
	public float halfWidth() {
		return slot("half_width");
	}

	/** {@code rect} Y half-extent. */
	public float halfHeight() {
		return slot("half_height");
	}

	/** {@code rect} corner rounding. */
	public float cornerRadius() {
		return slot("corner_radius");
	}

	/** {@code polygon} side count (>= 3). */
	public int sides() {
		return (int) slot("sides");
	}

	/** Tile count along X ({@code 1..64}). */
	public int repeatX() {
		return (int) slot("repeat_x");
	}

	/** Tile count along Y ({@code 1..64}). */
	public int repeatY() {
		return (int) slot("repeat_y");
	}

	private float slot(final String name) {
		return this.values[VFXFieldFn.SHAPE.paramIndex(name)];
	}

	private static JsonArray array(final String key, final JsonElement value, final int size) {
		if (!value.isJsonArray() || value.getAsJsonArray().size() != size) {
			throw new IllegalArgumentException("pattern: '" + key + "' must be an array of " + size + " numbers");
		}
		return value.getAsJsonArray();
	}

	private static float number(final String key, final JsonElement value) {
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException("pattern: '" + key + "' must be a number");
		}
		return ((JsonPrimitive) value).getAsFloat();
	}

	private static String str(final JsonObject json, final String key, final String fallback) {
		final JsonElement element = json.get(key);
		return element != null && !element.isJsonNull() ? element.getAsString() : fallback;
	}
}

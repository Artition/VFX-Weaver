package dev.vfxweaver.effect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Immutable configuration of a spark: a glowing, camera-facing sprite emitted in a burst,
 * integrated through gravity, air drag and optional world collision, and faded by its size and
 * colour curves over its life. Mirrors {@link VFXBlockParticleSpec}: a preset is parsed from
 * {@code data/<namespace>/vfx_particles/<name>.json} (with {@code "kind": "spark"}) and an effect's
 * numeric {@code params} are laid over it by {@link #withOverrides(Map)}.
 *
 * <p>Only numeric knobs live here and are effect-overridable. The boolean {@link #glow()} and the
 * curves are structural (preset) fields: they never travel in the numeric {@code params} map,
 * matching the repo rule that non-numeric knobs stay in structural sections. The emitter shape is
 * the effect's own structural {@code shape} field.</p>
 *
 * <p>Curves are sampled by normalized life {@code t = age / life} in {@code [0,1]} with linear
 * interpolation. The size curve multiplies {@link #size()}; the colour curve supplies RGB. The
 * arrays are exposed directly and must not be mutated after construction.</p>
 *
 * @param count      total sparks emitted across the effect's whole duration
 * @param speed      launch speed in blocks per tick
 * @param spread     aim-cone jitter {@code 0..1} (only meaningful with a two-slot aimed emitter)
 * @param life       lifetime in ticks
 * @param gravity    downward acceleration per tick, as a multiple of {@link VFXBlockParticleSpec#GRAVITY_STEP}
 * @param bounce     restitution of the normal velocity on world contact, {@code 0..1}
 * @param size       peak sprite size in blocks (before the size curve)
 * @param trail      tail length in ticks, {@code 0} = no trail
 * @param glow       {@code true} renders additively (glow), {@code false} renders translucent
 * @param sizeTimes  ascending curve times in {@code [0,1]} (at least two)
 * @param sizeValues size multipliers at {@code sizeTimes}
 * @param colorTimes ascending colour-curve times in {@code [0,1]} (at least two)
 * @param colorR     red at {@code colorTimes}, {@code 0..1}
 * @param colorG     green at {@code colorTimes}, {@code 0..1}
 * @param colorB     blue at {@code colorTimes}, {@code 0..1}
 */
public record VFXSparkSpec(
	int count,
	float speed,
	float spread,
	int life,
	float gravity,
	float bounce,
	float size,
	int trail,
	boolean glow,
	float[] sizeTimes,
	float[] sizeValues,
	float[] colorTimes,
	float[] colorR,
	float[] colorG,
	float[] colorB
) {
	/** Upper bound on emitted sparks, so a bad datapack value cannot pin the budget. */
	public static final int MAX_COUNT = 10000;
	/** Upper bound on {@code life}, in ticks. */
	public static final int MAX_LIFE = 1200;
	/** Upper bound on {@code trail}, in ticks. */
	public static final int MAX_TRAIL = 16;
	/** Upper bound on points per curve. */
	public static final int MAX_CURVE_POINTS = 32;

	private static final int DEFAULT_COUNT = 200;
	private static final float DEFAULT_SPEED = 0.4F;
	private static final float DEFAULT_SPREAD = 0.6F;
	private static final int DEFAULT_LIFE = 25;
	private static final float DEFAULT_GRAVITY = 0.4F;
	private static final float DEFAULT_BOUNCE = 0.2F;
	private static final float DEFAULT_SIZE = 0.08F;
	private static final int DEFAULT_TRAIL = 0;
	private static final boolean DEFAULT_GLOW = true;

	/** Default spec: a small white-to-ember additive spark. */
	public static final VFXSparkSpec DEFAULT = builder().build();

	public VFXSparkSpec {
		count = Math.max(0, Math.min(MAX_COUNT, count));
		speed = clamp(speed, 0.0F, 16.0F);
		spread = clamp(spread, 0.0F, 1.0F);
		life = Math.max(1, Math.min(MAX_LIFE, life));
		gravity = clamp(gravity, 0.0F, 64.0F);
		bounce = clamp(bounce, 0.0F, 1.0F);
		size = clamp(size, 0.01F, 4.0F);
		trail = Math.max(0, Math.min(MAX_TRAIL, trail));
		sizeTimes = validTimes(sizeTimes) ? sizeTimes : new float[] { 0.0F, 1.0F };
		sizeValues = sizeValues != null && sizeValues.length == sizeTimes.length ? sizeValues : new float[] { 1.0F, 0.0F };
		colorTimes = validTimes(colorTimes) ? colorTimes : new float[] { 0.0F, 1.0F };
		colorR = validChannel(colorR, colorTimes.length) ? colorR : new float[] { 1.0F, 1.0F };
		colorG = validChannel(colorG, colorTimes.length) ? colorG : new float[] { 1.0F, 1.0F };
		colorB = validChannel(colorB, colorTimes.length) ? colorB : new float[] { 1.0F, 1.0F };
	}

	/**
	 * Samples the size multiplier at a normalized life fraction.
	 *
	 * @param lifeFraction {@code age / life}, clamped to {@code [0,1]}
	 * @return the multiplier applied to {@link #size()}
	 */
	public float sizeAt(final float lifeFraction) {
		return sample(this.sizeTimes, this.sizeValues, clamp(lifeFraction, 0.0F, 1.0F));
	}

	/**
	 * Samples the colour at a normalized life fraction, packed as {@code 0xRRGGBB}.
	 *
	 * @param lifeFraction {@code age / life}, clamped to {@code [0,1]}
	 * @return the packed RGB colour
	 */
	public int colorAt(final float lifeFraction) {
		final float t = clamp(lifeFraction, 0.0F, 1.0F);
		return (byte255(sample(this.colorTimes, this.colorR, t)) << 16)
			| (byte255(sample(this.colorTimes, this.colorG, t)) << 8)
			| byte255(sample(this.colorTimes, this.colorB, t));
	}

	/**
	 * Starts a builder with every field at its documented default.
	 *
	 * @return a builder carrying the defaults
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Parses a spark preset. The {@code kind} discriminator is not checked here (the manager
	 * selects the spark branch); the method only reads the spark fields.
	 *
	 * @param object the preset JSON object
	 * @return the parsed spec
	 * @throws IllegalArgumentException when a curve is malformed
	 */
	public static VFXSparkSpec parse(final JsonObject object) {
		final Builder builder = builder();
		if (has(object, "count")) {
			builder.count(object.get("count").getAsInt());
		}
		if (has(object, "speed")) {
			builder.speed(object.get("speed").getAsFloat());
		}
		if (has(object, "spread")) {
			builder.spread(object.get("spread").getAsFloat());
		}
		if (has(object, "life")) {
			builder.life(object.get("life").getAsInt());
		}
		if (has(object, "gravity")) {
			builder.gravity(object.get("gravity").getAsFloat());
		}
		if (has(object, "bounce")) {
			builder.bounce(object.get("bounce").getAsFloat());
		}
		if (has(object, "size")) {
			builder.size(object.get("size").getAsFloat());
		}
		if (has(object, "trail")) {
			builder.trail(object.get("trail").getAsInt());
		}
		if (has(object, "glow")) {
			builder.glow(object.get("glow").getAsBoolean());
		}
		parseSizeCurve(object, builder);
		parseColorCurve(object, builder);
		return builder.build();
	}

	/**
	 * Lays effect parameter overrides over this spec. Every key present in {@code params} replaces
	 * the matching numeric field (clamped by the canonical constructor); {@code glow} and the
	 * curves are structural and are intentionally not overridable.
	 *
	 * @param params effect parameter values (may be empty)
	 * @return the overridden spec, or {@code this} when nothing applies
	 */
	public VFXSparkSpec withOverrides(final Map<String, Float> params) {
		if (params.isEmpty()) {
			return this;
		}
		int newCount = this.count;
		float newSpeed = this.speed;
		float newSpread = this.spread;
		int newLife = this.life;
		float newGravity = this.gravity;
		float newBounce = this.bounce;
		float newSize = this.size;
		int newTrail = this.trail;
		Float value;
		if ((value = params.get("count")) != null) {
			newCount = value.intValue();
		}
		if ((value = params.get("speed")) != null) {
			newSpeed = value;
		}
		if ((value = params.get("spread")) != null) {
			newSpread = value;
		}
		if ((value = params.get("life")) != null) {
			newLife = value.intValue();
		}
		if ((value = params.get("gravity")) != null) {
			newGravity = value;
		}
		if ((value = params.get("bounce")) != null) {
			newBounce = value;
		}
		if ((value = params.get("size")) != null) {
			newSize = value;
		}
		if ((value = params.get("trail")) != null) {
			newTrail = value.intValue();
		}
		if (newCount == this.count && newSpeed == this.speed && newSpread == this.spread && newLife == this.life
			&& newGravity == this.gravity && newBounce == this.bounce && newSize == this.size && newTrail == this.trail) {
			return this;
		}
		return new VFXSparkSpec(newCount, newSpeed, newSpread, newLife, newGravity, newBounce, newSize, newTrail, this.glow,
			this.sizeTimes, this.sizeValues, this.colorTimes, this.colorR, this.colorG, this.colorB);
	}

	private static void parseSizeCurve(final JsonObject object, final Builder builder) {
		final JsonArray array = curveArray(object, "size_curve");
		if (array == null) {
			return;
		}
		final float[] times = new float[array.size()];
		final float[] values = new float[array.size()];
		for (int i = 0; i < array.size(); i++) {
			final JsonObject point = point(array, i, "size_curve");
			times[i] = point.has("t") ? point.get("t").getAsFloat() : (i == 0 ? 0.0F : 1.0F);
			values[i] = point.has("value") ? point.get("value").getAsFloat() : 0.0F;
			if (i > 0 && times[i] <= times[i - 1]) {
				throw new IllegalArgumentException("spark 'size_curve' t must be strictly ascending");
			}
		}
		builder.sizeCurve(times, values);
	}

	private static void parseColorCurve(final JsonObject object, final Builder builder) {
		final JsonArray array = curveArray(object, "color_curve");
		if (array == null) {
			return;
		}
		final float[] times = new float[array.size()];
		final float[] r = new float[array.size()];
		final float[] g = new float[array.size()];
		final float[] b = new float[array.size()];
		for (int i = 0; i < array.size(); i++) {
			final JsonObject point = point(array, i, "color_curve");
			times[i] = point.has("t") ? point.get("t").getAsFloat() : (i == 0 ? 0.0F : 1.0F);
			r[i] = point.has("r") ? point.get("r").getAsFloat() : 1.0F;
			g[i] = point.has("g") ? point.get("g").getAsFloat() : 1.0F;
			b[i] = point.has("b") ? point.get("b").getAsFloat() : 1.0F;
			if (i > 0 && times[i] <= times[i - 1]) {
				throw new IllegalArgumentException("spark 'color_curve' t must be strictly ascending");
			}
		}
		builder.colorCurve(times, r, g, b);
	}

	private static @Nullable JsonArray curveArray(final JsonObject object, final String key) {
		final JsonElement element = object.get(key);
		if (element == null || element.isJsonNull()) {
			return null;
		}
		if (!element.isJsonArray()) {
			throw new IllegalArgumentException("spark '" + key + "' must be an array");
		}
		final JsonArray array = element.getAsJsonArray();
		if (array.size() < 2) {
			throw new IllegalArgumentException("spark '" + key + "' needs at least two points");
		}
		if (array.size() > MAX_CURVE_POINTS) {
			throw new IllegalArgumentException("spark '" + key + "' has more than " + MAX_CURVE_POINTS + " points");
		}
		return array;
	}

	private static JsonObject point(final JsonArray array, final int index, final String key) {
		if (!array.get(index).isJsonObject()) {
			throw new IllegalArgumentException("spark '" + key + "' point " + index + " must be an object");
		}
		return array.get(index).getAsJsonObject();
	}

	private static boolean validTimes(final float[] times) {
		if (times == null || times.length < 2) {
			return false;
		}
		for (int i = 1; i < times.length; i++) {
			if (times[i] <= times[i - 1]) {
				return false;
			}
		}
		return true;
	}

	private static boolean validChannel(final float[] channel, final int length) {
		return channel != null && channel.length == length;
	}

	private static float sample(final float[] times, final float[] values, final float t) {
		for (int i = 1; i < times.length; i++) {
			if (t <= times[i] || i == times.length - 1) {
				final float span = times[i] - times[i - 1];
				final float f = span <= 0.0F ? 0.0F : (t - times[i - 1]) / span;
				return values[i - 1] + (values[i] - values[i - 1]) * clamp(f, 0.0F, 1.0F);
			}
		}
		return values[0];
	}

	private static int byte255(final float value) {
		return Math.max(0, Math.min(255, Math.round(value * 255.0F)));
	}

	private static boolean has(final JsonObject object, final String key) {
		return object.has(key) && !object.get(key).isJsonNull();
	}

	private static float clamp(final float value, final float min, final float max) {
		return Math.max(min, Math.min(max, value));
	}

	/** Mutable builder for {@link VFXSparkSpec}; every field starts at its documented default. */
	public static final class Builder {
		private int count = DEFAULT_COUNT;
		private float speed = DEFAULT_SPEED;
		private float spread = DEFAULT_SPREAD;
		private int life = DEFAULT_LIFE;
		private float gravity = DEFAULT_GRAVITY;
		private float bounce = DEFAULT_BOUNCE;
		private float size = DEFAULT_SIZE;
		private int trail = DEFAULT_TRAIL;
		private boolean glow = DEFAULT_GLOW;
		private float[] sizeTimes = { 0.0F, 0.1F, 1.0F };
		private float[] sizeValues = { 0.0F, 1.0F, 0.0F };
		private float[] colorTimes = { 0.0F, 0.35F, 1.0F };
		private float[] colorR = { 1.0F, 1.0F, 0.35F };
		private float[] colorG = { 1.0F, 0.62F, 0.05F };
		private float[] colorB = { 0.95F, 0.15F, 0.0F };

		private Builder() {
		}

		/** @param value total sparks emitted across the effect duration */
		public Builder count(final int value) {
			this.count = value;
			return this;
		}

		public Builder speed(final float value) {
			this.speed = value;
			return this;
		}

		public Builder spread(final float value) {
			this.spread = value;
			return this;
		}

		public Builder life(final int value) {
			this.life = value;
			return this;
		}

		public Builder gravity(final float value) {
			this.gravity = value;
			return this;
		}

		public Builder bounce(final float value) {
			this.bounce = value;
			return this;
		}

		public Builder size(final float value) {
			this.size = value;
			return this;
		}

		/** @param value tail length in ticks, {@code 0} = off */
		public Builder trail(final int value) {
			this.trail = value;
			return this;
		}

		/** @param value {@code true} = additive glow, {@code false} = translucent */
		public Builder glow(final boolean value) {
			this.glow = value;
			return this;
		}

		public Builder sizeCurve(final float[] times, final float[] values) {
			this.sizeTimes = times;
			this.sizeValues = values;
			return this;
		}

		public Builder colorCurve(final float[] times, final float[] r, final float[] g, final float[] b) {
			this.colorTimes = times;
			this.colorR = r;
			this.colorG = g;
			this.colorB = b;
			return this;
		}

		/** @return the built immutable spec */
		public VFXSparkSpec build() {
			return new VFXSparkSpec(this.count, this.speed, this.spread, this.life, this.gravity, this.bounce, this.size,
				this.trail, this.glow, this.sizeTimes, this.sizeValues, this.colorTimes, this.colorR, this.colorG, this.colorB);
		}
	}
}

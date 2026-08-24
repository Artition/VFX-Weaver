package dev.vfxweaver.effect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.GsonHelper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A datapack-defined VFX effect loaded from {@code data/<namespace>/vfx/<effect>.json}.
 * The JSON declares the effect kind, the default duration, the default easing curve and
 * a set of parameters (either constant values or start/end animated pairs).
 */
public class VFXDefinition {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/vfx-def");
	private final Identifier id;
	private final VFXEffectType type;
	private final int defaultDuration;
	private final EasingFunction defaultEasing;
	private final Map<String, ParamSpec> params;
	private final boolean persistent;
	private final boolean loop;
	private final int fadeTicks;
	private final List<ChildEffect> children;
	private final List<BlockPos> positions;
	private final @Nullable Identifier sound;
	private final @Nullable String entitySelector;

	private VFXDefinition(
		final Identifier id,
		final VFXEffectType type,
		final int defaultDuration,
		final EasingFunction defaultEasing,
		final Map<String, ParamSpec> params,
		final boolean persistent,
		final boolean loop,
		final int fadeTicks,
		final List<ChildEffect> children,
		final List<BlockPos> positions,
		final @Nullable Identifier sound,
		final @Nullable String entitySelector
	) {
		this.id = id;
		this.type = type;
		this.defaultDuration = defaultDuration;
		this.defaultEasing = defaultEasing;
		this.params = Collections.unmodifiableMap(params);
		this.persistent = persistent;
		this.loop = loop;
		this.fadeTicks = fadeTicks;
		this.children = List.copyOf(children);
		this.positions = List.copyOf(positions);
		this.sound = sound;
		this.entitySelector = entitySelector;
	}

	/**
	 * Creates a definition programmatically (used for the built-in effects).
	 */
	public static VFXDefinition create(
		final Identifier id,
		final VFXEffectType type,
		final int defaultDuration,
		final EasingFunction defaultEasing,
		final Map<String, ParamSpec> params
	) {
		return create(id, type, defaultDuration, defaultEasing, params, false, false, 0, List.of(), List.of(), null, null);
	}

	/**
	 * Creates a definition programmatically with persistence, looping, fade and collection children.
	 */
	public static VFXDefinition create(
		final Identifier id,
		final VFXEffectType type,
		final int defaultDuration,
		final EasingFunction defaultEasing,
		final Map<String, ParamSpec> params,
		final boolean persistent,
		final boolean loop,
		final int fadeTicks,
		final List<ChildEffect> children
	) {
		return create(id, type, defaultDuration, defaultEasing, params, persistent, loop, fadeTicks, children, List.of(), null, null);
	}

	/**
	 * Creates a definition programmatically with all fields.
	 */
	public static VFXDefinition create(
		final Identifier id,
		final VFXEffectType type,
		final int defaultDuration,
		final EasingFunction defaultEasing,
		final Map<String, ParamSpec> params,
		final boolean persistent,
		final boolean loop,
		final int fadeTicks,
		final List<ChildEffect> children,
		final List<BlockPos> positions,
		final @Nullable Identifier sound
	) {
		return create(id, type, defaultDuration, defaultEasing, params, persistent, loop, fadeTicks, children, positions, sound, null);
	}

	/**
	 * Creates a definition programmatically with all fields plus an entity selector.
	 */
	public static VFXDefinition create(
		final Identifier id,
		final VFXEffectType type,
		final int defaultDuration,
		final EasingFunction defaultEasing,
		final Map<String, ParamSpec> params,
		final boolean persistent,
		final boolean loop,
		final int fadeTicks,
		final List<ChildEffect> children,
		final List<BlockPos> positions,
		final @Nullable Identifier sound,
		final @Nullable String entitySelector
	) {
		return new VFXDefinition(id, type, defaultDuration, defaultEasing, params, persistent, loop, fadeTicks, children, positions, sound, entitySelector);
	}

	/**
	 * Parses a definition from a datapack JSON object.
	 *
	 * @param id   the effect id (derived from the file name)
	 * @param json the parsed {@code vfx/<name>.json} contents
	 * @return the parsed definition
	 */
	public static VFXDefinition parse(final Identifier id, final JsonObject json) {
		VFXEffectType type = VFXEffectType.fromString(GsonHelper.getAsString(json, "type", ""));
		if (type == null) {
			throw new IllegalArgumentException("Unknown effect type in '" + id + "': " + GsonHelper.getAsString(json, "type", ""));
		}

		int duration = GsonHelper.getAsInt(json, "duration", 40);
		EasingFunction easing = parseEasing(json.get("easing"));
		boolean persistent = GsonHelper.getAsBoolean(json, "persistent", false);
		boolean loop = GsonHelper.getAsBoolean(json, "loop", false);
		int fadeTicks = GsonHelper.getAsInt(json, "fade_ticks", persistent || loop ? 10 : 0);

		Map<String, ParamSpec> params = new LinkedHashMap<>();
		if (json.has("params")) {
			JsonObject paramsJson = GsonHelper.getAsJsonObject(json, "params");
			for (Map.Entry<String, JsonElement> entry : paramsJson.entrySet()) {
				params.put(entry.getKey(), parseParam(entry.getValue()));
			}
		}

		// Optional "sound_pos": [x,y,z] sugar — expanded into sound_pos_x/y/z params so the sound
		// position can be overridden via sendEffect like any other parameter. When absent the
		// sound plays to the player directly (no world position).
		if (json.has("sound_pos")) {
			JsonArray sp = GsonHelper.getAsJsonArray(json, "sound_pos");
			if (sp.size() != 3) {
				throw new IllegalArgumentException("'sound_pos' must be an array of [x, y, z]");
			}
			params.putIfAbsent("sound_pos_x", ParamSpec.constant(sp.get(0).getAsFloat()));
			params.putIfAbsent("sound_pos_y", ParamSpec.constant(sp.get(1).getAsFloat()));
			params.putIfAbsent("sound_pos_z", ParamSpec.constant(sp.get(2).getAsFloat()));
		}

		List<ChildEffect> children = new ArrayList<>();
		if (json.has("effects")) {
			for (JsonElement entry : GsonHelper.getAsJsonArray(json, "effects")) {
				children.add(parseChild(entry));
			}
		}

		List<BlockPos> positions = parsePositions(json, params);

		Identifier sound = null;
		if (json.has("sound") && !json.get("sound").isJsonNull()) {
			sound = Identifier.parse(GsonHelper.getAsString(json, "sound"));
		}

		String entitySelector = json.has("entity_selector") && !json.get("entity_selector").isJsonNull()
			? GsonHelper.getAsString(json, "entity_selector")
			: null;

		return new VFXDefinition(id, type, duration, easing, params, persistent, loop, fadeTicks, children, positions, sound, entitySelector);
	}

	/**
	 * Parses an easing reference: a plain name (built-in or named datapack curve) or an inline
	 * object with a {@code curve} control-point array.
	 *
	 * @param element the JSON value of an {@code easing} field (may be null)
	 * @return the resolved easing function (never null)
	 */
	private static EasingFunction parseEasing(final @Nullable JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return EasingFunction.builtIn(EasingType.LINEAR);
		}
		if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
			return EasingFunction.fromString(element.getAsString());
		}
		if (element.isJsonObject() && element.getAsJsonObject().has("curve")) {
			JsonArray curve = GsonHelper.getAsJsonArray(element.getAsJsonObject(), "curve");
			float[] ts = new float[curve.size()];
			float[] vs = new float[curve.size()];
			for (int i = 0; i < curve.size(); i++) {
				JsonArray point = GsonHelper.convertToJsonArray(curve.get(i), "curve point");
				if (point.size() != 2) {
					throw new IllegalArgumentException("Curve point must be [t, v]: " + point);
				}
				ts[i] = point.get(0).getAsFloat();
				vs[i] = point.get(1).getAsFloat();
				if (i > 0 && ts[i] <= ts[i - 1]) {
					throw new IllegalArgumentException("Curve times must be strictly ascending: " + point);
				}
			}
			if (ts.length < 2 || Math.abs(ts[0]) > 1.0e-4F || Math.abs(ts[ts.length - 1] - 1.0F) > 1.0e-4F) {
				throw new IllegalArgumentException("Curve times must start at 0 and end at 1");
			}
			return EasingFunction.curve("inline", ts, vs);
		}
		throw new IllegalArgumentException("'easing' must be a name string or an object with a 'curve' array: " + element);
	}

	/** Safety cap on parsed positions per effect (external input, see AGENTS.md). */
	private static final int MAX_POSITIONS = 4096;

	private static List<BlockPos> parsePositions(final JsonObject json, final Map<String, ParamSpec> params) {
		List<BlockPos> positions = new ArrayList<>();
		if (json.has("positions")) {
			for (JsonElement entry : GsonHelper.getAsJsonArray(json, "positions")) {
				if (positions.size() >= MAX_POSITIONS) {
					LOGGER.warn("Effect declares more than {} positions; the rest are ignored", MAX_POSITIONS);
					break;
				}
				JsonArray array = GsonHelper.convertToJsonArray(entry, "position");
				if (array.size() != 3) {
					throw new IllegalArgumentException("Position must be an array of [x, y, z]: " + entry);
				}
				positions.add(new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt()));
			}
		}
		if (json.has("region")) {
			JsonArray region = GsonHelper.getAsJsonArray(json, "region");
			if (region.size() != 6) {
				throw new IllegalArgumentException("Region must be an array of [x0, y0, z0, x1, y1, z1]");
			}
			BlockPos min = new BlockPos(
				Math.min(region.get(0).getAsInt(), region.get(3).getAsInt()),
				Math.min(region.get(1).getAsInt(), region.get(4).getAsInt()),
				Math.min(region.get(2).getAsInt(), region.get(5).getAsInt())
			);
			BlockPos max = new BlockPos(
				Math.max(region.get(0).getAsInt(), region.get(3).getAsInt()),
				Math.max(region.get(1).getAsInt(), region.get(4).getAsInt()),
				Math.max(region.get(2).getAsInt(), region.get(5).getAsInt())
			);
			for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
				if (positions.size() >= MAX_POSITIONS) {
					LOGGER.warn("Effect region exceeds {} blocks; the rest are ignored", MAX_POSITIONS);
					break;
				}
				positions.add(pos.immutable());
			}
		}
		if (positions.isEmpty()) {
			// Fallback to legacy single-position params.
			ParamSpec x = params.get("pos_x");
			ParamSpec y = params.get("pos_y");
			ParamSpec z = params.get("pos_z");
			if (x != null && y != null && z != null && !x.animated() && !y.animated() && !z.animated()
				&& x.keyframes().isEmpty() && y.keyframes().isEmpty() && z.keyframes().isEmpty()
				&& x.bound() == null && y.bound() == null && z.bound() == null) {
				positions.add(new BlockPos((int) x.constant(), (int) y.constant(), (int) z.constant()));
			}
		}
		return positions;
	}

	private static ChildEffect parseChild(final JsonElement element) {
		JsonObject object = GsonHelper.convertToJsonObject(element, "effect entry");
		Identifier effectId = Identifier.parse(GsonHelper.getAsString(object, "effect"));
		float delay = GsonHelper.getAsFloat(object, "delay", 0.0F);
		int duration = GsonHelper.getAsInt(object, "duration", 0);
		EasingFunction easing = object.has("easing") && !object.get("easing").isJsonNull() ? parseEasing(object.get("easing")) : null;
		Map<String, Float> overrides = new LinkedHashMap<>();
		if (object.has("params")) {
			JsonObject paramsJson = GsonHelper.getAsJsonObject(object, "params");
			for (Map.Entry<String, JsonElement> entry : paramsJson.entrySet()) {
				JsonElement value = entry.getValue();
				if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
					overrides.put(entry.getKey(), value.getAsFloat());
				} else {
					throw new IllegalArgumentException("Collection child params must be plain numbers: " + element);
				}
			}
		}
		return new ChildEffect(effectId, delay, duration, overrides, easing);
	}

	/**
	 * One entry of a collection definition: which effect to play, after which delay, with which
	 * duration, constant parameter overrides and easing.
	 *
	 * @param effect  child effect id
	 * @param delay   delay in ticks before the child starts (relative to the collection start)
	 * @param duration child duration in ticks (0 = the child definition default, -1 = persistent)
	 * @param params  constant parameter overrides
	 * @param easing  easing override (null = the child definition default)
	 */
	public record ChildEffect(Identifier effect, float delay, int duration, Map<String, Float> params, EasingFunction easing) {
	}

	private static ParamSpec parseParam(final JsonElement element) {
		if (element.isJsonPrimitive()) {
			JsonPrimitive primitive = element.getAsJsonPrimitive();
			if (primitive.isNumber()) {
				return ParamSpec.constant(primitive.getAsFloat());
			}
			throw new IllegalArgumentException("Parameter must be a number or an object: " + element);
		}

		if (element.isJsonObject()) {
			JsonObject object = element.getAsJsonObject();
			ParamSpec spec;
			if (object.has("bind")) {
				spec = ParamSpec.bound(parseBound(object));
			} else if (object.has("expr")) {
				spec = ParamSpec.expression(GsonHelper.getAsString(object, "expr"));
			} else if (object.has("keyframes")) {
				spec = ParamSpec.keyframed(parseKeyframes(object));
			} else if (object.has("start") && object.has("end")) {
				float start = GsonHelper.getAsFloat(object, "start");
				float end = GsonHelper.getAsFloat(object, "end");
				spec = ParamSpec.animated(start, end);
			} else if (object.has("value")) {
				spec = ParamSpec.constant(GsonHelper.getAsFloat(object, "value"));
			} else {
				throw new IllegalArgumentException("Animated parameter must define 'start' and 'end', 'keyframes', 'expr' or 'value': " + element);
			}
			if (object.has("multiply")) {
				spec = spec.multiplied(parseBound(GsonHelper.getAsJsonObject(object, "multiply")));
			}
			return spec;
		}

		throw new IllegalArgumentException("Unsupported parameter value: " + element);
	}

	private static List<Keyframe> parseKeyframes(final JsonObject object) {
		JsonArray array = GsonHelper.getAsJsonArray(object, "keyframes");
		List<Keyframe> keyframes = new ArrayList<>();
		for (JsonElement entry : array) {
			JsonObject frame = GsonHelper.convertToJsonObject(entry, "keyframe");
			float time = GsonHelper.getAsFloat(frame, "time");
			float value = GsonHelper.getAsFloat(frame, "value");
			EasingFunction easing = parseEasing(frame.get("easing"));
			keyframes.add(new Keyframe(time, value, easing));
		}
		return keyframes;
	}

	private static BoundParam parseBound(final JsonObject object) {
		BoundParam.Kind kind = BoundParam.Kind.fromString(GsonHelper.getAsString(object, "bind"));
		double x = 0.0;
		double y = 0.0;
		double z = 0.0;
		if (kind.needsPos()) {
			JsonArray pos = GsonHelper.getAsJsonArray(object, "pos");
			if (pos.size() != 3) {
				throw new IllegalArgumentException("Binding 'pos' must be an array of [x, y, z]: " + object);
			}
			x = pos.get(0).getAsDouble();
			y = pos.get(1).getAsDouble();
			z = pos.get(2).getAsDouble();
		}
		float defaultRange = switch (kind) {
			case LOOK -> 90.0F;
			case SPEED -> 5.0F;
			default -> 16.0F;
		};
		float range = GsonHelper.getAsFloat(object, "range", defaultRange);
		boolean invert = GsonHelper.getAsBoolean(object, "invert", false);
		float scale = GsonHelper.getAsFloat(object, "scale", 1.0F);
		float yaw = GsonHelper.getAsFloat(object, "yaw", 0.0F);
		float pitch = GsonHelper.getAsFloat(object, "pitch", 0.0F);
		return new BoundParam(kind, x, y, z, yaw, pitch, range, invert, scale);
	}

	/**
	 * Builds a playable timeline for an instance of this effect.
	 *
	 * @param durationTicks the effective duration in ticks (payload value, or the definition default)
	 * @param overrides     user-supplied constant parameter overrides (may be empty)
	 * @param easing        the effective easing curve (payload value, or the definition default)
	 * @param instanceSeed  per-instance seed used to vary {@code random()}/{@code noise()} inside
	 *                      {@code expr} parameters between instances
	 */
	public VFXTimeline createTimeline(final float durationTicks, final Map<String, Float> overrides, final EasingFunction easing, final long instanceSeed) {
		float duration = Math.max(1.0F, durationTicks);
		Map<String, AnimatedValue> values = new LinkedHashMap<>();
		Map<String, BoundParam> bindings = new LinkedHashMap<>();
		Map<String, BoundParam> multipliers = new LinkedHashMap<>();
		Map<String, MathExpression> expressions = new LinkedHashMap<>();
		for (Map.Entry<String, ParamSpec> entry : this.params.entrySet()) {
			String name = entry.getKey();
			ParamSpec spec = entry.getValue();
			Float override = overrides.get(name);
			if (override != null) {
				values.put(name, AnimatedValue.constant(override));
			} else if (spec.bound() != null) {
				bindings.put(name, spec.bound());
			} else if (spec.exprSource() != null) {
				MathExpression expr = MathExpression.compile(instanceSeed, spec.exprSource());
				if (expr == null) {
					LOGGER.warn("Invalid expr for parameter '{}' in '{}': '{}'", name, this.id, spec.exprSource());
					values.put(name, AnimatedValue.constant(0.0F));
				} else {
					expressions.put(name, expr);
				}
			} else if (!spec.keyframes().isEmpty()) {
				values.put(name, AnimatedValue.fromKeyframes(spec.keyframes().toArray(new Keyframe[0])));
			} else if (spec.animated()) {
				values.put(name, AnimatedValue.between(0.0F, duration, spec.start(), spec.end(), easing));
			} else {
				values.put(name, AnimatedValue.constant(spec.constant()));
			}
			if (spec.multiply() != null) {
				multipliers.put(name, spec.multiply());
			}
		}
		// Overrides for parameters the definition does not declare (e.g. through_blocks on the
		// built-in entity/block effects) must still land in the timeline — renderers read them
		// with getParam(name, fallback), so silently dropping them made such parameters inert.
		// Logged once per play so map-makers notice typos like "through_bloks".
		for (Map.Entry<String, Float> entry : overrides.entrySet()) {
			if (!values.containsKey(entry.getKey())) {
				values.put(entry.getKey(), AnimatedValue.constant(entry.getValue()));
				LOGGER.info("Effect '{}' received an override for undeclared parameter '{}' (applied as a constant)", this.getId(), entry.getKey());
			}
		}
		return new VFXTimeline(duration, values, bindings, multipliers, expressions);
	}

	/**
	 * Returns a constant parameter value, or the fallback when the parameter is absent, animated or bound.
	 */
	public float getParam(final String name, final float fallback) {
		ParamSpec spec = this.params.get(name);
		if (spec == null || spec.animated() || !spec.keyframes().isEmpty() || spec.bound() != null || spec.exprSource() != null) {
			return fallback;
		}
		return spec.constant();
	}

	public Identifier getId() {
		return this.id;
	}

	public VFXEffectType getType() {
		return this.type;
	}

	public int getDefaultDuration() {
		return this.defaultDuration;
	}

	public EasingFunction getDefaultEasing() {
		return this.defaultEasing;
	}

	public Map<String, ParamSpec> getParams() {
		return this.params;
	}

	/**
	 * True when instances of this definition run forever until stopped.
	 */
	public boolean isPersistent() {
		return this.persistent;
	}

	/**
	 * True when the timeline restarts from the beginning every time it reaches its duration.
	 * Looping effects are implicitly persistent.
	 */
	public boolean isLoop() {
		return this.loop;
	}

	/**
	 * Fade duration in ticks used when a persistent instance starts or is stopped.
	 */
	public int getFadeTicks() {
		return this.fadeTicks;
	}

	/**
	 * Child effects of a collection definition (empty for regular effects).
	 */
	public List<ChildEffect> getChildren() {
		return this.children;
	}

	/**
	 * World positions this effect applies to (for world-space effects such as block highlighting).
	 */
	public List<BlockPos> getPositions() {
		return this.positions;
	}

	/**
	 * Optional sound event played on the client when the effect starts.
	 */
	public @Nullable Identifier getSound() {
		return this.sound;
	}

	/**
	 * Optional entity selector string (e.g. {@code "@e[type=minecraft:zombie,distance=..10]"}).
	 * When present, the server resolves it to the target entities' UUIDs on every play, so an
	 * entity effect can be triggered with plain {@code /vfx play} (no {@code playentity} needed).
	 */
	public @Nullable String getEntitySelector() {
		return this.entitySelector;
	}

	/**
	 * A single parameter specification: a constant value, an animated start/end pair, a list
	 * of keyframes (in ticks relative to the effect start), a compiled mathematical expression,
	 * a world binding and/or a multiplicative world binding ({@link #multiply()}) applied on top
	 * of the base value.
	 *
	 * @param animated  true when the parameter animates between {@link #start()} and {@link #end()}
	 * @param constant  constant value (when not animated)
	 * @param start     start value (when animated)
	 * @param end       end value (when animated)
	 * @param keyframes keyframe list (when keyframed); empty otherwise
	 * @param exprSource the raw {@code "expr"} source string (compiled per instance with its seed);
	 *                   {@code null} when not used
	 * @param bound     world binding (when bound); {@code null} otherwise
	 * @param multiply  world binding whose evaluated value is multiplied onto the base value
	 *                  (e.g. keyframes × proximity); {@code null} when not used
	 */
	public record ParamSpec(boolean animated, float constant, float start, float end, List<Keyframe> keyframes, String exprSource, BoundParam bound, BoundParam multiply) {
		public static ParamSpec constant(final float value) {
			return new ParamSpec(false, value, 0.0F, 0.0F, List.of(), null, null, null);
		}

		public static ParamSpec animated(final float start, final float end) {
			return new ParamSpec(true, 0.0F, start, end, List.of(), null, null, null);
		}

		public static ParamSpec keyframed(final List<Keyframe> keyframes) {
			return new ParamSpec(false, 0.0F, 0.0F, 0.0F, List.copyOf(keyframes), null, null, null);
		}

		public static ParamSpec expression(final String exprSource) {
			return new ParamSpec(false, 0.0F, 0.0F, 0.0F, List.of(), exprSource, null, null);
		}

		public static ParamSpec bound(final BoundParam binding) {
			return new ParamSpec(false, 0.0F, 0.0F, 0.0F, List.of(), null, binding, null);
		}

		/**
		 * Returns a copy of this spec with the given multiplicative binding attached.
		 */
		public ParamSpec multiplied(final BoundParam multiplier) {
			return new ParamSpec(this.animated, this.constant, this.start, this.end, this.keyframes, this.exprSource, this.bound, multiplier);
		}
	}
}

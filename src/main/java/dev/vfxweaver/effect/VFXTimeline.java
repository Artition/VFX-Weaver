package dev.vfxweaver.effect;

import dev.vfxweaver.field.VFXField;
import dev.vfxweaver.field.VFXFieldProgram;
import dev.vfxweaver.graph.VFXGraph;
import dev.vfxweaver.graph.VFXGraphEvaluator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A collection of named {@link AnimatedValue}s that together drive a single visual effect.
 * The timeline has a total duration in ticks; all values are advanced together with
 * {@link #update(float)} and queried by name with {@link #getValue(String, float)}.
 *
 * <p>Live overrides (added at runtime, e.g. via {@code /vfx set}) are stored separately and
 * win over both world bindings and the definition values.
 */
public class VFXTimeline {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/timeline");
	/** Safety cap for runtime overrides (network/command input, see AGENTS.md). */
	private static final int MAX_OVERRIDES = 32;

	private final float duration;
	private Map<String, AnimatedValue> values;
	private Map<String, BoundParam> bindings;
	private Map<String, BoundParam> multipliers;
	private Map<String, MathExpression> expressions;
	private final @Nullable VFXGraph graph;
	private final Map<String, String> graphInputs;
	private final @Nullable VFXGraphEvaluator graphEvaluator;
	private final Map<String, VFXField> fields;
	private final Map<String, VFXFieldProgram> fieldPrograms;
	private final boolean fieldNeedsDepth;
	private final Map<String, AnimatedValue> overrides = new LinkedHashMap<>();
	private float elapsed;

	/**
	 * Creates a timeline with the given duration and value set.
	 *
	 * @param duration total duration in ticks
	 * @param values   named animated values
	 */
	public VFXTimeline(final float duration, final Map<String, AnimatedValue> values) {
		this(duration, values, Map.of(), Map.of(), Map.of());
	}

	/**
	 * Creates a timeline with time-animated values and world bindings.
	 *
	 * @param duration total duration in ticks
	 * @param values   named animated values
	 * @param bindings named world bindings (evaluated per frame against the camera)
	 */
	public VFXTimeline(final float duration, final Map<String, AnimatedValue> values, final Map<String, BoundParam> bindings) {
		this(duration, values, bindings, Map.of(), Map.of());
	}

	/**
	 * Creates a timeline with time-animated values, world bindings and multiplicative modifiers.
	 *
	 * @param duration    total duration in ticks
	 * @param values      named animated values
	 * @param bindings    named world bindings (evaluated per frame against the camera)
	 * @param multipliers named bindings whose evaluated value is multiplied onto the base value
	 *                    of the same parameter (e.g. keyframes × proximity falloff)
	 */
	public VFXTimeline(final float duration, final Map<String, AnimatedValue> values, final Map<String, BoundParam> bindings, final Map<String, BoundParam> multipliers) {
		this(duration, values, bindings, multipliers, Map.of());
	}

	/**
	 * Creates a timeline with time-animated values, world bindings, multiplicative modifiers and
	 * compiled math expressions.
	 *
	 * @param duration    total duration in ticks
	 * @param values      named animated values
	 * @param bindings    named world bindings (evaluated per frame against the camera)
	 * @param multipliers named bindings whose evaluated value is multiplied onto the base value
	 *                    of the same parameter (e.g. keyframes × proximity falloff)
	 * @param expressions named compiled expressions (evaluated with t/x/y/z per frame)
	 */
	public VFXTimeline(final float duration, final Map<String, AnimatedValue> values, final Map<String, BoundParam> bindings, final Map<String, BoundParam> multipliers, final Map<String, MathExpression> expressions) {
		this(duration, values, bindings, multipliers, expressions, null, Map.of(), Map.of(), 0L);
	}

	/**
	 * Creates a timeline that also drives a uniform graph.
	 *
	 * @param graph       the definition's graph, or {@code null} for a definition without one
	 * @param graphInputs effect input name to source node id (inputs block plus graph edges)
	 * @param fields      per-pixel fields declared on field-capable inputs (empty when none)
	 * @param graphSeed   per-instance seed passed to the graph evaluator
	 */
	public VFXTimeline(final float duration, final Map<String, AnimatedValue> values, final Map<String, BoundParam> bindings, final Map<String, BoundParam> multipliers, final Map<String, MathExpression> expressions, final @Nullable VFXGraph graph, final Map<String, String> graphInputs, final Map<String, VFXField> fields, final long graphSeed) {
		this.duration = duration;
		this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
		this.bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
		this.multipliers = Collections.unmodifiableMap(new LinkedHashMap<>(multipliers));
		this.expressions = Collections.unmodifiableMap(new LinkedHashMap<>(expressions));
		this.graph = graph;
		this.graphInputs = Map.copyOf(graphInputs);
		this.graphEvaluator = graph == null ? null : new VFXGraphEvaluator(graph, graphSeed);
		this.fields = Map.copyOf(fields);
		final Map<String, VFXFieldProgram> packed = new LinkedHashMap<>();
		boolean needsDepth = false;
		for (final Map.Entry<String, VFXField> entry : this.fields.entrySet()) {
			packed.put(entry.getKey(), VFXFieldProgram.of(entry.getKey(), entry.getValue(), graph));
			needsDepth |= entry.getValue().needsDepth();
		}
		this.fieldPrograms = Map.copyOf(packed);
		this.fieldNeedsDepth = needsDepth;
		this.elapsed = 0.0F;
	}

	/**
	 * Advances the whole timeline to the given elapsed time (in ticks) since the effect started.
	 *
	 * @param now elapsed time in ticks
	 */
	public void update(final float now) {
		this.elapsed = Math.max(0.0F, now);
		for (AnimatedValue value : this.values.values()) {
			value.update(this.elapsed);
		}
		for (AnimatedValue value : this.overrides.values()) {
			value.update(this.elapsed);
		}
	}

	/**
	 * Advances the graph to the current frame. Call once per frame, after {@link #update(float)};
	 * a no-op when the definition has no graph.
	 *
	 * @param now elapsed effect time in ticks (the same value passed to {@link #update(float)})
	 */
	public void updateGraph(final float now) {
		if (this.graphEvaluator != null) {
			this.graphEvaluator.beginFrame(now);
		}
	}

	/**
	 * The definition's uniform graph, or {@code null}.
	 */
	public @Nullable VFXGraph getGraph() {
		return this.graph;
	}

	/**
	 * The per-pixel fields declared by the definition, by input name.
	 */
	public Map<String, VFXField> getFields() {
		return this.fields;
	}

	/**
	 * The packed shader program of a field-capable input, or {@code null} when it has no field.
	 */
	public @Nullable VFXFieldProgram getFieldProgram(final String input) {
		return this.fieldPrograms.get(input);
	}

	/**
	 * True when any declared field needs the scene depth (a geometry function or world space).
	 */
	public boolean fieldNeedsDepth() {
		return this.fieldNeedsDepth;
	}

	/**
	 * Evaluates a graph node by its precomputed slot for the current frame, used by the packed
	 * field programs. Returns {@code fallback} when the definition has no graph.
	 */
	public float evaluateGraphIndex(final int index, final float fallback) {
		return this.graphEvaluator == null ? fallback : this.graphEvaluator.evaluateIndex(index, fallback);
	}

	/**
	 * The per-frame graph evaluator, or {@code null} when the definition has no graph. Read by the
	 * client field writer without allocation.
	 */
	public @Nullable VFXGraphEvaluator getGraphEvaluator() {
		return this.graphEvaluator;
	}

	/**
	 * Reads the current value of the given parameter. Runtime overrides win over world-bound
	 * parameters, which in turn are evaluated against the camera state fed by the client.
	 *
	 * @param name     parameter name
	 * @param fallback value returned when the parameter is not present
	 */
	public float getValue(final String name, final float fallback) {
		AnimatedValue override = this.overrides.get(name);
		if (override != null) {
			return override.get();
		}
		final String graphNode = this.graphInputs.get(name);
		if (graphNode != null && this.graphEvaluator != null) {
			return this.graphEvaluator.evaluate(graphNode, fallback);
		}
		BoundParam binding = this.bindings.get(name);
		float base;
		if (binding != null) {
			base = VFXWorldBindings.evaluate(binding, fallback);
		} else {
			MathExpression expr = this.expressions.get(name);
			if (expr != null) {
				float[] pos = VFXWorldBindings.cameraPosition();
				return expr.eval(this.elapsed, pos[0], pos[1], pos[2]);
			}
			AnimatedValue value = this.values.get(name);
			base = value == null ? fallback : value.get();
		}
		BoundParam multiplier = this.multipliers.get(name);
		if (multiplier != null) {
			// Neutral multiplier (no camera state available) is 1.0 so the base value is unchanged.
			base *= VFXWorldBindings.evaluate(multiplier, 1.0F);
		}
		return base;
	}

	/**
	 * True when the elapsed time reached or exceeded the timeline duration.
	 */
	public boolean isFinished() {
		return this.elapsed >= this.duration;
	}

	/**
	 * True when every runtime parameter edit (keyframes from {@code /vfx key}, constants from
	 * {@code /vfx set}) has completed and rests at zero — i.e. the edits that were driving the
	 * effect no longer contribute anything visible. Definition-driven values are not considered:
	 * a plain persistent effect without runtime edits never counts as zeroed out.
	 */
	public boolean isZeroedOut() {
		if (this.overrides.isEmpty()) {
			return false;
		}
		for (final AnimatedValue value : this.overrides.values()) {
			Keyframe rest = null;
			for (final Keyframe frame : value.getKeyframes()) {
				if (frame.time() <= this.elapsed) {
					rest = frame;
				}
			}
			if (rest == null || rest.value() != 0.0F) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Normalized progress in {@code [0, 1]}.
	 */
	public float getProgress() {
		return this.duration <= 0.0F ? 1.0F : Math.min(1.0F, this.elapsed / this.duration);
	}

	public float getDuration() {
		return this.duration;
	}

	public float getElapsed() {
		return this.elapsed;
	}

	public Map<String, AnimatedValue> getValues() {
		return this.values;
	}

	/**
	 * Names of the runtime-added constant overrides (e.g. from {@code /vfx set}), which are stored
	 * separately from the definition values. {@link #getValue(String, float)} already prefers them
	 * over bindings and definition values.
	 */
	public java.util.Set<String> getOverrideNames() {
		return java.util.Set.copyOf(this.overrides.keySet());
	}

	public Map<String, BoundParam> getBindings() {
		return this.bindings;
	}

	public Map<String, BoundParam> getMultipliers() {
		return this.multipliers;
	}

	public Map<String, MathExpression> getExpressions() {
		return this.expressions;
	}

	/**
	 * Re-anchors every spatial binding ({@code screen_x}/{@code screen_y}/{@code proximity})
	 * to the given world position — used when an explicit position override arrives (e.g.
	 * {@code /vfx playat}), letting world-bound effects be re-targeted per play.
	 *
	 * @param x world X
	 * @param y world Y
	 * @param z world Z
	 */
	public void rebindPositions(final double x, final double y, final double z) {
		this.bindings = rebindAll(this.bindings, x, y, z);
		this.multipliers = rebindAll(this.multipliers, x, y, z);
	}

	private static Map<String, BoundParam> rebindAll(final Map<String, BoundParam> source, final double x, final double y, final double z) {
		final Map<String, BoundParam> updated = new LinkedHashMap<>();
		for (final Map.Entry<String, BoundParam> entry : source.entrySet()) {
			final BoundParam binding = entry.getValue();
			if (binding.kind().needsPos()) {
				updated.put(entry.getKey(), new BoundParam(binding.kind(), x, y, z, binding.yaw(), binding.pitch(), binding.range(), binding.invert(), binding.scale(), null, null));
			} else {
				updated.put(entry.getKey(), binding);
			}
		}
		return Collections.unmodifiableMap(updated);
	}

	/**
	 * Live-overrides the parameter with a constant, winning over any binding or animation
	 * from the definition (used by {@code /vfx set}).
	 *
	 * @param name  parameter name
	 * @param value the new constant value
	 */
	public void setOverride(final String name, final float value) {
		this.putOverride(name, AnimatedValue.constant(value));
	}

	/**
	 * Adds or replaces a keyframe of the parameter on a live copy of its animation (used by
	 * {@code /vfx set} + the network {@code KEYFRAME} action). When the parameter has no animation
	 * yet, a step animation starting at the keyframe is created.
	 *
	 * <p><b>Negative {@code time} means "from here":</b> the value the parameter has right now is
	 * pinned at the current elapsed time, and the animation then runs to {@code value} over
	 * {@code |time|} ticks. This makes consecutive animation segments chain seamlessly without the
	 * caller knowing the current value, e.g. ramp {@code radius} 0 -&gt; 4 and later send
	 * {@code time = -20, value = 0} to fade that blur out from wherever it stands.</p>
	 *
	 * @param name    parameter name
	 * @param time    keyframe time in ticks from the effect start, or a negative tick count to
	 *                start the segment at the current time (see above)
	 * @param value   keyframe value
	 * @param easing  easing curve towards the next keyframe
	 */
	public void setKeyframe(final String name, final float time, final float value, final EasingFunction easing) {
		AnimatedValue base = this.overrides.get(name);
		if (base == null) {
			base = this.values.get(name);
		}
		final boolean fromNow = time < 0.0F;
		// A negative time counts from the current moment; the constructor sorts the frames anyway.
		final float at = fromNow ? this.elapsed - time : time;
		List<Keyframe> frames = base != null ? new ArrayList<>(base.getKeyframes()) : new ArrayList<>();
		if (fromNow) {
			// Pin what is on screen right now so the new segment starts where the previous one stands.
			frames.removeIf(frame -> Float.compare(frame.time(), this.elapsed) == 0);
			frames.add(new Keyframe(this.elapsed, this.getValue(name, value), easing));
		}
		frames.removeIf(frame -> Float.compare(frame.time(), at) == 0);
		frames.add(new Keyframe(at, value, easing));
		if (frames.size() < 2) {
			// Single keyframe: hold the value before it (step function).
			frames.add(new Keyframe(Float.MAX_VALUE, value));
		}
		this.putOverride(name, AnimatedValue.fromKeyframes(frames.toArray(new Keyframe[0])));
	}

	/**
	 * Live-replaces a parameter with a compiled math expression (used by the network
	 * {@code SET_EXPR} action). The expression is evaluated per frame with {@code t} and the
	 * camera position, and wins over whatever the parameter had before: any entry under this
	 * name is removed from the value, binding and multiplier maps. When the source cannot be
	 * compiled, a warning is logged and the parameter falls back to a constant {@code 0}
	 * (matching the definition loading behavior for invalid expressions).
	 *
	 * @param name   parameter name
	 * @param source expression source (may be null, which falls back to {@code 0})
	 * @param seed   per-instance seed used to drive {@code random()}/{@code noise()} in the expression
	 */
	public void setExpression(final String name, final String source, final long seed) {
		MathExpression expr = MathExpression.compile(seed, source);
		if (expr == null) {
			LOGGER.warn("Invalid expression for parameter '{}': '{}' (falling back to 0)", name, source);
		}
		Map<String, MathExpression> expressions = new LinkedHashMap<>(this.expressions);
		Map<String, AnimatedValue> values = new LinkedHashMap<>(this.values);
		if (expr != null) {
			evictOldest(expressions, name);
			expressions.put(name, expr);
			values.remove(name);
		} else {
			evictOldest(values, name);
			values.put(name, AnimatedValue.constant(0.0F));
			expressions.remove(name);
		}
		Map<String, BoundParam> bindings = new LinkedHashMap<>(this.bindings);
		Map<String, BoundParam> multipliers = new LinkedHashMap<>(this.multipliers);
		bindings.remove(name);
		multipliers.remove(name);
		this.expressions = Collections.unmodifiableMap(expressions);
		this.values = Collections.unmodifiableMap(values);
		this.bindings = Collections.unmodifiableMap(bindings);
		this.multipliers = Collections.unmodifiableMap(multipliers);
	}

	private void putOverride(final String name, final AnimatedValue value) {
		evictOldest(this.overrides, name);
		this.overrides.put(name, value);
		value.update(this.elapsed);
	}

	/** Bounds map growth from network/command input: evicts the oldest entry when full and the name is new. */
	private static void evictOldest(final Map<String, ?> map, final String name) {
		if (map.size() >= MAX_OVERRIDES && !map.containsKey(name)) {
			Iterator<String> it = map.keySet().iterator();
			if (it.hasNext()) {
				it.next();
				it.remove();
			}
		}
	}
}

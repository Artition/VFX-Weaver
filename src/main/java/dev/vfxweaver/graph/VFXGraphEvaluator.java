package dev.vfxweaver.graph;

import dev.vfxweaver.effect.MathExpression;
import dev.vfxweaver.effect.VFXWorldBindings;
import dev.vfxweaver.noise.SimplexNoise;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * Pull-based CPU evaluator for one {@link VFXGraph} instance. Created per running effect (in the
 * {@code VFXTimeline}), it evaluates each node at most once per frame using an epoch-stamped memo:
 * {@link #beginFrame(float)} invalidates the previous frame, and shared nodes are computed once
 * no matter how many inputs reference them. After construction the hot path allocates nothing
 * itself (the existing {@code MathExpression} and {@code VFXWorldBindings} implementations do
 * allocate internally, unchanged, for {@code expr} and spatial {@code bind} nodes).
 */
public final class VFXGraphEvaluator {
	private final VFXGraph graph;
	private final float[] cache;
	private final int[] stamp;
	private final MathExpression[] expressions;
	private final long seed;
	private int epoch;
	private float elapsed;
	private float camX;
	private float camY;
	private float camZ;

	/**
	 * Creates an evaluator for one effect instance.
	 *
	 * @param graph the parsed graph
	 * @param seed  per-instance seed driving {@code random} and {@code expr}'s {@code random()}
	 */
	public VFXGraphEvaluator(final VFXGraph graph, final long seed) {
		this.graph = graph;
		this.seed = seed;
		this.cache = new float[graph.nodeCount()];
		this.stamp = new int[graph.nodeCount()];
		Arrays.fill(this.stamp, -1);
		this.expressions = new MathExpression[graph.nodeCount()];
		for (int i = 0; i < graph.nodeCount(); i++) {
			final VFXGraphNode node = graph.nodes().get(i);
			if (node.kind() == VFXNodeKind.EXPR) {
				this.expressions[i] = MathExpression.compile(seed, node.exprSource());
			}
		}
	}

	/**
	 * Starts a new frame: captures the camera snapshot and invalidates the memo so every node is
	 * evaluated once against the new {@code elapsedTicks}.
	 *
	 * @param elapsedTicks elapsed effect time in ticks (same value the timeline animates on)
	 */
	public void beginFrame(final float elapsedTicks) {
		this.elapsed = elapsedTicks;
		final VFXWorldBindings.Frame frame = VFXWorldBindings.currentFrame();
		if (frame != null) {
			this.camX = frame.camX();
			this.camY = frame.camY();
			this.camZ = frame.camZ();
		} else {
			this.camX = 0.0F;
			this.camY = 0.0F;
			this.camZ = 0.0F;
		}
		if (++this.epoch == 0) {
			Arrays.fill(this.stamp, -1);
			this.epoch = 1;
		}
	}

	/**
	 * Evaluates a node for the current frame.
	 *
	 * @param nodeId   stable node id
	 * @param fallback value returned when the node does not exist, and the value a {@code bind}
	 *                 node resolves to when it has no camera and no explicit {@code fallback} input
	 */
	public float evaluate(final String nodeId, final float fallback) {
		final Integer index = this.graph.indexOf(nodeId);
		return index == null ? fallback : eval(index, fallback);
	}

	/**
	 * Evaluates a node by its precomputed cache slot, for packed consumers (the field program)
	 * that resolved {@code { "from": <node> }} ids once at pack time.
	 *
	 * @param index    slot from {@link VFXGraph#indexOf(String)}
	 * @param fallback value returned when the slot is out of range
	 */
	public float evaluateIndex(final int index, final float fallback) {
		return index < 0 || index >= this.cache.length ? fallback : eval(index);
	}

	private float eval(final int index) {
		return eval(index, 0.0F);
	}

	private float eval(final int index, final float fallback) {
		if (this.stamp[index] == this.epoch) {
			return this.cache[index];
		}
		final float value = compute(this.graph.nodes().get(index), index, fallback);
		this.stamp[index] = this.epoch;
		this.cache[index] = value;
		return value;
	}

	private float input(final VFXGraphNode node, final String name, final float fallback) {
		return resolve(node.inputs().get(name), fallback);
	}

	private float resolve(final @Nullable VFXGraphInput in, final float fallback) {
		if (in == null) {
			return fallback;
		}
		if (!in.reference()) {
			return in.literal();
		}
		final Integer index = this.graph.indexOf(in.node());
		return index == null ? fallback : eval(index);
	}

	private float compute(final VFXGraphNode node, final int index, final float fallback) {
		return switch (node.kind()) {
			case CONSTANT -> input(node, "value", 0.0F);
			case TIME -> this.elapsed * input(node, "speed", 1.0F) + input(node, "offset", 0.0F);
			case RANDOM -> {
				final float min = input(node, "min", 0.0F);
				final float max = input(node, "max", 1.0F);
				final float indexValue = input(node, "index", 0.0F);
				yield min + (max - min) * hash01(this.seed, (long) indexValue);
			}
			case NOISE -> {
				final float x = input(node, "x", this.elapsed);
				final float y = input(node, "y", 0.0F);
				final float z = input(node, "z", 0.0F);
				final float scale = input(node, "scale", 1.0F);
				final int octaves = Math.max(1, Math.min(VFXGraph.MAX_NOISE_OCTAVES, Math.round(input(node, "octaves", 1.0F))));
				final float gain = input(node, "gain", 0.5F);
				final float lacunarity = input(node, "lacunarity", 2.0F);
				double sum = 0.0;
				double amplitude = 1.0;
				double frequency = 1.0;
				double norm = 0.0;
				for (int i = 0; i < octaves; i++) {
					sum += amplitude * SimplexNoise.noise(x * scale * frequency, y * scale * frequency, z * scale * frequency);
					norm += amplitude;
					amplitude *= gain;
					frequency *= lacunarity;
				}
				yield norm > 0.0 ? (float) (sum / norm) : 0.0F;
			}
			case CURVE -> curve(node, input(node, "time", this.elapsed));
			case MATH -> math(node);
			case MIX -> {
				final float a = input(node, "a", 0.0F);
				final float b = input(node, "b", 0.0F);
				yield a + (b - a) * input(node, "factor", 0.5F);
			}
			case CLAMP -> {
				final float value = input(node, "value", 0.0F);
				final float min = input(node, "min", 0.0F);
				final float max = input(node, "max", 1.0F);
				yield Math.max(min, Math.min(max, value));
			}
			case REMAP -> {
				final float value = input(node, "value", 0.0F);
				final float inMin = input(node, "in_min", 0.0F);
				final float inMax = input(node, "in_max", 1.0F);
				final float outMin = input(node, "out_min", 0.0F);
				final float outMax = input(node, "out_max", 1.0F);
				final float span = inMax - inMin;
				yield span == 0.0F ? outMin : outMin + (value - inMin) / span * (outMax - outMin);
			}
			case BIND -> VFXWorldBindings.evaluate(node.bound(), input(node, "fallback", fallback));
			case EXPR -> {
				final MathExpression expression = this.expressions[index];
				yield expression == null ? 0.0F : expression.eval(this.elapsed, this.camX, this.camY, this.camZ);
			}
			case COMPARE -> compare(node);
			case BOOLEAN -> bool(node);
			case IF -> input(node, "condition", 0.0F) != 0.0F
				? input(node, "then", 0.0F)
				: input(node, "else", 0.0F);
			case SWITCH -> {
				final int which = Math.round(input(node, "index", 0.0F));
				final VFXGraphInput chosen = node.inputs().get("case_" + which);
				yield chosen == null ? input(node, "default", 0.0F) : resolve(chosen, 0.0F);
			}
		};
	}

	private float compare(final VFXGraphNode node) {
		final float a = input(node, "a", 0.0F);
		final float b = input(node, "b", 0.0F);
		final boolean result = switch (node.compareOp()) {
			case EQ -> a == b;
			case NE -> a != b;
			case LT -> a < b;
			case LE -> a <= b;
			case GT -> a > b;
			case GE -> a >= b;
		};
		return result ? 1.0F : 0.0F;
	}

	/**
	 * The boolean operators. {@code and}/{@code or} short-circuit: the {@code b} operand is
	 * evaluated only when it can change the result, so a branch hidden behind a constant gets no
	 * {@code eval} call at all.
	 */
	private float bool(final VFXGraphNode node) {
		final boolean a = input(node, "a", 0.0F) != 0.0F;
		final boolean result = switch (node.booleanOp()) {
			case AND -> a && input(node, "b", 0.0F) != 0.0F;
			case OR -> a || input(node, "b", 0.0F) != 0.0F;
			case XOR -> a ^ (input(node, "b", 0.0F) != 0.0F);
			case NOT -> !a;
		};
		return result ? 1.0F : 0.0F;
	}

	private float math(final VFXGraphNode node) {
		final float a = input(node, "a", 0.0F);
		final float b = input(node, "b", 0.0F);
		return switch (node.mathOp()) {
			case ADD -> a + b;
			case SUBTRACT -> a - b;
			case MULTIPLY -> a * b;
			case DIVIDE -> b == 0.0F ? 0.0F : a / b;
			case MIN -> Math.min(a, b);
			case MAX -> Math.max(a, b);
			case POW -> (float) Math.pow(a, b);
			case MOD -> b == 0.0F ? 0.0F : ((a % b) + b) % b;
			case FLOOR -> (float) Math.floor(a);
		};
	}

	private static float curve(final VFXGraphNode node, final float time) {
		final float[] times = node.curveTimes();
		final float[] values = node.curveValues();
		if (time <= times[0]) {
			return values[0];
		}
		if (time >= times[times.length - 1]) {
			return values[values.length - 1];
		}
		for (int i = 0; i < times.length - 1; i++) {
			if (time >= times[i] && time <= times[i + 1]) {
				final float span = times[i + 1] - times[i];
				final float local = span <= 0.0F ? 1.0F : (time - times[i]) / span;
				return values[i] + (values[i + 1] - values[i]) * node.curveEasings()[i].apply(local);
			}
		}
		return values[values.length - 1];
	}

	private static float hash01(final long seed, final long index) {
		long h = seed ^ (index * 0x9E3779B97F4A7C15L);
		h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
		h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
		h = h ^ (h >>> 31);
		return (h & 0xFFFFFFFFL) / (float) 0x100000000L;
	}
}

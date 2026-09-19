package dev.vfxweaver.graph;

import dev.vfxweaver.effect.BoundParam;
import dev.vfxweaver.effect.EasingFunction;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One parsed uniform-graph node. Kind-specific configuration lives in the nullable fields
 * below; a node only uses the fields matching its {@link #kind()}. Immutable after parse.
 */
public final class VFXGraphNode {
	/** The arithmetic operators accepted by a {@code math} node. */
	public enum MathOp {
		ADD("add"), SUBTRACT("subtract"), MULTIPLY("multiply"), DIVIDE("divide"),
		MIN("min"), MAX("max"), POW("pow"), MOD("mod");

		private final String id;

		MathOp(final String id) {
			this.id = id;
		}

		public String id() {
			return this.id;
		}

		/**
		 * Resolves an operator from its datapack spelling.
		 *
		 * @param name raw string, e.g. {@code "multiply"}
		 * @return the matching operator, or {@code null} when unknown
		 */
		public static @Nullable MathOp fromString(final String name) {
			if (name == null) {
				return null;
			}
			for (final MathOp op : values()) {
				if (op.id.equalsIgnoreCase(name.trim())) {
					return op;
				}
			}
			return null;
		}
	}

	/** Comparison operators accepted by a {@code compare} node. Output is {@code 1} or {@code 0}. */
	public enum CompareOp {
		EQ("eq"), NE("ne"), LT("lt"), LE("le"), GT("gt"), GE("ge");

		private final String id;

		CompareOp(final String id) {
			this.id = id;
		}

		public String id() {
			return this.id;
		}

		/**
		 * Resolves an operator from its datapack spelling.
		 *
		 * @param name raw string, e.g. {@code "lt"}
		 * @return the matching operator, or {@code null} when unknown
		 */
		public static @Nullable CompareOp fromString(final String name) {
			if (name == null) {
				return null;
			}
			for (final CompareOp op : values()) {
				if (op.id.equalsIgnoreCase(name.trim())) {
					return op;
				}
			}
			return null;
		}
	}

	/** Boolean operators accepted by a {@code boolean} node. Output is {@code 1} or {@code 0}. */
	public enum BooleanOp {
		AND("and"), OR("or"), XOR("xor"), NOT("not");

		private final String id;

		BooleanOp(final String id) {
			this.id = id;
		}

		public String id() {
			return this.id;
		}

		/**
		 * Resolves an operator from its datapack spelling.
		 *
		 * @param name raw string, e.g. {@code "and"}
		 * @return the matching operator, or {@code null} when unknown
		 */
		public static @Nullable BooleanOp fromString(final String name) {
			if (name == null) {
				return null;
			}
			for (final BooleanOp op : values()) {
				if (op.id.equalsIgnoreCase(name.trim())) {
					return op;
				}
			}
			return null;
		}
	}

	private final String id;
	private final VFXNodeKind kind;
	private final Map<String, VFXGraphInput> inputs;
	private final @Nullable String exprSource;
	private final @Nullable MathOp mathOp;
	private final @Nullable CompareOp compareOp;
	private final @Nullable BooleanOp booleanOp;
	private final float[] curveTimes;
	private final float[] curveValues;
	private final EasingFunction[] curveEasings;
	private final @Nullable BoundParam bound;

	VFXGraphNode(final String id, final VFXNodeKind kind, final Map<String, VFXGraphInput> inputs, final @Nullable String exprSource, final @Nullable MathOp mathOp, final @Nullable CompareOp compareOp, final @Nullable BooleanOp booleanOp, final float[] curveTimes, final float[] curveValues, final EasingFunction[] curveEasings, final @Nullable BoundParam bound) {
		this.id = id;
		this.kind = kind;
		this.inputs = inputs;
		this.exprSource = exprSource;
		this.mathOp = mathOp;
		this.compareOp = compareOp;
		this.booleanOp = booleanOp;
		this.curveTimes = curveTimes;
		this.curveValues = curveValues;
		this.curveEasings = curveEasings;
		this.bound = bound;
	}

	public String id() {
		return this.id;
	}

	public VFXNodeKind kind() {
		return this.kind;
	}

	public Map<String, VFXGraphInput> inputs() {
		return this.inputs;
	}

	public @Nullable String exprSource() {
		return this.exprSource;
	}

	public @Nullable MathOp mathOp() {
		return this.mathOp;
	}

	public @Nullable CompareOp compareOp() {
		return this.compareOp;
	}

	public @Nullable BooleanOp booleanOp() {
		return this.booleanOp;
	}

	/**
	 * The inputs that must be present (literal or reference) for a valid node. For
	 * {@code and}/{@code or}/{@code xor} the second operand is required; {@code not} uses only
	 * {@code a}, and every other kind delegates to {@link VFXNodeKind#requiredInputs()}.
	 */
	public List<String> requiredInputs() {
		if (this.kind == VFXNodeKind.BOOLEAN && this.booleanOp != BooleanOp.NOT) {
			return List.of("a", "b");
		}
		return this.kind.requiredInputs();
	}

	/** Curve control-point times in ascending order; empty for non-curve nodes. */
	public float[] curveTimes() {
		return this.curveTimes;
	}

	/** Curve control-point values; empty for non-curve nodes. */
	public float[] curveValues() {
		return this.curveValues;
	}

	/** Per-segment easing (segment i eases from point i to point i+1); empty for non-curve nodes. */
	public EasingFunction[] curveEasings() {
		return this.curveEasings;
	}

	public @Nullable BoundParam bound() {
		return this.bound;
	}
}

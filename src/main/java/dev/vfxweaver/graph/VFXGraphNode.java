package dev.vfxweaver.graph;

import dev.vfxweaver.effect.BoundParam;
import dev.vfxweaver.effect.EasingFunction;
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

	private final String id;
	private final VFXNodeKind kind;
	private final Map<String, VFXGraphInput> inputs;
	private final @Nullable String exprSource;
	private final @Nullable MathOp mathOp;
	private final float[] curveTimes;
	private final float[] curveValues;
	private final EasingFunction[] curveEasings;
	private final @Nullable BoundParam bound;

	VFXGraphNode(final String id, final VFXNodeKind kind, final Map<String, VFXGraphInput> inputs, final @Nullable String exprSource, final @Nullable MathOp mathOp, final float[] curveTimes, final float[] curveValues, final EasingFunction[] curveEasings, final @Nullable BoundParam bound) {
		this.id = id;
		this.kind = kind;
		this.inputs = inputs;
		this.exprSource = exprSource;
		this.mathOp = mathOp;
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

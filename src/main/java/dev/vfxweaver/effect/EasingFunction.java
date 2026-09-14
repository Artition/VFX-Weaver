package dev.vfxweaver.effect;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An interpolation curve: maps a normalized progress value in {@code [0, 1]} to an eased value.
 * Covers both the built-in {@link EasingType} curves and custom curves loaded from the datapack
 * ({@code data/<namespace>/vfx_curves/<name>.json} or inline {@code { "curve": [[t,v],...] }} objects).
 *
 * <p>A curve carries its canonical name so it can travel over the network and be re-resolved on
 * the client (built-in name or {@code namespace:path} curve id).
 */
public final class EasingFunction {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/easing");
	private static final float EPSILON = 1.0e-6F;

	private final String name;
	private final CurveFunction function;
	private final float[] ts;
	private final float[] vs;

	@FunctionalInterface
	private interface CurveFunction {
		float apply(float t);
	}

	private EasingFunction(final String name, final CurveFunction function, final float[] ts, final float[] vs) {
		this.name = name;
		this.function = function;
		this.ts = ts;
		this.vs = vs;
	}

	/**
	 * Wraps a built-in easing type as a function.
	 */
	public static EasingFunction builtIn(final EasingType type) {
		return new EasingFunction(type.name(), type::apply, null, null);
	}

	/**
	 * Creates a custom piecewise-linear curve from control points. The points must be ordered by
	 * ascending {@code t} with the first {@code t == 0} and the last {@code t == 1}.
	 *
	 * @param name the curve's canonical name (its datapack id or "inline")
	 * @param ts   control point times in ascending order
	 * @param vs   control point values
	 * @throws IllegalArgumentException when fewer than two points are given or the times are invalid
	 */
	public static EasingFunction curve(final String name, final float[] ts, final float[] vs) {
		if (ts.length < 2 || ts.length != vs.length) {
			throw new IllegalArgumentException("A curve needs at least two [t, v] control points");
		}
		return new EasingFunction(name, t -> evaluateCurve(ts, vs, t), ts, vs);
	}

	/**
	 * Creates a standard cubic-Bézier easing curve with fixed endpoints (0,0) and (1,1) and the
	 * two control points {@code (x1,y1)} / {@code (x2,y2)} — the same convention as the CSS
	 * {@code cubic-bezier(x1,y1,x2,y2)} function. The y ordinates may exceed [0,1] for
	 * anticipation/overshoot; e.g. ease-out-back = {@code 0.34, 1.56, 0.64, 1}.
	 */
	public static EasingFunction cubicBezier(final String name, final float x1, final float y1, final float x2, final float y2) {
		return new EasingFunction(name, t -> bezierComponent(solveBezierT(x1, x2, t), y1, y2), null, null);
	}

	private static float bezierComponent(final float t, final float a1, final float a2) {
		final float mt = 1.0F - t;
		return 3.0F * mt * mt * t * a1 + 3.0F * mt * t * t * a2 + t * t * t;
	}

	/** Solves for the curve parameter t whose x equals {@code x} (bisection on [0,1]). */
	private static float solveBezierT(final float x1, final float x2, final float x) {
		if (x <= 0.0F) {
			return 0.0F;
		}
		if (x >= 1.0F) {
			return 1.0F;
		}
		float lo = 0.0F;
		float hi = 1.0F;
		float t = 0.5F;
		for (int i = 0; i < 30; i++) {
			t = (lo + hi) * 0.5F;
			if (bezierComponent(t, x1, x2) < x) {
				lo = t;
			} else {
				hi = t;
			}
		}
		return t;
	}

	/**
	 * Resolves an easing name to a function: a built-in {@link EasingType} first, then a custom
	 * curve from the datapack registry. Unknown names fall back to {@link EasingType#LINEAR}.
	 *
	 * @param name raw name, e.g. {@code "ease_out_cubic"}, {@code "my_curve"} or {@code "ns:my_curve"}
	 */
	public static EasingFunction fromString(final String name) {
		if (name == null || name.isBlank()) {
			return builtIn(EasingType.LINEAR);
		}
		String normalized = name.trim().toUpperCase(Locale.ROOT).replace('-', '_');
		for (EasingType type : EasingType.values()) {
			if (type.name().equals(normalized)) {
				return builtIn(type);
			}
		}
		final String curveName = name.trim();
		VFXCurve curve = VFXCurveManager.get().get(curveName);
		if (curve != null) {
			return curve.function();
		}
		// The curve may not be registered yet when a definition is parsed (reload listeners run
		// prepare() before apply()), so resolve it lazily — but cache the hit and warn once on a
		// miss instead of silently degrading to a linear identity.
		final VFXCurve[] cache = new VFXCurve[1];
		final boolean[] warned = new boolean[1];
		return new EasingFunction(curveName, t -> {
			VFXCurve resolved = cache[0];
			if (resolved == null) {
				resolved = VFXCurveManager.get().get(curveName);
				if (resolved == null) {
					if (!warned[0]) {
						warned[0] = true;
						LOGGER.warn("Easing curve '{}' is not registered; affected params will animate LINEAR", curveName);
					}
					return t;
				}
				cache[0] = resolved;
			}
			return resolved.function().apply(t);
		}, null, null);
	}

	/**
	 * The canonical name of this curve (built-in enum name or datapack id) used for network
	 * serialization and logging.
	 */
	public String name() {
		return this.name;
	}

	/**
	 * True for a curve built from inline control points. Its control points are not carried over
	 * the network (only the name is), so an inline curve cannot be reconstructed remotely — it
	 * must be resolved from the receiving side's own definition.
	 */
	public boolean isInline() {
		return "inline".equals(this.name);
	}

	/**
	 * Applies the curve to the given progress value, clamped to {@code [0, 1]}.
	 */
	public float apply(final float progress) {
		if (this.ts != null) {
			if (progress <= EPSILON) {
				return this.vs[0];
			}
			if (progress >= 1.0F - EPSILON) {
				return this.vs[this.vs.length - 1];
			}
		}
		return this.function.apply(progress);
	}

	private static float evaluateCurve(final float[] ts, final float[] vs, final float t) {
		for (int i = 0; i < ts.length - 1; i++) {
			if (t >= ts[i] && t <= ts[i + 1]) {
				float span = ts[i + 1] - ts[i];
				float local = span <= 0.0F ? 1.0F : (t - ts[i]) / span;
				return vs[i] + (vs[i + 1] - vs[i]) * local;
			}
		}
		return vs[vs.length - 1];
	}

	@Override
	public String toString() {
		return "EasingFunction(" + this.name + ")";
	}
}
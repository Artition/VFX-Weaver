package dev.vfxweaver.mask;

import java.util.ArrayList;
import java.util.List;

/**
 * A custom shape reachable from the public API (expanded design). Two families:
 * {@link Family#COMPOSED} carries a fixed composition of the shared primitives (`circle`,
 * `ellipse`, `rect`, `polygon`, `sphere`, `box`) with the composition ops and optional rounding /
 * repeat / transform — no compilation, the default path; {@link Family#GLSL_PLUGIN} carries a
 * {@link VFXMaskShapeGlsl} function, compiled into a bounded shader variant.
 *
 * <p>A registered shape is immutable and uses only literal values (no graph animation inside the
 * shape). When a mask references it as a leaf, the leaf's eight animatable params are passed to the
 * composed parts / plugin, so the shape can still be driven by the mask.
 */
public final class VFXCustomShape {
	/** The most parts a composed custom shape may have (2 composition ops fit in the packed header). */
	public static final int MAX_CUSTOM_PARTS = 3;
	/** The most custom (composed or plugin) leaves one mask may carry. */
	public static final int MAX_CUSTOM_LEAVES = 2;

	/** The implementation family of a custom shape. */
	public enum Family {
		COMPOSED, GLSL_PLUGIN
	}

	/**
	 * One composed part: a shared primitive kind and its literal parameters. All composed parts are
	 * solid (fill/stroke are a field/mask-leaf concern, not a composition part), so the packed
	 * header uses its spare components for `rounding` (subtracted from the distance: positive
	 * inflates) and a uniform `repeat` tiling factor (1 = none).
	 */
	public record Part(VFXMaskShapeKind shape, VFXMaskSpace space, float[] center, float rotation, float[] params, float rounding, float repeat) {
		public Part {
			center = center.clone();
			params = params.clone();
		}
	}

	private final String id;
	private final VFXMaskSpace space;
	private final Family family;
	private final List<Part> parts;
	private final List<VFXMaskOp> ops;

	VFXCustomShape(final String id, final VFXMaskSpace space, final Family family, final List<Part> parts, final List<VFXMaskOp> ops) {
		this.id = id;
		this.space = space;
		this.family = family;
		this.parts = List.copyOf(parts);
		this.ops = List.copyOf(ops);
	}

	/**
	 * Starts a composed custom shape.
	 *
	 * @param id    the registration id, e.g. {@code "mymod:ringed"}
	 * @param space the space its parts are classified in
	 * @return a builder; add parts and ops, then {@link Builder#build()}
	 */
	public static Builder composed(final String id, final VFXMaskSpace space) {
		return new Builder(id, space);
	}

	/** The registration id. */
	public String id() {
		return this.id;
	}

	/** The space the shape is classified in. */
	public VFXMaskSpace space() {
		return this.space;
	}

	/** The implementation family. */
	public Family family() {
		return this.family;
	}

	/** The composed parts (empty for a plugin). */
	public List<Part> parts() {
		return this.parts;
	}

	/** The ops joining the parts (length {@code parts().size()-1}, empty for a plugin). */
	public List<VFXMaskOp> ops() {
		return this.ops;
	}

	/** Builds a composed custom shape; all values are literals. */
	public static final class Builder {
		private final String id;
		private final VFXMaskSpace space;
		private final List<Part> parts = new ArrayList<>();
		private final List<VFXMaskOp> ops = new ArrayList<>();
		private float pendingRounding = 0.0F;
		private float pendingRepeat = 1.0F;

		private Builder(final String id, final VFXMaskSpace space) {
			this.id = id;
			this.space = space;
		}

		private Builder part(final VFXMaskShapeKind shape, final float[] center, final float rotation, final float[] params) {
			if (this.parts.size() >= MAX_CUSTOM_PARTS) {
				throw new IllegalArgumentException("custom shape '" + this.id + "' exceeds " + MAX_CUSTOM_PARTS + " parts");
			}
			this.parts.add(new Part(shape, this.space, center, rotation, params, this.pendingRounding, this.pendingRepeat));
			this.pendingRounding = 0.0F;
			this.pendingRepeat = 1.0F;
			return this;
		}

		/** Rounds (positive) or sharpens (negative) the next part's distance. */
		public Builder round(final float amount) {
			this.pendingRounding = amount;
			return this;
		}

		/** Tiles the next part at this frequency (1 = no tiling). */
		public Builder repeat(final float frequency) {
			this.pendingRepeat = frequency;
			return this;
		}

		/** Adds a 2D circle part. */
		public Builder circle(final float[] center, final float radius) {
			return part(VFXMaskShapeKind.CIRCLE, center, 0.0F, new float[]{radius});
		}

		/** Adds a 2D ellipse part. */
		public Builder ellipse(final float[] center, final float radiusX, final float radiusY) {
			return part(VFXMaskShapeKind.ELLIPSE, center, 0.0F, new float[]{radiusX, radiusY});
		}

		/** Adds a 2D rounded-rect part. */
		public Builder rect(final float[] center, final float halfWidth, final float halfHeight, final float cornerRadius) {
			return part(VFXMaskShapeKind.RECT, center, 0.0F, new float[]{halfWidth, halfHeight, cornerRadius});
		}

		/** Adds a 2D regular-polygon part. */
		public Builder polygon(final float[] center, final float radius, final float sides) {
			return part(VFXMaskShapeKind.POLYGON, center, 0.0F, new float[]{radius, sides});
		}

		/** Adds a 3D sphere part (world). */
		public Builder sphere(final float[] center, final float radius) {
			return part(VFXMaskShapeKind.SPHERE, center, 0.0F, new float[]{radius});
		}

		/** Adds a 3D box part (world). */
		public Builder box(final float[] center, final float halfWidth, final float halfHeight, final float halfDepth) {
			return part(VFXMaskShapeKind.BOX, center, 0.0F, new float[]{halfWidth, halfHeight, halfDepth});
		}

		/** Joins the last two parts with {@code op}; must alternate part/op or the build fails. */
		public Builder op(final VFXMaskOp op) {
			this.ops.add(op);
			return this;
		}

		/** Validates and freezes the shape. */
		public VFXCustomShape build() {
			if (this.parts.isEmpty()) {
				throw new IllegalArgumentException("custom shape '" + this.id + "' has no parts");
			}
			if (this.ops.size() != this.parts.size() - 1) {
				throw new IllegalArgumentException("custom shape '" + this.id + "': parts and ops do not alternate");
			}
			return new VFXCustomShape(this.id, this.space, Family.COMPOSED, this.parts, this.ops);
		}
	}
}

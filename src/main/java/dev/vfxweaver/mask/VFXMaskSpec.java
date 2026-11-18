package dev.vfxweaver.mask;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

/**
 * Builds a mask JSON object in code, without a datapack - the mask counterpart of
 * {@code VFXBlockParticleSpec}. The output is the <b>exact</b> {@code mask} object a datapack
 * authors, parsed by the same {@link VFXMaskParser} on the client, so a spec can express every
 * leaf family and composition the JSON format supports.
 *
 * <p>Specs are immutable: every modifier (and every composition) returns a new spec, so a base
 * figure can be shared and varied. The builder covers the JSON keys it names - leaf family and its
 * numeric parameters, {@code center}, {@code rotation}, {@code fill}, {@code stroke_width},
 * {@code softness} and the composition {@code op}/{@code a}/{@code b} plus the top-level
 * {@code invert}. Anything else the datapack accepts (a {@code from}/{@code bind} value, a
 * {@code field} block, {@code volume}, {@code occlude}, {@code data}) can be sent as raw JSON
 * through {@code VFXAPI.sendMask(player, effectId, String)}.
 *
 * <pre>{@code
 * VFXMaskSpec ring = VFXMaskSpec.circle(0.4F).softness(0.02F);
 * VFXMaskSpec hole = VFXMaskSpec.circle(0.15F);
 * VFXAPI.sendMask(player, effectId, ring.difference(hole));
 * }</pre>
 */
public final class VFXMaskSpec {
	/** Screen-space fallback centre of the 2D leaves ({@code [0.5, 0.5]} in UV). */
	private static final float SCREEN_CENTER = 0.5F;
	/** World-space fallback centre of the 3D leaves and the block leaf. */
	private static final float WORLD_CENTER = 0.0F;

	private final JsonObject node;

	private VFXMaskSpec(final JsonObject node) {
		this.node = node;
	}

	/** A screen-space circle of the given radius (UV units). */
	public static VFXMaskSpec circle(final float radius) {
		final JsonObject node = screenLeaf(VFXMaskShapeKind.CIRCLE);
		node.addProperty("radius", radius);
		return new VFXMaskSpec(node);
	}

	/** A screen-space ellipse with independent radius axes. */
	public static VFXMaskSpec ellipse(final float radiusX, final float radiusY) {
		final JsonObject node = screenLeaf(VFXMaskShapeKind.ELLIPSE);
		node.addProperty("radius_x", radiusX);
		node.addProperty("radius_y", radiusY);
		return new VFXMaskSpec(node);
	}

	/** A screen-space rectangle with square corners. */
	public static VFXMaskSpec rect(final float halfWidth, final float halfHeight) {
		return rect(halfWidth, halfHeight, 0.0F);
	}

	/** A screen-space rectangle with the given corner radius (0 = square). */
	public static VFXMaskSpec rect(final float halfWidth, final float halfHeight, final float cornerRadius) {
		final JsonObject node = screenLeaf(VFXMaskShapeKind.RECT);
		node.addProperty("half_width", halfWidth);
		node.addProperty("half_height", halfHeight);
		node.addProperty("corner_radius", cornerRadius);
		return new VFXMaskSpec(node);
	}

	/**
	 * A screen-space regular polygon.
	 *
	 * @param radius circumradius in UV units
	 * @param sides  number of sides; the parser rejects fewer than 3
	 */
	public static VFXMaskSpec polygon(final float radius, final int sides) {
		final JsonObject node = screenLeaf(VFXMaskShapeKind.POLYGON);
		node.addProperty("radius", radius);
		node.addProperty("sides", sides);
		return new VFXMaskSpec(node);
	}

	/** A world-space sphere around {@link #center(float, float, float)}. */
	public static VFXMaskSpec sphere(final float radius) {
		final JsonObject node = worldLeaf(VFXMaskShapeKind.SPHERE);
		node.addProperty("radius", radius);
		return new VFXMaskSpec(node);
	}

	/** A world-space axis-aligned box around {@link #center(float, float, float)}. */
	public static VFXMaskSpec box(final float halfWidth, final float halfHeight, final float halfDepth) {
		final JsonObject node = worldLeaf(VFXMaskShapeKind.BOX);
		node.addProperty("half_width", halfWidth);
		node.addProperty("half_height", halfHeight);
		node.addProperty("half_depth", halfDepth);
		return new VFXMaskSpec(node);
	}

	/**
	 * A block-geometry leaf that scans a bounded region for the given block ids.
	 *
	 * @param radius   scan radius in blocks, from {@link #center(float, float, float)}
	 * @param blockIds block ids to select (at least one)
	 */
	public static VFXMaskSpec block(final float radius, final String... blockIds) {
		if (blockIds == null || blockIds.length == 0) {
			throw new IllegalArgumentException("VFXMaskSpec.block: at least one block id is required");
		}
		final JsonObject node = blockLeaf();
		node.addProperty("radius", radius);
		final JsonArray blocks = new JsonArray();
		for (final String id : blockIds) {
			blocks.add(id);
		}
		node.add("blocks", blocks);
		return new VFXMaskSpec(node);
	}

	/**
	 * A block-geometry leaf that scans a bounded region for a block tag.
	 *
	 * @param tag    block tag id (e.g. {@code minecraft:logs})
	 * @param radius scan radius in blocks, from {@link #center(float, float, float)}
	 */
	public static VFXMaskSpec blockTag(final float radius, final String tag) {
		final JsonObject node = blockLeaf();
		node.addProperty("radius", radius);
		node.addProperty("tag", tag);
		return new VFXMaskSpec(node);
	}

	/**
	 * A {@code block_list} leaf that masks exactly the given blocks (no scan, no cap).
	 *
	 * @param positions block coordinates, each a {@code [x, y, z]} triple
	 */
	public static VFXMaskSpec blockList(final float[]... positions) {
		if (positions == null || positions.length == 0) {
			throw new IllegalArgumentException("VFXMaskSpec.blockList: at least one position is required");
		}
		final JsonObject node = blockListNode();
		final JsonArray array = new JsonArray();
		for (final float[] position : positions) {
			if (position == null || position.length != 3) {
				throw new IllegalArgumentException("VFXMaskSpec.blockList: every position must be [x, y, z]");
			}
			final JsonArray point = new JsonArray();
			point.add(position[0]);
			point.add(position[1]);
			point.add(position[2]);
			array.add(point);
		}
		node.add("positions", array);
		return new VFXMaskSpec(node);
	}

	/** A {@code block_list} leaf covering an inclusive box (capped at the geometry budget). */
	public static VFXMaskSpec blockRegion(final float x0, final float y0, final float z0, final float x1, final float y1, final float z1) {
		final JsonObject node = blockListNode();
		final JsonArray region = new JsonArray();
		region.add(x0);
		region.add(y0);
		region.add(z0);
		region.add(x1);
		region.add(y1);
		region.add(z1);
		node.add("region", region);
		return new VFXMaskSpec(node);
	}

	/**
	 * A custom leaf, resolved against the mask-shape registry (composed SDF or GLSL plugin). The
	 * default centre follows the registered shape's space: UV {@code [0.5, 0.5]} for a screen shape,
	 * world {@code [0, 0, 0]} for a world one (override it with {@link #center(float, float)} or
	 * {@link #center(float, float, float)}).
	 *
	 * @param shapeId the registered shape id (e.g. {@code mymod:rune})
	 */
	public static VFXMaskSpec custom(final String shapeId) {
		final JsonObject node = new JsonObject();
		node.addProperty("shape", shapeId);
		final VFXCustomShape registered = VFXShapeRegistry.get().get(shapeId);
		final JsonArray center = new JsonArray();
		if (registered != null && registered.space() == VFXMaskSpace.WORLD) {
			center.add(WORLD_CENTER);
			center.add(WORLD_CENTER);
			center.add(WORLD_CENTER);
		} else {
			center.add(SCREEN_CENTER);
			center.add(SCREEN_CENTER);
		}
		node.add("center", center);
		return new VFXMaskSpec(node);
	}

	/**
	 * A custom leaf with literal numeric parameters ({@code params}, up to
	 * {@link VFXMaskSlots#MAX_LEAF_PARAMS}).
	 */
	public static VFXMaskSpec custom(final String shapeId, final float... params) {
		final JsonObject node = custom(shapeId).node;
		if (params != null && params.length > 0) {
			final JsonArray array = new JsonArray();
			for (final float value : params) {
				array.add(value);
			}
			node.add("params", array);
		}
		return new VFXMaskSpec(node);
	}

	/** Sets the leaf centre in UV space (2D leaves). */
	public VFXMaskSpec center(final float x, final float y) {
		return copyWith(node -> {
			final JsonArray center = new JsonArray();
			center.add(x);
			center.add(y);
			node.add("center", center);
		});
	}

	/** Sets the leaf centre in world blocks (3D and block leaves). */
	public VFXMaskSpec center(final float x, final float y, final float z) {
		return copyWith(node -> {
			final JsonArray center = new JsonArray();
			center.add(x);
			center.add(y);
			center.add(z);
			node.add("center", center);
		});
	}

	/** Sets the leaf rotation in degrees. */
	public VFXMaskSpec rotation(final float degrees) {
		return copyWith(node -> node.addProperty("rotation", degrees));
	}

	/** Sets the leaf's edge falloff width. */
	public VFXMaskSpec softness(final float softness) {
		return copyWith(node -> node.addProperty("softness", softness));
	}

	/** Sets the leaf fill mode ({@code solid}/{@code stroke}/{@code hollow}). */
	public VFXMaskSpec fill(final VFXMaskFill fill) {
		if (fill == null) {
			throw new IllegalArgumentException("VFXMaskSpec.fill: fill must not be null");
		}
		return copyWith(node -> node.addProperty("fill", fill.id()));
	}

	/** Sets the stroke width, required by {@link VFXMaskFill#STROKE}. */
	public VFXMaskSpec strokeWidth(final float width) {
		return copyWith(node -> node.addProperty("stroke_width", width));
	}

	/** Inverts the whole mask (top-level only; the parser rejects it on a nested node). */
	public VFXMaskSpec invert() {
		return copyWith(node -> node.addProperty("invert", true));
	}

	/** Composes {@code this ∪ other} (union = max). */
	public VFXMaskSpec union(final VFXMaskSpec other) {
		return compose(VFXMaskOp.UNION, other);
	}

	/** Composes {@code this ∩ other} (intersection = min). */
	public VFXMaskSpec intersection(final VFXMaskSpec other) {
		return compose(VFXMaskOp.INTERSECTION, other);
	}

	/** Composes {@code this - other} (difference, left-associative). */
	public VFXMaskSpec difference(final VFXMaskSpec other) {
		return compose(VFXMaskOp.DIFFERENCE, other);
	}

	/**
	 * The mask object exactly as a datapack file would author it.
	 *
	 * @return a fresh copy the caller may own
	 */
	public JsonObject buildJson() {
		return this.node.deepCopy();
	}

	@Override
	public String toString() {
		return this.node.toString();
	}

	private VFXMaskSpec compose(final VFXMaskOp op, final @Nullable VFXMaskSpec other) {
		if (other == null) {
			throw new IllegalArgumentException("VFXMaskSpec." + op.id() + ": the right operand must not be null");
		}
		final JsonObject node = new JsonObject();
		node.addProperty("op", op.id());
		node.add("a", this.node.deepCopy());
		node.add("b", other.node.deepCopy());
		return new VFXMaskSpec(node);
	}

	private VFXMaskSpec copyWith(final java.util.function.Consumer<JsonObject> mutation) {
		final JsonObject copy = this.node.deepCopy();
		mutation.accept(copy);
		return new VFXMaskSpec(copy);
	}

	private static JsonObject screenLeaf(final VFXMaskShapeKind kind) {
		final JsonObject node = new JsonObject();
		node.addProperty("shape", kind.id());
		final JsonArray center = new JsonArray();
		center.add(SCREEN_CENTER);
		center.add(SCREEN_CENTER);
		node.add("center", center);
		return node;
	}

	private static JsonObject worldLeaf(final VFXMaskShapeKind kind) {
		final JsonObject node = new JsonObject();
		node.addProperty("shape", kind.id());
		final JsonArray center = new JsonArray();
		center.add(WORLD_CENTER);
		center.add(WORLD_CENTER);
		center.add(WORLD_CENTER);
		node.add("center", center);
		return node;
	}

	private static JsonObject blockLeaf() {
		final JsonObject node = new JsonObject();
		node.addProperty("shape", "block");
		final JsonArray center = new JsonArray();
		center.add(WORLD_CENTER);
		center.add(WORLD_CENTER);
		center.add(WORLD_CENTER);
		node.add("center", center);
		return node;
	}

	private static JsonObject blockListNode() {
		final JsonObject node = new JsonObject();
		node.addProperty("shape", "block_list");
		return node;
	}
}

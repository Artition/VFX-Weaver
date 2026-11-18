package dev.vfxweaver.mask;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Builds a mask JSON object in code, without a datapack - the mask counterpart of
 * {@code VFXBlockParticleSpec}. The output is the <b>exact</b> {@code mask} object a datapack
 * authors, parsed by the same {@link VFXMaskParser} on the client, so a spec can express every
 * leaf family and composition the JSON format supports.
 *
 * <p>Specs are immutable: every modifier (and every composition) returns a new spec, so a base
 * figure can be shared and varied. {@link #buildJson()} validates the combination before it hands
 * the object out, so a spec can never emit JSON the parser would reject: {@code hollow} is only
 * accepted on a {@code block_list}, {@code stroke} requires a {@code stroke_width}, a polygon needs
 * at least 3 sides, a block scan radius stays inside the parser's bound, a custom leaf carries at
 * most {@link VFXMaskSlots#MAX_LEAF_PARAMS} params, and a leaf's {@code space} must be one its
 * family supports.
 *
 * <p>Anything else the datapack accepts (a {@code from}/{@code bind} value, a {@code field} block,
 * {@code volume}, {@code occlude}, a custom leaf's {@code data}) can be sent as raw JSON through
 * {@code VFXAPI.sendMask(player, effectId, String)}.
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
	 * @param sides  number of sides, at least 3 (the parser's own lower bound)
	 */
	public static VFXMaskSpec polygon(final float radius, final int sides) {
		if (sides < 3) {
			throw new IllegalArgumentException("VFXMaskSpec.polygon: sides must be at least 3, got " + sides);
		}
		final JsonObject node = screenLeaf(VFXMaskShapeKind.POLYGON);
		node.addProperty("radius", radius);
		node.addProperty("sides", sides);
		return new VFXMaskSpec(node);
	}

	/** The whole sky dome (dome space, no shape parameters). */
	public static VFXMaskSpec sky() {
		final JsonObject node = new JsonObject();
		node.addProperty("shape", VFXMaskShapeKind.SKY.id());
		return new VFXMaskSpec(node);
	}

	/**
	 * A band of scene distance: coverage by the pixel's reconstructed distance.
	 *
	 * @param distance the band centre in world blocks (the leaf's softness is its width)
	 */
	public static VFXMaskSpec depth(final float distance) {
		final JsonObject node = screenLeaf(VFXMaskShapeKind.DEPTH);
		node.addProperty("distance", distance);
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
	 * @param radius   scan radius in blocks, from {@link #center(float, float, float)}; at most
	 *                 {@link VFXMaskBlockSelection#MAX_RADIUS}
	 * @param blockIds block ids to select (at least one)
	 */
	public static VFXMaskSpec block(final float radius, final String... blockIds) {
		if (blockIds == null || blockIds.length == 0) {
			throw new IllegalArgumentException("VFXMaskSpec.block: at least one block id is required");
		}
		final JsonObject node = blockLeaf(radius);
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
	 * @param radius scan radius in blocks, from {@link #center(float, float, float)}; at most
	 *               {@link VFXMaskBlockSelection#MAX_RADIUS}
	 */
	public static VFXMaskSpec blockTag(final float radius, final String tag) {
		final JsonObject node = blockLeaf(radius);
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
	 * A custom leaf resolved against the mask-shape registry (composed SDF or GLSL plugin).
	 *
	 * <p>The space is <b>explicit</b>: the registry is client-local, so a shape built on a server
	 * has nothing to look up (see {@code VFXAPI.registerMaskShape}), and a guess would emit a
	 * centre or params the parser rejects on the client. Pass the space the shape is registered
	 * with - {@link VFXMaskSpace#SCREEN} or {@link VFXMaskSpace#WORLD} - and a {@code WORLD} shape
	 * gets a 3-component centre.
	 *
	 * @param shapeId the registered shape id (e.g. {@code mymod:rune})
	 * @param space   the space the shape is registered in (screen or world)
	 */
	public static VFXMaskSpec custom(final String shapeId, final VFXMaskSpace space) {
		return custom(shapeId, space, new float[0]);
	}

	/**
	 * A custom leaf with literal numeric parameters ({@code params}, at most
	 * {@link VFXMaskSlots#MAX_LEAF_PARAMS}).
	 *
	 * @param shapeId the registered shape id (e.g. {@code mymod:rune})
	 * @param space   the space the shape is registered in (screen or world)
	 * @param params  the literal {@code p0..p7} values (may be empty)
	 */
	public static VFXMaskSpec custom(final String shapeId, final VFXMaskSpace space, final float... params) {
		if (space == null || space == VFXMaskSpace.DOME) {
			throw new IllegalArgumentException("VFXMaskSpec.custom: a custom shape lives in screen or world space, got " + space);
		}
		if (params != null && params.length > VFXMaskSlots.MAX_LEAF_PARAMS) {
			throw new IllegalArgumentException("VFXMaskSpec.custom: at most " + VFXMaskSlots.MAX_LEAF_PARAMS + " params, got " + params.length);
		}
		final JsonObject node = new JsonObject();
		node.addProperty("shape", shapeId);
		node.addProperty("space", space.id());
		final JsonArray center = new JsonArray();
		final int arity = space == VFXMaskSpace.WORLD ? 3 : 2;
		final float fallback = space == VFXMaskSpace.WORLD ? WORLD_CENTER : SCREEN_CENTER;
		for (int i = 0; i < arity; i++) {
			center.add(fallback);
		}
		node.add("center", center);
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

	/**
	 * Overrides the leaf's coordinate space. Only the two spaces a 2D leaf can carry are accepted:
	 * {@link VFXMaskSpace#SCREEN} (the default) and {@link VFXMaskSpace#DOME} (the equirectangular
	 * sky, where {@code center} is a literal {@code [yaw, pitch]} in degrees). A world leaf is
	 * created in world space by its own factory ({@code sphere}/{@code box}/{@code block}) or by
	 * {@link #custom(String, VFXMaskSpace)}.
	 */
	public VFXMaskSpec space(final VFXMaskSpace space) {
		if (space == null) {
			throw new IllegalArgumentException("VFXMaskSpec.space: space must not be null");
		}
		if (this.node.has("op")) {
			throw new IllegalArgumentException("VFXMaskSpec.space: a composition has no space of its own; set it on the leaves");
		}
		final String shape = this.node.has("shape") ? this.node.get("shape").getAsString() : "";
		final boolean domeOnly = VFXMaskShapeKind.SKY.id().equals(shape);
		final boolean worldLeaf = VFXMaskShapeKind.SPHERE.id().equals(shape)
			|| VFXMaskShapeKind.BOX.id().equals(shape)
			|| "block".equals(shape)
			|| "block_list".equals(shape);
		if (domeOnly && space != VFXMaskSpace.DOME) {
			throw new IllegalArgumentException("VFXMaskSpec.space: the 'sky' leaf is dome-only");
		}
		if (worldLeaf && space != VFXMaskSpace.WORLD) {
			throw new IllegalArgumentException("VFXMaskSpec.space: '" + shape + "' is world-only");
		}
		if (!domeOnly && !worldLeaf && space == VFXMaskSpace.WORLD) {
			throw new IllegalArgumentException("VFXMaskSpec.space: a 2D leaf cannot be world space; use a world leaf or custom(id, VFXMaskSpace.WORLD)");
		}
		return copyWith(node -> node.addProperty("space", space.id()));
	}

	/** Sets the leaf rotation in degrees. */
	public VFXMaskSpec rotation(final float degrees) {
		return copyWith(node -> node.addProperty("rotation", degrees));
	}

	/** Sets the leaf's edge falloff width. */
	public VFXMaskSpec softness(final float softness) {
		return copyWith(node -> node.addProperty("softness", softness));
	}

	/**
	 * Sets the leaf fill mode ({@code solid}/{@code stroke}/{@code hollow}). {@code hollow} is only
	 * valid on a {@code block_list}; {@code stroke} needs a {@link #strokeWidth(float)}, checked at
	 * {@link #buildJson()}.
	 */
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
	 * The mask object exactly as a datapack file would author it, validated against the parser's
	 * rules so it always parses.
	 *
	 * @return a fresh copy the caller may own
	 * @throws IllegalArgumentException when the combination is not valid mask JSON
	 */
	public JsonObject buildJson() {
		final JsonObject copy = this.node.deepCopy();
		validate(copy, true);
		return copy;
	}

	@Override
	public String toString() {
		return this.node.toString();
	}

	/**
	 * Checks the combination the parser would reject, so a builder mistake surfaces here instead of
	 * as a client-side "invalid mask JSON" warning. Mirrors {@link VFXMaskParser}.
	 */
	private static void validate(final JsonObject node, final boolean root) {
		if (node.has("op") && !node.get("op").isJsonNull()) {
			if (node.has("invert") && !node.get("invert").isJsonNull() && !root) {
				throw new IllegalArgumentException("VFXMaskSpec: 'invert' is only valid on the top-level mask");
			}
			validate(requireObject(node, "a"), false);
			validate(requireObject(node, "b"), false);
			return;
		}
		final JsonElement shapeElement = node.get("shape");
		if (node.has("invert") && !node.get("invert").isJsonNull() && !root) {
			throw new IllegalArgumentException("VFXMaskSpec: 'invert' is only valid on the top-level mask");
		}
		if (shapeElement == null || shapeElement.isJsonNull()) {
			throw new IllegalArgumentException("VFXMaskSpec: every leaf needs a 'shape'");
		}
		final String shape = shapeElement.getAsString().trim().toLowerCase(Locale.ROOT);
		final boolean blockList = "block_list".equals(shape);
		final boolean block = blockList || "block".equals(shape);
		final VFXMaskFill fill = node.has("fill") && !node.get("fill").isJsonNull()
			? VFXMaskFill.fromString(node.get("fill").getAsString())
			: VFXMaskFill.SOLID;
		if (fill == null) {
			throw new IllegalArgumentException("VFXMaskSpec: 'fill' must be 'solid', 'stroke' or 'hollow'");
		}
		if (fill == VFXMaskFill.HOLLOW && !blockList) {
			throw new IllegalArgumentException("VFXMaskSpec: 'fill': 'hollow' is only valid on a 'block_list' leaf");
		}
		if (fill == VFXMaskFill.STROKE && blockList) {
			throw new IllegalArgumentException("VFXMaskSpec: 'fill': a 'block_list' leaf takes 'solid' or 'hollow' (the band is its stroke)");
		}
		if (fill == VFXMaskFill.STROKE && (!node.has("stroke_width") || node.get("stroke_width").isJsonNull())) {
			throw new IllegalArgumentException("VFXMaskSpec: fill 'stroke' requires strokeWidth(...)");
		}
		if (block) {
			if (node.has("radius") && !node.get("radius").isJsonNull()) {
				final float radius = node.get("radius").getAsFloat();
				if (radius <= 0.0F || radius > VFXMaskBlockSelection.MAX_RADIUS) {
					throw new IllegalArgumentException("VFXMaskSpec: block radius must be in (0, " + VFXMaskBlockSelection.MAX_RADIUS + "], got " + radius);
				}
			}
		}
		if (VFXMaskShapeKind.POLYGON.id().equals(shape) && node.has("sides") && !node.get("sides").isJsonNull()) {
			final int sides = Math.round(node.get("sides").getAsFloat());
			if (sides < 3) {
				throw new IllegalArgumentException("VFXMaskSpec: polygon 'sides' must be at least 3, got " + sides);
			}
		}
		if (node.has("params") && !node.get("params").isJsonNull()) {
			final JsonElement params = node.get("params");
			if (!params.isJsonArray() || params.getAsJsonArray().size() > VFXMaskSlots.MAX_LEAF_PARAMS) {
				throw new IllegalArgumentException("VFXMaskSpec: a custom leaf takes at most " + VFXMaskSlots.MAX_LEAF_PARAMS + " params");
			}
		}
		if (node.has("space") && !node.get("space").isJsonNull()) {
			final VFXMaskSpace space = VFXMaskSpace.fromString(node.get("space").getAsString());
			if (space == null) {
				throw new IllegalArgumentException("VFXMaskSpec: 'space' must be 'screen', 'world' or 'dome'");
			}
			if (block && space != VFXMaskSpace.WORLD) {
				throw new IllegalArgumentException("VFXMaskSpec: block masks are world-only");
			}
			if (VFXMaskShapeKind.SKY.id().equals(shape) && space != VFXMaskSpace.DOME) {
				throw new IllegalArgumentException("VFXMaskSpec: the 'sky' leaf is dome-only");
			}
			final VFXMaskShapeKind kind = VFXMaskShapeKind.fromString(shape);
			if (kind != null && kind.worldOnly() && space != VFXMaskSpace.WORLD) {
				throw new IllegalArgumentException("VFXMaskSpec: shape '" + shape + "' is world-only");
			}
			if (space == VFXMaskSpace.DOME && kind == null && !VFXMaskShapeKind.SKY.id().equals(shape)) {
				throw new IllegalArgumentException("VFXMaskSpec: 'space': 'dome' is only valid on the 2D shapes or the 'sky' leaf");
			}
		}
	}

	private static JsonObject requireObject(final JsonObject parent, final String key) {
		final JsonElement element = parent.get(key);
		if (element == null || !element.isJsonObject()) {
			throw new IllegalArgumentException("VFXMaskSpec: a composition needs object '" + key + "'");
		}
		return element.getAsJsonObject();
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

	private static JsonObject blockLeaf(final float radius) {
		if (radius <= 0.0F || radius > VFXMaskBlockSelection.MAX_RADIUS) {
			throw new IllegalArgumentException("VFXMaskSpec: block radius must be in (0, " + VFXMaskBlockSelection.MAX_RADIUS + "], got " + radius);
		}
		final JsonObject node = new JsonObject();
		node.addProperty("shape", "block");
		final JsonArray center = new JsonArray();
		center.add(WORLD_CENTER);
		center.add(WORLD_CENTER);
		center.add(WORLD_CENTER);
		node.add("center", center);
		node.addProperty("radius", radius);
		return node;
	}

	private static JsonObject blockListNode() {
		final JsonObject node = new JsonObject();
		node.addProperty("shape", "block_list");
		return node;
	}
}

package dev.vfxweaver.mask;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.vfxweaver.effect.BoundParam;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Parses a {@code mask} block into a flattened, validated {@link VFXMask}. Strict and per-file:
 * every fault throws {@link IllegalArgumentException} naming the offending field, which
 * {@code VFXDefinitionManager} catches so one broken file does not take down the pack.
 *
 * <p>Composition is flattened depth-first and folded left-associatively by the shader, so only
 * left-nesting is accepted: a composition in the right operand is rejected (it would flatten to a
 * different tree). A leaf's reserved slot index is assigned in depth-first order, so it always
 * matches {@link VFXMask#primitives()}. Shape parameter names and defaults are mirrored from
 * {@link VFXMaskShapeKind}; the shape math is the shared library's.
 */
public final class VFXMaskParser {
	private static final float SCREEN_DEFAULT_SOFTNESS = 0.01F;
	private static final float WORLD_DEFAULT_SOFTNESS = 0.25F;
	private static final float SCREEN_CENTER_X = 0.5F;
	private static final float SCREEN_CENTER_Y = 0.5F;
	private static final float WORLD_CENTER = 0.0F;
	private static final float DOME_CENTER = 0.0F;

	private VFXMaskParser() {
	}

	private record Partial(List<VFXMaskPrimitive> primitives, List<VFXMaskOp> ops) {
	}

	/**
	 * Parses a {@code mask} JSON object.
	 *
	 * @param owner effect id for error messages
	 * @param json  the {@code mask} object
	 * @return the parsed mask
	 */
	public static VFXMask parse(final String owner, final JsonObject json) {
		final boolean invert = bool(json, "invert", false);
		// A present-but-invalid top-level "space" is a typo, not "absent": it must throw like an
		// invalid leaf-level space instead of silently falling back to the default.
		final VFXMaskSpace topSpace;
		if (json.has("space") && !json.get("space").isJsonNull()) {
			topSpace = VFXMaskSpace.fromString(json.get("space").getAsString());
			if (topSpace == null) {
				throw new IllegalArgumentException("mask: 'space' must be 'screen', 'world' or 'dome'");
			}
		} else {
			topSpace = null;
		}
		final Map<String, VFXMask.MaskSlot> slots = new LinkedHashMap<>();
		final int[] counter = {0};
		// A mask is either a composition ({"op": ..., "a": ..., "b": ...}) or a single leaf,
		// authored as {"invert"/"space", "a": { "shape": ... }}. Unwrap the root 'a' when the
		// object is not a composition and carries no shape of its own.
		JsonObject node = json;
		final boolean hasOp = json.has("op") && !json.get("op").isJsonNull();
		final JsonElement rootA = json.get("a");
		final boolean hasShape = json.has("shape") && !json.get("shape").isJsonNull();
		if (!hasOp && !hasShape && rootA != null && rootA.isJsonObject()) {
			node = rootA.getAsJsonObject();
		}
		final Partial partial = parseNode(node, topSpace, 0, true, counter, slots);
		if (partial.primitives().isEmpty()) {
			throw new IllegalArgumentException("mask: at least one shape is required");
		}
		if (partial.primitives().size() > VFXMask.MAX_PRIMITIVES) {
			throw new IllegalArgumentException("mask: " + partial.primitives().size() + " shapes exceed the limit of " + VFXMask.MAX_PRIMITIVES);
		}
		// The shader packs one custom row per distinct custom shape (custom_op has only
		// MAX_CUSTOM_LEAVES slots) and a single geometry scratch is shared by every block leaf, so
		// a third custom leaf or a second block leaf would alias row 0 / fold a coverage against
		// itself. Reject both here, with the cap named, rather than render them as garbage.
		int customLeaves = 0;
		int blockLeaves = 0;
		for (final VFXMaskPrimitive primitive : partial.primitives()) {
			if (primitive.customShape() != null) {
				customLeaves++;
			}
			if (primitive.family() == VFXMaskPrimitive.Family.BLOCK) {
				blockLeaves++;
			}
		}
		if (customLeaves > VFXCustomShape.MAX_CUSTOM_LEAVES) {
			throw new IllegalArgumentException("mask: " + customLeaves + " custom leaves exceed the limit of " + VFXCustomShape.MAX_CUSTOM_LEAVES);
		}
		if (blockLeaves > 1) {
			throw new IllegalArgumentException("mask: a mask may carry at most one block leaf (all block leaves share one geometry scratch)");
		}
		return new VFXMask(invert, partial.primitives(), partial.ops(), slots);
	}

	private static Partial parseNode(final JsonObject json, final @Nullable VFXMaskSpace topSpace, final int depth, final boolean root, final int[] counter, final Map<String, VFXMask.MaskSlot> slots) {
		final JsonElement opElement = json.get("op");
		if (opElement != null && !opElement.isJsonNull()) {
			if (depth >= VFXMask.MAX_COMPOSITION_DEPTH) {
				throw new IllegalArgumentException("mask: composition depth exceeds " + VFXMask.MAX_COMPOSITION_DEPTH);
			}
			if (json.has("invert") && !json.get("invert").isJsonNull() && !root) {
				throw new IllegalArgumentException("mask: 'invert' is only allowed on the top-level mask");
			}
			if (json.has("field") && !json.get("field").isJsonNull()) {
				throw new IllegalArgumentException("mask: 'field' is only allowed on a leaf shape");
			}
			final VFXMaskOp op = VFXMaskOp.fromString(opElement.getAsString());
			if (op == null) {
				throw new IllegalArgumentException("mask: unknown op '" + opElement.getAsString() + "'");
			}
			final JsonElement a = json.get("a");
			final JsonElement b = json.get("b");
			if (a == null || !a.isJsonObject() || b == null || !b.isJsonObject()) {
				throw new IllegalArgumentException("mask: a composition needs object 'a' and 'b'");
			}
			final Partial left = parseNode(a.getAsJsonObject(), topSpace, depth + 1, false, counter, slots);
			final Partial right = parseNode(b.getAsJsonObject(), topSpace, depth + 1, false, counter, slots);
			// The leaves are flattened depth-first and the shader folds them left-associatively
			// (acc = op(acc, leaf)). That is only equivalent to the authored tree when every
			// composition nests on the LEFT: a right-nested operand such as
			// union(A, intersection(B, C)) would flatten to min(max(A, B), C). Reject it instead
			// of silently mis-evaluating.
			if (!right.ops().isEmpty()) {
				throw new IllegalArgumentException("mask: the right operand of op '" + op.id() + "' is itself a composition; nest compositions on the left (e.g. intersection before union)");
			}
			final List<VFXMaskPrimitive> primitives = new ArrayList<>(left.primitives());
			primitives.addAll(right.primitives());
			final List<VFXMaskOp> ops = new ArrayList<>(left.ops());
			ops.add(op);
			ops.addAll(right.ops());
			return new Partial(primitives, ops);
		}
		return parseShape(json, topSpace, root, counter, slots);
	}

	private static Partial parseShape(final JsonObject json, final @Nullable VFXMaskSpace topSpace, final boolean root, final int[] counter, final Map<String, VFXMask.MaskSlot> slots) {
		if (json.has("invert") && !json.get("invert").isJsonNull() && !root) {
			throw new IllegalArgumentException("mask: 'invert' is only allowed on the top-level mask");
		}
		final String shapeName = str(json, "shape", null);
		if (shapeName == null || shapeName.isBlank()) {
			throw new IllegalArgumentException("mask: 'shape' must be a shape name or a registered custom-shape id");
		}
		// Three families: a built-in kind, the block-geometry sentinel, or a registered custom id.
		final boolean blockLeaf = "block".equalsIgnoreCase(shapeName.trim());
		final VFXMaskShapeKind shape = blockLeaf ? null : VFXMaskShapeKind.fromString(shapeName);
		final VFXCustomShape customDefinition = !blockLeaf && shape == null ? VFXShapeRegistry.get().get(shapeName) : null;
		if (!blockLeaf && shape == null && customDefinition == null) {
			throw new IllegalArgumentException("mask: unknown shape '" + shapeName + "'");
		}
		final VFXMaskSpace space;
		if (json.has("space") && !json.get("space").isJsonNull()) {
			space = VFXMaskSpace.fromString(json.get("space").getAsString());
		} else if (blockLeaf) {
			space = VFXMaskSpace.WORLD;
		} else if (shape != null) {
			space = topSpace != null ? topSpace : shape.space();
		} else {
			space = topSpace != null ? topSpace : customDefinition.space();
		}
		if (space == null) {
			throw new IllegalArgumentException("mask: 'space' must be 'screen', 'world' or 'dome'");
		}
		if (blockLeaf && space != VFXMaskSpace.WORLD) {
			throw new IllegalArgumentException("mask: block masks are world-only");
		}
		if (shape != null && shape.worldOnly() && space != VFXMaskSpace.WORLD) {
			throw new IllegalArgumentException("mask: shape '" + shape.id() + "' is world-only ('sphere'/'box' classify a 3D world volume)");
		}
		if (shape == VFXMaskShapeKind.SKY && space != VFXMaskSpace.DOME) {
			throw new IllegalArgumentException("mask: shape 'sky' is dome-only");
		}
		// Dome space is the equirectangular sky: only the 2D kinds and the `sky` leaf address it. A
		// custom (plugin/composed) SDF is evaluated in screen or world units, so it has no dome meaning.
		if (space == VFXMaskSpace.DOME && shape == null) {
			throw new IllegalArgumentException("mask: 'space': 'dome' is only valid on the 2D shapes or the 'sky' leaf");
		}
		final String customShape = (!blockLeaf && shape == null) ? shapeName : null;
		final int i = counter[0]++;
		final int centerArity = space == VFXMaskSpace.WORLD ? 3 : 2;
		final String[] centerSlots = new String[centerArity];
		final float[] centerDefaults = new float[centerArity];
		final String[] axes = {"x", "y", "z"};
		final float[] fallback = space == VFXMaskSpace.WORLD
			? new float[]{WORLD_CENTER, WORLD_CENTER, WORLD_CENTER}
			: space == VFXMaskSpace.DOME
				? new float[]{DOME_CENTER, DOME_CENTER}
				: new float[]{SCREEN_CENTER_X, SCREEN_CENTER_Y};
		// A centre may be a fixed/animatable array, or one world-point binding object
		// ({ "bind": "camera"|"player"|"entity"|"point"|"block", ... }): the binding drives every
		// centre axis. A screen `rect` may instead carry a derived `screen_rect` binding (below),
		// which supplies the centre, so `center` is optional then.
		final JsonElement screenRectElement = json.get("screen_rect");
		final boolean hasScreenRect = screenRectElement != null && !screenRectElement.isJsonNull();
		final JsonElement centerElement = json.get("center");
		final BoundParam centerBinding;
		final JsonArray center;
		if (centerElement != null && centerElement.isJsonObject() && centerElement.getAsJsonObject().has("bind")) {
			centerBinding = BoundParam.parse(centerElement.getAsJsonObject());
			if (centerBinding.kind() != BoundParam.Kind.POINT) {
				throw new IllegalArgumentException("mask: 'center' binding must be a point source (camera/player/entity/point/block)");
			}
			center = null;
		} else if (centerElement == null && hasScreenRect) {
			centerBinding = null;
			center = null;
		} else if (centerElement == null && shape == VFXMaskShapeKind.SKY) {
			// A whole-sky leaf has no position: it covers the entire dome, so its centre is the
			// fallback and the shader ignores it.
			centerBinding = null;
			center = null;
		} else {
			centerBinding = null;
			center = array(json, "center", centerArity);
		}
		// A dome leaf addresses the equirectangular sky by a literal [yaw, pitch] in degrees. A bound
		// `center` yields a world position, whose dome conversion is a later stage (celestial anchors).
		if (space == VFXMaskSpace.DOME && centerBinding != null) {
			throw new IllegalArgumentException("mask: a 'dome' leaf takes a literal [yaw, pitch] centre in degrees; a bound 'center' is not supported yet");
		}
		for (int j = 0; j < centerArity; j++) {
			centerSlots[j] = VFXMaskSlots.center(i, axes[j]);
			centerDefaults[j] = center == null ? fallback[j] : number(slots, centerSlots[j], center.get(j), fallback[j]);
		}
		// A derived entity screen-rectangle: only on a screen `rect`, mutually exclusive with a bound
		// centre. The coverage writer resolves it to centre + half-extents at render time.
		BoundParam resolvedCenterBinding = centerBinding;
		if (hasScreenRect) {
			if (shape != VFXMaskShapeKind.RECT || space != VFXMaskSpace.SCREEN) {
				throw new IllegalArgumentException("mask: 'screen_rect' is only valid on a screen-space 'rect'");
			}
			if (resolvedCenterBinding != null) {
				throw new IllegalArgumentException("mask: 'screen_rect' and a bound 'center' are mutually exclusive");
			}
			final JsonObject rectJson = screenRectElement.getAsJsonObject().deepCopy();
			rectJson.addProperty("derive", "screen_rect");
			final BoundParam rectBinding = BoundParam.parse(rectJson);
			if (rectBinding.kind() != BoundParam.Kind.SCREEN_RECT) {
				throw new IllegalArgumentException("mask: 'screen_rect' needs an entity source deriving 'screen_rect'");
			}
			resolvedCenterBinding = rectBinding;
		}
		final String rotationSlot = VFXMaskSlots.rotation(i);
		final float rotationDefault = number(slots, rotationSlot, json.get("rotation"), 0.0F);

		// Numeric parameters by family. A built-in shape has named per-kind params; a block leaf has
		// the selection radius (the region is bounded, so it is a normal animatable slot); a custom
		// leaf has the generic p0..p7 slots the plugin/composed SDF reads.
		final String[] parameterSlots;
		final float[] parameterDefaults;
		if (blockLeaf) {
			parameterSlots = new String[]{VFXMaskSlots.param(i, 0)};
			parameterDefaults = new float[]{number(slots, parameterSlots[0], json.get("radius"), 16.0F)};
		} else if (shape != null) {
			final List<String> parameterNames = shape.parameterNames();
			final float[] parameterFallbacks = shape.parameterDefaults();
			parameterSlots = new String[parameterNames.size()];
			parameterDefaults = new float[parameterNames.size()];
			for (int j = 0; j < parameterNames.size(); j++) {
				parameterSlots[j] = VFXMaskSlots.param(i, j);
				parameterDefaults[j] = number(slots, parameterSlots[j], json.get(parameterNames.get(j)), parameterFallbacks[j]);
			}
			if (shape == VFXMaskShapeKind.POLYGON) {
				final int sides = Math.round(parameterDefaults[parameterNames.indexOf("sides")]);
				if (sides < 3) {
					throw new IllegalArgumentException("mask: polygon 'sides' must be at least 3");
				}
			}
		} else {
			// Custom shape: up to VFXMaskSlots.MAX_LEAF_PARAMS generic params, authored as a
			// `"params": [ n0, n1, ... ]` array (each a number or { "from": node }); absent = 0.
			final JsonElement paramsElement = json.get("params");
			final JsonArray customParams = paramsElement != null && paramsElement.isJsonArray() ? paramsElement.getAsJsonArray() : null;
			if (customParams != null && customParams.size() > VFXMaskSlots.MAX_LEAF_PARAMS) {
				throw new IllegalArgumentException("mask: custom shape '" + shapeName + "' takes at most " + VFXMaskSlots.MAX_LEAF_PARAMS + " params");
			}
			parameterSlots = new String[VFXMaskSlots.MAX_LEAF_PARAMS];
			parameterDefaults = new float[VFXMaskSlots.MAX_LEAF_PARAMS];
			for (int j = 0; j < VFXMaskSlots.MAX_LEAF_PARAMS; j++) {
				parameterSlots[j] = VFXMaskSlots.param(i, j);
				final JsonElement value = customParams != null && customParams.size() > j ? customParams.get(j) : null;
				parameterDefaults[j] = number(slots, parameterSlots[j], value, 0.0F);
			}
			// Dynamic float data: up to VFXMaskSlots.MAX_LEAF_DATA reserved slots (mask.p<N>.d<J>),
			// authored as a `"data": [ n0, n1, ... ]` array (each a number, { "from": node } or a
			// binding); absent = 0. A GLSL plugin reads them live through vfx_mask_data(...) without
			// a recompile. Registered as ordinary slots so every live-control path reaches them.
			final JsonElement dataElement = json.get("data");
			final JsonArray customData = dataElement != null && dataElement.isJsonArray() ? dataElement.getAsJsonArray() : null;
			if (customData != null && customData.size() > VFXMaskSlots.MAX_LEAF_DATA) {
				throw new IllegalArgumentException("mask: custom shape '" + shapeName + "' takes at most " + VFXMaskSlots.MAX_LEAF_DATA + " data values");
			}
			for (int j = 0; j < VFXMaskSlots.MAX_LEAF_DATA; j++) {
				final JsonElement value = customData != null && customData.size() > j ? customData.get(j) : null;
				number(slots, VFXMaskSlots.data(i, j), value, 0.0F);
			}
		}

		final VFXMaskFill fill = json.has("fill") && !json.get("fill").isJsonNull()
			? VFXMaskFill.fromString(json.get("fill").getAsString())
			: VFXMaskFill.SOLID;
		if (fill == null) {
			throw new IllegalArgumentException("mask: 'fill' must be 'solid' or 'stroke'");
		}
		final String strokeSlot = VFXMaskSlots.stroke(i);
		final float strokeDefault;
		if (fill == VFXMaskFill.STROKE) {
			if (!json.has("stroke_width") || json.get("stroke_width").isJsonNull()) {
				throw new IllegalArgumentException("mask: fill 'stroke' requires 'stroke_width'");
			}
			strokeDefault = number(slots, strokeSlot, json.get("stroke_width"), 0.05F);
		} else {
			strokeDefault = 0.0F;
			slots.put(strokeSlot, new VFXMask.MaskSlot(strokeSlot, 0.0F, null, null));
		}

		float softnessDefault = space == VFXMaskSpace.SCREEN ? SCREEN_DEFAULT_SOFTNESS : WORLD_DEFAULT_SOFTNESS;
		final String softnessSlot = VFXMaskSlots.soft(i);
		if (json.has("softness") && !json.get("softness").isJsonNull()) {
			softnessDefault = number(slots, softnessSlot, json.get("softness"), softnessDefault);
		} else {
			slots.put(softnessSlot, new VFXMask.MaskSlot(softnessSlot, softnessDefault, null, null));
		}

		// The world-volume evaluation mode: built-in sphere/box volumes and world GLSL plugins can
		// cast a ray at their raw distance field. Composed shapes have no raw SDF to march.
		final VFXMaskVolumeMode volumeMode;
		if (json.has("volume") && !json.get("volume").isJsonNull()) {
			final VFXMaskVolumeMode requested = VFXMaskVolumeMode.fromString(json.get("volume").getAsString());
			if (requested == VFXMaskVolumeMode.AURA && customDefinition != null) {
				if (space == VFXMaskSpace.WORLD && customDefinition.family() == VFXCustomShape.Family.GLSL_PLUGIN) {
					volumeMode = requested;
				} else if (customDefinition.family() == VFXCustomShape.Family.COMPOSED) {
					throw new IllegalArgumentException("mask: 'volume': 'aura' is not supported on a composed custom leaf ('" + shapeName + "' has no raw SDF to march); use a single-primitive custom shape.");
				} else {
					throw new IllegalArgumentException("mask: 'volume' is only valid on a world 'sphere'/'box' leaf, or on a world GLSL-plugin custom leaf with 'aura'");
				}
			} else if (shape == VFXMaskShapeKind.SPHERE || shape == VFXMaskShapeKind.BOX) {
				if (requested == null) {
					throw new IllegalArgumentException("mask: 'volume' must be 'surface' or 'aura'");
				}
				volumeMode = requested;
			} else {
				throw new IllegalArgumentException("mask: 'volume' is only valid on a world 'sphere'/'box' leaf, or on a world GLSL-plugin custom leaf with 'aura'");
			}
		} else {
			volumeMode = VFXMaskVolumeMode.SURFACE;
		}

		final String fieldAmountSlot = VFXMaskSlots.fieldAmount(i);
		final String fieldScaleSlot = VFXMaskSlots.fieldScale(i);
		final VFXMaskField field;
		final float fieldAmountDefault;
		final float fieldScaleDefault;
		final JsonElement fieldElement = json.get("field");
		if (fieldElement != null && fieldElement.isJsonObject()) {
			final JsonObject fieldJson = fieldElement.getAsJsonObject();
			field = VFXMaskField.fromString(str(fieldJson, "field", "noise"));
			if (field == null || field == VFXMaskField.NONE) {
				throw new IllegalArgumentException("mask: unknown field '" + str(fieldJson, "field", "") + "'");
			}
			fieldAmountDefault = number(slots, fieldAmountSlot, fieldJson.get("amount"), 0.0F);
			fieldScaleDefault = number(slots, fieldScaleSlot, fieldJson.get("scale"), 1.0F);
		} else {
			field = VFXMaskField.NONE;
			fieldAmountDefault = 0.0F;
			fieldScaleDefault = 1.0F;
			slots.put(fieldAmountSlot, new VFXMask.MaskSlot(fieldAmountSlot, 0.0F, null, null));
			slots.put(fieldScaleSlot, new VFXMask.MaskSlot(fieldScaleSlot, 1.0F, null, null));
		}
		// The block selection reuses the already-resolved centre and radius slots (both may be
		// graph-animated), so it only carries the selection identity plus the bounded region.
		final VFXMaskBlockSelection blockSelection = blockLeaf
			? parseBlockSelection(json, centerDefaults, parameterDefaults[0])
			: null;
		// A block leaf's geometry is occluded by scene depth unless it explicitly opts into the
		// see-through ("x-ray") look; the flag is meaningless on any other family.
		if (json.has("occlude") && !json.get("occlude").isJsonNull() && !blockLeaf) {
			throw new IllegalArgumentException("mask: 'occlude' is only valid on a block leaf");
		}
		final boolean occlude = blockLeaf && bool(json, "occlude", true);
		final VFXMaskPrimitive primitive = new VFXMaskPrimitive(shape, space, centerSlots, centerDefaults, rotationSlot, rotationDefault,
			parameterSlots, parameterDefaults, fill, strokeSlot, strokeDefault, softnessSlot, softnessDefault, volumeMode,
			field, fieldAmountSlot, fieldAmountDefault, fieldScaleSlot, fieldScaleDefault, i * 17.0F + 1.0F,
			blockSelection, occlude, customShape, resolvedCenterBinding, null);
		return new Partial(List.of(primitive), List.of());
	}

	/**
	 * Parses a block-geometry selection: at least one of {@code blocks} (id strings), {@code tag}
	 * or {@code properties}, plus a bounded spherical region ({@code center} + {@code radius}).
	 * The region is mandatory so the scan is always bounded; the selection is resolved to real
	 * block states by the client geometry pass.
	 */
	private static VFXMaskBlockSelection parseBlockSelection(final JsonObject json, final float[] center, final float radius) {
		final List<String> blockIds = new ArrayList<>();
		final JsonElement blocks = json.get("blocks");
		if (blocks != null && blocks.isJsonArray()) {
			if (blocks.getAsJsonArray().size() > VFXMaskBlockSelection.MAX_BLOCKS) {
				throw new IllegalArgumentException("mask: block 'blocks' takes at most " + VFXMaskBlockSelection.MAX_BLOCKS + " ids");
			}
			for (final JsonElement id : blocks.getAsJsonArray()) {
				if (id.isJsonPrimitive()) {
					blockIds.add(id.getAsString().trim().toLowerCase(java.util.Locale.ROOT));
				}
			}
		}
		final String tag = json.has("tag") && !json.get("tag").isJsonNull() ? json.get("tag").getAsString().trim().toLowerCase(java.util.Locale.ROOT) : null;
		final Map<String, String> properties = new LinkedHashMap<>();
		final JsonElement props = json.get("properties");
		if (props != null && props.isJsonObject()) {
			if (props.getAsJsonObject().size() > VFXMaskBlockSelection.MAX_PROPERTIES) {
				throw new IllegalArgumentException("mask: block 'properties' takes at most " + VFXMaskBlockSelection.MAX_PROPERTIES + " entries");
			}
			for (final Map.Entry<String, JsonElement> entry : props.getAsJsonObject().entrySet()) {
				properties.put(entry.getKey(), entry.getValue().getAsString());
			}
		}
		if (blockIds.isEmpty() && tag == null && properties.isEmpty()) {
			throw new IllegalArgumentException("mask: a block mask needs 'blocks', 'tag' or 'properties'");
		}
		if (radius <= 0.0F || radius > VFXMaskBlockSelection.MAX_RADIUS) {
			throw new IllegalArgumentException("mask: block 'radius' must be in (0, " + VFXMaskBlockSelection.MAX_RADIUS + "]");
		}
		return new VFXMaskBlockSelection(blockIds, tag, properties, center, radius);
	}

	private static JsonArray array(final JsonObject json, final String key, final int size) {
		final JsonElement element = json.get(key);
		if (element == null || !element.isJsonArray() || element.getAsJsonArray().size() != size) {
			throw new IllegalArgumentException("mask: '" + key + "' must be an array of " + size + " numbers");
		}
		return element.getAsJsonArray();
	}

	private static float number(final Map<String, VFXMask.MaskSlot> slots, final String slot, final @Nullable JsonElement element, final float fallback) {
		if (element == null || element.isJsonNull()) {
			slots.put(slot, new VFXMask.MaskSlot(slot, fallback, null, null));
			return fallback;
		}
		if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
			final float value = element.getAsFloat();
			slots.put(slot, new VFXMask.MaskSlot(slot, value, null, null));
			return value;
		}
		if (element.isJsonObject()) {
			final JsonObject object = element.getAsJsonObject();
			final JsonElement from = object.get("from");
			if (from != null && !from.isJsonNull() && !from.getAsString().isBlank()) {
				slots.put(slot, new VFXMask.MaskSlot(slot, fallback, from.getAsString(), null));
				return fallback;
			}
			if (object.has("bind") && !object.get("bind").isJsonNull()) {
				// A world-coordinate binding on a scalar leaf: the derived scalar output (default
				// `distance`) is resolved per frame by VFXWorldBindings (Task 3). A point/rect
				// output is a parse error here — those belong to `center`/`screen_rect`.
				final BoundParam binding = BoundParam.parse(object);
				if (binding.kind() == BoundParam.Kind.POINT || binding.kind() == BoundParam.Kind.SCREEN_RECT) {
					throw new IllegalArgumentException("mask: '" + slot + "' is a scalar; a point source belongs on 'center' and 'screen_rect'");
				}
				slots.put(slot, new VFXMask.MaskSlot(slot, fallback, null, binding));
				return fallback;
			}
		}
		throw new IllegalArgumentException("mask: '" + slot + "' must be a number, { \"from\": \"<node>\" } or { \"bind\": ... }");
	}

	private static String str(final JsonObject json, final String key, final @Nullable String fallback) {
		final JsonElement element = json.get(key);
		return element != null && !element.isJsonNull() ? element.getAsString() : fallback;
	}

	private static boolean bool(final JsonObject json, final String key, final boolean fallback) {
		final JsonElement element = json.get(key);
		return element != null && !element.isJsonNull() ? element.getAsBoolean() : fallback;
	}
}

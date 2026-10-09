package dev.vfxweaver.editor;

import dev.vfxweaver.effect.VFXEffectType;
import dev.vfxweaver.mask.VFXMaskShapeKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The non-parameter (structural) affordances of one effect type: which optional sub-structures the
 * visual node editor may offer for it (design §13.1, extended by §13.4). Additive beside the
 * parameter list — a descriptor without it behaves exactly as before this section existed.
 *
 * @param supportsMask           whether the effect accepts a top-level {@code mask} block. Only a
 *                               post-processing effect does ({@link VFXEffectType#isPostProcessing()});
 *                               a world/camera effect ignores the mask, so the editor must not offer
 *                               it.
 * @param maskShapes             the available mask leaf shape ids, in {@link VFXMaskShapeKind}
 *                               declaration order. The editor populates its shape combo from this
 *                               list instead of hardcoding the shapes, so a new kind appears without
 *                               an editor change. The {@code block} geometry and custom-shape families
 *                               are not enum kinds and are therefore not listed here.
 * @param supportsPositions      whether the effect reads a top-level {@code positions} list
 *                               ({@code [x,y,z]} or an entity-anchored object) — the world-overlay
 *                               targets (design §13.4).
 * @param supportsRegion         whether the effect reads a top-level {@code region}
 *                               ({@code [x0,y0,z0,x1,y1,z1]}) that expands into {@code positions}.
 * @param supportsEntitySelector whether the effect reads a top-level {@code entity_selector} string
 *                               (the entity-target effects).
 * @param supportsSound          whether the effect reads a top-level {@code sound} (plus optional
 *                               {@code sound_pos} and the {@code volume}/{@code pitch} params).
 *                               Every effect does.
 */
public record VFXStructuralDescriptor(boolean supportsMask, List<String> maskShapes,
		boolean supportsPositions, boolean supportsRegion, boolean supportsEntitySelector,
		boolean supportsSound) {
	/** The mask shape ids, derived once from the shared {@link VFXMaskShapeKind} enum. */
	private static final List<String> SHAPE_IDS = shapeIds();

	public VFXStructuralDescriptor {
		Objects.requireNonNull(maskShapes, "maskShapes");
		maskShapes = List.copyOf(maskShapes);
	}

	/**
	 * The structural affordances of an effect type (design §13.4). Everything is derived from the
	 * type through its public predicates, so the editor offers a sub-editor exactly when the
	 * renderer/parser reads the matching top-level field — no per-type branch in the UI.
	 *
	 * @param type the effect type
	 * @return the descriptor for that type
	 */
	public static VFXStructuralDescriptor of(final VFXEffectType type) {
		Objects.requireNonNull(type, "type");
		// A world-overlay effect (except the entity-target pair, which use entity_selector instead)
		// anchors at the top-level positions/region.
		final boolean positions = type.isWorldOverlay()
			&& type != VFXEffectType.ENTITY_TINT && type != VFXEffectType.ENTITY_OUTLINE;
		final boolean entitySelector = type == VFXEffectType.ENTITY_TINT
			|| type == VFXEffectType.ENTITY_OUTLINE || type == VFXEffectType.ENTITY_DISPLACE;
		// Every effect type may carry a sound (the manager plays definition.getSound() for any type).
		return new VFXStructuralDescriptor(type.isPostProcessing(), SHAPE_IDS, positions, positions,
			entitySelector, true);
	}

	/**
	 * The structural affordances of a post/non-post pair when only {@code post} is known. The
	 * option flags are all false, since the option set depends on the concrete {@link VFXEffectType}
	 * (see {@link #of(VFXEffectType)}); this factory exists for the backwards-compatible 3-argument
	 * descriptor construction.
	 *
	 * @param post whether the effect renders as a screen post-processing layer
	 * @return a descriptor whose {@link #supportsMask()} matches {@code post}
	 * @deprecated prefer {@link #of(VFXEffectType)} so the structural options are advertised
	 */
	@Deprecated
	public static VFXStructuralDescriptor of(final boolean post) {
		return new VFXStructuralDescriptor(post, SHAPE_IDS, false, false, false, false);
	}

	/**
	 * Derives the mask shape id list from {@link VFXMaskShapeKind}, so the editor's combo cannot
	 * drift from the parser's shape table.
	 *
	 * @return the shape ids in enum declaration order
	 */
	private static List<String> shapeIds() {
		final List<String> ids = new ArrayList<>(VFXMaskShapeKind.values().length);
		for (final VFXMaskShapeKind kind : VFXMaskShapeKind.values()) {
			ids.add(kind.id());
		}
		return List.copyOf(ids);
	}

	/**
	 * The per-shape field metadata the editor's MaskLeaf nodes generate their sub-editors from
	 * (design §13.1: "UI does not hardcode the shapes"). Additive beside {@link #maskShapes()}:
	 * one entry per {@link VFXMaskShapeKind}, in declaration order.
	 *
	 * <p>Each entry is {@code { id, space, worldOnly, deferred, params }} where {@code params} is the
	 * ordered {@code [[name, default], ...]} list from the shared shape enum. {@code deferred} marks
	 * a kind the parser understands but the editor does not author yet (only {@code polygon} today),
	 * so it round-trips read-only instead of emitting half-baked JSON. The block/custom families are
	 * not enum kinds and are therefore absent, as from {@link #maskShapes()}.
	 *
	 * @return the shape field specs, in enum declaration order
	 */
	public static List<Map<String, Object>> shapeFields() {
		final List<Map<String, Object>> out = new ArrayList<>(VFXMaskShapeKind.values().length);
		for (final VFXMaskShapeKind kind : VFXMaskShapeKind.values()) {
			final Map<String, Object> spec = new LinkedHashMap<>();
			spec.put("id", kind.id());
			spec.put("space", kind.space().id());
			spec.put("worldOnly", kind.worldOnly());
			spec.put("deferred", "polygon".equals(kind.id()));
			final List<String> names = kind.parameterNames();
			final float[] defaults = kind.parameterDefaults();
			final List<List<Object>> params = new ArrayList<>(names.size());
			for (int i = 0; i < names.size(); i++) {
				params.add(List.of(names.get(i), defaults[i]));
			}
			spec.put("params", params);
			out.add(spec);
		}
		return out;
	}

	/**
	 * The {@code positions} entry metadata the editor's positions sub-editor generates its fields
	 * from (design §13.4), mirroring {@code VFXDefinition.parsePositions}/{@code parseEntityAnchor}:
	 * a plain {@code [x,y,z]} array or an object with an {@code entity} selector, a {@code point}
	 * reference, an optional {@code dir} push by {@code distance} and an optional {@code offset}.
	 *
	 * @return the positions field spec
	 */
	public static Map<String, Object> positionFields() {
		final Map<String, Object> spec = new LinkedHashMap<>();
		spec.put("arrayArity", 3);
		spec.put("objectFields", List.of("entity", "point", "dir", "offset", "distance"));
		spec.put("pointValues", List.of("feet", "center", "eyes"));
		spec.put("dirValues", List.of("none", "look"));
		spec.put("offsetArity", 3);
		return spec;
	}

	/**
	 * The {@code region} metadata: one {@code [x0,y0,z0,x1,y1,z1]} array that expands into block
	 * positions (mirrors {@code VFXDefinition.parsePositions}).
	 *
	 * @return the region field spec
	 */
	public static Map<String, Object> regionFields() {
		final Map<String, Object> spec = new LinkedHashMap<>();
		spec.put("arity", 6);
		return spec;
	}

	/**
	 * The {@code entity_selector} metadata: one selector string (mirrors
	 * {@code VFXDefinition.parse}'s {@code entity_selector} read).
	 *
	 * @return the entity-selector field spec
	 */
	public static Map<String, Object> entitySelectorFields() {
		final Map<String, Object> spec = new LinkedHashMap<>();
		spec.put("field", "entity_selector");
		return spec;
	}

	/**
	 * The {@code sound} metadata (design §13.4): the sound id, an optional {@code [x,y,z]}
	 * {@code sound_pos}, and the {@code volume}/{@code pitch} params (mirrors
	 * {@code VFXDefinition.parse}'s {@code sound}/{@code sound_pos} sugar and
	 * {@code VFXEffectManager}'s {@code volume}/{@code pitch} reads).
	 *
	 * @return the sound field spec
	 */
	public static Map<String, Object> soundFields() {
		final Map<String, Object> spec = new LinkedHashMap<>();
		spec.put("fields", List.of("sound", "sound_pos", "volume", "pitch"));
		spec.put("posArity", 3);
		return spec;
	}
}

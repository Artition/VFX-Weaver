package dev.vfxweaver.editor;

import dev.vfxweaver.effect.CelestialAnchor;
import dev.vfxweaver.effect.VFXEffectType;
import dev.vfxweaver.effect.VFXWindowSpec;
import dev.vfxweaver.field.VFXField;
import dev.vfxweaver.field.VFXFieldFn;
import dev.vfxweaver.field.VFXFieldOp;
import dev.vfxweaver.field.VFXShape;
import dev.vfxweaver.field.VFXShapeFigure;
import dev.vfxweaver.field.VFXShapeFill;
import dev.vfxweaver.field.VFXSurfaceSelection;
import dev.vfxweaver.field.VFXTexture;
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
 * @param supportsParticleIds    whether the effect reads the optional top-level {@code particle}/
 *                               {@code shape}/{@code block}/{@code item} strings (the {@code particles}
 *                               and {@code block_chain} effects).
 * @param supportsPattern        whether the effect reads a top-level {@code pattern} block
 *                               ({@code VFXShape.parse} — the {@code surface_pattern} and
 *                               {@code sky_pattern} effects).
 * @param supportsSurface        whether the effect reads a top-level {@code surface} block
 *                               ({@code VFXSurfaceSelection.parse} — the {@code surface_pattern} effect).
 * @param supportsAnchor         whether the effect reads a top-level {@code anchor} string
 *                               ({@code CelestialAnchor} — the {@code sky_pattern} effect only).
 * @param supportsWindow         whether the effect reads the {@code VFXWindowSpec} fields
 *                               ({@code id}, optional {@code texture}, {@code titles} — the
 *                               {@code window_create}/{@code window_control} effects).
 * @param supportsScreenImage    whether the effect reads the {@code VFXScreenImageSpec} field
 *                               (required {@code texture} — the {@code screen_image} effect).
 */
public record VFXStructuralDescriptor(boolean supportsMask, List<String> maskShapes,
		boolean supportsPositions, boolean supportsRegion, boolean supportsEntitySelector,
		boolean supportsSound, boolean supportsParticleIds, boolean supportsPattern,
		boolean supportsSurface, boolean supportsAnchor, boolean supportsWindow,
		boolean supportsScreenImage) {
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
		final boolean particleIds = type == VFXEffectType.PARTICLES || type == VFXEffectType.BLOCK_CHAIN;
		final boolean pattern = type == VFXEffectType.SURFACE_PATTERN || type == VFXEffectType.SKY_PATTERN;
		return new VFXStructuralDescriptor(type.isPostProcessing(), SHAPE_IDS, positions, positions,
			entitySelector, true, particleIds, pattern, type == VFXEffectType.SURFACE_PATTERN,
			type == VFXEffectType.SKY_PATTERN,
			type == VFXEffectType.WINDOW_CREATE || type == VFXEffectType.WINDOW_CONTROL,
			type == VFXEffectType.SCREEN_IMAGE);
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
		return new VFXStructuralDescriptor(post, SHAPE_IDS, false, false, false, false,
			false, false, false, false, false, false);
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

	/**
	 * The {@code particle}/{@code shape}/{@code block}/{@code item} metadata (design §13.4): four
	 * optional top-level strings the {@code particles}/{@code block_chain} renderers read (mirrors
	 * {@code VFXDefinition.parse}'s string reads).
	 *
	 * @return the particle-id field spec
	 */
	public static Map<String, Object> particleFields() {
		final Map<String, Object> spec = new LinkedHashMap<>();
		spec.put("fields", List.of("particle", "shape", "block", "item"));
		return spec;
	}

	/**
	 * The {@code pattern} metadata (design §13.4), derived from the shared shape library so the
	 * editor's figure/fill combos and numeric fields cannot drift from {@code VFXShape.parse}: the
	 * figure/fill spellings, the {@code VFXFieldFn.SHAPE} parameter names with their defaults, the
	 * {@code repeat} bounds, the {@code center} arity and the {@code texture} sub-block keys with
	 * their enum spellings and sheet caps (mirrors {@code VFXTexture.parse}).
	 *
	 * @return the pattern field spec
	 */
	public static Map<String, Object> patternFields() {
		final List<String> figures = new ArrayList<>();
		for (final VFXShapeFigure figure : VFXShapeFigure.values()) {
			figures.add(figure.name().toLowerCase(java.util.Locale.ROOT));
		}
		final List<String> fills = new ArrayList<>();
		for (final VFXShapeFill fill : VFXShapeFill.values()) {
			fills.add(fill.name().toLowerCase(java.util.Locale.ROOT));
		}
		final List<String> names = VFXFieldFn.SHAPE.paramNames();
		final List<List<Object>> params = new ArrayList<>(names.size());
		for (final String name : names) {
			params.add(List.of(name, VFXFieldFn.SHAPE.defaultParam(name)));
		}
		final List<String> sources = new ArrayList<>();
		for (final VFXTexture.Source source : VFXTexture.Source.values()) {
			sources.add(source.name().toLowerCase(java.util.Locale.ROOT));
		}
		final List<String> channels = new ArrayList<>();
		for (final VFXTexture.Channel channel : VFXTexture.Channel.values()) {
			channels.add(channel.name().toLowerCase(java.util.Locale.ROOT));
		}
		final List<String> aspects = new ArrayList<>();
		for (final VFXTexture.Aspect aspect : VFXTexture.Aspect.values()) {
			aspects.add(aspect.name().toLowerCase(java.util.Locale.ROOT));
		}
		final Map<String, Object> spec = new LinkedHashMap<>();
		spec.put("figureValues", figures);
		spec.put("fillValues", fills);
		spec.put("shapeParams", params);
		spec.put("repeatMin", VFXShape.MIN_REPEAT);
		spec.put("repeatMax", VFXShape.MAX_REPEAT);
		spec.put("centerArity", 3);
		spec.put("textureKeys", List.of("id", "source", "atlas", "channel", "sheet", "aspect"));
		spec.put("textureSources", sources);
		spec.put("textureChannels", channels);
		spec.put("textureAspects", aspects);
		spec.put("sheetDim", VFXTexture.MAX_SHEET_DIM);
		spec.put("sheetFrames", VFXTexture.MAX_SHEET_FRAMES);
		return spec;
	}

	/**
	 * The {@code surface} metadata (design §13.4), mirroring {@code VFXSurfaceSelection.parse}: the
	 * face tokens, the token cap, the band bounds and the soft-edge cap.
	 *
	 * @return the surface field spec
	 */
	public static Map<String, Object> surfaceFields() {
		final Map<String, Object> spec = new LinkedHashMap<>();
		spec.put("fields", List.of("faces", "min", "max", "band_softness", "stitch"));
		spec.put("faceTokens", List.of("up", "down", "north", "south", "east", "west",
			"x", "y", "z", "horizontal", "vertical", "all"));
		spec.put("maxTokens", VFXSurfaceSelection.MAX_TOKENS);
		spec.put("bandSoftnessMax", VFXSurfaceSelection.MAX_BAND_SOFTNESS);
		return spec;
	}

	/**
	 * The {@code anchor} metadata (design §13.4): the {@code sky_pattern}-only celestial anchor
	 * spellings, derived from {@link CelestialAnchor} (mirrors
	 * {@code VFXDefinition.parse}'s {@code anchor} read).
	 *
	 * @return the anchor field spec
	 */
	public static Map<String, Object> anchorFields() {
		final List<String> values = new ArrayList<>();
		for (final CelestialAnchor anchor : CelestialAnchor.values()) {
			values.add(anchor.key());
		}
		final Map<String, Object> spec = new LinkedHashMap<>();
		spec.put("field", "anchor");
		spec.put("values", values);
		return spec;
	}

	/**
	 * The {@code VFXWindowSpec} metadata (design §13.4): the required {@code id}, the optional
	 * {@code texture} and the {@code titles} list cap (mirrors {@code VFXWindowSpec.parse}).
	 *
	 * @return the window field spec
	 */
	public static Map<String, Object> windowFields() {
		final Map<String, Object> spec = new LinkedHashMap<>();
		spec.put("fields", List.of("id", "texture", "titles"));
		spec.put("maxTitles", VFXWindowSpec.MAX_TITLES);
		return spec;
	}

	/**
	 * The {@code VFXScreenImageSpec} metadata (design §13.4): the required top-level
	 * {@code texture} string (mirrors {@code VFXScreenImageSpec.parse}).
	 *
	 * @return the screen-image field spec
	 */
	public static Map<String, Object> screenImageFields() {
		final Map<String, Object> spec = new LinkedHashMap<>();
		spec.put("field", "texture");
		return spec;
	}

	/**
	 * The per-pixel field function inventory the editor's field nodes generate from (design
	 * §13.2: the UI never hardcodes the function list). One entry per {@link VFXFieldFn}, in
	 * declaration order.
	 *
	 * @return the field function specs, in enum declaration order
	 */
	public static List<Map<String, Object>> fieldFunctions() {
		final List<Map<String, Object>> out = new ArrayList<>(VFXFieldFn.values().length);
		for (final VFXFieldFn fn : VFXFieldFn.values()) {
			final Map<String, Object> spec = new LinkedHashMap<>();
			spec.put("id", fn.id());
			spec.put("outputType", fn.outputType().name().toLowerCase(java.util.Locale.ROOT));
			spec.put("spatial", fn.spatial());
			spec.put("hasChannel", fn.hasChannel());
			spec.put("needsDepth", fn.needsDepth());
			spec.put("hasCurve", fn.hasCurve());
			spec.put("hasTexture", fn.hasTexture());
			spec.put("hasShape", fn.hasShape());
			final List<String> names = fn.paramNames();
			final List<List<Object>> params = new ArrayList<>(names.size());
			for (final String name : names) {
				params.add(List.of(name, fn.defaultParam(name)));
			}
			spec.put("params", params);
			out.add(spec);
		}
		return out;
	}

	/**
	 * The field composition operators, derived from {@link VFXFieldOp} so the editor cannot
	 * drift from the parser (design §13.2). One {@code { id, arity }} entry per operator, in
	 * declaration order.
	 *
	 * @return the field operator specs, in enum declaration order
	 */
	public static List<Map<String, Object>> fieldOperators() {
		final List<Map<String, Object>> out = new ArrayList<>(VFXFieldOp.values().length);
		for (final VFXFieldOp op : VFXFieldOp.values()) {
			final Map<String, Object> spec = new LinkedHashMap<>();
			spec.put("id", op.id());
			spec.put("arity", op.arity());
			out.add(spec);
		}
		return out;
	}

	/**
	 * The field tree caps, mirroring {@link VFXField} (design §13.2). The editor enforces
	 * them with warnings, never by refusing to emit.
	 *
	 * @return the cap map
	 */
	public static Map<String, Object> fieldCaps() {
		final Map<String, Object> spec = new LinkedHashMap<>();
		spec.put("maxDepth", VFXField.MAX_DEPTH);
		spec.put("maxLeaves", VFXField.MAX_LEAVES);
		spec.put("maxProgram", VFXField.MAX_PROGRAM);
		spec.put("maxCurvePoints", VFXField.MAX_CURVE_POINTS);
		spec.put("maxTextureLeaves", VFXField.MAX_TEXTURE_LEAVES);
		spec.put("maxParams", VFXField.MAX_PARAMS);
		return spec;
	}
}

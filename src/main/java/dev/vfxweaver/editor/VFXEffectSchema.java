package dev.vfxweaver.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.vfxweaver.effect.BoundParam;
import dev.vfxweaver.effect.VFXEffectType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The registry of editable effect schemas, keyed by {@link VFXEffectType}. Descriptors are
 * registered once at client init (one per effect type) and read by the visual node editor;
 * {@link #toJson()} exports the whole registry for the editor UI.
 */
public final class VFXEffectSchema {
	private static final Map<VFXEffectType, VFXEffectDescriptor> DESCRIPTORS = new LinkedHashMap<>();
	/** Guards {@link #registerBuiltIns()} so repeated calls (e.g. toggling the editor) are no-ops. */
	private static boolean builtInsRegistered;
	/**
	 * Reflection-based serialisation with special floats allowed, so the tree can be built before
	 * {@link #toJson()} replaces every non-finite bound with {@code null} (see {@link #nullifyNonFinite}).
	 * Nulls are not emitted here, so a {@code null} {@code neutral} stays absent as before.
	 */
	private static final Gson GSON = new GsonBuilder()
			.serializeSpecialFloatingPointValues()
			.create();

	/**
	 * Serialises the already-built tree, emitting the {@code null} bounds written by
	 * {@link #nullifyNonFinite} explicitly.
	 */
	private static final Gson OUTPUT_GSON = new GsonBuilder()
			.serializeNulls()
			.create();

	private VFXEffectSchema() {
	}

	/**
	 * Returns the registered schema for {@code type}.
	 *
	 * @param type the effect type
	 * @return the descriptor, or {@code null} when none is registered
	 */
	@Nullable
	public static VFXEffectDescriptor get(final VFXEffectType type) {
		return DESCRIPTORS.get(type);
	}

	/**
	 * Registers the schema of an effect type.
	 *
	 * @param descriptor the descriptor to register
	 * @throws IllegalStateException when {@code descriptor.type()} is already registered
	 */
	public static void register(final VFXEffectDescriptor descriptor) {
		Objects.requireNonNull(descriptor, "descriptor");
		if (DESCRIPTORS.putIfAbsent(descriptor.type(), descriptor) != null) {
			throw new IllegalStateException("Duplicate effect schema for " + descriptor.type());
		}
	}

	/**
	 * Registers a descriptor for every effect type, one per {@link VFXEffectType}, so the editor UI
	 * has full coverage. Called once at client init, before the UI reads the schema.
	 *
	 * <p>Post-processing types come from the verbatim parameter lists in {@link PostParamSources};
	 * each parameter's {@code defaultValue} is its {@link VFXEffectType#neutralValue(String)
	 * neutral} value when finite, else {@code 0}; {@code neutral} is that value or {@code null} when
	 * the parameter must not fade; {@code fieldCapable} follows
	 * {@link VFXEffectType#fieldCapableInputs()}. A field-capable input (a {@code registerFieldPost}
	 * field input such as {@code color_grade.tint_r} / {@code dent.intensity}) uses
	 * {@link VFXEffectType#fieldNeutral(String)} as its default/neutral. Only {@code radius} has a
	 * spec-fixed range ({@code 0..64}); every other range is unbounded and {@code step} is {@code 0}.
	 *
	 * <p>Every non-post type is registered by {@link #registerNonPost()} from the parameter lists
	 * documented under {@code docs/guide/effects/**}.
	 */
	public static void registerBuiltIns() {
		if (builtInsRegistered) {
			return;
		}
		builtInsRegistered = true;
		for (final Map.Entry<VFXEffectType, String[]> entry : PostParamSources.PARAMS.entrySet()) {
			register(describe(entry.getKey(), entry.getValue()));
		}
		registerNonPost();
	}

	/**
	 * Registers a descriptor for every non-post-processing effect type from the parameter lists
	 * documented under {@code docs/guide/effects/**} (one page per type). {@code COLLECTION} carries
	 * no parameters (it plays {@code effects[]}), and the window types declare only their numeric
	 * params (their string fields {@code id}/{@code texture}/{@code titles} live in the definition).
	 */
	private static void registerNonPost() {
		register(new VFXEffectDescriptor(VFXEffectType.COLLECTION, List.of(),
			VFXEffectType.COLLECTION.isPostProcessing()));

		registerNonPost(VFXEffectType.CAMERA_SHAKE,
			new String[] {"amplitude_x", "amplitude_y", "amplitude_z", "yaw", "pitch", "roll", "frequency", "hand"},
			Map.of());
		registerNonPost(VFXEffectType.CAMERA_ROLL,
			new String[] {"angle", "wobble", "wobble_speed"},
			Map.of("wobble", new float[] {0.0F, 45.0F}, "wobble_speed", new float[] {0.0F, 2.0F}));
		registerNonPost(VFXEffectType.BLOCK_OUTLINE,
			new String[] {"color_r", "color_g", "color_b", "alpha", "width", "shell", "through_blocks"},
			Map.of());
		registerNonPost(VFXEffectType.BLOCK_TINT,
			new String[] {"color_r", "color_g", "color_b", "alpha", "through_blocks"},
			Map.of("alpha", new float[] {0.0F, 1.0F}));
		registerNonPost(VFXEffectType.LIGHT_BEAM,
			new String[] {"radius", "height", "dir_x", "dir_y", "dir_z", "end_at_x", "end_at_y", "end_at_z",
				"top_scale", "softness", "top_fade", "bottom_fade", "red", "green", "blue", "through_blocks", "intensity"},
			Map.of(
				"top_scale", new float[] {0.1F, Float.POSITIVE_INFINITY},
				"top_fade", new float[] {0.0F, 1.0F},
				"bottom_fade", new float[] {0.0F, 1.0F}));
		registerNonPost(VFXEffectType.PULSE_RING,
			new String[] {"radius", "thickness", "billboard", "rot_x", "rot_y", "rot_z", "red", "green", "blue",
				"through_blocks", "intensity"},
			Map.of(
				"rot_x", new float[] {-360.0F, 360.0F},
				"rot_y", new float[] {-360.0F, 360.0F},
				"rot_z", new float[] {-360.0F, 360.0F}));
		registerNonPost(VFXEffectType.GUIDE_LINE,
			new String[] {"width", "dash_length", "gap", "speed", "arc", "red", "green", "blue",
				"through_blocks", "intensity"},
			Map.of());
		registerNonPost(VFXEffectType.PARTICLES,
			new String[] {"rate", "radius", "height", "turns", "spin", "speed", "aim", "spread", "accel",
				"lifetime", "vel_y", "size", "color_r", "color_g", "color_b"},
			Map.of("size", new float[] {0.05F, Float.POSITIVE_INFINITY}));
		registerNonPost(VFXEffectType.BLOCK_CHAIN,
			new String[] {"spacing", "arc", "scale", "align", "physics", "length", "sway"},
			Map.of(
				"spacing", new float[] {0.25F, Float.POSITIVE_INFINITY},
				"scale", new float[] {0.1F, Float.POSITIVE_INFINITY},
				"sway", new float[] {0.0F, 1.0F}));
		registerNonPost(VFXEffectType.ENTITY_TINT,
			new String[] {"color_r", "color_g", "color_b", "alpha", "texture", "through_blocks"},
			Map.of());
		registerNonPost(VFXEffectType.ENTITY_OUTLINE,
			new String[] {"color_r", "color_g", "color_b", "alpha", "width", "through_blocks"},
			Map.of());
		registerNonPost(VFXEffectType.ENTITY_DISPLACE,
			new String[] {"amplitude", "scale", "seed"},
			Map.of("amplitude", new float[] {0.0F, 2.0F}, "scale", new float[] {0.5F, Float.POSITIVE_INFINITY}));
		registerNonPost(VFXEffectType.FOV_MODIFIER,
			new String[] {"fov_delta"},
			Map.of());
		registerNonPost(VFXEffectType.FOG_MODIFIER,
			new String[] {"fog_start_scale", "fog_end_scale", "fog_r", "fog_g", "fog_b", "fog_color_amount"},
			Map.of(
				"fog_r", new float[] {0.0F, 1.0F},
				"fog_g", new float[] {0.0F, 1.0F},
				"fog_b", new float[] {0.0F, 1.0F},
				"fog_color_amount", new float[] {0.0F, 1.0F}));
		registerNonPost(VFXEffectType.WINDOW_CREATE,
			new String[] {"pos_x", "pos_y", "size_w", "size_h", "opacity", "frames", "frame_time", "title_index"},
			windowBounds());
		registerNonPost(VFXEffectType.WINDOW_CONTROL,
			new String[] {"pos_x", "pos_y", "size_w", "size_h", "opacity", "frames", "frame_time", "title_index"},
			windowBounds());
	}

	/**
	 * The {@code window_create}/{@code window_control} geometry bounds: the position/size/opacity
	 * params are fractions in {@code 0..1}, and {@code frames} is at least {@code 1}.
	 *
	 * @return the bounded-parameter map
	 */
	private static Map<String, float[]> windowBounds() {
		return Map.of(
			"pos_x", new float[] {0.0F, 1.0F},
			"pos_y", new float[] {0.0F, 1.0F},
			"size_w", new float[] {0.0F, 1.0F},
			"size_h", new float[] {0.0F, 1.0F},
			"opacity", new float[] {0.0F, 1.0F},
			"frames", new float[] {1.0F, Float.POSITIVE_INFINITY});
	}

	/**
	 * Registers the descriptor of one non-post effect type.
	 *
	 * @param type   the effect type
	 * @param names  the parameter names, in the order the guide page documents them
	 * @param bounds the {@code name -> [min, max]} ranges fixed by the guide; absent names are
	 *               unbounded
	 */
	private static void registerNonPost(final VFXEffectType type, final String[] names,
			final Map<String, float[]> bounds) {
		final List<VFXParamDescriptor> params = new ArrayList<>(names.length);
		for (final String name : names) {
			final float[] range = bounds.get(name);
			final float min = range == null ? Float.NEGATIVE_INFINITY : range[0];
			final float max = range == null ? Float.POSITIVE_INFINITY : range[1];
			final float neutral = type.neutralValue(name);
			final boolean finite = !Float.isNaN(neutral);
			// Keep the default inside the documented range: a read-once param such as "frames"
			// (neutral NaN -> 0) is documented with default 1 and min 1.
			final float value = Math.max(min, Math.min(max, finite ? neutral : 0.0F));
			// "frames" is read once when the window opens, so it cannot be animated or bound.
			final boolean animatable = !"frames".equals(name);
			params.add(new VFXParamDescriptor(
				name,
				VFXParamKind.FLOAT,
				value,
				min,
				max,
				0.0F,
				ParamGroups.groupOf(type, name),
				animatable,
				animatable,
				false,
				List.of(),
				finite ? neutral : null,
				null,
				0));
		}
		register(new VFXEffectDescriptor(type, withVectorGroups(params), type.isPostProcessing()));
	}

	/**
	 * Builds the descriptor of one post-processing effect from its ordered parameter names.
	 * Each parameter's display group comes from {@link ParamGroups} (presentation-only; the
	 * compiler never reads it).
	 *
	 * @param type  the effect type
	 * @param names the parameter names, in {@code Config} order with any field input appended
	 * @return the descriptor
	 */
	private static VFXEffectDescriptor describe(final VFXEffectType type, final String[] names) {
		final List<VFXParamDescriptor> params = new ArrayList<>(names.length);
		for (final String name : names) {
			final boolean fieldCapable = type.fieldCapableInputs().contains(name);
			final float neutral = fieldCapable ? type.fieldNeutral(name) : type.neutralValue(name);
			final boolean bounded = "radius".equals(name);
			params.add(new VFXParamDescriptor(
				name,
				VFXParamKind.FLOAT,
				Float.isNaN(neutral) ? 0.0F : neutral,
				bounded ? 0.0F : Float.NEGATIVE_INFINITY,
				bounded ? 64.0F : Float.POSITIVE_INFINITY,
				0.0F,
				ParamGroups.groupOf(type, name),
				true,
				true,
				fieldCapable,
				List.of(),
				Float.isNaN(neutral) ? null : neutral,
				null,
				0));
		}
		return new VFXEffectDescriptor(type, withVectorGroups(params), type.isPostProcessing());
	}

	/**
	 * Assigns vector-group metadata (design §15.9.6/P4, fix #21) to a param list by a naming
	 * convention over the existing names:
	 *
	 * <ul>
	 *   <li>a trailing {@code _x}/{@code _y}/{@code _z} (component index 0/1/2) groups under the
	 *       name with the suffix removed (e.g. {@code pos_x/_y/_z} → {@code pos});</li>
	 *   <li>a trailing {@code _r}/{@code _g}/{@code _b} (component index 0/1/2) groups under the
	 *       prefix (e.g. {@code color_r/_g/_b} → {@code color}).</li>
	 * </ul>
	 *
	 * <p>A group is only marked when <b>all three</b> components exist for this effect: a partial
	 * triple ({@code center_x}/{@code center_y} with no {@code center_z}) stays ungrouped and no
	 * component is ever invented. Only an exact suffix matches, so an ambiguous name such as
	 * {@code x0} or {@code tex_u0} is never grouped. The result is a new list; ungrouped params are
	 * reused unchanged and grouped ones are copied with {@code vectorGroup}/{@code vectorIndex} set.
	 *
	 * @param params the params in display order
	 * @return the params with vector grouping filled in
	 */
	private static List<VFXParamDescriptor> withVectorGroups(final List<VFXParamDescriptor> params) {
		final String[] suffixes = {"_r", "_g", "_b", "_x", "_y", "_z"};
		final int[] axes = {0, 1, 2, 0, 1, 2};
		// prefix -> component index -> param index, for every name ending in one of the suffixes.
		final Map<String, Map<Integer, Integer>> candidates = new LinkedHashMap<>();
		for (int i = 0; i < params.size(); i++) {
			final String name = params.get(i).name();
			for (int s = 0; s < suffixes.length; s++) {
				if (name.endsWith(suffixes[s])) {
					final String prefix = name.substring(0, name.length() - suffixes[s].length());
					if (!prefix.isEmpty()) {
						candidates.computeIfAbsent(prefix, key -> new LinkedHashMap<>()).put(axes[s], i);
					}
					break;
				}
			}
		}
		final String[] group = new String[params.size()];
		final int[] index = new int[params.size()];
		candidates.forEach((prefix, byAxis) -> {
			if (byAxis.containsKey(0) && byAxis.containsKey(1) && byAxis.containsKey(2)) {
				byAxis.forEach((axis, paramIndex) -> {
					group[paramIndex] = prefix;
					index[paramIndex] = axis;
				});
			}
		});
		final List<VFXParamDescriptor> out = new ArrayList<>(params.size());
		for (int i = 0; i < params.size(); i++) {
			final VFXParamDescriptor param = params.get(i);
			if (group[i] == null) {
				out.add(param);
			} else {
				out.add(new VFXParamDescriptor(param.name(), param.kind(), param.defaultValue(),
						param.min(), param.max(), param.step(), param.group(), param.animatable(),
						param.bindable(), param.fieldCapable(), param.enumValues(), param.neutral(),
						group[i], index[i]));
			}
		}
		return out;
	}

	/**
	 * Serialises the registry to JSON, shaped as
	 * {@code { "<type name>": { "params": [ ... ] } }} with the shader-style lowercase type
	 * name ({@link VFXEffectType#getName()}) as the key. An unbounded soft {@code min}/{@code max}
	 * ({@code ±Infinity}) is emitted as {@code null}, which is valid JSON.
	 *
	 * @return the JSON export
	 */
	public static String toJson() {
		final Map<String, Object> root = new LinkedHashMap<>();
		for (final VFXEffectDescriptor descriptor : DESCRIPTORS.values()) {
			// COLLECTION is a serialisation artifact, not a user-selectable effect (design §15.2): it is
			// kept registered so compilation can still resolve it, but it must not appear in the schema
			// the editor palette is built from.
			if (descriptor.type() == VFXEffectType.COLLECTION) {
				continue;
			}
			final Map<String, Object> descriptorJson = new LinkedHashMap<>();
			descriptorJson.put("params", descriptor.params());
			descriptorJson.put("post", descriptor.post());
			// Additive structural section (design §13.1): the mask affordance and the shape ids the
			// editor's shape combo is populated from. Existing keys are unchanged.
			final VFXStructuralDescriptor structural = descriptor.structural();
			final Map<String, Object> structuralJson = new LinkedHashMap<>();
			structuralJson.put("supportsMask", structural.supportsMask());
			structuralJson.put("maskShapes", structural.maskShapes());
			// Additive per-shape field metadata (design §13.1): the MaskLeaf sub-editors are generated
			// from this, so a new VFXMaskShapeKind appears without an editor change.
			structuralJson.put("maskShapeFields", VFXStructuralDescriptor.shapeFields());
			// Additive structural options (design §13.4): the non-post sub-editors (positions/region,
			// entity selector, sound) are generated from these flags plus their field metadata, so a
			// new effect type advertises them without any per-type branch in the UI.
			structuralJson.put("supportsPositions", structural.supportsPositions());
			structuralJson.put("supportsRegion", structural.supportsRegion());
			structuralJson.put("supportsEntitySelector", structural.supportsEntitySelector());
			structuralJson.put("supportsSound", structural.supportsSound());
			structuralJson.put("positionsFields", VFXStructuralDescriptor.positionFields());
			structuralJson.put("regionFields", VFXStructuralDescriptor.regionFields());
			structuralJson.put("entitySelectorFields", VFXStructuralDescriptor.entitySelectorFields());
			structuralJson.put("soundFields", VFXStructuralDescriptor.soundFields());
			// Additive tail slice (design §13.4): particle ids, pattern/surface, anchor, window and
			// screen-image — same flag-plus-metadata shape, no per-type branch in the UI.
			structuralJson.put("supportsParticleIds", structural.supportsParticleIds());
			structuralJson.put("supportsPattern", structural.supportsPattern());
			structuralJson.put("supportsSurface", structural.supportsSurface());
			structuralJson.put("supportsAnchor", structural.supportsAnchor());
			structuralJson.put("supportsWindow", structural.supportsWindow());
			structuralJson.put("supportsScreenImage", structural.supportsScreenImage());
			structuralJson.put("particleFields", VFXStructuralDescriptor.particleFields());
			structuralJson.put("patternFields", VFXStructuralDescriptor.patternFields());
			structuralJson.put("surfaceFields", VFXStructuralDescriptor.surfaceFields());
			structuralJson.put("anchorFields", VFXStructuralDescriptor.anchorFields());
			structuralJson.put("windowFields", VFXStructuralDescriptor.windowFields());
			structuralJson.put("screenImageFields", VFXStructuralDescriptor.screenImageFields());
			descriptorJson.put("structural", structuralJson);
			root.put(descriptor.type().getName(), descriptorJson);
		}
		root.put("bind", bindSection());
		root.put("field", fieldSection());
		final JsonElement tree = GSON.toJsonTree(root);
		nullifyNonFinite(tree);
		return OUTPUT_GSON.toJson(tree);
	}

	/**
	 * Builds the {@code bind} section of the schema, derived directly from {@link BoundParam.Kind}
	 * and {@link BoundParam.SourceKind} so the editor's bind-kind/needsPos/field lists cannot drift
	 * from the datapack parser.
	 *
	 * <p>Shape: {@code { "kinds": [ { "id", "needsPos", "requiresObjective", "fields" }, ... ],
	 * "sources": [ "camera", ... ] }}. Additive: it sits beside the per-effect keys and changes
	 * none of them.
	 *
	 * @return the {@code bind} map
	 */
	private static Map<String, Object> bindSection() {
		final List<Map<String, Object>> kinds = new ArrayList<>(BoundParam.Kind.values().length);
		for (final BoundParam.Kind kind : BoundParam.Kind.values()) {
			final Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("id", kind.id());
			entry.put("needsPos", kind.needsPos());
			entry.put("requiresObjective", kind.requiresObjective());
			entry.put("fields", kind.optionalFields());
			kinds.add(entry);
		}
		final List<String> sources = new ArrayList<>(BoundParam.SourceKind.values().length);
		for (final BoundParam.SourceKind source : BoundParam.SourceKind.values()) {
			sources.add(source.id());
		}
		final Map<String, Object> bind = new LinkedHashMap<>();
		bind.put("kinds", kinds);
		bind.put("sources", sources);
		return bind;
	}

	/**
	 * Builds the {@code field} section of the schema (design §13.2), derived directly from
	 * {@link dev.vfxweaver.field.VFXFieldFn}, {@link dev.vfxweaver.field.VFXFieldOp} and
	 * {@link dev.vfxweaver.field.VFXField} so the editor's field-node inventory and caps cannot
	 * drift from the datapack parser.
	 *
	 * <p>Shape: {@code { "fns": [ { "id", "outputType", "spatial", "hasChannel", "needsDepth",
	 * "hasCurve", "hasTexture", "hasShape", "params" }, ... ], "ops": [ { "id", "arity" }, ... ],
	 * "caps": { "maxDepth", "maxLeaves", "maxProgram", "maxCurvePoints", "maxTextureLeaves",
	 * "maxParams" } }}. Additive: it sits beside the per-effect keys and changes none of them.
	 * Per-param field-capability rides on each {@code VFXParamDescriptor.fieldCapable}.
	 *
	 * @return the {@code field} map
	 */
	private static Map<String, Object> fieldSection() {
		final Map<String, Object> field = new LinkedHashMap<>();
		field.put("fns", VFXStructuralDescriptor.fieldFunctions());
		field.put("ops", VFXStructuralDescriptor.fieldOperators());
		field.put("caps", VFXStructuralDescriptor.fieldCaps());
		return field;
	}

	/**
	 * Replaces every non-finite number in the tree with JSON {@code null}. The only non-finite values
	 * the schema can hold are the unbounded soft {@code min}/{@code max} sentinels ({@code ±Infinity});
	 * {@code null} is valid JSON and reads as unbounded.
	 *
	 * @param element the tree to scrub in place
	 */
	private static void nullifyNonFinite(final JsonElement element) {
		if (element instanceof JsonObject object) {
			for (final String key : new ArrayList<>(object.keySet())) {
				final JsonElement value = object.get(key);
				if (value instanceof JsonPrimitive primitive && primitive.isNumber()
						&& !Double.isFinite(primitive.getAsDouble())) {
					object.add(key, JsonNull.INSTANCE);
				} else {
					nullifyNonFinite(value);
				}
			}
		} else if (element instanceof JsonArray array) {
			for (final JsonElement child : array) {
				nullifyNonFinite(child);
			}
		}
	}
}

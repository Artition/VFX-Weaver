package dev.vfxweaver.client.postprocessing;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.vfxweaver.effect.VFXEffectType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
//? if <26.1 {
/*import com.mojang.blaze3d.platform.DepthTestFunction;
*///?} else {
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;
//?}
//? if <26.2 {
import com.mojang.blaze3d.vertex.VertexFormat;
//?}
//? if >=26.2 {
/*import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import net.minecraft.client.renderer.BindGroupLayouts;
*///?}
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Registers a dedicated {@link RenderPipeline} for every post-processing effect type and exposes
 * the parameter layout of each shader (used to size and fill the {@code Config} UBO).
 *
 * <p>Some effects are implemented as multiple sequential passes (e.g. {@code blur} runs a
 * horizontal and a vertical Gaussian pass). Pipelines are registered during client init so that
 * they are picked up by the shader reload ({@code ShaderManager}) and precompiled from
 * {@code assets/vfxweaver/shaders/post/*.fsh}.
 */
public final class VFXShaderPrograms {
	/**
	 * How a pass participates in the feedback pipeline. {@code NORMAL} is the standard one-shot
	 * post pass. {@code FEEDBACK_UPDATE} writes the history buffer, {@code FEEDBACK_COMPOSITE}
	 * blends it over the live frame, {@code STOP_MOTION} selects between the live frame and a
	 * held copy via the reserved {@code hold} parameter (see VFXPostProcessingManager).
	 */
	public enum PassRole {
		NORMAL, FEEDBACK_UPDATE, FEEDBACK_COMPOSITE, STOP_MOTION
	}

	/**
	 * Describes one effect shader: its pipeline plus the ordered float parameter names of the
	 * {@code Config} uniform block and its std140-aligned byte size. {@code mask} marks the shared
	 * coverage-read consumer. {@code depthConfig} marks a pass whose {@code Config} starts with
	 * {@code mat4 inv_view_proj} (written by the manager, not by the per-param loop) and which
	 * binds {@code DepthSampler}: {@code surface_pattern} and {@code sky_pattern}. {@code
	 * depthHasCamPos} marks the subset whose {@code Config} then declares {@code vec4 cam_pos}
	 * before the float params (the camera world position the dome view ray starts from); a
	 * non-camPos depth pass keeps the original 64-byte prefix so its layout is unchanged.
	 */
	public record ProgramInfo(RenderPipeline pipeline, String[] configParams, int configUboSize, PassRole role, boolean usesDepth, @Nullable String fieldInput, boolean mask, boolean depthConfig, boolean depthHasCamPos) {
		/** The 8-component form for every depth pass whose Config has no {@code cam_pos} prefix. */
		public ProgramInfo(final RenderPipeline pipeline, final String[] configParams, final int configUboSize, final PassRole role, final boolean usesDepth, final @Nullable String fieldInput, final boolean mask, final boolean depthConfig) {
			this(pipeline, configParams, configUboSize, role, usesDepth, fieldInput, mask, depthConfig, false);
		}

		public ProgramInfo(final RenderPipeline pipeline, final String[] configParams, final int configUboSize, final PassRole role) {
			this(pipeline, configParams, configUboSize, role, false, null, false, false, false);
		}

		public ProgramInfo(final RenderPipeline pipeline, final String[] configParams, final int configUboSize) {
			this(pipeline, configParams, configUboSize, PassRole.NORMAL, false, null, false, false, false);
		}

		/** True when this pipeline declares the {@code FieldConfig} uniform block. */
		public boolean usesField() {
			return this.fieldInput != null;
		}
	}

	private static final Map<VFXEffectType, List<ProgramInfo>> PROGRAMS = new EnumMap<>(VFXEffectType.class);
	private static @Nullable RenderPipeline copyPipeline;
	private static @Nullable RenderPipeline coveragePipeline;
	private static @Nullable ProgramInfo coverageProgram;
	private static @Nullable RenderPipeline maskPipeline;
	private static @Nullable ProgramInfo maskProgram;
	private static @Nullable RenderPipeline blockGeometryPipeline;
	private static @Nullable ProgramInfo blockGeometryProgram;

	//? if >=26.2 {
	/*	// 26.2 moved sampler/uniform declarations to explicit bind-group layouts.
	private static final BindGroupLayout HIST_SAMPLER_LAYOUT = BindGroupLayout.builder()
		.withSampler("HistSampler")
		.build();
	private static final BindGroupLayout SAMPLER_INFO_LAYOUT = BindGroupLayout.builder()
		.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER)
		.build();
	private static final BindGroupLayout SAMPLER_INFO_CONFIG_LAYOUT = BindGroupLayout.builder()
		.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER)
		.withUniform("Config", UniformType.UNIFORM_BUFFER)
		.build();
	// 26.2 bind-group layouts for the field pass. The sampler names must match field.glsl's
	// declarations (`DepthSampler`, `fld_tex0`) and the block name (`FieldConfig`) exactly.
	private static final BindGroupLayout DEPTH_SAMPLER_LAYOUT = BindGroupLayout.builder()
		.withSampler("DepthSampler")
		.build();
	private static final BindGroupLayout FIELD_TEXTURE_LAYOUT = BindGroupLayout.builder()
		.withSampler("fld_tex0")
		.build();
	private static final BindGroupLayout FIELD_CONFIG_LAYOUT = BindGroupLayout.builder()
		.withUniform("FieldConfig", UniformType.UNIFORM_BUFFER)
		.build();
	private static final BindGroupLayout COVERAGE_SAMPLER_LAYOUT = BindGroupLayout.builder()
		.withSampler("CoverageSampler")
		.build();
	private static final BindGroupLayout GEOMETRY_COVERAGE_SAMPLER_LAYOUT = BindGroupLayout.builder()
		.withSampler("GeometryCoverageSampler")
		.build();
	// The depth pass's pattern texture (textured surface_pattern). The sampler name must match
	// surface_pattern.fsh's declaration (`PatternSampler`).
	private static final BindGroupLayout PATTERN_SAMPLER_LAYOUT = BindGroupLayout.builder()
		.withSampler("PatternSampler")
		.build();
	*///?}

	/**
	 * The std140 size of the {@code FieldConfig} block. <b>Contract</b> (AGENTS.md UBO field-order
	 * rule): this mirrors the declaration order in
	 * {@code assets/vfxweaver/shaders/include/field.glsl}, which {@link dev.vfxweaver.field.VFXFieldProgram#write}
	 * emits — change all three together. The four leading floats ({@code fld_uniform},
	 * {@code fld_depth_valid}, {@code fld_leaf_count}, {@code fld_weight}) fill the first 16 bytes,
	 * so {@code fld_weight} reuses the padding the three-float form left before {@code fld_leaf_fn}.
	 */
	public static final int FIELD_CONFIG_SIZE = new Std140SizeCalculator()
		.putFloat().putFloat().putFloat().putFloat()
		.putVec4().putVec4().putVec4()
		.putVec4().putVec4()
		.putVec4().putVec4().putVec4().putVec4()
		.putVec4().putVec4().putVec4().putVec4()
		.putVec4().putVec4().putVec4().putVec4()
		.putVec4().putVec4().putVec4().putVec4()
		.putFloat()
		.putVec4().putVec4().putVec4().putVec4()
		.putFloat().putFloat().putFloat().putFloat().putFloat().putFloat().putFloat().putFloat()
		.putMat4f()
		.putVec4().putVec4()
		.get();

	private VFXShaderPrograms() {
	}

	/**
	 * True when this node's scene depth buffer is reversed (near = 1, far = 0). Proven statically
	 * against the real client jars: 26.2 calls {@code glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE)}
	 * and builds a near/far-swapped projection (reversed); 26.1.2 and 1.21.11 build a standard
	 * projection (near = 0, far = 1). Injected into every depth-reading pipeline as the
	 * {@code VFX_DEPTH_REVERSED} shader define so one shader source serves all nodes (see
	 * {@code include/camera.glsl}). A compile-time constant, so the per-node value is visible in the
	 * built class and the standalone depth-convention check reads it with {@code javap -constants}.
	 */
	//? if >=26.2 {
	/*static final boolean DEPTH_REVERSED = true;
	*///?} else {
	static final boolean DEPTH_REVERSED = false;
	//?}

	/** The {@code withShaderDefine} value for {@link #DEPTH_REVERSED} (the shader tests {@code #if}). */
	private static int depthReversedDefine() {
		return DEPTH_REVERSED ? 1 : 0;
	}

	/**
	 * Builds and registers all effect pipelines. Safe to call multiple times (idempotent).
	 */
	public static void register() {
		if (!PROGRAMS.isEmpty()) {
			return;
		}
		registerPost(VFXEffectType.CHROMATIC_ABERRATION, "intensity", "radius");
		registerFieldPost(VFXEffectType.COLOR_GRADE, new String[]{"saturation", "contrast", "brightness", "tint_g", "tint_b"}, "tint_r");
		registerPost(VFXEffectType.DISTORTION, "amount", "radius");
		registerFieldPost(VFXEffectType.DENT, new String[]{"strength", "radius", "center_x", "center_y", "line_mode", "x0", "y0", "x1", "y1"}, "intensity");
		registerPost(VFXEffectType.GRADIENT_MAP, "from_r", "from_g", "from_b", "to_r", "to_g", "to_b", "intensity", "mode", "pos");
		registerPost(VFXEffectType.POSTERIZE, "strength");
		registerMultiPass(VFXEffectType.BLUR, List.of("blur_x", "blur_y"), List.of(new String[]{"radius"}, new String[]{"radius"}));
		registerPost(VFXEffectType.PIXELATE, "cell_size");
		registerPost(VFXEffectType.HUE_ISOLATION, "hue", "tolerance", "intensity");
		registerPost(VFXEffectType.VIGNETTE, "intensity", "color_r", "color_g", "color_b");
		registerPost(VFXEffectType.SCREEN_FLASH, "alpha", "color_r", "color_g", "color_b");
		registerPost(VFXEffectType.MOTION_BLUR, "intensity", "yaw_delta", "pitch_delta");
		registerPost(VFXEffectType.BLOOM, "intensity", "threshold", "radius");
		registerPost(VFXEffectType.FILM_GRAIN, "intensity", "size", "time");
		registerPost(VFXEffectType.SCANLINES, "intensity", "line_count", "speed", "time");
		registerPost(VFXEffectType.DEPTH_OF_FIELD, "intensity", "focus_center", "focus_range");
		registerPost(VFXEffectType.LETTERBOX, "height", "color_r", "color_g", "color_b");
		registerPost(VFXEffectType.INVERT, "intensity");
		registerPost(VFXEffectType.VORTEX, "strength", "radius", "center_x", "center_y");
		registerPost(VFXEffectType.SPEED_LINES, "center_x", "center_y", "count", "length", "length_rand", "pos_rand", "width", "seed", "color_r", "color_g", "color_b", "intensity");
		registerPost(VFXEffectType.SLICE_SHIFT, "angle", "offset", "shift", "mirror");
		registerPost(VFXEffectType.SOLARIZE, "threshold", "softness", "intensity");
		registerPost(VFXEffectType.DOUBLE_VISION, "offset", "ghost_opacity", "drift", "intensity", "time");
		registerPost(VFXEffectType.EYELIDS, "openness", "softness", "curve", "red", "green", "blue");
		registerPost(VFXEffectType.IRIS_WIPE, "radius", "softness", "center_x", "center_y", "zoom");
		registerPost(VFXEffectType.DIGITAL_GLITCH, "block", "displacement", "rate", "chroma", "seed", "chance", "intensity", "time");
		registerPost(VFXEffectType.VHS, "tracking", "band_height", "band_speed", "bleed", "wobble", "intensity", "time");
		registerPost(VFXEffectType.SHOCKWAVE, "center_x", "center_y", "radius", "width", "amplitude", "sharpness");
		registerFeedbackEffects();
		registerPost(VFXEffectType.NOISE_WARP, "scale", "amplitude", "contrast", "coherence", "speed", "drift_x", "drift_y", "seed", "time");

		// surface_pattern reads scene depth and reconstructs a world position: it is the pass that
		// needs the depth mechanism (spec §5, §9 step 5). Registered on every node — the shader
		// converts the raw depth to NDC z per node (VFX_DEPTH_REVERSED: 26.2 reversed, 26.1.2/1.21.11
		// standard), so the same recipe works everywhere. Every shape number is uploaded; the shared
		// shape library dispatches on it, so there is no grid/ring branch on this side.
		registerDepthPost(VFXEffectType.SURFACE_PATTERN,
			"tile_scale", "color_r", "color_g", "color_b", "opacity",
			"fade_radius", "normal_mask", "distort",
			"center_x", "center_y", "center_z", "shape", "fill",
			"rotation", "stroke_width", "softness", "repeat_x", "repeat_y",
			"radius", "radius_x", "radius_y", "half_width", "half_height",
			"corner_radius", "sides", "time",
			// Appended after the existing names so no earlier std140 offset shifts (AGENTS.md).
			// face_mask: -1 = legacy normal_mask; >= 0 = the surface block's 6-bit face set.
			// band_min/band_max: inclusive band along the fragment's dominant normal axis.
			// band_softness: half-width (blocks) of the band edge fade (0 = hard, legacy).
			"face_mask", "band_min", "band_max", "band_softness",
			// Textured figure (pattern.texture), appended after band_softness in this exact order
			// (std140 offsets are positional; the shader's Config block mirrors it).
			// shape_present, tex_u0/v0/u1/v1, tex_aspect, tex_cols, tex_rows, tex_frame,
			// tex_flags, tex_channel, texture_tint, tex_px_w/tex_px_h (the sprite pixel size for
			// the half-texel sheet inset).
			"shape_present", "tex_u0", "tex_v0", "tex_u1", "tex_v1", "tex_aspect",
			"tex_cols", "tex_rows", "tex_frame", "tex_flags", "tex_channel", "texture_tint",
			"tex_px_w", "tex_px_h",
			// stitch: 1 = unfold a vertical wall into the floor plane (surface.stitch flag), appended
			// last so no earlier std140 offset shifts, 0 = today's hard floor/wall switch.
			"stitch");

		// sky_pattern is surface_pattern's sibling: the same pattern/texture machinery, but the
		// projection target is the sky dome (spec §3.1/§3.2/§4.1). It registers through the same
		// depth-post builder so the per-node VFX_DEPTH_REVERSED define is injected, and adds a
		// vec4 cam_pos after inv_view_proj (the camera world position vfx_view_dir subtracts from
		// the far-plane point). The float tail is the figure + resolved texture surface; the
		// surface-only names (normal_mask, faces, band, stitch) do not exist here.
		registerDepthPost(VFXEffectType.SKY_PATTERN, true,
			"anchor_yaw", "anchor_pitch", "dome_rotation",
			"tile_scale", "color_r", "color_g", "color_b", "opacity", "distort",
			"shape", "fill", "rotation", "stroke_width", "softness",
			"repeat_x", "repeat_y",
			"radius", "radius_x", "radius_y", "half_width", "half_height",
			"corner_radius", "sides", "time",
			"shape_present", "tex_u0", "tex_v0", "tex_u1", "tex_v1", "tex_aspect",
			"tex_cols", "tex_rows", "tex_frame", "tex_flags", "tex_channel", "texture_tint",
			"tex_px_w", "tex_px_h");

		copyPipeline = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
				.withLocation(Identifier.fromNamespaceAndPath("vfxweaver", "post/copy"))
				.withVertexShader("core/screenquad")
				.withFragmentShader(Identifier.fromNamespaceAndPath("vfxweaver", "post/copy"))
				//? if <26.2 {
				.withSampler("InSampler")
				.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER)
				//?} else {
				/*.withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
				.withBindGroupLayout(SAMPLER_INFO_LAYOUT)*/
				//?}
				.build()
		);

		final Identifier coverageShader = Identifier.fromNamespaceAndPath("vfxweaver", "post/mask_coverage");
		coveragePipeline = RenderPipelines.register(buildCoveragePipeline(coverageShader, coverageShader));
		// The coverage Config block is written by VFXMaskUniforms, not the generic per-param loop,
		// so its configParams list stays empty and only its size is carried here.
		coverageProgram = new ProgramInfo(coveragePipeline, new String[0], VFXMaskUniforms.uboSize(), PassRole.NORMAL, false, null, false, false);

		maskPipeline = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
				.withLocation(Identifier.fromNamespaceAndPath("vfxweaver", "post/mask_apply"))
				.withVertexShader("core/screenquad")
				.withFragmentShader(Identifier.fromNamespaceAndPath("vfxweaver", "post/mask_apply"))
				//? if <26.2 {
				.withSampler("InSampler")
				.withSampler("HistSampler")
				.withSampler("CoverageSampler")
				.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER)
				//?} else {
				/*.withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
				.withBindGroupLayout(HIST_SAMPLER_LAYOUT)
				.withBindGroupLayout(COVERAGE_SAMPLER_LAYOUT)
				.withBindGroupLayout(SAMPLER_INFO_LAYOUT)
				*///?}
				.build()
		);
		maskProgram = new ProgramInfo(maskPipeline, new String[0], 0, PassRole.NORMAL, false, null, true, false);

		// The block-geometry contribution writes coverage into the geometry scratch; it is drawn by
		// the manager, not scheduled as an effect-chain ProgramInfo. It draws the selected blocks'
		// baked model quads as triangles (TRIANGLES, one vertex buffer, no index buffer), camera-
		// relative in the DynamicTransforms ModelViewMat, with an identity Projection.
		blockGeometryPipeline = RenderPipelines.register(
			RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("vfxweaver", "world/mask_block_geometry"))
				.withVertexShader("core/position_color")
				.withFragmentShader(Identifier.fromNamespaceAndPath("vfxweaver", "post/mask_block_geometry"))
				.withShaderDefine("VFX_DEPTH_REVERSED", depthReversedDefine())
				//? if <26.2 {
				// The fragment shader depth-tests each occluding block leaf's fragment against the
				// main target's depth view (bound at draw time).
				.withSampler("DepthSampler")
				.withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
				.withUniform("Projection", UniformType.UNIFORM_BUFFER)
				.withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLES)
				//?} else {
				/*.withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
				.withBindGroupLayout(DEPTH_SAMPLER_LAYOUT)
				.withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				*///?}
				//? if <26.1 {
				/*.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
				.withDepthWrite(false)
				.withBlend(BlendFunction.TRANSLUCENT)
				*///?} else {
				//? if <26.2 {
				.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
				//?} else {
				/*.withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, false))
				*///?}
				.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
				//?}
				.withCull(false)
				.build()
		);
		blockGeometryProgram = new ProgramInfo(blockGeometryPipeline, new String[0], 0, PassRole.NORMAL, false, null, false, false);
	}

	/**
	 * Returns the pipeline descriptions for an effect type (one or more sequential passes), or an
	 * empty list when the type has no post-processing shader (e.g. {@code camera_shake}).
	 */
	public static List<ProgramInfo> getPrograms(final VFXEffectType type) {
		return PROGRAMS.getOrDefault(type, List.of());
	}

	/**
	 * Returns the pipeline that copies an arbitrary input texture to the main target.
	 */
	public static @Nullable RenderPipeline getCopyPipeline() {
		return copyPipeline;
	}

	/**
	 * The coverage prepass pipeline, or {@code null} before {@link #register()} runs.
	 */
	public static @Nullable ProgramInfo coverageProgram() {
		return coverageProgram;
	}

	/**
	 * The shared coverage-read mask pipeline, or {@code null} before {@link #register()} runs.
	 */
	public static @Nullable ProgramInfo maskProgram() {
		return maskProgram;
	}

	/**
	 * The block-geometry coverage pipeline, or {@code null} before {@link #register()} runs.
	 */
	public static @Nullable ProgramInfo blockGeometryProgram() {
		return blockGeometryProgram;
	}

	/**
	 * Builds a coverage prepass pipeline with the exact sampler/uniform declaration the coverage
	 * shader needs, under the given pipeline location and fragment shader id. The base coverage pass
	 * uses {@code vfxweaver:post/mask_coverage} for both; a custom-shape variant uses a distinct
	 * {@code .../mask_coverage_v<k>} id so the device shader cache does not collide. A variant is
	 * deliberately <b>not</b> registered through {@link RenderPipelines#register} — the shader
	 * reload precompiles every registered pipeline with the default shader source, which has no
	 * variant shader, and an invalid one would drop every resource pack.
	 */
	static RenderPipeline buildCoveragePipeline(final Identifier location, final Identifier fragmentShader) {
		return RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
			.withLocation(location)
			.withVertexShader("core/screenquad")
			.withFragmentShader(fragmentShader)
			.withShaderDefine("VFX_DEPTH_REVERSED", depthReversedDefine())
			//? if <26.2 {
			.withSampler("DepthSampler")
			.withSampler("GeometryCoverageSampler")
			.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER)
			.withUniform("Config", UniformType.UNIFORM_BUFFER)
			//?} else {
			/*.withBindGroupLayout(DEPTH_SAMPLER_LAYOUT)
			.withBindGroupLayout(GEOMETRY_COVERAGE_SAMPLER_LAYOUT)
			.withBindGroupLayout(SAMPLER_INFO_CONFIG_LAYOUT)
			*///?}
			.build();
	}

	private static void registerPost(final VFXEffectType type, final String... params) {
		registerMultiPass(type, List.of(type.getName()), List.<String[]>of(params));
	}

	/**
	 * Registers a single-pass effect whose fragment shader imports the field library: the pipeline
	 * declares the depth sampler, the field texture sampler and the {@code FieldConfig} uniform
	 * block in addition to the standard inputs. {@code fieldInput} is the effect input whose field
	 * drives the shader.
	 */
	private static void registerFieldPost(final VFXEffectType type, final String[] params, final String fieldInput) {
		Identifier location = Identifier.fromNamespaceAndPath("vfxweaver", "post/" + type.getName());
		RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
			.withLocation(location)
			.withVertexShader("core/screenquad")
			.withFragmentShader(location)
			.withShaderDefine("VFX_DEPTH_REVERSED", depthReversedDefine())
			//? if <26.2 {
			.withSampler("InSampler")
			.withSampler("DepthSampler")
			.withSampler("fld_tex0")
			.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER)
			.withUniform("Config", UniformType.UNIFORM_BUFFER)
			.withUniform("FieldConfig", UniformType.UNIFORM_BUFFER);
			//?} else {
			/*.withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
			.withBindGroupLayout(DEPTH_SAMPLER_LAYOUT)
			.withBindGroupLayout(FIELD_TEXTURE_LAYOUT)
			.withBindGroupLayout(SAMPLER_INFO_CONFIG_LAYOUT)
			.withBindGroupLayout(FIELD_CONFIG_LAYOUT);
			*///?}
		RenderPipeline pipeline = RenderPipelines.register(builder.build());
		PROGRAMS.put(type, List.of(new ProgramInfo(pipeline, params, align16(params.length * 4), PassRole.NORMAL, true, fieldInput, false, false)));
	}

	/**
	 * Registers a single-pass effect that reads the scene depth as {@code DepthSampler}, in
	 * addition to the usual {@code InSampler} and the {@code SamplerInfo}/{@code Config} UBOs.
	 * The shader's {@code Config} block must declare {@code mat4 inv_view_proj;} first, then the
	 * {@code params} floats in the exact order given here (std140 offsets are positional). The
	 * shape itself is evaluated by the shared shape library; this pass supplies its numbers.
	 * The Config has no {@code cam_pos} prefix, so an existing depth pass's layout is unchanged.
	 */
	private static void registerDepthPost(final VFXEffectType type, final String... params) {
		registerDepthPost(type, false, params);
	}

	/**
	 * The camPos-aware depth post. With {@code hasCamPos} the shader's {@code Config} declares
	 * {@code vec4 cam_pos} between {@code inv_view_proj} and the float params, so the Config is
	 * 16 bytes longer and every float sits 16 bytes further in (std140 positional contract);
	 * the manager writes the vec4 and the layout guard checks the same prefix.
	 *
	 * @param type      the effect type
	 * @param hasCamPos true when the shader's Config declares {@code vec4 cam_pos} after the matrix
	 * @param params    the float Config names, in shader order
	 */
	private static void registerDepthPost(final VFXEffectType type, final boolean hasCamPos, final String... params) {
		Identifier location = Identifier.fromNamespaceAndPath("vfxweaver", "post/" + type.getName());
		RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
			.withLocation(location)
			.withVertexShader("core/screenquad")
			.withFragmentShader(location)
			.withShaderDefine("VFX_DEPTH_REVERSED", depthReversedDefine())
			//? if <26.2 {
			.withSampler("InSampler")
			.withSampler("DepthSampler")
			.withSampler("PatternSampler")
			.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER)
			.withUniform("Config", UniformType.UNIFORM_BUFFER)
			//?} else {
			/*.withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
			.withBindGroupLayout(DEPTH_SAMPLER_LAYOUT)
			.withBindGroupLayout(PATTERN_SAMPLER_LAYOUT)
			.withBindGroupLayout(SAMPLER_INFO_CONFIG_LAYOUT)
			*///?}
			.build();
		RenderPipelines.register(pipeline);
		final int uboSize = hasCamPos ? depthConfigSize(params.length, true) : depthConfigSize(params.length);
		PROGRAMS.put(type, List.of(new ProgramInfo(pipeline, params, uboSize, PassRole.NORMAL, true, null, false, true, hasCamPos)));
	}

	/**
	 * The std140 byte size of a depth pass's {@code Config} block: a leading {@code mat4}
	 * {@code inv_view_proj} (64 bytes), an optional {@code vec4} {@code cam_pos} (16 bytes) and
	 * one {@code float} per registered name. The size is derived from the name list, never a
	 * hand-written constant, so appending a name to {@link #registerDepthPost} sizes the block
	 * and the arena slice with it. The trailing {@code align16} is the std140 rule that rounds
	 * the block up to the 16-byte struct alignment.
	 *
	 * @param nameCount the number of registered {@code Config} float names
	 * @return the std140 size in bytes
	 */
	static int depthConfigSize(final int nameCount) {
		return depthConfigSize(nameCount, false);
	}

	/**
	 * The std140 byte size of a depth pass's {@code Config} block with an explicit {@code cam_pos}
	 * prefix (see {@link #registerDepthPost(VFXEffectType, boolean, String...)}).
	 *
	 * @param nameCount the number of registered {@code Config} float names
	 * @param hasCamPos true when the block declares {@code vec4 cam_pos} after the matrix
	 * @return the std140 size in bytes
	 */
	static int depthConfigSize(final int nameCount, final boolean hasCamPos) {
		return (hasCamPos ? 80 : 64) + align16(nameCount * 4);
	}

	/**
	 * Builds the feedback pipelines: {@code afterimage} (update + composite) and
	 * {@code stop_motion}. Each pass samples the live frame ({@code InSampler}) and the relevant
	 * history target ({@code HistSampler}); the save/swap semantics live in
	 * {@code VFXPostProcessingManager}.
	 */
	private static void registerFeedbackEffects() {
		RenderPipeline update = feedbackPipeline("afterimage_update", "decay", "blend", "drift");
		RenderPipeline composite = feedbackPipeline("afterimage_composite", "decay", "blend", "drift", "desat", "intensity");
		RenderPipeline stopMotion = feedbackPipeline("stop_motion", "fps", "hold");

		PROGRAMS.put(VFXEffectType.AFTERIMAGE, List.of(
			new ProgramInfo(update, new String[]{"decay", "blend", "drift"}, align16(4 * 4), PassRole.FEEDBACK_UPDATE),
			new ProgramInfo(composite, new String[]{"decay", "blend", "drift", "desat", "intensity"}, align16(5 * 4), PassRole.FEEDBACK_COMPOSITE)
		));
		PROGRAMS.put(VFXEffectType.STOP_MOTION, List.of(
			new ProgramInfo(stopMotion, new String[]{"fps", "hold"}, align16(2 * 4), PassRole.STOP_MOTION)
		));
	}

	private static RenderPipeline feedbackPipeline(final String shader, final String... configParams) {
		RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
			.withLocation(Identifier.fromNamespaceAndPath("vfxweaver", "post/" + shader))
			.withVertexShader("core/screenquad")
			.withFragmentShader(Identifier.fromNamespaceAndPath("vfxweaver", "post/" + shader))
			//? if <26.2 {
			.withSampler("InSampler")
			.withSampler("HistSampler")
			.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER)
			.withUniform("Config", UniformType.UNIFORM_BUFFER);
			//?} else {
			/*.withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
			.withBindGroupLayout(HIST_SAMPLER_LAYOUT)
			.withBindGroupLayout(SAMPLER_INFO_CONFIG_LAYOUT);
			*///?}
		return RenderPipelines.register(builder.build());
	}

	private static void registerMultiPass(final VFXEffectType type, final List<String> shaders, final List<String[]> params) {
		List<ProgramInfo> programs = new java.util.ArrayList<>(shaders.size());
		for (int i = 0; i < shaders.size(); i++) {
			Identifier location = Identifier.fromNamespaceAndPath("vfxweaver", "post/" + shaders.get(i));
			RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
				.withLocation(location)
				.withVertexShader("core/screenquad")
				.withFragmentShader(Identifier.fromNamespaceAndPath("vfxweaver", "post/" + shaders.get(i)))
				//? if <26.2 {
				.withSampler("InSampler")
				.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER)
				.withUniform("Config", UniformType.UNIFORM_BUFFER)
				//?} else {
				/*.withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
				.withBindGroupLayout(SAMPLER_INFO_CONFIG_LAYOUT)
				*///?}
				.build();
			RenderPipelines.register(pipeline);
			String[] configParams = params.get(i);
			programs.add(new ProgramInfo(pipeline, configParams, align16(configParams.length * 4)));
		}
		PROGRAMS.put(type, List.copyOf(programs));
	}

	private static int align16(final int size) {
		return (size + 15) & ~15;
	}
}

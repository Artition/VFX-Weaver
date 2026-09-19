package dev.vfxweaver.client.postprocessing;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import dev.vfxweaver.effect.VFXEffectType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
//? if >=26.2 {
/*import com.mojang.blaze3d.pipeline.BindGroupLayout;
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
	 * {@code Config} uniform block and its std140-aligned byte size.
	 */
	public record ProgramInfo(RenderPipeline pipeline, String[] configParams, int configUboSize, PassRole role, boolean usesDepth, @Nullable String fieldInput) {
		public ProgramInfo(final RenderPipeline pipeline, final String[] configParams, final int configUboSize, final PassRole role) {
			this(pipeline, configParams, configUboSize, role, false, null);
		}

		public ProgramInfo(final RenderPipeline pipeline, final String[] configParams, final int configUboSize) {
			this(pipeline, configParams, configUboSize, PassRole.NORMAL, false, null);
		}

		/** True when this pipeline declares the {@code FieldConfig} uniform block. */
		public boolean usesField() {
			return this.fieldInput != null;
		}
	}

	private static final Map<VFXEffectType, List<ProgramInfo>> PROGRAMS = new EnumMap<>(VFXEffectType.class);
	private static @Nullable RenderPipeline copyPipeline;

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
	*///?}

	/**
	 * The std140 size of the {@code FieldConfig} block. <b>Contract</b> (AGENTS.md UBO field-order
	 * rule): this mirrors the declaration order in
	 * {@code assets/vfxweaver/shaders/include/field.glsl}, which {@link dev.vfxweaver.field.VFXFieldProgram#write}
	 * emits — change all three together.
	 */
	public static final int FIELD_CONFIG_SIZE = new Std140SizeCalculator()
		.putFloat().putFloat().putFloat()
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
	 * Builds and registers all effect pipelines. Safe to call multiple times (idempotent).
	 */
	public static void register() {
		if (!PROGRAMS.isEmpty()) {
			return;
		}
		registerPost(VFXEffectType.CHROMATIC_ABERRATION, "intensity", "radius");
		registerPost(VFXEffectType.COLOR_GRADE, "saturation", "contrast", "brightness", "tint_r", "tint_g", "tint_b");
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
		PROGRAMS.put(type, List.of(new ProgramInfo(pipeline, params, align16(params.length * 4), PassRole.NORMAL, true, fieldInput)));
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

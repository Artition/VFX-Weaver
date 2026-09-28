package dev.vfxweaver.client.postprocessing;

import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.vfxweaver.client.postprocessing.VFXFusionPlanner.StageRef;
import dev.vfxweaver.util.VFXLog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//? if >=26.2 {
/*import com.mojang.blaze3d.pipeline.BindGroupLayout;
import net.minecraft.client.renderer.BindGroupLayouts;
*///?}

/**
 * Compiles and caches the generated fused pass for a stage topology.
 *
 * <p>The cache key is the SHA-256 of the generated source, so any {@code .fsh} edit or generator
 * change invalidates automatically; two runs of the same shape share one compiled program. The cache
 * is a bounded LRU ({@link VFXFusionPolicy#MAX_FUSED}) and a source whose pipeline failed to compile
 * is remembered in a poison set and never retried. {@link #acquire} returns {@code null} on any
 * failure, so the planner emits the run as {@code Single}s in the same frame.
 */
public final class VFXFusedPrograms {
	/** The role of one fused-run binding beyond each stage's own declared samplers. */
	public enum Kind {
		/** The chain value entering the run ({@link VFXFusionPolicy#INPUT_SAMPLER}). */
		INPUT,
		/** The captured pre-run "before" a run-head consumer reads ({@link VFXFusionPolicy#HIST_SAMPLER}). */
		HISTORY,
		/** A mask's coverage texture ({@link VFXFusionPolicy#COVERAGE_SAMPLER}). */
		COVERAGE,
		/** The shared scene depth ({@code DepthSampler}). */
		DEPTH,
		/** A stage's field texture ({@code fld_tex0}). */
		FIELD
	}

	/**
	 * One fused-run binding: the canonical sampler name, its {@link Kind} and the stage it belongs
	 * to ({@code -1} for a run-level binding).
	 */
	public record Binding(Kind kind, String sampler, int stageIndex) {
	}

	/**
	 * A compiled fused pass: the pipeline, its program name, the sampler bindings its merged
	 * {@code Config} expects and the merged uniform block's size.
	 */
	public record FusedProgram(RenderPipeline pipeline, String name, List<Binding> bindings, int configParamCount, int configUboSize) {
	}

	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/fusion");

	/** The bounded LRU: a new program evicts the least recently used past {@link VFXFusionPolicy#MAX_FUSED}. */
	private static final Map<String, FusedProgram> CACHE = new LinkedHashMap<>(16, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(final Map.Entry<String, FusedProgram> eldest) {
			return size() > VFXFusionPolicy.MAX_FUSED;
		}
	};

	/** Source keys whose pipeline failed to compile; never retried, so a bad topology cannot thrash. */
	private static final Set<String> POISONED = new LinkedHashSet<>();

	/**
	 * The fused program for this exact stage topology, or {@code null} on any failure (disabled,
	 * {@code UNUSABLE} source, cache miss that failed to compile).
	 *
	 * @param stages the run's stages in chain order
	 * @return the compiled program, or {@code null} so the caller falls back to {@code Single}s
	 */
	public static @Nullable FusedProgram acquire(final List<StageRef> stages) {
		if (!VFXFusionPolicy.ENABLED) {
			return null;
		}
		final VFXFusedShaderGenerator.Generated generated;
		try {
			generated = VFXFusedShaderGenerator.generate(stages, VFXFusedPrograms::assetSource);
		} catch (final RuntimeException failure) {
			return null;
		}
		if (generated == null) {
			return null;
		}
		final String key = sha256(generated.source());
		if (POISONED.contains(key)) {
			return null;
		}
		final FusedProgram cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		final FusedProgram compiled = compile(key, generated);
		if (compiled == null) {
			POISONED.add(key);
			VFXLog.warnOnce(LOGGER, "fused_compile:" + key, "fused post program {} failed to compile - the run stays unfused", key.substring(0, 16));
			return null;
		}
		CACHE.put(key, compiled);
		return compiled;
	}

	/** The raw fragment source for a post shader or include, or {@code null} when unavailable. */
	private static @Nullable String assetSource(final Identifier location) {
		final Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			return null;
		}
		final ShaderManager shaders = minecraft.getShaderManager();
		return shaders == null ? null : shaders.getShader(location, ShaderType.FRAGMENT);
	}

	private static @Nullable FusedProgram compile(final String key, final VFXFusedShaderGenerator.Generated generated) {
		final Identifier location = Identifier.fromNamespaceAndPath("vfxweaver", "post/fused_" + key.substring(0, 16));
		final RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
			.withLocation(location)
			.withVertexShader("core/screenquad")
			.withFragmentShader(location);
		//? if >=26.2 {
		/*builder.withBindGroupLayout(BindGroupLayouts.IN_SAMPLER);
		if (hasKind(generated, Kind.DEPTH)) {
			builder.withBindGroupLayout(BindGroupLayout.builder().withSampler("DepthSampler").build());
		}
		for (final String sampler : boundSamplers(generated)) {
			builder.withBindGroupLayout(BindGroupLayout.builder().withSampler(sampler).build());
		}
		final BindGroupLayout.Builder info = BindGroupLayout.builder().withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER);
		if (generated.configParamCount() > 0) {
			info.withUniform("Config", UniformType.UNIFORM_BUFFER);
		}
		builder.withBindGroupLayout(info.build());
		*///?} else {
		builder.withSampler("InSampler");
		if (hasKind(generated, Kind.DEPTH)) {
			builder.withSampler("DepthSampler");
		}
		for (final String sampler : boundSamplers(generated)) {
			builder.withSampler(sampler);
		}
		builder.withUniform("SamplerInfo", UniformType.UNIFORM_BUFFER);
		if (generated.configParamCount() > 0) {
			builder.withUniform("Config", UniformType.UNIFORM_BUFFER);
		}
		//?}
		final RenderPipeline pipeline = builder.build();
		final ShaderSource shaderSource = (sourceLocation, type) -> type == ShaderType.FRAGMENT && sourceLocation.equals(location)
			? generated.source()
			: assetSource(sourceLocation);
		final CompiledRenderPipeline compiled = RenderSystem.getDevice().precompilePipeline(pipeline, shaderSource);
		if (compiled == null || !compiled.isValid()) {
			return null;
		}
		return new FusedProgram(pipeline, location.toString(), generated.bindings(), generated.configParamCount(), generated.configUboSize());
	}

	private static boolean hasKind(final VFXFusedShaderGenerator.Generated generated, final Kind kind) {
		for (final Binding binding : generated.bindings()) {
			if (binding.kind() == kind) {
				return true;
			}
		}
		return false;
	}

	private static Set<String> boundSamplers(final VFXFusedShaderGenerator.Generated generated) {
		final Set<String> samplers = new LinkedHashSet<>();
		for (final Binding binding : generated.bindings()) {
			if (binding.kind() != Kind.INPUT && binding.kind() != Kind.DEPTH) {
				samplers.add(binding.sampler());
			}
		}
		return samplers;
	}

	private static String sha256(final String source) {
		try {
			final MessageDigest digest = MessageDigest.getInstance("SHA-256");
			final byte[] hash = digest.digest(source.getBytes(StandardCharsets.UTF_8));
			final StringBuilder hex = new StringBuilder(hash.length * 2);
			for (final byte b : hash) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		} catch (final NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is required", impossible);
		}
	}

	private VFXFusedPrograms() {
	}
}

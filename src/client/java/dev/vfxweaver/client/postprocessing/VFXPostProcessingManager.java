package dev.vfxweaver.client.postprocessing;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.SamplerCache;
import com.mojang.blaze3d.textures.FilterMode;
import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.effect.VFXActiveEffect;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the active post-processing effects to the main render target every frame. The chain
 * reads the main target, bounces through two persistent ping-pong {@link TextureTarget}s and
 * finally writes back to the main target (so that the GUI is drawn on top of the effects).
 *
 * <p>The execution mirrors the vanilla {@code PostPass} pattern: an orthographic projection, a
 * {@code SamplerInfo} UBO ({@code vec2 OutSize, vec2 InSize}) and a per-effect {@code Config}
 * UBO fed through ring buffers that are mapped and rotated every frame.
 */
public final class VFXPostProcessingManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/post");
	private static final int SAMPLER_INFO_SIZE = new Std140SizeCalculator().putVec2().putVec2().get();
	private static final int UBO_USAGE = 130;

	private final TextureTarget[] pingPong = new TextureTarget[2];
	private final Projection projection = new Projection();
	private final Map<Identifier, VFXPass> passes = new HashMap<>();
	private @Nullable ProjectionMatrixBuffer projectionMatrixBuffer;
	private int lastWidth;
	private int lastHeight;

	/** Frees GPU buffers and cached passes on client shutdown. */
	public void freeGpuResources() {
		for (TextureTarget target : this.pingPong) {
			if (target != null) {
				target.destroyBuffers();
			}
		}
		this.passes.clear();
		this.projectionMatrixBuffer = null;
	}

	private VFXPostProcessingManager() {
	}

	public static VFXPostProcessingManager get() {
		return VFXPostProcessingManagerHolder.INSTANCE;
	}

	/**
	 * Runs the chain of active screen effects assigned to layer {@code layer} (see
	 * {@code screen_layer}). Called on the render thread every frame; may be called several times
	 * per frame with different layers.
	 *
	 * @param layer 0 = below the first-person hand, 1 = above the hand below the GUI (default),
	 *              2 = above everything including the GUI
	 */
	public void process(final VFXEffectManager effects, final RenderTarget mainTarget, final int layer) {
		List<VFXActiveEffect> active = new ArrayList<>();
		for (VFXActiveEffect effect : effects.getActivePostEffects()) {
			if (Math.round(Mth.clamp(effect.getParam("screen_layer", 1.0F), 0.0F, 2.0F)) == layer) {
				active.add(effect);
			}
		}
		if (active.isEmpty() || mainTarget == null) {
			return;
		}
		int width = mainTarget.width;
		int height = mainTarget.height;
		if (width <= 0 || height <= 0) {
			return;
		}

		// Expand every active effect into its sequential shader passes (e.g. blur = X + Y).
		List<PassRun> chain = new ArrayList<>();
		for (VFXActiveEffect effect : active) {
			for (VFXShaderPrograms.ProgramInfo info : VFXShaderPrograms.getPrograms(effect.getType())) {
				chain.add(new PassRun(this.pass(info), effect));
			}
		}
		if (chain.isEmpty()) {
			return;
		}

		this.ensureTargets(width, height);
		this.projection.setSize(width, height);
		if (this.projectionMatrixBuffer == null) {
			this.projectionMatrixBuffer = new ProjectionMatrixBuffer("vfxweaver_post");
		}
		GpuBufferSlice ortho = this.projectionMatrixBuffer.getBuffer(this.projection);

		RenderSystem.backupProjectionMatrix();
		RenderSystem.setProjectionMatrix(ortho, ProjectionType.ORTHOGRAPHIC);
		try {
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			SamplerCache samplerCache = RenderSystem.getSamplerCache();

			// Always bounce through the ping-pong targets so that effects sample from a
			// texture that is not the one they are writing to. Copy main -> pingpong[0]
			// first, then run the chain starting from that buffer.
			VFXPass copy = this.copyPass();
			copy.execute(encoder, samplerCache, mainTarget, this.pingPong[0], null);

			RenderTarget read = this.pingPong[0];
			int pingPongIndex = 1;
			for (int i = 0; i < chain.size(); i++) {
				boolean last = i == chain.size() - 1;
				PassRun run = chain.get(i);
				RenderTarget output = last ? mainTarget : this.pingPong[pingPongIndex];
				run.pass().execute(encoder, samplerCache, read, output, run.effect());
				read = output;
				if (!last) {
					pingPongIndex = 1 - pingPongIndex;
				}
			}
		} catch (Exception e) {
			LOGGER.warn("Failed to apply VFX post-processing", e);
		} finally {
			RenderSystem.restoreProjectionMatrix();
		}
	}

	/**
	 * One scheduled pass of the chain: the shader plus the effect whose parameters drive it.
	 */
	private record PassRun(VFXPass pass, VFXActiveEffect effect) {
	}

	private void ensureTargets(final int width, final int height) {
		if (width != this.lastWidth || height != this.lastHeight) {
			for (int i = 0; i < this.pingPong.length; i++) {
				if (this.pingPong[i] != null) {
					this.pingPong[i].destroyBuffers();
				}
				this.pingPong[i] = new TextureTarget("vfxweaver pingpong " + i, width, height, false);
			}
			this.lastWidth = width;
			this.lastHeight = height;
		}
	}

	private VFXPass pass(final VFXShaderPrograms.ProgramInfo info) {
		return this.passes.computeIfAbsent(info.pipeline().getLocation(), location -> new VFXPass(info));
	}

	private VFXPass copyPass() {
		RenderPipeline pipeline = VFXShaderPrograms.getCopyPipeline();
		return this.passes.computeIfAbsent(pipeline.getLocation(), location -> new VFXPass(
			new VFXShaderPrograms.ProgramInfo(pipeline, new String[0], 0)
		));
	}

	/**
	 * One post-processing step: samples {@code InSampler} from the input target and writes the
	 * result into the output target, driven by the effect's animated parameters.
	 */
	private static final class VFXPass {
		private final RenderPipeline pipeline;
		private final String[] configParams;
		private final MappableRingBuffer samplerInfoUbo;
		private final @Nullable MappableRingBuffer configUbo;

		private VFXPass(final VFXShaderPrograms.ProgramInfo info) {
			this.pipeline = info.pipeline();
			this.configParams = info.configParams();
			this.samplerInfoUbo = new MappableRingBuffer(() -> this.pipeline.getLocation() + " SamplerInfo", UBO_USAGE, SAMPLER_INFO_SIZE);
			this.configUbo = info.configUboSize() > 0
				? new MappableRingBuffer(() -> this.pipeline.getLocation() + " Config", UBO_USAGE, Math.max(16, info.configUboSize()))
				: null;
		}

		private void execute(
			final CommandEncoder encoder,
			final SamplerCache samplerCache,
			final RenderTarget input,
			final RenderTarget output,
			final @Nullable VFXActiveEffect effect
		) {
			try (GpuBuffer.MappedView view = encoder.mapBuffer(this.samplerInfoUbo.currentBuffer(), false, true)) {
				Std140Builder.intoBuffer(view.data()).putVec2(output.width, output.height).putVec2(input.width, input.height);
			}

			if (this.configUbo != null && effect != null) {
				float weight = effect.getWeight();
				try (GpuBuffer.MappedView view = encoder.mapBuffer(this.configUbo.currentBuffer(), false, true)) {
					Std140Builder builder = Std140Builder.intoBuffer(view.data());
					for (String param : this.configParams) {
						// Reserved "time" parameter: the effect's unwrapped age in ticks (never
						// faded), used by shaders that animate procedurally (grain, scanlines).
						float raw = "time".equals(param) ? effect.getAge() : effect.getParam(param, 0.0F);
						float neutral = "time".equals(param) ? Float.NaN : effect.getType().neutralValue(param);
						builder.putFloat(Float.isNaN(neutral) ? raw : neutral + (raw - neutral) * weight);
					}
				}
			}

			try (RenderPass renderPass = encoder.createRenderPass(
					() -> "VFX post " + this.pipeline.getLocation(),
					output.getColorTextureView(),
					OptionalInt.empty()
				)) {
				renderPass.setPipeline(this.pipeline);
				RenderSystem.bindDefaultUniforms(renderPass);
				renderPass.setUniform("SamplerInfo", this.samplerInfoUbo.currentBuffer());
				if (this.configUbo != null) {
					renderPass.setUniform("Config", this.configUbo.currentBuffer());
				}
				renderPass.bindTexture("InSampler", input.getColorTextureView(), samplerCache.getClampToEdge(FilterMode.LINEAR));
				renderPass.draw(0, 3);
			}

			this.samplerInfoUbo.rotate();
			if (this.configUbo != null) {
				this.configUbo.rotate();
			}
		}
	}

	private static final class VFXPostProcessingManagerHolder {
		private static final VFXPostProcessingManager INSTANCE = new VFXPostProcessingManager();
	}
}

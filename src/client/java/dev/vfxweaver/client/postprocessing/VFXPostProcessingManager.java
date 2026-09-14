package dev.vfxweaver.client.postprocessing;

import com.mojang.blaze3d.ProjectionType;
//? if >=26.2 {
/*import com.mojang.blaze3d.GpuFormat;
*///?}
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
//? if >=26.2 {
/*import java.util.Optional;
*///?}
import java.util.OptionalInt;
import net.minecraft.client.renderer.MappableRingBuffer;
//? if <26.1 {
/*import net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer;
*///?} else {
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
//?}
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
	/** Double-buffered history for the feedback effects (afterimage). */
	private final TextureTarget[] history = new TextureTarget[2];
	private @Nullable TextureTarget stopMotionHold;
	/** Last quantised hold slot per stop_motion effect (effect id -> slot). */
	private final Map<Identifier, Integer> stopMotionSlots = new HashMap<>();
	//? if >=26.1
	private final Projection projection = new Projection();
	private final Map<Identifier, VFXPass> passes = new HashMap<>();
	//? if <26.1 {
/*	private @Nullable CachedOrthoProjectionMatrixBuffer projectionMatrixBuffer;
*///?} else {
	private @Nullable ProjectionMatrixBuffer projectionMatrixBuffer;
//?}
	private int lastWidth;
	private int lastHeight;
	/** True until the history targets are (re)created; first feedback blend treats prev = current. */
	private boolean historyDirty = true;

/** Frees GPU buffers and cached passes on client shutdown. */
	public void freeGpuResources() {
		for (TextureTarget target : this.pingPong) {
			if (target != null) {
				target.destroyBuffers();
			}
		}
		for (TextureTarget target : this.history) {
			if (target != null) {
				target.destroyBuffers();
			}
		}
		if (this.stopMotionHold != null) {
			this.stopMotionHold.destroyBuffers();
			this.stopMotionHold = null;
		}
		this.stopMotionSlots.clear();
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
		//? if <26.1 {
/*		if (this.projectionMatrixBuffer == null) {
			this.projectionMatrixBuffer = new CachedOrthoProjectionMatrixBuffer("vfxweaver_post", 0.1F, 1000.0F, false);
		}
		GpuBufferSlice ortho = this.projectionMatrixBuffer.getBuffer(width, height);
*///?} else {
		this.projection.setSize(width, height);
		if (this.projectionMatrixBuffer == null) {
			this.projectionMatrixBuffer = new ProjectionMatrixBuffer("vfxweaver_post");
		}
		GpuBufferSlice ortho = this.projectionMatrixBuffer.getBuffer(this.projection);
//?}

		RenderSystem.backupProjectionMatrix();
		RenderSystem.setProjectionMatrix(ortho, ProjectionType.ORTHOGRAPHIC);
		try {
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			SamplerCache samplerCache = RenderSystem.getSamplerCache();

			// Always bounce through the ping-pong targets so that effects sample from a
			// texture that is not the one they are writing to. Copy main -> pingpong[0]
			// first, then run the chain starting from that buffer.
			VFXPass copy = this.copyPass();
			copy.execute(encoder, samplerCache, mainTarget, this.pingPong[0], null, null, null);
			this.pruneStopMotionSlots(active);

			RenderTarget read = this.pingPong[0];
			int pingPongIndex = 1;
			for (int i = 0; i < chain.size(); i++) {
				boolean last = i == chain.size() - 1;
				PassRun run = chain.get(i);
				VFXShaderPrograms.PassRole role = run.role();
				if (role == VFXShaderPrograms.PassRole.FEEDBACK_UPDATE) {
					// History update: read the live frame + the previous history, write the next.
					RenderTarget histPrev = this.historyDirty ? read : this.history[0];
					run.pass().execute(encoder, samplerCache, read, this.history[1], run.effect(), histPrev, null);
					this.historyDirty = false;
					// read stays the live frame for the composite pass.
				} else {
					RenderTarget output = last ? mainTarget : this.pingPong[pingPongIndex];
					if (role == VFXShaderPrograms.PassRole.STOP_MOTION) {
						float hold = this.updateStopMotionHold(run.effect(), encoder, samplerCache, copy, mainTarget);
						run.pass().execute(encoder, samplerCache, read, output, run.effect(), this.stopMotionHold, hold);
					} else if (role == VFXShaderPrograms.PassRole.FEEDBACK_COMPOSITE) {
						run.pass().execute(encoder, samplerCache, read, output, run.effect(), this.history[1], null);
						this.swapHistory();
					} else {
						run.pass().execute(encoder, samplerCache, read, output, run.effect(), null, null);
					}
					read = output;
					if (!last) {
						pingPongIndex = 1 - pingPongIndex;
					}
				}
			}
		} catch (Exception e) {
			LOGGER.warn("Failed to apply VFX post-processing", e);
		} finally {
			RenderSystem.restoreProjectionMatrix();
		}
	}

	private void swapHistory() {
		TextureTarget tmp = this.history[0];
		this.history[0] = this.history[1];
		this.history[1] = tmp;
	}

	/**
	 * CPU hold gating for {@code stop_motion}: when the quantised age slot changes, recapture the
	 * live main target into the hold target (the next frames repeat it), else keep holding.
	 *
	 * @return 1.0F to hold the captured frame, 0.0F to pass the live frame through
	 */
	private float updateStopMotionHold(
		final VFXActiveEffect effect,
		final CommandEncoder encoder,
		final SamplerCache samplerCache,
		final VFXPass copy,
		final RenderTarget mainTarget
	) {
		float fps = effect.getParam("fps", 0.0F);
		if (fps <= 1.0F) {
			this.stopMotionSlots.remove(effect.getId());
			return 0.0F;
		}
		int slot = (int) Math.floor(effect.getAge() / 20.0F * fps);
		Integer prev = this.stopMotionSlots.get(effect.getId());
		this.stopMotionSlots.put(effect.getId(), slot);
		if (prev == null || prev != slot) {
			copy.execute(encoder, samplerCache, mainTarget, this.stopMotionHold, null, null, null);
			return 0.0F;
		}
		return 1.0F;
	}

	private void pruneStopMotionSlots(final List<VFXActiveEffect> active) {
		if (this.stopMotionSlots.isEmpty()) {
			return;
		}
		this.stopMotionSlots.keySet().removeIf(id -> active.stream().noneMatch(e -> e.getId().equals(id)));
	}

	/**
	 * One scheduled pass of the chain: the shader plus the effect whose parameters drive it.
	 */
	private record PassRun(VFXPass pass, VFXActiveEffect effect) {
		VFXShaderPrograms.PassRole role() {
			return this.pass.role();
		}
	}

	private void ensureTargets(final int width, final int height) {
		if (width != this.lastWidth || height != this.lastHeight) {
			for (int i = 0; i < this.pingPong.length; i++) {
				if (this.pingPong[i] != null) {
					this.pingPong[i].destroyBuffers();
				}
				this.pingPong[i] = createTarget("vfxweaver pingpong " + i, width, height, false);
			}
			for (int i = 0; i < this.history.length; i++) {
				if (this.history[i] != null) {
					this.history[i].destroyBuffers();
				}
				this.history[i] = createTarget("vfxweaver history " + i, width, height, false);
			}
			if (this.stopMotionHold != null) {
				this.stopMotionHold.destroyBuffers();
			}
			this.stopMotionHold = createTarget("vfxweaver stop motion hold", width, height, false);
			this.stopMotionSlots.clear();
			this.historyDirty = true;
			this.lastWidth = width;
			this.lastHeight = height;
		}
	}

	/** Creates a colour render target; 26.2 requires an explicit GPU format. */
	private static TextureTarget createTarget(final String label, final int width, final int height, final boolean useDepth) {
		//? if <26.2 {
		return new TextureTarget(label, width, height, useDepth);
		//?} else {
		/*return new TextureTarget(label, width, height, useDepth, GpuFormat.RGBA8_UNORM);*/
		//?}
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
		private final VFXShaderPrograms.PassRole role;
		private final MappableRingBuffer samplerInfoUbo;
		private final @Nullable MappableRingBuffer configUbo;

		private VFXPass(final VFXShaderPrograms.ProgramInfo info) {
			this.pipeline = info.pipeline();
			this.configParams = info.configParams();
			this.role = info.role();
			this.samplerInfoUbo = new MappableRingBuffer(() -> this.pipeline.getLocation() + " SamplerInfo", UBO_USAGE, SAMPLER_INFO_SIZE);
			this.configUbo = info.configUboSize() > 0
				? new MappableRingBuffer(() -> this.pipeline.getLocation() + " Config", UBO_USAGE, Math.max(16, info.configUboSize()))
				: null;
		}

		VFXShaderPrograms.PassRole role() {
			return this.role;
		}

		private void execute(
			final CommandEncoder encoder,
			final SamplerCache samplerCache,
			final RenderTarget input,
			final RenderTarget output,
			final @Nullable VFXActiveEffect effect,
			final @Nullable RenderTarget history,
			final @Nullable Float hold
		) {
			//? if <26.2 {
			try (GpuBuffer.MappedView view = encoder.mapBuffer(this.samplerInfoUbo.currentBuffer(), false, true)) {
			//?} else {
			/*try (GpuBufferSlice.MappedView view = this.samplerInfoUbo.currentBuffer().map(false, true)) {
			*///?}
				Std140Builder.intoBuffer(view.data()).putVec2(output.width, output.height).putVec2(input.width, input.height);
			}

			if (this.configUbo != null && effect != null) {
				float weight = effect.getWeight();
				//? if <26.2 {
				try (GpuBuffer.MappedView view = encoder.mapBuffer(this.configUbo.currentBuffer(), false, true)) {
				//?} else {
				/*try (GpuBufferSlice.MappedView view = this.configUbo.currentBuffer().map(false, true)) {
				*///?}
					Std140Builder builder = Std140Builder.intoBuffer(view.data());
					for (String param : this.configParams) {
						// Reserved "time" and "hold" parameters: never faded, filled from the
						// effect age / the CPU hold-gate instead of a user parameter.
						float raw;
						boolean reserved = "time".equals(param) || "hold".equals(param);
						if ("time".equals(param)) {
							raw = effect.getAge();
						} else if ("hold".equals(param)) {
							raw = hold != null ? hold : 0.0F;
						} else {
							raw = effect.getParam(param, 0.0F);
						}
						float neutral = reserved ? Float.NaN : effect.getType().neutralValue(param);
						builder.putFloat(Float.isNaN(neutral) ? raw : neutral + (raw - neutral) * weight);
					}
				}
			}

			try (RenderPass renderPass = encoder.createRenderPass(
					() -> "VFX post " + this.pipeline.getLocation(),
					output.getColorTextureView(),
					//? if <26.2 {
					OptionalInt.empty()
					//?} else {
					/*Optional.empty()
					*///?}
				)) {
				renderPass.setPipeline(this.pipeline);
				RenderSystem.bindDefaultUniforms(renderPass);
				renderPass.setUniform("SamplerInfo", this.samplerInfoUbo.currentBuffer());
				if (this.configUbo != null) {
					renderPass.setUniform("Config", this.configUbo.currentBuffer());
				}
				renderPass.bindTexture("InSampler", input.getColorTextureView(), samplerCache.getClampToEdge(FilterMode.LINEAR));
				if (history != null) {
					renderPass.bindTexture("HistSampler", history.getColorTextureView(), samplerCache.getClampToEdge(FilterMode.LINEAR));
				}
				//? if <26.2 {
				renderPass.draw(0, 3);
				//?} else {
				/*renderPass.draw(3, 1, 0, 0);
				*///?}
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

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
import dev.vfxweaver.field.VFXFieldProgram;
import dev.vfxweaver.mask.VFXCustomShape;
import dev.vfxweaver.mask.VFXMask;
import dev.vfxweaver.mask.VFXShapeRegistry;
import dev.vfxweaver.util.VFXLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
//? if >=26.2 {
/*import java.util.Optional;
*///?}
import java.util.OptionalInt;
import java.util.function.Consumer;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.Minecraft;
//? if <26.1 {
/*import net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer;
*///?} else {
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
//?}
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.joml.Matrix4fc;
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
	/** Uniform-arena slot alignment; over-aligns to the 256-byte desktop stride to avoid device queries. */
	private static final int ARENA_BLOCK_ALIGNMENT = 256;
	/** Initial slot count of a per-pass uniform arena (grows only on an unusually busy frame). */
	private static final int ARENA_INITIAL_CAPACITY = 16;

	private final TextureTarget[] pingPong = new TextureTarget[2];
	/** Double-buffered history for the feedback effects (afterimage). */
	private final TextureTarget[] history = new TextureTarget[2];
	private @Nullable TextureTarget stopMotionHold;
	/** Last quantised hold slot per stop_motion effect (effect id -> slot). */
	private final Map<Identifier, Integer> stopMotionSlots = new HashMap<>();
	/** One coverage target per distinct masked definition, reused across frames (pruned to the live set). */
	private final Map<Identifier, TextureTarget> coverageTargets = new HashMap<>();
	/** One geometry-coverage scratch per distinct block mask, cleared each frame before the prepass. */
	private final Map<Identifier, TextureTarget> geometryTargets = new HashMap<>();
	/** The pre-effect image one masked effect's consumer blends against; sequential use, one buffer. */
	private @Nullable TextureTarget maskBefore;
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
		for (TextureTarget target : this.coverageTargets.values()) {
			target.destroyBuffers();
		}
		this.coverageTargets.clear();
		for (TextureTarget target : this.geometryTargets.values()) {
			target.destroyBuffers();
		}
		this.geometryTargets.clear();
		if (this.maskBefore != null) {
			this.maskBefore.destroyBuffers();
			this.maskBefore = null;
		}
		VFXMaskBlockGeometry.freeGpuResources();
		this.stopMotionSlots.clear();
		for (final VFXPass pass : this.passes.values()) {
			pass.close();
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
		// Every distinct masked definition this frame, regardless of the owning effect's layer: the
		// coverage prepass runs at layer 0 for all of them, and the consumer reads it at any layer.
		final Map<Identifier, VFXActiveEffect> maskEffects = activeMaskEffects(effects.getActivePostEffects());
		if (mainTarget == null || (active.isEmpty() && (layer != 0 || maskEffects.isEmpty()))) {
			return;
		}
		int width = mainTarget.width;
		int height = mainTarget.height;
		if (width <= 0 || height <= 0) {
			return;
		}

		boolean anyField = false;
		boolean anyDepthField = false;
		for (final VFXActiveEffect effect : active) {
			anyField |= !effect.getTimeline().getFields().isEmpty();
			anyDepthField |= effect.getTimeline().fieldNeedsDepth();
		}
		if (anyField) {
			final boolean valid = layer == 0 && depthRecipeVerified();
			VFXFieldEnv.capture(mainTarget, valid);
			if (!valid && anyDepthField) {
				for (final VFXActiveEffect effect : active) {
					if (effect.getTimeline().fieldNeedsDepth()) {
						VFXLog.warnOnce(LOGGER, "field:layer:" + effect.getId(),
							"Effect '{}' uses a depth/world field but runs at screen_layer {} — depth fields need layer 0 on 26.2; falling back to the neutral value",
							effect.getId(), layer);
					}
				}
			}
		}
		if (!maskEffects.isEmpty()) {
			// The prepass owns the layer-0 requirement, not the effect: layer 0 is where the scene
			// depth is intact (the depth buffer is cleared before the hand at later layers).
			VFXFieldEnv.capture(mainTarget, layer == 0 && depthRecipeVerified());
		}

		// Expand every active effect into its sequential shader passes (e.g. blur = X + Y), appending
		// one shared coverage-read consumer after any effect that declares a mask (spec §4). A
		// definition without a mask gets exactly the same chain as before — the additive contract.
		final VFXShaderPrograms.ProgramInfo maskInfo = VFXShaderPrograms.maskProgram();
		List<PassRun> chain = new ArrayList<>();
		for (VFXActiveEffect effect : active) {
			List<VFXShaderPrograms.ProgramInfo> infos = VFXShaderPrograms.getPrograms(effect.getType());
			if (infos.isEmpty()) {
				continue;
			}
			final boolean masked = effect.getMask() != null && maskInfo != null;
			for (int i = 0; i < infos.size(); i++) {
				chain.add(new PassRun(this.pass(infos.get(i)), effect, false, masked && i == 0));
			}
			if (masked) {
				chain.add(new PassRun(this.pass(maskInfo), effect, true, false));
			}
		}
		if (chain.isEmpty() && (layer != 0 || maskEffects.isEmpty())) {
			return;
		}

		this.ensureTargets(width, height);
		this.ensureMaskTargets(width, height, maskEffects);
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
			if (!chain.isEmpty()) {
				copy.execute(encoder, samplerCache, mainTarget, this.pingPong[0], null, null, null, null, null, null);
				this.pruneStopMotionSlots(active);
			}

			if (layer == 0 && !maskEffects.isEmpty()) {
				// Geometry first: a block mask's model geometry is rasterised into its scratch target,
				// which the coverage shader then samples as that leaf's coverage.
				for (final Map.Entry<Identifier, VFXActiveEffect> entry : maskEffects.entrySet()) {
					final VFXMask entryMask = entry.getValue().getMask();
					if (entryMask != null && entryMask.hasBlockLeaf()) {
						final TextureTarget geometry = this.geometryTargets.get(entry.getKey());
						if (geometry != null) {
							VFXMaskBlockGeometry.render(encoder, geometry, entryMask, entry.getValue());
						}
					}
				}
				for (final Map.Entry<Identifier, VFXActiveEffect> entry : maskEffects.entrySet()) {
					runCoveragePrepass(encoder, samplerCache, mainTarget, entry.getValue(), entry.getKey());
				}
			}
			if (chain.isEmpty()) {
				return;
			}

			RenderTarget read = this.pingPong[0];
			int pingPongIndex = 1;
			for (int i = 0; i < chain.size(); i++) {
				boolean last = i == chain.size() - 1;
				PassRun run = chain.get(i);
				if (run.captureBefore()) {
					// Preserve the pre-effect image so the consumer can blend the coverage into it.
					copy.execute(encoder, samplerCache, read, this.maskBefore, null, null, null, null, null, null);
				}
				if (run.mask()) {
					final TextureTarget coverage = this.coverageTargets.get(run.effect().getId());
					RenderTarget output = last ? mainTarget : this.pingPong[pingPongIndex];
					run.pass().execute(encoder, samplerCache, read, output, run.effect(), this.maskBefore, null, null, null, coverage);
					read = output;
					if (!last) {
						pingPongIndex = 1 - pingPongIndex;
					}
					continue;
				}
				VFXShaderPrograms.PassRole role = run.role();
				if (role == VFXShaderPrograms.PassRole.FEEDBACK_UPDATE) {
					// History update: read the live frame + the previous history, write the next.
					RenderTarget histPrev = this.historyDirty ? read : this.history[0];
					run.pass().execute(encoder, samplerCache, read, this.history[1], run.effect(), histPrev, null, null, null, null);
					this.historyDirty = false;
					// read stays the live frame for the composite pass.
				} else {
					RenderTarget output = last ? mainTarget : this.pingPong[pingPongIndex];
					if (role == VFXShaderPrograms.PassRole.STOP_MOTION) {
						float hold = this.updateStopMotionHold(run.effect(), encoder, samplerCache, copy, mainTarget);
						run.pass().execute(encoder, samplerCache, read, output, run.effect(), this.stopMotionHold, hold, null, null, null);
					} else if (role == VFXShaderPrograms.PassRole.FEEDBACK_COMPOSITE) {
						run.pass().execute(encoder, samplerCache, read, output, run.effect(), this.history[1], null, null, null, null);
						this.swapHistory();
					} else {
						run.pass().execute(encoder, samplerCache, read, output, run.effect(), null, null, mainTarget, run.fieldProgram(), null);
					}
					read = output;
					if (!last) {
						pingPongIndex = 1 - pingPongIndex;
					}
				}
			}
		} catch (Exception e) {
			VFXLog.warnOnce(LOGGER, "post:apply:" + layer, "Failed to apply VFX post-processing", e);
		} finally {
			// Rotate each pass's uniform arena once, after all of this call's writes are recorded:
			// a MappableRingBuffer must never be rotated into a slot whose fence belongs to the
			// submit currently being built (26.2 throws "Cannot wait on a fence for the current submit").
			for (final VFXPass pass : this.passes.values()) {
				pass.endFrame();
			}
			RenderSystem.restoreProjectionMatrix();
		}
	}

	/** One representative effect per distinct masked definition (they share the mask and its slots). */
	private static Map<Identifier, VFXActiveEffect> activeMaskEffects(final List<VFXActiveEffect> active) {
		final Map<Identifier, VFXActiveEffect> effects = new LinkedHashMap<>();
		for (final VFXActiveEffect effect : active) {
			if (effect.getMask() != null) {
				effects.putIfAbsent(effect.getId(), effect);
			}
		}
		return effects;
	}

	/** The distinct GLSL-plugin custom-shape ids a mask references (composed shapes need no variant). */
	private static List<String> pluginShapeIds(final VFXMask mask) {
		final List<String> ids = new ArrayList<>();
		for (final String id : mask.customShapeIds()) {
			final VFXCustomShape shape = VFXShapeRegistry.get().get(id);
			if (shape != null && shape.family() == VFXCustomShape.Family.GLSL_PLUGIN && !ids.contains(id)) {
				ids.add(id);
			}
		}
		return ids;
	}

	/**
	 * Runs the coverage prepass for one distinct mask into its target. The caller guarantees layer 0,
	 * so the camera and depth are read live from {@link VFXFieldEnv} (the verified reversed-depth
	 * reconstruction). The block-geometry scratch (when the mask has a block leaf) was cleared and is
	 * bound as the block leaf's coverage.
	 */
	private void runCoveragePrepass(final CommandEncoder encoder, final SamplerCache samplerCache, final RenderTarget mainTarget, final VFXActiveEffect effect, final Identifier definitionId) {
		final VFXMask mask = effect.getMask();
		final TextureTarget coverage = this.coverageTargets.get(definitionId);
		if (mask == null || coverage == null) {
			return;
		}
		final VFXShaderPrograms.ProgramInfo coverageInfo = VFXMaskShaderVariants.variantFor(pluginShapeIds(mask));
		if (coverageInfo == null) {
			return;
		}
		final float time = Minecraft.getInstance().level == null ? 0.0F : Minecraft.getInstance().level.getGameTime() / 20.0F;
		this.pass(coverageInfo).executeCoverage(encoder, samplerCache, mainTarget, coverage, this.geometryTargets.get(definitionId),
			effect, mask, VFXFieldEnv.invViewProj(), VFXFieldEnv.cameraX(), VFXFieldEnv.cameraY(), VFXFieldEnv.cameraZ(), time);
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
			copy.execute(encoder, samplerCache, mainTarget, this.stopMotionHold, null, null, null, null, null, null);
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
	 * {@code mask} marks the shared coverage-read consumer; {@code captureBefore} marks the first
	 * pass of a masked effect, whose input must be preserved into {@code maskBefore} for the consumer.
	 */
	private record PassRun(VFXPass pass, VFXActiveEffect effect, boolean mask, boolean captureBefore) {
		VFXShaderPrograms.PassRole role() {
			return this.pass.role();
		}

		@Nullable VFXFieldProgram fieldProgram() {
			final String input = this.pass.fieldInput();
			return input == null ? null : this.effect.getTimeline().getFieldProgram(input);
		}
	}

	/**
	 * True when the reversed-depth world reconstruction is verified for this node. Depth findings
	 * confirm it on 26.2; older nodes bind depth but report it invalid so depth/world fields fall
	 * back to their neutral value.
	 */
	private static boolean depthRecipeVerified() {
		//? if >=26.2 {
		return true;
		//?} else {
		/*return false;
		*///?}
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

	/**
	 * Creates/reuses one coverage target per distinct masked definition and one geometry scratch per
	 * block mask, plus the shared pre-effect image. Entries for definitions no longer masked this
	 * frame are released, so the maps stay bounded by the live masked set.
	 */
	private void ensureMaskTargets(final int width, final int height, final Map<Identifier, VFXActiveEffect> maskEffects) {
		this.coverageTargets.keySet().removeIf(id -> {
			if (maskEffects.containsKey(id)) {
				return false;
			}
			final TextureTarget target = this.coverageTargets.get(id);
			if (target != null) {
				target.destroyBuffers();
			}
			return true;
		});
		this.geometryTargets.keySet().removeIf(id -> {
			if (maskEffects.containsKey(id)) {
				return false;
			}
			final TextureTarget target = this.geometryTargets.get(id);
			if (target != null) {
				target.destroyBuffers();
			}
			return true;
		});
		if (maskEffects.isEmpty()) {
			return;
		}
		if (this.maskBefore == null || this.maskBefore.width != width || this.maskBefore.height != height) {
			if (this.maskBefore != null) {
				this.maskBefore.destroyBuffers();
			}
			this.maskBefore = createTarget("vfxweaver mask before", width, height, false);
		}
		for (final Map.Entry<Identifier, VFXActiveEffect> entry : maskEffects.entrySet()) {
			final TextureTarget existing = this.coverageTargets.get(entry.getKey());
			if (existing == null || existing.width != width || existing.height != height) {
				if (existing != null) {
					existing.destroyBuffers();
				}
				this.coverageTargets.put(entry.getKey(), createTarget("vfxweaver mask coverage", width, height, false));
			}
			final VFXMask mask = entry.getValue().getMask();
			if (mask != null && mask.hasBlockLeaf()) {
				final TextureTarget geometry = this.geometryTargets.get(entry.getKey());
				if (geometry == null || geometry.width != width || geometry.height != height) {
					if (geometry != null) {
						geometry.destroyBuffers();
					}
					this.geometryTargets.put(entry.getKey(), createTarget("vfxweaver mask geometry", width, height, false));
				}
			}
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
		/** One arena for every uniform block this pass binds; a slot is handed out per write. */
		private final UniformArena arena;
		private final boolean hasConfig;
		private final boolean usesDepth;
		private final @Nullable String fieldInput;
		private final boolean hasField;
		private final boolean mask;
		private final boolean coverage;
		private final Map<String, com.mojang.blaze3d.textures.GpuTextureView> textureCache = new HashMap<>();

		private VFXPass(final VFXShaderPrograms.ProgramInfo info) {
			this.pipeline = info.pipeline();
			this.configParams = info.configParams();
			this.role = info.role();
			this.hasConfig = info.configUboSize() > 0;
			this.hasField = info.usesField();
			final int payload = Math.max(SAMPLER_INFO_SIZE, Math.max(
				this.hasConfig ? info.configUboSize() : 0,
				this.hasField ? VFXShaderPrograms.FIELD_CONFIG_SIZE : 0));
			this.arena = new UniformArena(this.pipeline.getLocation() + " uniforms", payload);
			this.usesDepth = info.usesDepth();
			this.fieldInput = info.fieldInput();
			this.mask = info.mask();
			this.coverage = info.coverage();
		}

		VFXShaderPrograms.PassRole role() {
			return this.role;
		}

		@Nullable String fieldInput() {
			return this.fieldInput;
		}

		/** Rotates this pass's uniform arena once per process call, never mid-submit. */
		void endFrame() {
			this.arena.endFrame();
		}

		/** Releases this pass's arena GPU buffers. */
		void close() {
			this.arena.close();
		}

		private void execute(
			final CommandEncoder encoder,
			final SamplerCache samplerCache,
			final RenderTarget input,
			final RenderTarget output,
			final @Nullable VFXActiveEffect effect,
			final @Nullable RenderTarget history,
			final @Nullable Float hold,
			final @Nullable RenderTarget depthSource,
			final @Nullable VFXFieldProgram fieldProgram,
			final @Nullable RenderTarget coverage
		) {
			final GpuBufferSlice samplerInfo = this.arena.write(encoder, builder ->
				builder.putVec2(output.width, output.height).putVec2(input.width, input.height));

			GpuBufferSlice config = null;
			if (this.hasConfig && effect != null) {
				final float weight = effect.getWeight();
				config = this.arena.write(encoder, builder -> {
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
				});
			}

			GpuBufferSlice field = null;
			if (this.hasField && effect != null) {
				final float weight = effect.getWeight();
				final float neutral = effect.getType().fieldNeutral(this.fieldInput);
				final float raw = effect.getParam(this.fieldInput, neutral);
				final float uniform = neutral + (raw - neutral) * weight;
				final VFXFieldProgram program = fieldProgram == null ? VFXFieldProgram.empty() : fieldProgram;
				field = this.arena.write(encoder, builder ->
					program.write(new VFXFieldValueWriterAdapter(builder),
						effect.getTimeline().getGraphEvaluator(), uniform, weight,
						VFXFieldEnv.depthValid() ? 1.0F : 0.0F,
						VFXFieldEnv.invWidth(), VFXFieldEnv.invHeight(),
						VFXFieldEnv.invViewProj(), VFXFieldEnv.cameraX(), VFXFieldEnv.cameraY(), VFXFieldEnv.cameraZ()));
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
				renderPass.setUniform("SamplerInfo", samplerInfo);
				if (config != null) {
					renderPass.setUniform("Config", config);
				}
				renderPass.bindTexture("InSampler", input.getColorTextureView(), samplerCache.getClampToEdge(FilterMode.LINEAR));
				if (history != null) {
					renderPass.bindTexture("HistSampler", history.getColorTextureView(), samplerCache.getClampToEdge(FilterMode.LINEAR));
				}
				if (this.mask && coverage != null) {
					// The precomputed coverage; the consumer does no shape/depth work.
					renderPass.bindTexture("CoverageSampler", coverage.getColorTextureView(), samplerCache.getClampToEdge(FilterMode.NEAREST));
				}
				if (field != null) {
					renderPass.setUniform("FieldConfig", field);
				}
				if (this.usesDepth && depthSource != null) {
					// Depth is non-filterable: NEAREST only (depth findings).
					renderPass.bindTexture("DepthSampler", depthSource.getDepthTextureView(), samplerCache.getClampToEdge(FilterMode.NEAREST));
				}
				if (field != null) {
					final String texture = fieldProgram == null ? null : fieldProgram.texture();
					renderPass.bindTexture("fld_tex0", texture == null ? input.getColorTextureView() : resolveTexture(texture), samplerCache.getClampToEdge(FilterMode.LINEAR));
				}
				//? if <26.2 {
				renderPass.draw(0, 3);
				//?} else {
				/*renderPass.draw(3, 1, 0, 0);
				*///?}
			}
		}

		/**
		 * Runs the coverage prepass: writes the mask Config via {@link VFXMaskUniforms}, binds the
		 * scene depth and the geometry scratch, and draws one fullscreen triangle into the coverage
		 * target. Called only by the manager at layer 0, so the main target's depth is intact.
		 */
		private void executeCoverage(
			final CommandEncoder encoder,
			final SamplerCache samplerCache,
			final RenderTarget mainTarget,
			final RenderTarget coverageTarget,
			final @Nullable RenderTarget geometry,
			final VFXActiveEffect effect,
			final VFXMask mask,
			final Matrix4fc invViewProj,
			final float camX,
			final float camY,
			final float camZ,
			final float time
		) {
			final GpuBufferSlice samplerInfo = this.arena.write(encoder, builder ->
				builder.putVec2(coverageTarget.width, coverageTarget.height).putVec2(mainTarget.width, mainTarget.height));
			GpuBufferSlice config = null;
			if (this.hasConfig) {
				config = this.arena.write(encoder, builder ->
					VFXMaskUniforms.writeCoverage(builder, effect, mask, invViewProj, camX, camY, camZ, time));
			}
			try (RenderPass renderPass = encoder.createRenderPass(
					() -> "VFX coverage " + this.pipeline.getLocation(),
					coverageTarget.getColorTextureView(),
					//? if <26.2 {
					OptionalInt.empty()
					//?} else {
					/*Optional.empty()
					*///?}
				)) {
				renderPass.setPipeline(this.pipeline);
				RenderSystem.bindDefaultUniforms(renderPass);
				renderPass.setUniform("SamplerInfo", samplerInfo);
				if (config != null) {
					renderPass.setUniform("Config", config);
				}
				renderPass.bindTexture("DepthSampler", mainTarget.getDepthTextureView(), samplerCache.getClampToEdge(FilterMode.NEAREST));
				// A block leaf samples the geometry scratch; a harmless placeholder bind when the mask
				// has no block leaf (the shader never reads it then).
				renderPass.bindTexture("GeometryCoverageSampler",
					geometry != null ? geometry.getColorTextureView() : coverageTarget.getColorTextureView(),
					samplerCache.getClampToEdge(FilterMode.NEAREST));
				//? if <26.2 {
				renderPass.draw(0, 3);
				//?} else {
				/*renderPass.draw(3, 1, 0, 0);
				*///?}
			}
		}

		/**
		 * Resolves a field texture to its view. Cached per pipeline; a datapack that swaps the
		 * texture id at runtime re-resolves once. ponytail: unbounded cache, capped in practice by
		 * the definition count (bounded by the datapack caps).
		 */
		private com.mojang.blaze3d.textures.GpuTextureView resolveTexture(final String id) {
			return this.textureCache.computeIfAbsent(id, key ->
				Minecraft.getInstance().getTextureManager().getTexture(Identifier.parse(key)).getTextureView());
		}
	}

	/**
	 * One pass's uniform storage for a frame. A single {@link MappableRingBuffer} is acquired on the
	 * first write of a {@code process} call and then handed out as aligned slices, so every write in
	 * that call reuses one buffer and the ring is rotated once, at the end of the call. This keeps
	 * the number of fence waits per frame bounded (one ring rotation per pass per process call) and
	 * independent of the number of masked definitions, instead of rotating a shared buffer per pass
	 * execution — which on 26.2 waits on a fence of the submit currently being built and throws
	 * {@code IllegalStateException: Cannot wait on a fence for the current submit}. The arena grows
	 * (retiring the old ring until the next rotation) only when a single call writes more slots than
	 * the current capacity.
	 */
	private static final class UniformArena {
		private final String label;
		private final int blockSize;
		private final List<MappableRingBuffer> retired = new ArrayList<>();
		private MappableRingBuffer ring;
		private int capacity;
		private int nextBlock;
		private boolean used;

		private UniformArena(final String label, final int payloadSize) {
			this.label = label;
			this.blockSize = Math.max(ARENA_BLOCK_ALIGNMENT,
				((payloadSize + ARENA_BLOCK_ALIGNMENT - 1) / ARENA_BLOCK_ALIGNMENT) * ARENA_BLOCK_ALIGNMENT);
			this.capacity = ARENA_INITIAL_CAPACITY;
			this.ring = new MappableRingBuffer(() -> this.label, UBO_USAGE, this.blockSize * this.capacity);
		}

		/** Writes one std140 payload into the next aligned slot and returns its slice. */
		private GpuBufferSlice write(final CommandEncoder encoder, final Consumer<Std140Builder> writer) {
			if (this.nextBlock >= this.capacity) {
				this.grow(this.capacity * 2);
			}
			final GpuBufferSlice slice = this.ring.currentBuffer().slice((long) this.nextBlock * this.blockSize, this.blockSize);
			this.nextBlock++;
			//? if <26.2 {
			try (GpuBuffer.MappedView view = encoder.mapBuffer(slice, false, true)) {
				writer.accept(Std140Builder.intoBuffer(view.data()));
			}
			//?} else {
			/*try (GpuBufferSlice.MappedView view = slice.map(false, true)) {
				writer.accept(Std140Builder.intoBuffer(view.data()));
			}
			*///?}
			this.used = true;
			return slice;
		}

		/** Rotates the ring after the call's writes, unless nothing was written. */
		private void endFrame() {
			if (!this.used) {
				return;
			}
			this.used = false;
			this.nextBlock = 0;
			this.ring.rotate();
			for (final MappableRingBuffer old : this.retired) {
				old.close();
			}
			this.retired.clear();
		}

		private void close() {
			this.ring.close();
			for (final MappableRingBuffer old : this.retired) {
				old.close();
			}
			this.retired.clear();
		}

		private void grow(final int newCapacity) {
			this.retired.add(this.ring);
			this.capacity = newCapacity;
			this.nextBlock = 0;
			this.ring = new MappableRingBuffer(() -> this.label, UBO_USAGE, this.blockSize * this.capacity);
		}
	}

	private static final class VFXPostProcessingManagerHolder {
		private static final VFXPostProcessingManager INSTANCE = new VFXPostProcessingManager();
	}
}

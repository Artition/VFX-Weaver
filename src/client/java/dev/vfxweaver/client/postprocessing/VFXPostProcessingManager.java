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
//? if >=26.1 {
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
//?}
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.SamplerCache;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.effect.VFXActiveEffect;
import dev.vfxweaver.effect.VFXDefinition;
import dev.vfxweaver.effect.VFXEffectType;
import dev.vfxweaver.effect.VFXWorldBindings;
import dev.vfxweaver.field.VFXFieldProgram;
import dev.vfxweaver.field.VFXShape;
import dev.vfxweaver.field.VFXSurfaceSelection;
import dev.vfxweaver.field.VFXTexture;
import dev.vfxweaver.mask.VFXCustomShape;
import dev.vfxweaver.mask.VFXMask;
import dev.vfxweaver.mask.VFXShapeRegistry;
import dev.vfxweaver.resource.VFXDefinitionManager;
import dev.vfxweaver.util.VFXLog;
import dev.vfxweaver.util.VFXReloadSafeCache;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
//? if >=26.1 {
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
//?}
//? if >=26.2 {
/*import java.util.Optional;
*///?}
import java.util.OptionalInt;
import java.util.function.Consumer;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
//? if <26.1 {
/*import net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer;
*///?} else {
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
//?}
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
//? if >=26.1 {
import org.lwjgl.opengl.GL31;
//?}
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
		// The scene depth buffer is only intact at screen layer 0 (depth findings), so
		// surface_pattern defaults there. Depth is also unusable when the main target has no depth
		// attachment or the camera snapshot is missing (e.g. the first frame after load): the pass
		// is then replaced by a passthrough below instead of reading garbage.
		List<VFXActiveEffect> active = new ArrayList<>();
		for (VFXActiveEffect effect : effects.getActivePostEffects()) {
			float defaultLayer = effect.getType() == VFXEffectType.SURFACE_PATTERN ? 0.0F : 1.0F;
			if (Math.round(Mth.clamp(effect.getParam("screen_layer", defaultLayer), 0.0F, 2.0F)) == layer) {
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
		boolean anyDepthPass = false;
		for (final VFXActiveEffect effect : active) {
			anyField |= !effect.getTimeline().getFields().isEmpty();
			anyDepthField |= effect.getTimeline().fieldNeedsDepth();
			for (final VFXShaderPrograms.ProgramInfo info : VFXShaderPrograms.getPrograms(effect.getType())) {
				anyDepthPass |= info.depthConfig();
			}
		}
		if (anyField || anyDepthPass) {
			final boolean valid = layer == 0 && depthRecipeVerified();
			VFXFieldEnv.capture(mainTarget, valid);
			if (!valid && anyDepthField) {
				for (final VFXActiveEffect effect : active) {
					if (effect.getTimeline().fieldNeedsDepth()) {
						VFXLog.warnOnce(LOGGER, "field:layer:" + effect.getId(),
							"Effect '{}' uses a depth/world field but runs at screen_layer {} — depth fields need layer 0 (the scene depth is intact only below the hand); falling back to the neutral value",
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
		final boolean depthReady = VFXFieldEnv.depthValid() && mainTarget.getDepthTextureView() != null;
		List<PassRun> chain = new ArrayList<>();
		for (VFXActiveEffect effect : active) {
			List<VFXShaderPrograms.ProgramInfo> infos = VFXShaderPrograms.getPrograms(effect.getType());
			if (infos.isEmpty()) {
				continue;
			}
			final boolean masked = effect.getMask() != null && maskInfo != null;
			for (int i = 0; i < infos.size(); i++) {
				final VFXShaderPrograms.ProgramInfo info = infos.get(i);
				if (info.depthConfig() && !depthReady) {
					VFXLog.warnOnce(LOGGER, "surface_pattern:nodepth:" + effect.getId(),
						"Effect '{}' needs scene depth but it is unavailable (main target depth missing or camera not ready); rendering a passthrough", effect.getId());
					chain.add(new PassRun(this.copyPass(), effect, false, masked && i == 0));
					continue;
				}
				chain.add(new PassRun(this.pass(info), effect, false, masked && i == 0));
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
							VFXMaskBlockGeometry.render(encoder, geometry, mainTarget, entryMask, entry.getValue(), depthReady);
						}
					}
				}
				for (final Map.Entry<Identifier, VFXActiveEffect> entry : maskEffects.entrySet()) {
					runCoveragePrepass(encoder, samplerCache, mainTarget, entry.getValue(), entry.getKey(), depthReady);
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

	/**
	 * One representative effect per distinct masked definition (they share the mask and its slots).
	 *
	 * <p>Known limitation: two concurrent plays of one masked definition share a single coverage
	 * target, so both are evaluated with the first play's animated centre/radius/softness. Per-instance
	 * coverage would require one prepass and one target per instance and a per-instance consumer
	 * lookup, which the pipeline does not do; a definition whose mask must differ per play should be
	 * played as two distinct definitions.
	 */
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
	 * so the camera and depth are read live from {@link VFXFieldEnv} (the per-node depth
	 * reconstruction, see {@code include/camera.glsl}). The block-geometry scratch (when the mask has
	 * a block leaf) was cleared and is bound as the block leaf's coverage.
	 *
	 * <p>{@code depthReady} mirrors the {@code surface_pattern} depth gate: when it is false (depth
	 * unavailable, or no depth attachment), a mask that needs depth fails closed - its coverage is
	 * cleared to zero and nothing is sampled.
	 */
	private void runCoveragePrepass(final CommandEncoder encoder, final SamplerCache samplerCache, final RenderTarget mainTarget, final VFXActiveEffect effect, final Identifier definitionId, final boolean depthReady) {
		final VFXMask mask = effect.getMask();
		final TextureTarget coverage = this.coverageTargets.get(definitionId);
		if (mask == null || coverage == null) {
			return;
		}
		if (mask.needsDepth() && !depthReady) {
			clearTarget(coverage);
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

	/** Clears a colour target to zero through a one-off encoder (used to fail a coverage prepass closed). */
	private static void clearTarget(final TextureTarget target) {
		final CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		//? if <26.2 {
		encoder.clearColorTexture(target.getColorTexture(), 0);
		//?} else {
		/*encoder.clearColorTexture(target.getColorTexture(), new org.joml.Vector4f(0.0F, 0.0F, 0.0F, 0.0F));
		*///?}
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
	 * True when the scene-depth world reconstruction is trusted for this node. Every supported node
	 * now converts the raw depth with its own convention (see
	 * {@link VFXShaderPrograms#DEPTH_REVERSED} and {@code include/camera.glsl}): 26.2 is reversed,
	 * 26.1.2 and 1.21.11 are standard, so the recipe is verified on all of them. Only camera
	 * readiness and the presence of a depth attachment gate a pass at runtime
	 * ({@code VFXFieldEnv.depthValid()} / {@code mainTarget.getDepthTextureView()}); a node that
	 * could not support depth would return false here and fail the depth-needing paths closed.
	 */
	private static boolean depthRecipeVerified() {
		return true;
	}

	//? if >=26.1 {
	/** Pipelines whose std140 {@code Config} layout was already verified against the driver. */
	private static final Set<String> VERIFIED_DEPTH_CONFIGS = ConcurrentHashMap.newKeySet();
	/** Pipelines whose std140 {@code Config} layout failed verification (never retried per frame). */
	private static final Set<String> FAILED_DEPTH_CONFIGS = ConcurrentHashMap.newKeySet();

	/**
	 * Verifies, once per program link, that the driver's real std140 layout of the depth pass's
	 * {@code Config} block matches the positional contract: {@code inv_view_proj} at offset 0
	 * (64 bytes), {@code names[i]} at {@code 64 + 4*i}, and the block size
	 * {@code 64 + align16(4 * names.length)}. This is the guard that makes a drifted tail fail
	 * loudly instead of reading as zeros; it would have caught a short range, a stale size or a
	 * reordered/renamed field.
	 *
	 * <p>An infrastructure miss (a non-GL backend, a shader that has not compiled yet, an LWJGL
	 * failure) only warns once — the game must not die because a debug query was unavailable. An
	 * actual layout mismatch logs at ERROR and throws; the caller's {@code process} catch reports
	 * it once. The set keeps this to one query per pipeline per process, never per frame.
	 *
	 * @param pipeline the compiled depth pass pipeline
	 * @param names    the registered {@code Config} float names, in positional order
	 */
	private static void verifyDepthConfigLayout(final RenderPipeline pipeline, final String[] names) {
		final String key = pipeline.getLocation().toString();
		if (VERIFIED_DEPTH_CONFIGS.contains(key) || FAILED_DEPTH_CONFIGS.contains(key)) {
			return;
		}
		try {
			final CompiledRenderPipeline compiled = RenderSystem.getDevice().precompilePipeline(pipeline);
			if (!(compiled instanceof GlRenderPipeline gl) || !gl.isValid()) {
				return;
			}
			final GlProgram program = gl.program();
			if (program == null) {
				return;
			}
			final int programId = program.getProgramId();
			final int blockIndex = GL31.glGetUniformBlockIndex(programId, "Config");
			if (blockIndex < 0) {
				throw new IllegalStateException("the 'Config' uniform block is not active");
			}
			final int[] blockSize = new int[1];
			GL31.glGetActiveUniformBlockiv(programId, blockIndex, GL31.GL_UNIFORM_BLOCK_DATA_SIZE, blockSize);
			final CharSequence[] qualified = new CharSequence[names.length];
			for (int i = 0; i < names.length; i++) {
				qualified[i] = "Config." + names[i];
			}
			final int[] indices = new int[names.length];
			final int[] offsets = new int[names.length];
			try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
				final java.nio.IntBuffer buffer = stack.mallocInt(names.length);
				GL31.glGetUniformIndices(programId, qualified, buffer);
				buffer.get(indices);
				GL31.glGetActiveUniformsiv(programId, indices, GL31.GL_UNIFORM_OFFSET, offsets);
			}
			for (int i = 0; i < names.length; i++) {
				if (indices[i] < 0) {
					throw new IllegalStateException("the '" + qualified[i] + "' uniform is not active");
				}
				final int expected = 64 + 4 * i;
				if (offsets[i] != expected) {
					throw new IllegalStateException("std140 offset drift: '" + names[i] + "' is at " + offsets[i] + ", expected " + expected);
				}
			}
			final int expectedSize = VFXShaderPrograms.depthConfigSize(names.length);
			final int usedBytes = 64 + 4 * names.length;
			// The bound range (the arena slot, an over-aligned multiple of expectedSize) must cover
			// the driver's block; the block must in turn cover every field. A driver that reports the
			// un-rounded 244 rather than 256 is fine — the bound range is longer either way.
			if (blockSize[0] > expectedSize) {
				throw new IllegalStateException("std140 block size drift: driver reports " + blockSize[0] + " bytes, the bound range is " + expectedSize);
			}
			if (blockSize[0] < usedBytes) {
				throw new IllegalStateException("std140 block size drift: driver reports " + blockSize[0] + " bytes, the fields need " + usedBytes);
			}
			VERIFIED_DEPTH_CONFIGS.add(key);
		} catch (final IllegalStateException e) {
			FAILED_DEPTH_CONFIGS.add(key);
			LOGGER.error("VFX surface_pattern Config layout guard failed for {}: {}", key, e.getMessage());
			throw e;
		} catch (final RuntimeException e) {
			// No GL backend / shader not compiled yet / LWJGL failure: never take the game down over
			// an unavailable debug query; the positional contract check still runs in the build.
			VFXLog.warnOnce(LOGGER, "depth-config-guard:" + key,
				"Could not verify the std140 layout of {} ({}); the positional check in scripts/ still applies", key, e.getMessage());
		}
	}
	//?}

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
				// A freshly created (or resized) target holds undefined GPU memory; a masked effect
				// that starts after its layer-0 prepass would sample garbage for one frame, so clear
				// it. Fail-closed: a coverage that never gets a prepass contributes nothing.
				final TextureTarget created = createTarget("vfxweaver mask coverage", width, height, false);
				clearTarget(created);
				this.coverageTargets.put(entry.getKey(), created);
			}
			final VFXMask mask = entry.getValue().getMask();
			if (mask != null && mask.hasBlockLeaf()) {
				final TextureTarget geometry = this.geometryTargets.get(entry.getKey());
				if (geometry == null || geometry.width != width || geometry.height != height) {
					if (geometry != null) {
						geometry.destroyBuffers();
					}
					final TextureTarget created = createTarget("vfxweaver mask geometry", width, height, false);
					clearTarget(created);
					this.geometryTargets.put(entry.getKey(), created);
				}
			}
		}
	}

	/**
	 * Creates a colour render target; 26.2 requires an explicit GPU format.
	 *
	 * <p>The coverage/geometry data is single-channel, but a render target's format must match the
	 * pipeline's {@code ColorTargetState}: the shared coverage/block-geometry pipelines declare
	 * RGBA8 (and the 26.1 line exposes no per-target format), so a single-channel target would need
	 * a per-node pipeline change. These stay RGBA8; the unused channels are simply not sampled.
	 */
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
		/** True when this pass's {@code Config} starts with {@code mat4 inv_view_proj} and it binds {@code DepthSampler}. */
		private final boolean depthConfig;
		/** The depth pass's resolved anchor (the shape centre / effect world position / player), reused every frame. */
		private final Vector3f scratchAnchor = new Vector3f();
		/**
		 * The field/pattern texture views, keyed by resource id. The loader is cached, never the
		 * view: a resource reload closes and recreates the texture's view, so a cached view would
		 * dangle after {@code /reload} (see {@link VFXReloadSafeCache}).
		 */
		private final VFXReloadSafeCache<com.mojang.blaze3d.textures.GpuTextureView> textureViews = new VFXReloadSafeCache<>();

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
			// The bound range is the arena slot, so it must cover the whole Config block; the
			// payload above already includes it, this asserts the two cannot drift apart.
			if (this.hasConfig && this.arena.blockSize() < info.configUboSize()) {
				throw new IllegalStateException("uniform arena slot " + this.arena.blockSize()
					+ " is shorter than the Config block " + info.configUboSize() + " for " + this.pipeline.getLocation());
			}
			this.usesDepth = info.usesDepth();
			this.fieldInput = info.fieldInput();
			this.mask = info.mask();
			this.depthConfig = info.depthConfig();
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
			//? if >=26.1 {
			if (this.depthConfig) {
				// Once per program link: assert the driver's real std140 offsets match the positional
				// contract, so a drifted tail fails loudly rather than reading as zeros.
				verifyDepthConfigLayout(this.pipeline, this.configParams);
			}
			//?}
			final GpuBufferSlice samplerInfo = this.arena.write(encoder, builder ->
				builder.putVec2(output.width, output.height).putVec2(input.width, input.height));

			// The structural shape/surface/texture describe the world anchor and the figure; the
			// shared VFXShape owns them and the reserved Config names carry them to the shader.
			// One definition lookup per execute (was three: shape, surface and the anchor's
			// pos_x/y/z probe) is enough for the whole pass. Resolved before the uniform lambda so
			// the lambda can capture them as final locals.
			final VFXDefinition definition = this.depthConfig && effect != null
				? VFXDefinitionManager.get().get(effect.getId())
				: null;
			final VFXShape shape = definition == null ? null : definition.getPattern();
			final VFXSurfaceSelection surface = definition == null ? null : definition.getSurface();
			final PatternTexture patternTexture = this.depthConfig && effect != null
				? resolvePatternTexture(shape == null ? null : shape.texture(), effect.getId())
				: PatternTexture.ABSENT;
			GpuBufferSlice config = null;
			if (this.hasConfig && effect != null) {
				final float weight = effect.getWeight();
				if (this.depthConfig) {
					this.resolveAnchor(effect, shape, definition);
				}
				config = this.arena.write(encoder, builder -> {
					if (this.depthConfig) {
						// spec §5 / depth findings: viewRotProj has no translation; the matrix was
						// already built translate(-camPos) then inverted, so write it as-is.
						builder.putMat4f(VFXFieldEnv.invViewProj());
						// One full-range write per frame, in registered-name order, sized by the name
						// list: resolveDepthConfig writes every name or throws, so the tail can never
						// silently upload as zero.
						for (final float value : this.resolveDepthConfig(effect, weight, shape, surface, patternTexture)) {
							builder.putFloat(value);
						}
						return;
					}
					for (final String param : this.configParams) {
						// Reserved parameters never come from the timeline: "time"/"hold" are the
						// existing reserved names; a non-depth pass has no structural block.
						final float raw;
						final boolean reserved = "time".equals(param) || "hold".equals(param);
						if ("time".equals(param)) {
							raw = effect.getAge();
						} else if ("hold".equals(param)) {
							raw = hold != null ? hold : 0.0F;
						} else {
							raw = effect.getParam(param, 0.0F);
						}
						final float neutral = reserved ? Float.NaN : effect.getType().neutralValue(param);
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
				if (this.usesDepth) {
					// The pattern texture; a placeholder bind when the effect has no texture, so the
					// pipeline's PatternSampler layout is always satisfied. NEAREST + no mipmaps
					// (SamplerCache.getClampToEdge(filter) clamps maxLod to 0), the same filtering
					// vanilla uses for the block atlas: a pixel texture projected over `tile_scale`
					// blocks must stay crisp, not bilinear-blur. The field texture (fld_tex0) keeps
					// LINEAR — it is a screen-space field, not a pixel figure.
					final com.mojang.blaze3d.textures.GpuTextureView patternView = patternTexture.view();
					renderPass.bindTexture("PatternSampler",
						patternView == null ? input.getColorTextureView() : patternView,
						samplerCache.getClampToEdge(FilterMode.NEAREST));
				}
				if (field != null) {
					final String texture = fieldProgram == null ? null : fieldProgram.texture();
					final com.mojang.blaze3d.textures.GpuTextureView textureView = texture == null ? null : resolveTexture(texture);
					renderPass.bindTexture("fld_tex0", textureView == null ? input.getColorTextureView() : textureView, samplerCache.getClampToEdge(FilterMode.LINEAR));
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
				// A depth-reconstructed (world/aura or block-occluded) mask is never run without a
				// trusted depth; a screen-only mask never samples DepthSampler, so the placeholder
				// is inert there and only keeps the sampler binding valid. The placeholder must not
				// be the coverage target this pass writes to - binding the render target's own
				// colour texture as a sampler is a feedback loop (undefined on some drivers). Bind
				// the main target's depth view when present (the shader does not read it for a
				// screen mask), else its colour view.
				final GpuTextureView depthBind = mainTarget.getDepthTextureView() != null
					? mainTarget.getDepthTextureView()
					: mainTarget.getColorTextureView();
				renderPass.bindTexture("DepthSampler", depthBind,
					samplerCache.getClampToEdge(FilterMode.NEAREST));
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
		 * Resolves a standalone field texture to its current view. The descriptor lookup is cached,
		 * the view is re-derived on every call: a resource reload closes and nulls the old view, so
		 * caching the view itself left a dangling handle (the {@code /reload} bug). A datapack that
		 * swaps the texture id at runtime resolves a new key.
		 */
		private com.mojang.blaze3d.textures.@Nullable GpuTextureView resolveTexture(final String id) {
			return this.textureViews.get(id, () -> {
				final Identifier textureId = Identifier.tryParse(id);
				if (textureId == null) {
					// A malformed datapack id must degrade only this effect's field pass (a null view
					// makes the shader read its neutral value), never throw into the layer-wide catch
					// and disable every post effect for the frame.
					VFXLog.warnOnce(LOGGER, "field:texture:" + id, "Ignoring VFX field texture '{}': not a valid resource id", id);
					return null;
				}
				return Minecraft.getInstance().getTextureManager().getTexture(textureId).getTextureView();
			});
		}

		/**
		 * A resolved pattern texture: the sampler's view plus the values the shader needs. Flags:
		 * {@code 1} = texture authored, {@code 2} = resolved, {@code 4} = preserve aspect.
		 * {@link #ABSENT} is the no-texture (legacy) case; an authored texture that failed to
		 * resolve is authored but not resolved, so the shader draws nothing (fail-closed) instead
		 * of falling through to the procedural figure.
		 */
		private record PatternTexture(
			@Nullable GpuTextureView view,
			float u0,
			float v0,
			float u1,
			float v1,
			float aspect,
			float flags,
			float cols,
			float rows,
			float channel,
			float pxW,
			float pxH
		) {
			/** No texture authored: the shader takes the legacy procedural-figure path. */
			static final PatternTexture ABSENT = new PatternTexture(null, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, 0.0F, 1.0F, 1.0F, 3.0F, 1.0F, 1.0F);
			static final int AUTHORED = 1;
			static final int RESOLVED = 2;
			static final int PRESERVE = 4;
		}

		/**
		 * Resolves a {@code pattern.texture} spec to the values the shader needs: the sampler's
		 * texture view, the sprite UV rect (an atlas sub-rect, or {@code 0..1} for standalone), the
		 * pixel aspect, the sheet grid and the channel. The atlas API exists only on {@code >=26.1}
		 * (the pass itself is only registered there), so the whole body is guarded.
		 *
		 * <p>A missing sprite inside a valid atlas is fail-visible (draws the missing texture) and
		 * warned once per effect; an unknown atlas or an unreadable standalone texture is
		 * fail-closed (nothing) and warned once. Re-resolved every frame, so a resource reload's
		 * re-stitch — which can move a sprite and recreate the view — is picked up.
		 */
		private static PatternTexture resolvePatternTexture(final @Nullable VFXTexture spec, final Identifier effectId) {
			if (spec == null) {
				return PatternTexture.ABSENT;
			}
			final int flagBits = PatternTexture.AUTHORED | (spec.preserveAspect() ? PatternTexture.PRESERVE : 0);
			//? if >=26.1 {
			try {
				// Parsed once at definition-parse time (VFXTexture.parsedId), never re-parsed here.
				final net.minecraft.resources.Identifier textureId = spec.parsedId();
				final net.minecraft.client.resources.model.sprite.AtlasManager atlasManager =
					Minecraft.getInstance().getAtlasManager();
				if (spec.source() == VFXTexture.Source.STANDALONE) {
					// Standalone resolution mirrors the field-texture path (TextureManager.getTexture
					// returns a SimpleTexture, creating it on demand, and logs "Missing resource" once
					// when the file is absent). On 26.x a texture id carries its extension — vanilla
					// blits `textures/gui/title/minecraft.png` — and TextureManager hands the id
					// straight to ResourceManager.getResourceOrThrow, so the authored
					// `…/textures/…` form (no extension) is completed with `.png` here. Without this
					// the resource manager looked for the extensionless path and every standalone
					// pattern failed closed. No view means the loader was unreachable, so it fails closed.
					final net.minecraft.resources.Identifier standaloneId = withPng(textureId);
					final net.minecraft.client.renderer.texture.AbstractTexture texture =
						Minecraft.getInstance().getTextureManager().getTexture(standaloneId);
					final com.mojang.blaze3d.textures.GpuTextureView view = texture.getTextureView();
					if (view == null) {
						VFXLog.warnOnce(LOGGER, "surface_pattern:texture:" + effectId,
							"surface_pattern '{}': texture '{}' did not upload a GPU view; drawing nothing", effectId, spec.id());
						return new PatternTexture(null, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, (float) flagBits, spec.sheetCols(), spec.sheetRows(), spec.channel().code(), 1.0F, 1.0F);
					}
					final com.mojang.blaze3d.textures.GpuTexture gpu = texture.getTexture();
					final float pxW = Math.max(1, gpu == null ? 1 : gpu.getWidth(0));
					final float pxH = Math.max(1, gpu == null ? 1 : gpu.getHeight(0));
					// A standalone texture has no atlas aspect: its pixel aspect is its width/height
					// (the sheet cell aspect is then tex_aspect * rows / cols in the shader). The old
					// hardcoded 1.0 made `preserve` a no-op for a non-square pack texture.
					final float aspect = pxW / pxH;
					return new PatternTexture(view, 0.0F, 0.0F, 1.0F, 1.0F, aspect,
						(float) (flagBits | PatternTexture.RESOLVED), spec.sheetCols(), spec.sheetRows(), spec.channel().code(), pxW, pxH);
				}
				// The 26.2 AtlasManager keeps two maps: `atlasById`, keyed by the atlas
				// *definition* id (AtlasIds.BLOCKS = minecraft:blocks, AtlasIds.ITEMS = minecraft:items)
				// which serves getAtlasOrThrow, and `atlasByTexture`, keyed by the atlas *texture* id
				// (TextureAtlas.LOCATION_BLOCKS = minecraft:textures/atlas/blocks.png). The sprite
				// lookup (`get(SpriteId)`) is keyed by the texture id too: `spriteLookup` is populated
				// with `new SpriteId(config.textureId, spriteId)`. So resolve the atlas by its
				// definition id, then take the texture id from TextureAtlas.location() for the
				// SpriteId. Passing the definition id to the SpriteId misses the lookup and throws
				// "Invalid atlas texture id: minecraft:blocks" (the previous bug).
				final net.minecraft.resources.Identifier atlasDefinition = switch (spec.source()) {
					case BLOCK -> net.minecraft.data.AtlasIds.BLOCKS;
					case ITEM -> net.minecraft.data.AtlasIds.ITEMS;
					default -> spec.parsedAtlasId();
				};
				final net.minecraft.client.renderer.texture.TextureAtlas atlas =
					atlasManager.getAtlasOrThrow(atlasDefinition);
				final net.minecraft.resources.Identifier atlasTextureId = atlas.location();
				final net.minecraft.client.renderer.texture.TextureAtlasSprite sprite;
				if (spec.source() == VFXTexture.Source.ATLAS) {
					// An explicit atlas + id: the id is the sprite id in that atlas, used verbatim.
					sprite = atlasManager.get(new net.minecraft.client.resources.model.sprite.SpriteId(
						atlasTextureId, textureId));
				} else {
					sprite = findSprite(atlasManager, atlasTextureId, textureId);
				}
				if (sprite == null) {
					VFXLog.warnOnce(LOGGER, "surface_pattern:texture:" + effectId,
						"surface_pattern '{}': sprite '{}' is missing from atlas '{}'; drawing nothing", effectId, spec.id(), atlasDefinition);
					return new PatternTexture(null, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, (float) flagBits, spec.sheetCols(), spec.sheetRows(), spec.channel().code(), 1.0F, 1.0F);
				}
				if (isMissingSprite(sprite)) {
					VFXLog.warnOnce(LOGGER, "surface_pattern:texture:" + effectId,
						"surface_pattern '{}': sprite '{}' is missing from atlas '{}'; drawing the missing texture", effectId, spec.id(), atlasDefinition);
				}
				final int spriteWidth = sprite.contents().width();
				final int spriteHeight = sprite.contents().height();
				final float aspect = spriteHeight > 0 ? spriteWidth / (float) spriteHeight : 1.0F;
				return new PatternTexture(atlas.getTextureView(), sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(),
					aspect, (float) (flagBits | PatternTexture.RESOLVED), spec.sheetCols(), spec.sheetRows(), spec.channel().code(),
					Math.max(1, spriteWidth), Math.max(1, spriteHeight));
			} catch (RuntimeException e) {
				VFXLog.warnOnce(LOGGER, "surface_pattern:texture:" + effectId,
					"surface_pattern '{}': texture '{}' could not be resolved ({}); drawing nothing", effectId, spec.id(), e.getMessage());
				return new PatternTexture(null, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, (float) flagBits, spec.sheetCols(), spec.sheetRows(), spec.channel().code(), 1.0F, 1.0F);
			}
			//?} else {
			/*return new PatternTexture(null, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F, (float) flagBits, spec.sheetCols(), spec.sheetRows(), spec.channel().code(), 1.0F, 1.0F);*/
			//?}
		}

		//? if >=26.1 {
		/**
		 * Finds a block/item sprite by its texture id. The vanilla id is a texture path
		 * ({@code minecraft:block/stone}), while a block's sprite in the sprite lookup is keyed by
		 * the blockstate id ({@code minecraft:stone}); both spellings are probed (a miss in the
		 * sprite lookup is a map lookup and returns the atlas's missing sprite, which is not a
		 * real match). When no spelling matches, the atlas's missing sprite is returned so the
		 * caller fails <b>visible</b> (spec §8: an unknown sprite inside a valid atlas draws the
		 * vanilla missing texture and warns once), never fails closed.
		 *
		 * @param atlasManager the client atlas manager
		 * @param atlasTextureId the atlas *texture* id the sprite lookup is keyed by
		 *        ({@code minecraft:textures/atlas/blocks.png}, from {@code TextureAtlas.location()})
		 * @param textureId the texture id from the definition ({@code minecraft:block/x})
		 * @return the matching sprite, else the atlas's missing sprite, else {@code null} when the
		 *         atlas lookup itself returns nothing
		 */
		private static net.minecraft.client.renderer.texture.TextureAtlasSprite findSprite(
			final net.minecraft.client.resources.model.sprite.AtlasManager atlasManager,
			final net.minecraft.resources.Identifier atlasTextureId,
			final net.minecraft.resources.Identifier textureId
		) {
			final net.minecraft.resources.Identifier stripped = stripPrefix(textureId, "block/", "item/");
			// Probe the two spellings without allocating a candidate list (this runs per frame).
			net.minecraft.client.renderer.texture.TextureAtlasSprite missing = null;
			for (int i = 0; i < 2; i++) {
				final net.minecraft.resources.Identifier candidate = i == 0 ? textureId : stripped;
				if (candidate == null) {
					continue;
				}
				final net.minecraft.client.resources.model.sprite.SpriteId key =
					new net.minecraft.client.resources.model.sprite.SpriteId(atlasTextureId, candidate);
				final net.minecraft.client.renderer.texture.TextureAtlasSprite sprite = atlasManager.get(key);
				if (sprite == null) {
					continue;
				}
				if (!isMissingSprite(sprite)) {
					return sprite;
				}
				missing = sprite;
			}
			return missing;
		}

		/** Strips a leading {@code block/} or {@code item/} path prefix from an id, or {@code null} if absent. */
		private static net.minecraft.resources.Identifier stripPrefix(
			final net.minecraft.resources.Identifier id, final String first, final String second
		) {
			final String path = id.getPath();
			final String trimmed;
			if (path.startsWith(first)) {
				trimmed = path.substring(first.length());
			} else if (path.startsWith(second)) {
				trimmed = path.substring(second.length());
			} else {
				return null;
			}
			return net.minecraft.resources.Identifier.fromNamespaceAndPath(id.getNamespace(), trimmed);
		}

		/**
		 * True when the sprite is the atlas's generated missing sprite ({@code minecraft:missingno},
		 * what {@code TextureAtlas.getSprite} substitutes for an unknown name). The sprite's own
		 * {@code isMissing} accessor only exists from a later line, so the shared missing location
		 * is compared instead — the same recipe on 26.1.2 and 26.2.
		 */
		private static boolean isMissingSprite(final net.minecraft.client.renderer.texture.TextureAtlasSprite sprite) {
			return sprite.contents().name().equals(net.minecraft.client.renderer.texture.MissingTextureAtlasSprite.getLocation());
		}

		/**
		 * Returns the texture id with a {@code .png} suffix unless it already has one. On 26.x a
		 * texture id is the full resource path — vanilla blits {@code textures/gui/title/minecraft.png}
		 * — and {@code TextureManager.getTexture} passes the id straight to the resource manager, so
		 * the authored {@code …/textures/…} form (no extension) needs the suffix to resolve.
		 */
		private static net.minecraft.resources.Identifier withPng(final net.minecraft.resources.Identifier id) {
			return id.getPath().endsWith(".png") ? id : id.withSuffix(".png");
		}
		//?}

		/**
		 * Fills {@link #scratchAnchor}: the shape's structural centre, else the effect instance's
		 * world position — its runtime move, then its first declared {@code positions} slot, then
		 * authored {@code pos_x/pos_y/pos_z} — else the local player's <b>eye</b> position for a
		 * player-anchored play. The camera is never the anchor: a camera-only change (F5, third
		 * person, or any camera motion) must not slide a pattern that is fixed to the world.
		 *
		 * <p>The player fallback is the eye, not the feet. The anchor's Y centres the wall
		 * projection ({@code centerP.y = center.y}), so an anchor at the feet puts the figure on
		 * the floor line and its lower half sits below the wall (buried in the ground) — the
		 * reported regression, and the floor/wall corner it then straddles is what flickers. The
		 * pre-762c836 camera anchor was the eye and carried the figure correctly onto walls; using
		 * the player's own eye height keeps that placement without consulting the camera, so the
		 * pattern still never slides when only the camera moves.
		 *
		 * @param definition the effect's definition, already resolved once by the caller
		 */
		private void resolveAnchor(final VFXActiveEffect effect, final @Nullable VFXShape shape, final @Nullable VFXDefinition definition) {
			if (shape != null && shape.center() != null) {
				final float[] center = shape.center();
				this.scratchAnchor.set(center[0], center[1], center[2]);
				return;
			}
			final Vec3 moved = effect.getMovePosition();
			if (moved != null) {
				this.scratchAnchor.set((float) moved.x, (float) moved.y, (float) moved.z);
				return;
			}
			// An entity-anchored slot stores a placeholder block at ZERO; only a real static slot
			// names the anchor here (entity anchors are the world overlays' concern).
			if (effect.getAnchors().isEmpty() && !effect.getPositions().isEmpty()) {
				final BlockPos pos = effect.getPositions().get(0);
				this.scratchAnchor.set(pos.getX() + 0.5F, pos.getY() + 0.5F, pos.getZ() + 0.5F);
				return;
			}
			if (hasPositionBind(definition)) {
				this.scratchAnchor.set(
					effect.getParam("pos_x", 0.0F), effect.getParam("pos_y", 0.0F), effect.getParam("pos_z", 0.0F));
				return;
			}
			final VFXWorldBindings.PlayerState player = VFXWorldBindings.playerState();
			if (player != null) {
				final Minecraft minecraft = Minecraft.getInstance();
				final float eyeHeight = minecraft.player == null ? 0.0F : minecraft.player.getEyeHeight();
				this.scratchAnchor.set(player.px(), player.py() + eyeHeight, player.pz());
				return;
			}
			this.scratchAnchor.set(0.0F, 0.0F, 0.0F);
		}

		/** True when the definition authors {@code pos_x/pos_y/pos_z}, the position binds that drive the anchor. */
		private static boolean hasPositionBind(final @Nullable VFXDefinition definition) {
			return definition != null && (definition.getParams().containsKey("pos_x")
				|| definition.getParams().containsKey("pos_y")
				|| definition.getParams().containsKey("pos_z"));
		}

		/**
		 * Resolves the depth pass's {@code Config} floats into a fresh array in registered-name
		 * order. The array starts {@link Float#NaN}; every name is written by
		 * {@link #resolveDepthValue} (whose switch throws on an unhandled name) and the post-check
		 * below fails loudly if any entry is still NaN. A name appended to
		 * {@code VFXShaderPrograms.registerDepthPost} without a resolver case therefore cannot
		 * silently upload as zero — the class of "head works, tail dead" bug is impossible.
		 *
		 * @param effect        the live effect instance
		 * @param weight        the effect's fade weight (applied to timeline parameters only)
		 * @param shape         the definition's structural shape, or {@code null}
		 * @param surface       the definition's structural surface selection, or {@code null}
		 * @param patternTexture the resolved pattern texture descriptor (never {@code null})
		 * @return one float per registered name, in order
		 */
		private float[] resolveDepthConfig(
			final VFXActiveEffect effect,
			final float weight,
			final @Nullable VFXShape shape,
			final @Nullable VFXSurfaceSelection surface,
			final PatternTexture patternTexture
		) {
			final float[] values = new float[this.configParams.length];
			java.util.Arrays.fill(values, Float.NaN);
			for (int i = 0; i < this.configParams.length; i++) {
				values[i] = this.resolveDepthValue(this.configParams[i], effect, weight, shape, surface, patternTexture);
			}
			for (int i = 0; i < values.length; i++) {
				if (Float.isNaN(values[i])) {
					throw new IllegalStateException("surface_pattern Config resolver left '" + this.configParams[i] + "' unwritten (NaN)");
				}
			}
			return values;
		}

		/**
		 * Resolves one depth {@code Config} name to its final float. The switch is exhaustive: a
		 * registered name without a case is a contract violation and throws instead of falling back
		 * to a timeline value of zero. A reserved name (the world anchor, the structural
		 * figure/surface, the resolved texture) is written as resolved; every other name is a
		 * timeline parameter, weight-blended against its neutral.
		 *
		 * @param param         the registered name (one entry of {@code registerDepthPost})
		 * @param effect        the live effect instance
		 * @param weight        the effect's fade weight
		 * @param shape         the structural shape, or {@code null}
		 * @param surface       the structural surface selection, or {@code null}
		 * @param patternTexture the resolved pattern texture descriptor
		 * @return the value to upload for this name
		 */
		private float resolveDepthValue(
			final String param,
			final VFXActiveEffect effect,
			final float weight,
			final @Nullable VFXShape shape,
			final @Nullable VFXSurfaceSelection surface,
			final PatternTexture patternTexture
		) {
			final float raw = switch (param) {
				case "time" -> effect.getAge();
				case "tile_scale" -> effect.getParam("tile_scale", 0.0F);
				case "color_r" -> effect.getParam("color_r", 0.0F);
				case "color_g" -> effect.getParam("color_g", 0.0F);
				case "color_b" -> effect.getParam("color_b", 0.0F);
				case "opacity" -> effect.getParam("opacity", 0.0F);
				case "fade_radius" -> effect.getParam("fade_radius", 0.0F);
				case "normal_mask" -> effect.getParam("normal_mask", 0.0F);
				case "distort" -> effect.getParam("distort", 0.0F);
				case "texture_tint" -> effect.getParam("texture_tint", 0.0F);
				case "center_x" -> this.scratchAnchor.x;
				case "center_y" -> this.scratchAnchor.y;
				case "center_z" -> this.scratchAnchor.z;
				case "shape" -> shape == null ? 0.0F : (float) shape.figure().ordinal();
				case "fill" -> shape == null ? 0.0F : (float) shape.fill().ordinal();
				// A numeric rotation param, when authored, overrides the structural rotation (same
				// style as line_width overriding stroke_width): the value spins the figure and the
				// texture together.
				case "rotation" -> effect.getParam("rotation", shape == null ? 0.0F : shape.rotation());
				// The numeric line_width param, when authored, overrides the shape's structural
				// stroke width.
				case "stroke_width" -> effect.getParam("line_width", shape == null ? 0.0F : shape.strokeWidth());
				case "softness" -> shape == null ? 0.0F : shape.softness();
				case "repeat_x" -> shape == null ? 1.0F : (float) shape.repeatX();
				case "repeat_y" -> shape == null ? 1.0F : (float) shape.repeatY();
				case "radius" -> shape == null ? 0.0F : shape.radius();
				case "radius_x" -> shape == null ? 0.0F : shape.radiusX();
				case "radius_y" -> shape == null ? 0.0F : shape.radiusY();
				case "half_width" -> shape == null ? 0.0F : shape.halfWidth();
				case "half_height" -> shape == null ? 0.0F : shape.halfHeight();
				case "corner_radius" -> shape == null ? 0.0F : shape.cornerRadius();
				case "sides" -> shape == null ? 3.0F : (float) shape.sides();
				case "face_mask" -> surface == null ? -1.0F : (float) surface.faceMask();
				case "band_min" -> surface == null ? VFXSurfaceSelection.UNBOUNDED_MIN : surface.min();
				case "band_max" -> surface == null ? VFXSurfaceSelection.UNBOUNDED_MAX : surface.max();
				case "band_softness" -> surface == null ? VFXSurfaceSelection.DEFAULT_BAND_SOFTNESS : surface.bandSoftness();
				// Textured figure: shape_present gates the shape mask; the tex_* values come from the
				// resolved texture descriptor (atlas rect, aspect, sheet, frame, flags, channel).
				case "shape_present" -> shape == null ? 0.0F : (shape.figureAuthored() ? 1.0F : 0.0F);
				case "tex_u0" -> patternTexture.u0();
				case "tex_v0" -> patternTexture.v0();
				case "tex_u1" -> patternTexture.u1();
				case "tex_v1" -> patternTexture.v1();
				case "tex_aspect" -> patternTexture.aspect();
				case "tex_cols" -> patternTexture.cols();
				case "tex_rows" -> patternTexture.rows();
				// The animatable sprite-sheet frame: a normal param, so it may be a literal, a
				// keyframe, an expr or a { "from": node } graph input.
				case "tex_frame" -> effect.getParam("frame", 0.0F);
				case "tex_flags" -> patternTexture.flags();
				case "tex_channel" -> patternTexture.channel();
				case "tex_px_w" -> patternTexture.pxW();
				case "tex_px_h" -> patternTexture.pxH();
				// surface.stitch: unfold a vertical wall into the floor plane. With no surface block
				// this is 0 = today's hard floor/wall switch.
				case "stitch" -> surface == null || !surface.stitch() ? 0.0F : 1.0F;
				default -> throw new IllegalStateException("surface_pattern Config resolver has no case for '" + param + "'");
			};
			final boolean reserved = "time".equals(param) || isReservedDepthParam(param);
			final float neutral = reserved ? Float.NaN : effect.getType().neutralValue(param);
			return Float.isNaN(neutral) ? raw : neutral + (raw - neutral) * weight;
		}

		/** True for the depth Config names the manager resolves from the shape/anchor, not the timeline. */
		private static boolean isReservedDepthParam(final String param) {
			return switch (param) {
				case "center_x", "center_y", "center_z", "shape", "fill", "rotation", "stroke_width",
					"softness", "repeat_x", "repeat_y", "radius", "radius_x", "radius_y",
					"half_width", "half_height", "corner_radius", "sides",
					"face_mask", "band_min", "band_max", "band_softness", "stitch" -> true;
				case "shape_present", "tex_u0", "tex_v0", "tex_u1", "tex_v1", "tex_aspect",
					"tex_cols", "tex_rows", "tex_frame", "tex_flags", "tex_channel",
					"tex_px_w", "tex_px_h" -> true;
				default -> false;
			};
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
		/** Rings retired by a growth during the current frame; closed only at the NEXT endFrame. */
		private final List<MappableRingBuffer> retired = new ArrayList<>();
		/** Rings retired in the previous frame; closing them here keeps a used ring alive one extra frame. */
		private final List<MappableRingBuffer> retiring = new ArrayList<>();
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

		/** The byte length of one slot, and so of the range bound for every uniform block in it. */
		private int blockSize() {
			return this.blockSize;
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
			// Close the rings retired one frame AGO, not those retired during this frame: this
			// endFrame rotates into a slot the submit just built, so the ring grown away from may
			// still be referenced by that submit. Keeping it one extra frame avoids a use-after-free
			// if MappableRingBuffer.close() frees immediately.
			for (final MappableRingBuffer old : this.retiring) {
				old.close();
			}
			this.retiring.clear();
			this.retiring.addAll(this.retired);
			this.retired.clear();
		}

		private void close() {
			this.ring.close();
			for (final MappableRingBuffer old : this.retiring) {
				old.close();
			}
			this.retiring.clear();
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

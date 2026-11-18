package dev.vfxweaver.client.platform;

import dev.vfxweaver.client.VFXClient;
import dev.vfxweaver.client.VFXScoreboardCache;
import dev.vfxweaver.client.audio.VFXVoiceManager;
import dev.vfxweaver.client.editor.EditorPreviewController;
import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.client.flashback.FlashbackCompat;
import dev.vfxweaver.client.postprocessing.VFXPostProcessingManager;
import dev.vfxweaver.client.render.VFXTextOverlayRenderer;
import dev.vfxweaver.client.render.VFXWorldOverlayRenderer;
import dev.vfxweaver.client.window.VFXWindowManager;
import dev.vfxweaver.effect.VFXActiveEffect;
import dev.vfxweaver.effect.VFXEffectType;
import dev.vfxweaver.effect.VFXTextSpec;
import dev.vfxweaver.effect.VFXWorldBindings;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
//? if <26.1 {
/*import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.state.SkyRenderState;
import net.minecraft.world.level.dimension.DimensionType;
*///?} else {
import net.minecraft.client.gui.GuiGraphicsExtractor;
//?}
import net.minecraft.resources.Identifier;
//? if <26.2 {
import net.minecraft.client.renderer.MultiBufferSource;
//?}
import net.minecraft.client.renderer.SubmitNodeCollector;
//? if <26.1 {
/*import net.minecraft.client.renderer.state.CameraRenderState;
*///?} else {
import net.minecraft.client.renderer.state.level.CameraRenderState;
//?}
//? if fabric {
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
//? if <26.1 {
/*import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
*///?} else {
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
//?}
//?} else {
/*import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
//? if <26.2 {
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
//?}
//? if >=26.1 {
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
//?}
import net.neoforged.neoforge.client.event.lifecycle.ClientStoppingEvent;
import net.neoforged.neoforge.common.NeoForge;*/
//?}
import org.jspecify.annotations.Nullable;

/**
 * Client-side loader glue: the client lifecycle/tick/join events and the world-overlay render
 * events. The render-event plumbing (camera + geometry sink) is captured here so
 * {@link VFXWorldOverlayRenderer} stays loader-agnostic; its drawing code is unchanged.
 *
 * <p>The captured context is only valid for the duration of the event callback: the loader event
 * sets it, invokes the renderer, and clears it in a {@code finally} block. World rendering is
 * single-threaded, so a static capture is safe.
 */
public final class VFXClientRenderHooks {
	private static @Nullable CameraRenderState currentCamera;
	/** Buffer source of the current event ({@code <26.2}; 26.2 submits through the collector). */
	//? if <26.2 {
	private static MultiBufferSource.@Nullable BufferSource currentBuffers;
	//?}
	/**
	 * Submit node collector of the current event (on {@code <26.1}: the Fabric command queue or,
	 * on NeoForge, the level renderer's per-frame submit storage).
	 */
	private static @Nullable SubmitNodeCollector currentCollector;

	// Identifier of the flat text HUD element: the HUD registry addresses elements by name on
	// every node, so this is what the element is attached (and, if it ever has to be, removed) by.
	private static final Identifier FLAT_TEXT_HUD =
		Identifier.fromNamespaceAndPath("vfxweaver", "text_overlay");

	private VFXClientRenderHooks() {
	}

	/**
	 * Registers the loader's client lifecycle events and the client network receivers. Called once
	 * from the client entry point.
	 */
	public static void initClient() {
		VFXClientNetwork.registerClient();
		//? if fabric {
		ClientTickEvents.END_CLIENT_TICK.register(client -> onClientTick());
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onClientJoin());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onClientDisconnect());
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> onClientStopping());
		// HudElementRegistry.addLast appends after every vanilla element, so the flat text is the
		// last thing drawn and sits over the hotbar, the chat and the vanilla overlays.
		HudElementRegistry.addLast(FLAT_TEXT_HUD, (graphics, delta) -> drawFlatText(graphics));
		//?} else {
		/*NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> onClientTick());
		NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> onClientJoin());
		NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> onClientDisconnect());
		NeoForge.EVENT_BUS.addListener((ClientStoppingEvent event) -> onClientStopping());
		// RenderGuiEvent.Post is the HUD pass on NeoForge: the event hands out the same GUI
		// graphics Fabric's HUD element gets, so both loaders share one drawing path.
		NeoForge.EVENT_BUS.addListener(
			(RenderGuiEvent.Post event) -> drawFlatText(event.getGuiGraphics()));*/
		//?}
	}

	/** Runs the once-per-tick client work (Flashback recording-start detection, preview clock). */
	public static void onClientTick() {
		FlashbackCompat.detectRecordingStart();
		FlashbackCompat.tickReplayState();
		// The preview clock advances here: the same client tick hook the editor/game work already
		// uses, so the controller's tick and the render frame stay in the same order.
		EditorPreviewController.get().onClientTick();
	}

	// Draws every running flat-mode text_overlay on the HUD, once per frame. This only walks the
	// active effects and picks the flat ones out: the layout, the reveal wipe and the colour all
	// live in VFXTextOverlayRenderer.renderFlat, so the 2D and 3D paths share one block of shape
	// code. Instance ids of the flat effects are collected here because the renderer's layout cache
	// is keyed by instance id - without the prune, every finished effect would leave its layout in
	// the cache for the life of the client.
	//
	// graphics - the GUI graphics of the current HUD pass
	//? if <26.1 {
	/*private static void drawFlatText(final GuiGraphics graphics) {
	*///?} else {
	private static void drawFlatText(final GuiGraphicsExtractor graphics) {
	//?}
		final Set<Long> flatInstances = new HashSet<>();
		for (final VFXActiveEffect effect : VFXEffectManager.get().getActiveWorldEffects()) {
			if (effect.getType() != VFXEffectType.TEXT_OVERLAY) {
				continue;
			}
			final VFXTextSpec spec = effect.getTextSpec();
			// A definition without a text block or in world mode is the world pass's business.
			if (spec == null || spec.mode() != VFXTextSpec.Mode.FLAT) {
				continue;
			}
			flatInstances.add(effect.getInstanceId());
			VFXTextOverlayRenderer.renderFlat(effect, graphics);
		}
		VFXTextOverlayRenderer.pruneFlat(flatInstances);
	}

	/** Resets the server-pushed scoreboard cache when the player joins a server. */
	public static void onClientJoin() {
		VFXScoreboardCache.clear();
	}

	/** Resets the server-pushed scoreboard cache and any runtime mask override on leaving a server. */
	public static void onClientDisconnect() {
		Minecraft.getInstance().execute(() -> {
			VFXScoreboardCache.clear();
			// A mask override was pushed by the server that just went away; keeping it would leak
			// one session's mask into the next world, so it is dropped with the scoreboard cache.
			VFXEffectManager.get().clearRuntimeMasks();
		});
	}

	/** Frees the lazily allocated GPU resources on client shutdown. */
	private static void onClientStopping() {
		VFXWorldOverlayRenderer.freeGpuResources();
		VFXPostProcessingManager.get().freeGpuResources();
		VFXWindowManager.get().closeAll();
		VFXVoiceManager.get().stopAll();
		VFXVoiceManager.get().shutdown();
		// The editor bridge runs a JDK HttpServer whose dispatcher/selector threads are NOT
		// daemon: leaving it running holds the JVM past shutdown and trips the client shutdown
		// watchdog ("Client shutdown from post-main"). Stop it here unconditionally.
		VFXClient.shutdownEditor();
	}

	/**
	 * Wires the loader's world-overlay render events to the renderer callbacks, capturing the
	 * event context for {@link #camera()}, {@link #buffers()} and {@link #collector()}.
	 *
	 * <p>Fabric keeps its two events ({@code AFTER_TRANSLUCENT_TERRAIN} + {@code COLLECT_SUBMITS}
	 * on {@code >=26.1}, {@code END_MAIN} + {@code BEFORE_ENTITIES} on {@code <26.1}). On NeoForge
	 * {@code >=26.1} the collector-based work runs at {@code SubmitCustomGeometryEvent}, the only
	 * phase where the level's {@code SubmitNodeCollector} is still live ({@code LevelRenderer}
	 * drains its submit storage into the prepared feature frame before the render-stage events, so
	 * a submit there is silently dropped); {@code 26.1.2} draws its buffer-source overlays at
	 * {@code RenderLevelStageEvent.AfterTranslucentBlocks}, which lacks a collector.
	 * {@code <26.1} has no submit-geometry event, so its stage event remains the only source.
	 *
	 * @param onRender         the overlay geometry callback (the Fabric stage event equivalent)
	 * @param onCollectSubmits the block-model submit callback
	 */
	public static void registerWorldOverlays(final Runnable onRender, final Runnable onCollectSubmits) {
		//? if fabric {
		//? if <26.1 {
		/*WorldRenderEvents.END_MAIN.register(context -> {
			currentCamera = context.worldState().cameraRenderState;
			// 1.21.11: the render state is only reachable through the render context here
			// (LevelRenderer.levelRenderState is package-private), so the sky angles are published
			// from the context's state. This is before the layer-0 post pass on every node.
			publishSkyState(context.worldState().skyRenderState);
			currentBuffers = context.consumers() instanceof MultiBufferSource.BufferSource buffers ? buffers : null;
			try {
				onRender.run();
			} finally {
				clearContext();
			}
		});
		WorldRenderEvents.BEFORE_ENTITIES.register(context -> {
			currentCamera = context.worldState().cameraRenderState;
			currentCollector = context.commandQueue();
			try {
				onCollectSubmits.run();
			} finally {
				clearContext();
			}
		});
		*///?} else {
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(context -> {
			currentCamera = context.levelState().cameraRenderState;
			//? if <26.2 {
			currentBuffers = context.bufferSource();
			//?} else {
			/*currentCollector = context.submitNodeCollector();
			*///?}
			try {
				onRender.run();
			} finally {
				clearContext();
			}
		});
		LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
			currentCamera = context.levelState().cameraRenderState;
			currentCollector = context.submitNodeCollector();
			try {
				onCollectSubmits.run();
			} finally {
				clearContext();
			}
		});
		//?}
		//?} else {
		/*		//? if <26.1 {
		// 1.21.11 NeoForge: the classic world-overlay API, one nested event per level-render stage.
		// The translucent-block stage captures the vanilla per-frame sources directly (main camera,
		// render buffers, the level renderer's submit storage) and runs both callbacks.
		NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterTranslucentBlocks event) -> {
			currentCamera = event.getLevelRenderState().cameraRenderState;
			publishSkyState(event.getLevelRenderState().skyRenderState);
			currentBuffers = Minecraft.getInstance().renderBuffers().bufferSource();
			currentCollector = event.getLevelRenderer().submitNodeStorage;
			try {
				onRender.run();
				onCollectSubmits.run();
			} finally {
				clearContext();
			}
		});
		//?} else {
		// 26.1.2 / 26.2 NeoForge: LevelRenderer drains its submitNodeStorage into the prepared
		// feature frame BEFORE the render-stage events fire, so writing to the collector at
		// AfterTranslucentBlocks is silently dropped. SubmitCustomGeometryEvent is the only phase
		// that hands out the live SubmitNodeCollector.
		NeoForge.EVENT_BUS.addListener((SubmitCustomGeometryEvent event) -> {
			currentCamera = event.getLevelRenderState().cameraRenderState;
			currentCollector = event.getSubmitNodeCollector();
			try {
				//? if >=26.2 {
				onRender.run();
				//?}
				onCollectSubmits.run();
			} finally {
				clearContext();
			}
		});
		//? if <26.2 {
		// 26.1.2 (>=26.1, <26.2): the overlay geometry path draws through the vanilla
		// MultiBufferSource, which SubmitCustomGeometryEvent does not expose, so it stays on the
		// level-render stage event (the collector-based submits above are the SubmitCustomGeometryEvent).
		NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterTranslucentBlocks event) -> {
			currentCamera = event.getLevelRenderState().cameraRenderState;
			currentBuffers = Minecraft.getInstance().renderBuffers().bufferSource();
			try {
				onRender.run();
			} finally {
				clearContext();
			}
		});
		//?}
		//?}*/
		//?}
	}

	private static void clearContext() {
		currentCamera = null;
		//? if <26.2 {
		currentBuffers = null;
		//?}
		currentCollector = null;
	}

	//? if <26.1 {
	/*// 1.21.11 only (see {@link #registerWorldOverlays}): the render state is reached through the
	// render context because LevelRenderer.levelRenderState is package-private there. On 26.x the
	// public GameRenderer.gameRenderState() chain in GameRendererMixin reads it directly.
	private static void publishSkyState(final SkyRenderState sky) {
		final boolean ready = sky.skybox == DimensionType.Skybox.OVERWORLD;
		VFXWorldBindings.updateSkyState(sky.sunAngle, sky.moonAngle, sky.starAngle, ready);
	}
	*///?}

	/** @return the camera of the render event being dispatched, or {@code null} outside one */
	public static @Nullable CameraRenderState camera() {
		return currentCamera;
	}

	/** @return the buffer source of the render event being dispatched ({@code <26.2}) */
	//? if <26.2 {
	public static MultiBufferSource.@Nullable BufferSource buffers() {
		return currentBuffers;
	}
	//?}

	/** @return the submit collector of the render event being dispatched */
	public static @Nullable SubmitNodeCollector collector() {
		return currentCollector;
	}
}

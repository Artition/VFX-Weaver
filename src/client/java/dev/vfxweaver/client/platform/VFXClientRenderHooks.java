package dev.vfxweaver.client.platform;

import dev.vfxweaver.client.VFXScoreboardCache;
import dev.vfxweaver.client.flashback.FlashbackCompat;
import dev.vfxweaver.client.postprocessing.VFXPostProcessingManager;
import dev.vfxweaver.client.render.VFXWorldOverlayRenderer;
import net.minecraft.client.Minecraft;
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
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
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
		//?} else {
		/*NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> onClientTick());
		NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> onClientJoin());
		NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> onClientDisconnect());
		NeoForge.EVENT_BUS.addListener((ClientStoppingEvent event) -> onClientStopping());*/
		//?}
	}

	/** Runs the once-per-tick client work (Flashback recording-start detection). */
	public static void onClientTick() {
		FlashbackCompat.detectRecordingStart();
	}

	/** Resets the server-pushed scoreboard cache when the player joins a server. */
	public static void onClientJoin() {
		VFXScoreboardCache.clear();
	}

	/** Resets the server-pushed scoreboard cache when the player leaves a server. */
	public static void onClientDisconnect() {
		Minecraft.getInstance().execute(VFXScoreboardCache::clear);
	}

	/** Frees the lazily allocated GPU resources on client shutdown. */
	private static void onClientStopping() {
		VFXWorldOverlayRenderer.freeGpuResources();
		VFXPostProcessingManager.get().freeGpuResources();
	}

	/**
	 * Wires the loader's world-overlay render events to the renderer callbacks, capturing the
	 * event context for {@link #camera()}, {@link #buffers()} and {@link #collector()}.
	 *
	 * <p>Fabric keeps its two events ({@code AFTER_TRANSLUCENT_TERRAIN} + {@code COLLECT_SUBMITS}
	 * on {@code >=26.1}, {@code END_MAIN} + {@code BEFORE_ENTITIES} on {@code <26.1}). NeoForge uses
	 * one event on every line, {@code RenderLevelStageEvent.AfterTranslucentBlocks}: it captures the
	 * main camera, the render buffers ({@code <26.2}) and the level renderer's per-frame submit
	 * storage, then runs both callbacks. {@code <26.1} has no submit-geometry event, so this stage
	 * event is its only source.
	 *
	 * @param onRender         the overlay geometry callback (the Fabric stage event equivalent)
	 * @param onCollectSubmits the block-model submit callback
	 */
	public static void registerWorldOverlays(final Runnable onRender, final Runnable onCollectSubmits) {
		//? if fabric {
		//? if <26.1 {
		/*WorldRenderEvents.END_MAIN.register(context -> {
			currentCamera = context.worldState().cameraRenderState;
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
		/*//? if <26.1 {
		// 1.21.11 NeoForge: the classic world-overlay API, one nested event per level-render stage.
		// The translucent-block stage captures the vanilla per-frame sources directly (main camera,
		// render buffers, the level renderer's submit storage) and runs both callbacks.
		NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterTranslucentBlocks event) -> {
			currentCamera = event.getLevelRenderState().cameraRenderState;
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
		// 26.1.2 / 26.2 NeoForge: the same stage event; the render buffers exist only below 26.2
		// (26.2 submits through the collector), so their capture is guarded.
		NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterTranslucentBlocks event) -> {
			currentCamera = event.getLevelRenderState().cameraRenderState;
			//? if <26.2 {
			currentBuffers = Minecraft.getInstance().renderBuffers().bufferSource();
			//?}
			currentCollector = event.getLevelRenderer().submitNodeStorage;
			try {
				onRender.run();
				onCollectSubmits.run();
			} finally {
				clearContext();
			}
		});
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

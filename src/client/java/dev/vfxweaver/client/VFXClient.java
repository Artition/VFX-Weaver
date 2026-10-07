package dev.vfxweaver.client;

import dev.vfxweaver.api.VFXAPI;
import dev.vfxweaver.client.editor.EditorServer;
import dev.vfxweaver.client.editor.EditorSession;
import dev.vfxweaver.client.editor.MinecraftEditorGame;
import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.client.compat.iris.VfxIrisCompat;
import dev.vfxweaver.client.flashback.FlashbackCompat;
import dev.vfxweaver.client.platform.VFXClientCommands;
import dev.vfxweaver.client.platform.VFXClientRenderHooks;
import dev.vfxweaver.client.postprocessing.VFXMaskShaderVariants;
import dev.vfxweaver.client.postprocessing.VFXShaderPrograms;
import dev.vfxweaver.client.render.VFXClientEntityReader;
import dev.vfxweaver.client.render.VFXEntityEffectRenderer;
import dev.vfxweaver.client.render.VFXWorldOverlayRenderer;
import dev.vfxweaver.editor.VFXEffectSchema;
import dev.vfxweaver.effect.EasingFunction;
import dev.vfxweaver.effect.VFXCurveManager;
import dev.vfxweaver.effect.VFXWorldBindings;
import dev.vfxweaver.mask.VFXShapeRegistry;
import dev.vfxweaver.network.VFXAction;
import dev.vfxweaver.network.VFXScoreboardPayload;
import dev.vfxweaver.network.VFXSyncPayload;
import dev.vfxweaver.network.VFXTriggerPayload;
import dev.vfxweaver.platform.VFXNetwork;
import dev.vfxweaver.resource.VFXDefinitionManager;
import dev.vfxweaver.util.VFXSettings;
import java.util.Map;
//? if fabric {
import net.fabricmc.api.ClientModInitializer;
//?} else {
/*import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;*/
//?}
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint: registers the post-processing pipelines, the local dispatcher and the
 * network receiver that turns {@link VFXTriggerPayload}s into running effects. Loader-specific
 * lifecycle and render-event wiring lives in {@link VFXClientRenderHooks}.
 */
//? if fabric {
public class VFXClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		initializeClient();
	}
//?} else {
/*@Mod(value = "vfxweaver", dist = Dist.CLIENT)
public class VFXClient {
	public VFXClient(final IEventBus modBus) {
		initializeClient();
	}
*///?}
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/client");
	/** POSTs per second accepted by the editor bridge (a compile is rare; a burst is a mistake). */
	private static final int EDITOR_RATE_LIMIT = 20;

	/** The one running editor bridge, or {@code null} while it is stopped. Client thread only. */
	private static @Nullable EditorServer editorServer;

	/** Runs the loader-agnostic client setup; invoked by the Fabric entry or the NeoForge client mod. */
	private void initializeClient() {
		VFXClientRenderHooks.initClient();
		VFXSettings.get().load();
		VFXShaderPrograms.register();
		VFXWorldOverlayRenderer.register();
		VFXEntityEffectRenderer.register();
		VFXAPI.setLocalDispatcher(new VFXClientAPI());
		VFXClientCommands.register();
		VFXShapeRegistry.get().setChangeListener(VFXMaskShaderVariants::invalidate);
		VFXWorldBindings.setScoreboardReader(VFXClient::readScoreboard);
		VFXWorldBindings.setEntityReader(new VFXClientEntityReader());
		VfxIrisCompat.init();
		FlashbackCompat.init();
		VFXNetwork.registerClientReceive(VFXTriggerPayload.TYPE, this::handleTrigger);
		VFXNetwork.registerClientReceive(VFXSyncPayload.TYPE, this::handleSync);
		VFXNetwork.registerClientReceive(VFXScoreboardPayload.TYPE, this::handleScoreboard);
		LOGGER.info("VFX Weaver client initialized");
	}

	/**
	 * Toggles the visual editor bridge: the first call starts it on a random loopback port,
	 * prints the token-bearing URL to chat and opens it in the OS browser; a later call stops it.
	 * Runs on the client thread (the command handler's thread).
	 *
	 * @return {@code 1} when the toggle succeeded, {@code 0} when the server could not start
	 */
	public static int toggleEditor() {
		if (editorServer != null) {
			editorServer.stop();
			editorServer = null;
			message("VFX editor stopped");
			return 1;
		}
		VFXEffectSchema.registerBuiltIns();
		try {
			editorServer = EditorServer.start(new EditorSession(new MinecraftEditorGame()), EDITOR_RATE_LIMIT);
		} catch (final RuntimeException e) {
			LOGGER.error("Failed to start the VFX editor server", e);
			message("Failed to start the VFX editor server");
			return 0;
		}
		final String url = editorServer.url();
		message("VFX editor: " + url);
		try {
			Util.getPlatform().openUri(url);
		} catch (final RuntimeException e) {
			LOGGER.warn("Could not open the editor URL in a browser; open it manually", e);
		}
		return 1;
	}

	/** Sends a client chat message and logs it at INFO (any bridge token is stripped from the log). */
	private static void message(final String text) {
		final Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) {
			//? if <26.1 {
			/*minecraft.player.displayClientMessage(Component.literal(text), false);
			*///?} else {
			minecraft.player.sendSystemMessage(Component.literal(text));
			//?}
		}
		LOGGER.info(redactToken(text));
	}

	/** Replaces a {@code token=...} query value with {@code ***}, so the bridge secret never reaches the log. */
	private static String redactToken(final String text) {
		return text.replaceAll("([?&]token=)[^&\\s]+", "$1***");
	}

	/**
	 * Reads a raw score from the client scoreboard for
	 * {@link VFXWorldBindings.ScoreboardReader}. Missing level, objective, holder or score
	 * yield {@code null} (evaluated as 0.0 downstream).
	 */
	private static @Nullable Integer readScoreboard(final String objectiveName, final @Nullable String holderName) {
		final Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.player == null) {
			return null;
		}
		// Server-pushed value for scoreboard bindings first (the vanilla client only mirrors
		// objectives that are displayed in a slot, so a bind cannot rely on the local scoreboard).
		final String holder = holderName != null ? holderName : minecraft.player.getScoreboardName();
		final Integer tracked = VFXScoreboardCache.get(objectiveName, holder);
		if (tracked != null) {
			return tracked;
		}
		final Scoreboard scoreboard = minecraft.level.getScoreboard();
		final Objective objective = scoreboard.getObjective(objectiveName);
		if (objective == null) {
			return null;
		}
		final ScoreHolder scoreHolder = holderName != null ? ScoreHolder.forNameOnly(holderName) : minecraft.player;
		final ReadOnlyScoreInfo info = scoreboard.getPlayerScoreInfo(scoreHolder, objective);
		return info != null ? Integer.valueOf(info.value()) : null;
	}

	private void handleScoreboard(final VFXScoreboardPayload payload) {
		VFXScoreboardCache.apply(payload);
	}

	private void handleSync(final VFXSyncPayload payload) {
		if (payload.protocolVersion() != VFXSyncPayload.PROTOCOL_VERSION) {
			LOGGER.warn("Ignoring VFX sync packet from server: protocol version mismatch (server={}, client={})", payload.protocolVersion(), VFXSyncPayload.PROTOCOL_VERSION);
			return;
		}
		VFXCurveManager.get().applySynced(payload.curves());
		VFXDefinitionManager.get().applySynced(payload.definitions());
		LOGGER.debug("Received VFX sync: {} definitions, {} curves", payload.definitions().size(), payload.curves().size());
	}

	private void handleTrigger(final VFXTriggerPayload payload) {
		if (payload.protocolVersion() != VFXTriggerPayload.PROTOCOL_VERSION) {
			LOGGER.warn("Ignoring VFX packet from server: protocol version mismatch (server={}, client={})", payload.protocolVersion(), VFXTriggerPayload.PROTOCOL_VERSION);
			return;
		}
		LOGGER.debug("Received VFX packet: action={}, effect={}, duration={}, instance={}, easing={}, params={}", payload.action(), payload.effectId(), payload.durationTicks(), payload.instanceId(), payload.easing(), payload.params().keySet());
		if (payload.action() == VFXAction.STOP) {
			FlashbackCompat.recordStop(payload.effectId());
			if (payload.instanceId() != 0L) {
				VFXEffectManager.get().stop(payload.effectId(), payload.instanceId());
			} else {
				VFXEffectManager.get().stop(payload.effectId());
			}
		} else if (payload.action() == VFXAction.SET_EXPR) {
			if (payload.exprParam() == null || payload.exprParam().isBlank()) {
				LOGGER.warn("Ignoring VFX packet: SET_EXPR without a parameter name");
				return;
			}
			FlashbackCompat.recordSetExpr(payload.effectId(), payload.exprParam(), payload.exprSource());
			if (!VFXEffectManager.get().setExpression(payload.effectId(), payload.exprParam(), payload.exprSource())) {
				LOGGER.warn("VFX set_expr: effect '{}' is not running", payload.effectId());
			}
		} else if (payload.action() == VFXAction.MOVE) {
			if (payload.position() == null) {
				LOGGER.warn("Ignoring VFX packet: MOVE without a position");
				return;
			}
			if (!VFXEffectManager.get().move(payload.effectId(), payload.instanceId(), payload.position())) {
				LOGGER.warn("VFX move: effect '{}' instance {} is not running", payload.effectId(), payload.instanceId());
			}
		} else if (payload.action() == VFXAction.SET_PARAM || payload.action() == VFXAction.KEYFRAME) {
			if (payload.params().size() != 1) {
				LOGGER.warn("Ignoring VFX packet: {} expects exactly one parameter, got {}", payload.action(), payload.params().size());
				return;
			}
			Map.Entry<String, Float> entry = payload.params().entrySet().iterator().next();
			if (payload.action() == VFXAction.SET_PARAM) {
				FlashbackCompat.recordSetParam(payload.effectId(), entry.getKey(), entry.getValue());
				if (!VFXEffectManager.get().setParam(payload.effectId(), entry.getKey(), entry.getValue())) {
					LOGGER.warn("VFX set_param: effect '{}' is not running", payload.effectId());
				}
			} else {
				FlashbackCompat.recordKeyframe(payload.effectId(), entry.getKey(), payload.durationTicks(), entry.getValue(), payload.easing());
				if (!VFXEffectManager.get().setKeyframe(payload.effectId(), entry.getKey(), payload.durationTicks(), entry.getValue(), EasingFunction.fromString(payload.easing()))) {
					LOGGER.warn("VFX keyframe: effect '{}' is not running", payload.effectId());
				}
			}
		} else {
			FlashbackCompat.recordServerPlay(payload.effectId(), payload.durationTicks(), payload.params(), payload.easing(), payload.position(), payload.entityUuids());
			// A blank easing name means "use the definition default" (e.g. an inline curve that
			// only exists in the definition, so it cannot travel as a name).
			EasingFunction payloadEasing = payload.easing() == null || payload.easing().isBlank() ? null : EasingFunction.fromString(payload.easing());
			VFXEffectManager.get().play(payload.effectId(), payload.durationTicks(), payload.elapsedTicks(), payload.instanceId(), payload.position(), payload.entityUuids(), payload.params(), payloadEasing);
		}
	}
}

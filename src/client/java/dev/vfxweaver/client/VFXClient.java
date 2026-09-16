package dev.vfxweaver.client;

import dev.vfxweaver.api.VFXAPI;
import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.client.compat.iris.VfxIrisCompat;
import dev.vfxweaver.client.flashback.FlashbackCompat;
import dev.vfxweaver.client.postprocessing.VFXPostProcessingManager;
import dev.vfxweaver.client.postprocessing.VFXShaderPrograms;
import dev.vfxweaver.client.render.VFXEntityEffectRenderer;
import dev.vfxweaver.client.render.VFXWorldOverlayRenderer;
import dev.vfxweaver.effect.EasingFunction;
import dev.vfxweaver.effect.VFXCurveManager;
import dev.vfxweaver.effect.VFXWorldBindings;
import dev.vfxweaver.network.VFXAction;
import dev.vfxweaver.network.VFXScoreboardPayload;
import dev.vfxweaver.network.VFXSyncPayload;
import dev.vfxweaver.network.VFXTriggerPayload;
import dev.vfxweaver.resource.VFXDefinitionManager;
import java.util.Map;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint: registers the post-processing pipelines, the local dispatcher and the
 * network receiver that turns {@link VFXTriggerPayload}s into running effects.
 */
public class VFXClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/client");

	@Override
	public void onInitializeClient() {
		VFXShaderPrograms.register();
		VFXWorldOverlayRenderer.register();
		VFXEntityEffectRenderer.register();
		VFXAPI.setLocalDispatcher(new VFXClientAPI());
		VFXWorldBindings.setScoreboardReader(VFXClient::readScoreboard);
		VfxIrisCompat.init();
		FlashbackCompat.init();
		ClientPlayNetworking.registerGlobalReceiver(VFXTriggerPayload.TYPE, this::handleTrigger);
		ClientPlayNetworking.registerGlobalReceiver(VFXSyncPayload.TYPE, this::handleSync);
		ClientPlayNetworking.registerGlobalReceiver(VFXScoreboardPayload.TYPE, this::handleScoreboard);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(VFXScoreboardCache::clear));
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			VFXWorldOverlayRenderer.freeGpuResources();
			VFXPostProcessingManager.get().freeGpuResources();
		});
		LOGGER.info("VFX Weaver client initialized");
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

	private void handleScoreboard(final VFXScoreboardPayload payload, final ClientPlayNetworking.Context context) {
		context.client().execute(() -> VFXScoreboardCache.apply(payload));
	}

	private void handleSync(final VFXSyncPayload payload, final ClientPlayNetworking.Context context) {
		context.client().execute(() -> {
			if (payload.protocolVersion() != VFXSyncPayload.PROTOCOL_VERSION) {
				LOGGER.warn("Ignoring VFX sync packet from server: protocol version mismatch (server={}, client={})", payload.protocolVersion(), VFXSyncPayload.PROTOCOL_VERSION);
				return;
			}
			VFXCurveManager.get().applySynced(payload.curves());
			VFXDefinitionManager.get().applySynced(payload.definitions());
			LOGGER.debug("Received VFX sync: {} definitions, {} curves", payload.definitions().size(), payload.curves().size());
		});
	}

	private void handleTrigger(final VFXTriggerPayload payload, final ClientPlayNetworking.Context context) {
		context.client().execute(() -> {
			if (payload.protocolVersion() != VFXTriggerPayload.PROTOCOL_VERSION) {
				LOGGER.warn("Ignoring VFX packet from server: protocol version mismatch (server={}, client={})", payload.protocolVersion(), VFXTriggerPayload.PROTOCOL_VERSION);
				return;
			}
			LOGGER.debug("Received VFX packet: action={}, effect={}, duration={}, instance={}, easing={}, params={}", payload.action(), payload.effectId(), payload.durationTicks(), payload.instanceId(), payload.easing(), payload.params().keySet());
			if (payload.action() == VFXAction.STOP) {
				FlashbackCompat.recordStop(payload.effectId());
				if (payload.instanceId() != 0L) {
					VFXEffectManager.get().stop(payload.effectId(), payload.instanceId());
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
			} else {
					VFXEffectManager.get().stop(payload.effectId());
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
				FlashbackCompat.recordServerPlay(payload.effectId(), payload.durationTicks(), payload.params(), payload.easing());
				// A blank easing name means "use the definition default" (e.g. an inline curve that
				// only exists in the definition, so it cannot travel as a name).
				EasingFunction payloadEasing = payload.easing() == null || payload.easing().isBlank() ? null : EasingFunction.fromString(payload.easing());
				VFXEffectManager.get().play(payload.effectId(), payload.durationTicks(), payload.elapsedTicks(), payload.instanceId(), payload.position(), payload.entityUuids(), payload.params(), payloadEasing);
			}
		});
	}
}

package dev.vfxweaver.client;

import dev.vfxweaver.api.VFXAPI;
import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.client.flashback.FlashbackCompat;
import dev.vfxweaver.client.postprocessing.VFXPostProcessingManager;
import dev.vfxweaver.client.postprocessing.VFXShaderPrograms;
import dev.vfxweaver.client.render.VFXEntityEffectRenderer;
import dev.vfxweaver.client.render.VFXWorldOverlayRenderer;
import dev.vfxweaver.effect.EasingFunction;
import dev.vfxweaver.effect.VFXCurveManager;
import dev.vfxweaver.network.VFXAction;
import dev.vfxweaver.network.VFXSyncPayload;
import dev.vfxweaver.network.VFXTriggerPayload;
import dev.vfxweaver.resource.VFXDefinitionManager;
import java.util.Map;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
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
		FlashbackCompat.init();
		ClientPlayNetworking.registerGlobalReceiver(VFXTriggerPayload.TYPE, this::handleTrigger);
		ClientPlayNetworking.registerGlobalReceiver(VFXSyncPayload.TYPE, this::handleSync);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			VFXWorldOverlayRenderer.freeGpuResources();
			VFXPostProcessingManager.get().freeGpuResources();
		});
		LOGGER.info("VFX Weaver client initialized");
	}

	private void handleSync(final VFXSyncPayload payload, final ClientPlayNetworking.Context context) {
		context.client().execute(() -> {
			if (payload.protocolVersion() != VFXSyncPayload.PROTOCOL_VERSION) {
				LOGGER.warn("Ignoring VFX sync packet from server: protocol version mismatch (server={}, client={})", payload.protocolVersion(), VFXSyncPayload.PROTOCOL_VERSION);
				return;
			}
			VFXDefinitionManager.get().applySynced(payload.definitions());
			VFXCurveManager.get().applySynced(payload.curves());
			LOGGER.info("Received VFX sync: {} definitions, {} curves", payload.definitions().size(), payload.curves().size());
		});
	}

	private void handleTrigger(final VFXTriggerPayload payload, final ClientPlayNetworking.Context context) {
		context.client().execute(() -> {
			if (payload.protocolVersion() != VFXTriggerPayload.PROTOCOL_VERSION) {
				LOGGER.warn("Ignoring VFX packet from server: protocol version mismatch (server={}, client={})", payload.protocolVersion(), VFXTriggerPayload.PROTOCOL_VERSION);
				return;
			}
			LOGGER.info("Received VFX packet: action={}, effect={}, duration={}, instance={}, params={}", payload.action(), payload.effectId(), payload.durationTicks(), payload.instanceId(), payload.params().keySet());
			if (payload.action() == VFXAction.STOP) {
				FlashbackCompat.recordStop(payload.effectId());
				if (payload.instanceId() != 0L) {
					VFXEffectManager.get().stop(payload.effectId(), payload.instanceId());
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
					if (!VFXEffectManager.get().setParam(payload.effectId(), entry.getKey(), entry.getValue())) {
						LOGGER.warn("VFX set_param: effect '{}' is not running", payload.effectId());
					}
				} else if (!VFXEffectManager.get().setKeyframe(payload.effectId(), entry.getKey(), payload.durationTicks(), entry.getValue(), EasingFunction.fromString(payload.easing()))) {
					LOGGER.warn("VFX keyframe: effect '{}' is not running", payload.effectId());
				}
			} else {
				FlashbackCompat.recordServerPlay(payload.effectId(), payload.durationTicks(), payload.params(), payload.easing());
				VFXEffectManager.get().play(payload.effectId(), payload.durationTicks(), payload.elapsedTicks(), payload.instanceId(), payload.position(), payload.entityUuids(), payload.params(), EasingFunction.fromString(payload.easing()));
			}
		});
	}
}

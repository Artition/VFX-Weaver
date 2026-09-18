package dev.vfxweaver.client.platform;

import dev.vfxweaver.network.VFXScoreboardPayload;
import dev.vfxweaver.network.VFXSyncPayload;
import dev.vfxweaver.network.VFXTriggerPayload;
import dev.vfxweaver.platform.VFXNetwork;
//? if fabric {
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
//?}

/**
 * Client-side payload receiver registration. Lives in the client source set because
 * {@code ClientPlayNetworking} is a client-only API (the common {@link VFXNetwork} cannot name
 * it). Every receiver forwards to {@link VFXNetwork#dispatchClient}.
 */
public final class VFXClientNetwork {
	private VFXClientNetwork() {
	}

	/** Registers the client-bound payload receivers. Called from the client entry point. */
	public static void registerClient() {
		//? if fabric {
		ClientPlayNetworking.registerGlobalReceiver(VFXTriggerPayload.TYPE, (payload, context) -> context.client().execute(() -> VFXNetwork.dispatchClient(payload)));
		ClientPlayNetworking.registerGlobalReceiver(VFXSyncPayload.TYPE, (payload, context) -> context.client().execute(() -> VFXNetwork.dispatchClient(payload)));
		ClientPlayNetworking.registerGlobalReceiver(VFXScoreboardPayload.TYPE, (payload, context) -> context.client().execute(() -> VFXNetwork.dispatchClient(payload)));
		//?} else {
		/* // client-bound handlers are declared in VFXNetwork.onRegisterPayloadHandlers() */
		//?}
	}
}

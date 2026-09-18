package dev.vfxweaver.network;

import dev.vfxweaver.api.VFXAPI;
import dev.vfxweaver.effect.EasingType;
import dev.vfxweaver.platform.VFXNetwork;
import dev.vfxweaver.resource.VFXDefinitionManager;
import dev.vfxweaver.util.VFXLog;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Holds the serverbound {@link VFXRequestPayload} receiver that lets a client play (or, with
 * gamemaster permissions, broadcast) a known effect. Payload type registration lives in
 * {@link VFXNetwork}.
 */
public final class VFXPayloads {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/network");

	private VFXPayloads() {
	}

	/**
	 * Handles a client's effect request on the server thread. Unknown effects and malformed
	 * payloads are dropped with a warning; broadcasts require the same gamemasters permission
	 * as the {@code /vfx} command.
	 */
	public static void handleRequest(final VFXRequestPayload payload, final ServerPlayer player, final MinecraftServer server) {
		if (payload.protocolVersion() != VFXTriggerPayload.PROTOCOL_VERSION) {
			VFXLog.warnOnce(LOGGER, "net:protocol:" + player.getUUID(), "Ignoring VFX request from {}: protocol version mismatch (client={}, server={})", player, payload.protocolVersion(), VFXTriggerPayload.PROTOCOL_VERSION);
			return;
		}
		if (VFXDefinitionManager.get().get(payload.effectId()) == null) {
			VFXLog.warnOnce(LOGGER, "net:unknown-effect:" + payload.effectId(), "Ignoring VFX request from {}: unknown effect '{}'", player, payload.effectId());
			return;
		}
		if (payload.broadcast() && !hasVfxPermission(player)) {
			VFXLog.warnOnce(LOGGER, "net:permission:" + payload.effectId(), "Ignoring VFX broadcast request for '{}': {} lacks gamemaster permissions", payload.effectId(), player);
			return;
		}
		if (payload.broadcast()) {
			for (final ServerPlayer target : VFXNetwork.allPlayers(server)) {
				VFXAPI.sendEffect(target, payload.effectId(), payload.instanceId(), payload.worldPos(), List.of(), payload.params(), EasingType.fromString(payload.easing()));
			}
		} else {
			VFXAPI.sendEffect(player, payload.effectId(), payload.instanceId(), payload.worldPos(), List.of(), payload.params(), EasingType.fromString(payload.easing()));
		}
	}

	/** Same permission level the {@code /vfx} command requires (gamemasters and above). */
	private static boolean hasVfxPermission(final ServerPlayer player) {
		return player.createCommandSourceStack().permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS));
	}
}

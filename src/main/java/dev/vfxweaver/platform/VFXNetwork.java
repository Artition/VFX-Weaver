package dev.vfxweaver.platform;

import dev.vfxweaver.network.VFXPayloads;
import dev.vfxweaver.network.VFXRequestPayload;
import dev.vfxweaver.network.VFXScoreboardPayload;
import dev.vfxweaver.network.VFXSyncPayload;
import dev.vfxweaver.network.VFXTriggerPayload;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
//? if fabric {
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
//?} else {
/*import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;*/
//?}

/**
 * Loader-specific payload registration and transport. Every method body is guarded by
 * Stonecutter so the shared sources never import a loader API outside this package.
 */
public final class VFXNetwork {
	/**
	 * Client-side receivers keyed by payload type. The map is populated by the client entry point
	 * ({@link #registerClientReceive}) and read by the loader's client receive callback
	 * ({@link #dispatchClient}); {@code platform} never references a client class.
	 */
	private static final Map<CustomPacketPayload.Type<?>, Consumer<CustomPacketPayload>> CLIENT_RECEIVERS = new HashMap<>();

	private VFXNetwork() {
	}

	/**
	 * Registers every payload type and the server-side request handler. Called once from the
	 * loader's common entry point.
	 */
	public static void registerCommon() {
		//? if fabric {
		//? if <26.1 {
		/*PayloadTypeRegistry.playS2C().register(VFXTriggerPayload.TYPE, VFXTriggerPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(VFXSyncPayload.TYPE, VFXSyncPayload.STREAM_CODEC);
		PayloadTypeRegistry.playS2C().register(VFXScoreboardPayload.TYPE, VFXScoreboardPayload.STREAM_CODEC);
		PayloadTypeRegistry.playC2S().register(VFXRequestPayload.TYPE, VFXRequestPayload.STREAM_CODEC);
		*///?} else {
		PayloadTypeRegistry.clientboundPlay().register(VFXTriggerPayload.TYPE, VFXTriggerPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(VFXSyncPayload.TYPE, VFXSyncPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(VFXScoreboardPayload.TYPE, VFXScoreboardPayload.STREAM_CODEC);
		PayloadTypeRegistry.serverboundPlay().register(VFXRequestPayload.TYPE, VFXRequestPayload.STREAM_CODEC);
		//?}
		ServerPlayNetworking.registerGlobalReceiver(VFXRequestPayload.TYPE, (payload, context) -> VFXPayloads.handleRequest(payload, context.player(), context.server()));
		//?} else {
		/* // payloads are registered on the mod bus in onRegisterPayloadHandlers() */
		//?}
	}

	//? if neoforge {
	/*// Registers the payload types and their handlers on the NeoForge mod bus. Optional so a
	// server without the mod does not reject clients that have it.
	public static void onRegisterPayloadHandlers(final RegisterPayloadHandlersEvent event) {
		final PayloadRegistrar registrar = event.registrar(Integer.toString(VFXTriggerPayload.PROTOCOL_VERSION)).optional();
		registrar.playToClient(VFXTriggerPayload.TYPE, VFXTriggerPayload.STREAM_CODEC, (payload, context) -> context.enqueueWork(() -> dispatchClient(payload)));
		registrar.playToClient(VFXSyncPayload.TYPE, VFXSyncPayload.STREAM_CODEC, (payload, context) -> context.enqueueWork(() -> dispatchClient(payload)));
		registrar.playToClient(VFXScoreboardPayload.TYPE, VFXScoreboardPayload.STREAM_CODEC, (payload, context) -> context.enqueueWork(() -> dispatchClient(payload)));
		registrar.playToServer(VFXRequestPayload.TYPE, VFXRequestPayload.STREAM_CODEC, (payload, context) -> context.enqueueWork(() -> {
			final ServerPlayer player = (ServerPlayer) context.player();
			VFXPayloads.handleRequest(payload, player, player.level().getServer());
		}));
	}
	*///?}

	/**
	 * Sends one payload to one player.
	 *
	 * @param player  the receiving player
	 * @param payload the payload to send
	 */
	public static void sendToPlayer(final ServerPlayer player, final CustomPacketPayload payload) {
		//? if fabric {
		ServerPlayNetworking.send(player, payload);
		//?} else {
		/*PacketDistributor.sendToPlayer(player, payload);*/
		//?}
	}

	/**
	 * Sends one payload to every given player. On NeoForge this is the server-wide broadcast, so
	 * it must only be called with every player.
	 *
	 * @param players the receiving players
	 * @param payload the payload to send
	 */
	public static void sendToAll(final Iterable<ServerPlayer> players, final CustomPacketPayload payload) {
		//? if fabric {
		for (final ServerPlayer player : players) {
			sendToPlayer(player, payload);
		}
		//?} else {
		/*PacketDistributor.sendToAllPlayers(payload);*/
		//?}
	}

	/**
	 * @param server the server to enumerate
	 * @return every player currently on the server
	 */
	public static Iterable<ServerPlayer> allPlayers(final MinecraftServer server) {
		//? if fabric {
		return PlayerLookup.all(server);
		//?} else {
		/*return server.getPlayerList().getPlayers();*/
		//?}
	}

	/**
	 * Registers the client-side handler for one payload type. Called from the client entry point.
	 *
	 * @param type    the payload type to handle
	 * @param handler the handler, invoked on the client thread
	 */
	@SuppressWarnings("unchecked")
	public static <T extends CustomPacketPayload> void registerClientReceive(final CustomPacketPayload.Type<T> type, final Consumer<T> handler) {
		CLIENT_RECEIVERS.put(type, (Consumer<CustomPacketPayload>) handler);
	}

	/**
	 * Looks up and invokes the client handler registered for the payload's type. The client
	 * source set's loader callback (Fabric {@code ClientPlayNetworking}, NeoForge's
	 * {@code enqueueWork}) runs this on the client thread.
	 *
	 * @param payload the received payload
	 */
	public static void dispatchClient(final CustomPacketPayload payload) {
		final Consumer<CustomPacketPayload> receiver = CLIENT_RECEIVERS.get(payload.type());
		if (receiver != null) {
			receiver.accept(payload);
		}
	}
}

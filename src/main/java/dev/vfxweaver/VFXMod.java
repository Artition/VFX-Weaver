package dev.vfxweaver;

import dev.vfxweaver.command.ParamMapArgument;
import dev.vfxweaver.command.VFXCommand;
import dev.vfxweaver.effect.VFXCurveManager;
import dev.vfxweaver.effect.VFXServerEffects;
import dev.vfxweaver.network.VFXPayloads;
import dev.vfxweaver.network.VFXSyncPayload;
import dev.vfxweaver.resource.VFXDefinitionManager;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.ArgumentTypeRegistry;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
//? if <26.1 {
/*import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
*///?} else {
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
//?}
import net.minecraft.commands.synchronization.SingletonArgumentInfo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VFXMod implements ModInitializer {
	public static final String MOD_ID = "vfxweaver";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		VFXPayloads.register();
		// Registered eagerly with the vanilla stateless serializer: a custom type missing
		// from the registry makes the server fail to serialize the command tree (kicks
		// players on join), and a hand-rolled serializer breaks client decoding.
		ArgumentTypeRegistry.registerArgumentType(id("param_map"), ParamMapArgument.class, SingletonArgumentInfo.contextFree(ParamMapArgument::new));
		CommandRegistrationCallback.EVENT.register(VFXCommand::register);

		// Datapack VFX definitions (data/<namespace>/vfx/<effect>.json) and custom easing curves
		// (data/<namespace>/vfx_curves/<curve>.json). Also registered from the client entrypoint so
		// single player rendering can resolve datapack-defined effects and curves.
		registerVfxDefinitionReloadListener();
		registerVfxCurveReloadListener();

		// Sync datapack VFX definitions and curves to clients: on join (vanilla data-pack content
		// sync) and on /reload, so custom (datapack) effects work on dedicated servers.
		ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS.register(VFXMod::syncToPlayer);
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register(VFXMod::syncToAll);
	}

	private static void syncToPlayer(final ServerPlayer player, final boolean joined) {
		sendSync(player);
		if (joined) {
			// Re-apply effects that were sent to this player before the reconnect, after their
			// definitions have been synced so the client can resolve the effect ids.
			VFXServerEffects.get().applyTo(player);
		}
	}

	private static void syncToAll(final MinecraftServer server, final net.minecraft.server.packs.resources.CloseableResourceManager manager, final boolean success) {
		if (!success) {
			return;
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			sendSync(player);
		}
	}

	private static void sendSync(final ServerPlayer player) {
		ServerPlayNetworking.send(player, new VFXSyncPayload(
			VFXSyncPayload.PROTOCOL_VERSION,
			new HashMap<>(VFXDefinitionManager.get().getRawDefinitions()),
			new HashMap<>(VFXCurveManager.get().getRawCurves())
		));
	}

	public static void registerVfxDefinitionReloadListener() {
		try {
			//? if <26.1 {
			/*ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(VFXDefinitionManager.get());
			*///?} else {
			ResourceLoader.get(PackType.SERVER_DATA)
				.registerReloadListener(id("vfx_definitions"), VFXDefinitionManager.get());
			//?}
		} catch (RuntimeException e) {
			LOGGER.warn("Could not register VFX definition reload listener", e);
		}
	}

	public static void registerVfxCurveReloadListener() {
		try {
			//? if <26.1 {
			/*ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(VFXCurveManager.get());
			*///?} else {
			ResourceLoader.get(PackType.SERVER_DATA)
				.registerReloadListener(id("vfx_curves"), VFXCurveManager.get());
			//?}
		} catch (RuntimeException e) {
			LOGGER.warn("Could not register VFX curve reload listener", e);
		}
	}

	public static Identifier id(final String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}

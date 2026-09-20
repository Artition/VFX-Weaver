package dev.vfxweaver.platform;

import com.mojang.brigadier.CommandDispatcher;
import dev.vfxweaver.command.ParamMapArgument;
import dev.vfxweaver.command.VFXCommand;
import dev.vfxweaver.effect.VFXCurveManager;
import dev.vfxweaver.effect.VFXScoreboardSync;
import dev.vfxweaver.effect.VFXServerEffects;
import dev.vfxweaver.network.VFXSyncPayload;
import dev.vfxweaver.resource.VFXBlockParticleManager;
import dev.vfxweaver.resource.VFXDefinitionManager;
import java.util.HashMap;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.commands.synchronization.SingletonArgumentInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
//? if fabric {
import net.fabricmc.fabric.api.command.v2.ArgumentTypeRegistry;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
//? if <26.1 {
/*import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
*///?} else {
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
//?}
//?} else {
/*import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddServerReloadListenersEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.RegisterEvent;*/
//?}
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loader-specific server lifecycle, command and datapack-reload wiring. Every method body is
 * guarded by Stonecutter so the shared sources never import a loader API outside this package.
 */
public final class VFXLoaderEvents {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/loader");

	private VFXLoaderEvents() {
	}

	/**
	 * Registers everything the loaders cannot express declaratively. Called once from the loader
	 * entry point ({@code VFXMod.onInitialize} on Fabric, the {@code VFXNeoForgeMod} constructor on
	 * NeoForge).
	 */
	public static void initCommon() {
		VFXNetwork.registerCommon();
		//? if fabric {
		// Registered eagerly with the vanilla stateless serializer: a custom type missing from the
		// registry makes the server fail to serialize the command tree (kicks players on join), and
		// a hand-rolled serializer breaks client decoding.
		ArgumentTypeRegistry.registerArgumentType(id("param_map"), ParamMapArgument.class, SingletonArgumentInfo.contextFree(ParamMapArgument::new));
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> onRegisterCommands(dispatcher, buildContext));

		// Registered eagerly on Fabric; on NeoForge they are added when the server asks for its
		// reload listeners (see onReload).
		registerVfxDefinitionReloadListener();
		registerVfxCurveReloadListener();
		registerVfxBlockParticleReloadListener();

		// Sync datapack VFX definitions and curves to clients: on join (vanilla data-pack content
		// sync) and on /reload, so custom (datapack) effects work on dedicated servers.
		ServerLifecycleEvents.SYNC_DATA_PACK_CONTENTS.register((player, joined) -> {
			if (joined) {
				onPlayerJoin(player);
			} else {
				sendSync(player);
			}
		});
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, manager, success) -> {
			if (success) {
				syncAll(server);
			}
		});

		// Scoreboard synchronization for `scoreboard` bindings (the vanilla client only mirrors
		// displayed objectives, so the server pushes the referenced values instead).
		ServerTickEvents.END_SERVER_TICK.register(VFXLoaderEvents::onServerTick);
		ServerLifecycleEvents.SERVER_STARTED.register(VFXLoaderEvents::onServerStarted);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> onPlayerDisconnect(handler.player));
		ServerLifecycleEvents.SERVER_STOPPED.register(VFXLoaderEvents::onServerStopping);
		//?} else {
		/*NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> onServerTick(event.getServer()));
		NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> onServerStarted(event.getServer()));
		NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> onServerStopping(event.getServer()));
		NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> onPlayerDisconnect((ServerPlayer) event.getEntity()));
		NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> onRegisterCommands(event.getDispatcher(), event.getBuildContext()));
		NeoForge.EVENT_BUS.addListener((AddServerReloadListenersEvent event) -> onReload(event));
		NeoForge.EVENT_BUS.addListener((OnDatapackSyncEvent event) -> {
			final ServerPlayer player = event.getPlayer();
			if (player != null) {
				onPlayerJoin(player);
			} else {
				event.getRelevantPlayers().forEach(VFXLoaderEvents::sendSync);
			}
		});*/
		//?}
	}

	/**
	 * Sends the datapack definitions and curves to a joining player and re-applies that player's
	 * still-running effects once the client can resolve their ids.
	 *
	 * @param player the player that joined
	 */
	public static void onPlayerJoin(final ServerPlayer player) {
		sendSync(player);
		VFXServerEffects.get().applyTo(player);
	}

	/**
	 * Releases a disconnecting player's per-player state: scoreboard subscriptions and the
	 * recorded-effect registry.
	 *
	 * @param player the player that left
	 */
	public static void onPlayerDisconnect(final ServerPlayer player) {
		VFXScoreboardSync.onPlayerLeft(player);
		VFXServerEffects.get().remove(player);
	}

	/**
	 * Pushes the watched scoreboard values once per server tick.
	 *
	 * @param server the server that ticked
	 */
	public static void onServerTick(final MinecraftServer server) {
		VFXScoreboardSync.tick(server);
	}

	/**
	 * Logs the loader once per server session, for diagnostics.
	 *
	 * @param server the server that started
	 */
	public static void onServerStarted(final MinecraftServer server) {
		LOGGER.info("VFX Weaver server initialized on {}", VFXPlatform.name());
	}

	/**
	 * Drops the per-player scoreboard state on server shutdown.
	 *
	 * @param server the server that is stopping
	 */
	public static void onServerStopping(final MinecraftServer server) {
		VFXScoreboardSync.clear();
	}

	/**
	 * Registers the {@code /vfx} command tree.
	 *
	 * @param dispatcher   the command dispatcher
	 * @param buildContext the command build context
	 */
	public static void onRegisterCommands(final CommandDispatcher<CommandSourceStack> dispatcher, final CommandBuildContext buildContext) {
		VFXCommand.register(dispatcher, buildContext, Commands.CommandSelection.ALL);
	}

	/**
	 * Adds the datapack reload listeners. On Fabric they are registered eagerly from
	 * {@link #initCommon()}; on NeoForge this is invoked by {@code AddServerReloadListenersEvent}
	 * once the server asks for its listeners.
	 *
	 * @param reloadListener the event carrying the listener registry
	 */
	public static void onReload(final Object reloadListener) {
		//? if neoforge {
		/*		if (reloadListener instanceof AddServerReloadListenersEvent event) {
			event.addListener(id("vfx_definitions"), VFXDefinitionManager.get());
			event.addListener(id("vfx_curves"), VFXCurveManager.get());
			event.addListener(id("vfx_particles"), VFXBlockParticleManager.get());
		}*/
		//?}
	}

	/**
	 * Sends the datapack definitions and curves to one player.
	 *
	 * @param player the receiving player
	 */
	private static void sendSync(final ServerPlayer player) {
		VFXNetwork.sendToPlayer(player, new VFXSyncPayload(
			VFXSyncPayload.PROTOCOL_VERSION,
			new HashMap<>(VFXDefinitionManager.get().getRawDefinitions()),
			new HashMap<>(VFXCurveManager.get().getRawCurves())
		));
	}

	/**
	 * Sends the datapack definitions and curves to every player currently on the server.
	 *
	 * @param server the server to enumerate
	 */
	private static void syncAll(final MinecraftServer server) {
		for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
			sendSync(player);
		}
	}

	/**
	 * @param path the identifier path under the mod namespace
	 * @return the identifier
	 */
	private static Identifier id(final String path) {
		return Identifier.fromNamespaceAndPath("vfxweaver", path);
	}

	//? if fabric {
	private static void registerVfxDefinitionReloadListener() {
		try {
			//? if <26.1 {
			/*ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(VFXDefinitionManager.get());
			*///?} else {
			ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(id("vfx_definitions"), VFXDefinitionManager.get());
			//?}
		} catch (RuntimeException e) {
			LOGGER.warn("Could not register VFX definition reload listener", e);
		}
	}

	private static void registerVfxCurveReloadListener() {
		try {
			//? if <26.1 {
			/*ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(VFXCurveManager.get());
			*///?} else {
			ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(id("vfx_curves"), VFXCurveManager.get());
			//?}
		} catch (RuntimeException e) {
			LOGGER.warn("Could not register VFX curve reload listener", e);
		}
	}

	private static void registerVfxBlockParticleReloadListener() {
		try {
			//? if <26.1 {
			/*ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(VFXBlockParticleManager.get());
			*///?} else {
			ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(id("vfx_particles"), VFXBlockParticleManager.get());
			//?}
		} catch (RuntimeException e) {
			LOGGER.warn("Could not register VFX block-particle reload listener", e);
		}
	}
	//?}

	//? if neoforge {
	/*// Registers the param_map command argument type on the mod bus (NeoForge has no eager
	// ArgumentTypeRegistry counterpart).
	public static void onRegisterArgumentType(final RegisterEvent event) {
		event.register(Registries.COMMAND_ARGUMENT_TYPE, id("param_map"),
			() -> ArgumentTypeInfos.registerByClass(ParamMapArgument.class, SingletonArgumentInfo.contextFree(ParamMapArgument::new)));
	}
	*///?}
}

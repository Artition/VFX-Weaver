package dev.vfxweaver.client.platform;

import dev.vfxweaver.client.config.VFXSettingsScreen;
//? if neoforge {
/*import net.minecraft.commands.Commands;
*///?}
//? if fabric {
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
//? if <26.1 {
/*import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
*///?} else {
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
//?}
//?} else {
/*import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;*/
//?}

/**
 * Registers the client-only {@code /vfxconfig} command, which opens the settings screen
 * ({@link VFXSettingsScreen}). The command never reaches the server: it is dispatched locally, so
 * it needs no operator rights and works while connected to any server, including a dedicated one.
 *
 * <p>The root is {@code vfxconfig}, deliberately not {@code vfx}: a client command registered
 * under the server's root literal would shadow the whole {@code /vfx} tree on this client, hiding
 * {@code /vfx play}, {@code /vfx stop} and the rest.
 */
public final class VFXClientCommands {
	private static final String ROOT = "vfxconfig";

	private VFXClientCommands() {
	}

	/**
	 * Registers the command on the loader's client command event. Called once from the client
	 * entry point.
	 */
	public static void register() {
		//? if fabric {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) ->
			dispatcher.register(
				//? if <26.1 {
				/*ClientCommandManager.literal(ROOT)
				*///?} else {
				ClientCommands.literal(ROOT)
				//?}
				.executes(context -> openSettings())
			)
		);
		//?} else {
		/*NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) ->
			event.getDispatcher().register(Commands.literal(ROOT).executes(context -> openSettings()))
		);*/
		//?}
	}

	/** Opens the settings screen on the client thread. */
	private static int openSettings() {
		VFXSettingsScreen.open();
		return 1;
	}
}

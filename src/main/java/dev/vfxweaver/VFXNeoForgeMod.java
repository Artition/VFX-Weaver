//? if neoforge {
/*package dev.vfxweaver;

import dev.vfxweaver.platform.VFXLoaderEvents;
import dev.vfxweaver.platform.VFXNetwork;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

// NeoForge entry point. The whole file is Stonecutter-guarded so it only exists on the NeoForge
// node; Fabric uses VFXMod.
@Mod(value = "vfxweaver")
public final class VFXNeoForgeMod {
	public VFXNeoForgeMod(final IEventBus modBus) {
		// Mod-bus events are subscribed here because only the entry constructor receives the bus;
		// the game-bus events are subscribed from VFXLoaderEvents.initCommon().
		modBus.addListener(VFXNetwork::onRegisterPayloadHandlers);
		modBus.addListener(VFXLoaderEvents::onRegisterArgumentType);
		VFXMod.init();
	}
}
*/
//?}

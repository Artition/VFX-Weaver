package dev.vfxweaver;

import dev.vfxweaver.platform.VFXLoaderEvents;
//? if fabric {
import net.fabricmc.api.ModInitializer;
//?}

//? if fabric {
public final class VFXMod implements ModInitializer {
	@Override
	public void onInitialize() {
		VFXLoaderEvents.initCommon();
	}
}
//?} else {
/*public final class VFXMod {
	public static void init() {
		VFXLoaderEvents.initCommon();
	}
}*/
//?}

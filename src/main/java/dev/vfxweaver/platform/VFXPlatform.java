package dev.vfxweaver.platform;

//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?} else {
/*import net.neoforged.fml.ModList;*/
//?}

/**
 * Loader queries used by the shared code. Every method body is guarded by Stonecutter so the
 * shared sources never import a loader API outside this package.
 */
public final class VFXPlatform {
	private VFXPlatform() {
	}

	/**
	 * @param modId the mod id to look for
	 * @return whether a mod with that id is loaded
	 */
	public static boolean isModLoaded(final String modId) {
		//? if fabric {
		return FabricLoader.getInstance().isModLoaded(modId);
		//?} else {
		/*return ModList.get().isLoaded(modId);*/
		//?}
	}

	/**
	 * @return the loader name, for logs and diagnostics
	 */
	public static String name() {
		//? if fabric {
		return "fabric";
		//?} else {
		/*return "neoforge";*/
		//?}
	}
}

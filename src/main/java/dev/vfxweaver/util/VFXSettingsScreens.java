package dev.vfxweaver.util;

import org.jspecify.annotations.Nullable;

/**
 * Main-side bridge that lets {@code /vfx config} open the client settings screen without the common
 * source ever naming a client class (mirroring {@code VFXLocalDispatcher}).
 *
 * <p>The client entrypoint sets the opener once at init; on a dedicated server it stays {@code null}
 * and the command reports that the screen is client-only.
 */
public final class VFXSettingsScreens {
	private static @Nullable Runnable opener;

	private VFXSettingsScreens() {
	}

	/**
	 * @param opener the client opener, or {@code null} on a dedicated server
	 */
	public static void setOpener(final @Nullable Runnable opener) {
		VFXSettingsScreens.opener = opener;
	}

	/**
	 * Opens the settings screen on the client, if one is present.
	 *
	 * @return {@code false} when no client registered an opener (a dedicated server)
	 */
	public static boolean open() {
		final Runnable current = opener;
		if (current == null) {
			return false;
		}
		current.run();
		return true;
	}
}

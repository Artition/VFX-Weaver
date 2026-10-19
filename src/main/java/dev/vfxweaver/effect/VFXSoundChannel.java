package dev.vfxweaver.effect;

import net.minecraft.sounds.SoundSource;

/**
 * The {@code volume_duck} channel table: the names the {@code channel} param accepts next to
 * the historical {@code 0..10} ordinals.
 *
 * <p>Names resolve through {@link SoundSource#valueOf(String)} (case-insensitive), so they
 * track the vanilla enum whatever its order on this node; numbers keep indexing
 * {@link SoundSource#values()} exactly as before.
 */
public final class VFXSoundChannel {
	private VFXSoundChannel() {
	}

	/**
	 * Maps a channel name ({@code master}, {@code music}, {@code records}, {@code weather},
	 * {@code blocks}, {@code hostile}, {@code neutral}, {@code players}, {@code ambient},
	 * {@code voice}, {@code ui}) to its {@link SoundSource} ordinal as a float.
	 * Case-insensitive; surrounding whitespace is ignored.
	 *
	 * @param name the authored name (may be null)
	 * @return the ordinal, or {@link Float#NaN} when the name matches no category (callers
	 *         turn that into a warned-once no-op, never a failed load)
	 */
	public static float code(final String name) {
		if (name == null) {
			return Float.NaN;
		}
		final String wanted = name.trim();
		for (final SoundSource category : SoundSource.values()) {
			if (category.name().equalsIgnoreCase(wanted)) {
				return (float) category.ordinal();
			}
		}
		return Float.NaN;
	}
}

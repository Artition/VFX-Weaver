package dev.vfxweaver.client.editor;

import org.jspecify.annotations.Nullable;

/**
 * The game-side operations the visual editor drives.
 *
 * <p>Implemented on the client (by {@code MinecraftEditorGame}); deliberately free of Minecraft and
 * loader types so an {@link EditorSession} can be exercised on a plain JVM. All string ids are raw
 * effect ids of the form {@code <namespace>:<name>}.
 */
public interface EditorGame {
	/**
	 * Register {@code json} as the local definition of {@code effectId} and play it.
	 *
	 * @param transport the requested transport, {@code "play"} or {@code "stop"}
	 * @return the play instance id, or {@code 0} when nothing was played
	 */
	long applyAndPlay(String effectId, String json, String transport);

	/**
	 * Stop a playing effect.
	 *
	 * @return {@code true} if an effect was stopped
	 */
	boolean stop(String effectId);

	/**
	 * Set one parameter on a live effect.
	 *
	 * @return {@code true} if the effect accepted the edit
	 */
	boolean setParam(String effectId, String name, float value);

	/**
	 * Move/add one keyframe on a live effect.
	 *
	 * @return {@code true} if the effect accepted the edit
	 */
	boolean setKeyframe(String effectId, String name, int time, float value);

	/**
	 * Re-play a live effect at a tick offset.
	 *
	 * @return {@code true} if the effect was seeked
	 */
	boolean seek(String effectId, int elapsedTicks);

	/**
	 * The client's {@code saves} directory, so the bridge can list world saves to export into. A
	 * default keeps the interface backward compatible for any test double.
	 *
	 * @return the absolute saves directory, or {@code null} when it is unknown
	 */
	default @Nullable String savesDir() {
		return null;
	}
}

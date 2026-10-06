package dev.vfxweaver.client.editor;

import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.jspecify.annotations.Nullable;

/**
 * Owns the editor's monotonic revision and gates edits on it.
 *
 * <p>JSON is validated with Gson before the game is touched, so a malformed definition never reaches
 * {@link EditorGame}. A full compile is authoritative and always bumps the revision; hot edits
 * ({@link #setParam}/{@link #setKeyframe}) apply only when they carry the current revision, so an
 * out-of-order request cannot corrupt a newer state. No Minecraft or loader types are referenced, so
 * this class runs on a plain JVM.
 */
public final class EditorSession {
	/** Effect id used by the convenience {@link #compile(String, Long)} overload. */
	private static final String PREVIEW_ID = "vfxweaver:editor_preview";
	/** Transport requested by the convenience {@link #compile(String, Long)} overload. */
	private static final String TRANSPORT_PLAY = "play";

	private final EditorGame game;
	private long revision;

	/**
	 * @param game the game-side bridge all requests are forwarded to
	 */
	public EditorSession(final EditorGame game) {
		this.game = game;
	}

	/**
	 * @return the current revision, {@code 0} until the first successful compile
	 */
	public long revision() {
		return this.revision;
	}

	/**
	 * Compile and play a full definition under the editor's default preview id.
	 *
	 * @param json     the full VFX definition JSON
	 * @param revision the client's last-seen revision, or {@code null} (a full compile is
	 *                 authoritative and is not gated)
	 * @return the outcome, carrying the new revision on success
	 */
	public EditorResult compile(final String json, final @Nullable Long revision) {
		return compile(PREVIEW_ID, json, TRANSPORT_PLAY, revision);
	}

	/**
	 * Compile and play a full definition for one effect id. This is the form the {@code /compile}
	 * route uses, which supplies the effect id and transport.
	 *
	 * @param effectId  the raw effect id ({@code <namespace>:<name>})
	 * @param json      the full VFX definition JSON
	 * @param transport {@code "play"} or {@code "stop"}
	 * @param revision  the client's last-seen revision, or {@code null}
	 * @return the outcome, carrying the new revision on success; {@code game_rejected} when a
	 *         {@code "play"} compile produced no play instance (e.g. an unknown effect type)
	 */
	public EditorResult compile(final String effectId, final String json, final String transport, final @Nullable Long revision) {
		try {
			JsonParser.parseString(json);
		} catch (final JsonSyntaxException | NullPointerException e) {
			return new EditorResult(false, this.revision, EditorResult.INVALID_JSON, e.getMessage());
		}
		final long instanceId = this.game.applyAndPlay(effectId, json, transport);
		if (instanceId == 0L && "play".equalsIgnoreCase(transport)) {
			// A "stop" compile legitimately plays nothing; a "play" that produced no instance did not
			// apply (unknown type, empty graph, ...) and must not be reported as success.
			return new EditorResult(false, this.revision, EditorResult.GAME_REJECTED,
					"definition for " + effectId + " produced no play instance");
		}
		final long next = ++this.revision;
		return new EditorResult(true, next, null, null);
	}

	/**
	 * Apply a live parameter edit, gated on the revision.
	 *
	 * @param effectId the raw effect id
	 * @param name     the parameter name
	 * @param value    the new value
	 * @param revision the revision this edit is based on
	 * @return the outcome; {@code stale_revision} when the revision is not current
	 */
	public EditorResult setParam(final String effectId, final String name, final float value, final long revision) {
		if (revision != this.revision) {
			return stale(revision);
		}
		final boolean ok = this.game.setParam(effectId, name, value);
		return result(ok, "setParam");
	}

	/**
	 * Apply a live keyframe edit, gated on the revision.
	 *
	 * @param effectId the raw effect id
	 * @param name     the parameter name
	 * @param time     the keyframe time in ticks
	 * @param value    the keyframe value
	 * @param revision the revision this edit is based on
	 * @return the outcome; {@code stale_revision} when the revision is not current
	 */
	public EditorResult setKeyframe(final String effectId, final String name, final int time, final float value, final long revision) {
		if (revision != this.revision) {
			return stale(revision);
		}
		final boolean ok = this.game.setKeyframe(effectId, name, time, value);
		return result(ok, "setKeyframe");
	}

	/**
	 * Stop a live effect. Needed by the {@code /transport} route's {@code stop} action.
	 *
	 * @param effectId the raw effect id
	 * @return the outcome; {@code unknown_effect} when no live effect with that id was playing
	 */
	public EditorResult stop(final String effectId) {
		if (this.game.stop(effectId)) {
			return new EditorResult(true, this.revision, null, null);
		}
		return new EditorResult(false, this.revision, EditorResult.UNKNOWN_EFFECT,
				"no playing effect " + effectId);
	}

	/**
	 * Re-play a live effect at a tick offset. Needed by the {@code /transport} route's {@code seek}
	 * action.
	 *
	 * @param effectId     the raw effect id
	 * @param elapsedTicks the tick offset to seek to
	 * @return the outcome; {@code unknown_effect} when no live effect with that id was seeked
	 */
	public EditorResult seek(final String effectId, final int elapsedTicks) {
		if (this.game.seek(effectId, elapsedTicks)) {
			return new EditorResult(true, this.revision, null, null);
		}
		return new EditorResult(false, this.revision, EditorResult.UNKNOWN_EFFECT,
				"no live effect " + effectId);
	}

	private EditorResult stale(final long revision) {
		return new EditorResult(false, this.revision, EditorResult.STALE_REVISION,
				"expected revision " + this.revision + ", got " + revision);
	}

	private EditorResult result(final boolean ok, final String action) {
		if (ok) {
			return new EditorResult(true, this.revision, null, null);
		}
		return new EditorResult(false, this.revision, EditorResult.GAME_REJECTED, action + " rejected by the game");
	}
}

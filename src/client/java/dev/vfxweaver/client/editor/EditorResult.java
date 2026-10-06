package dev.vfxweaver.client.editor;

import org.jspecify.annotations.Nullable;

/**
 * Outcome of an {@link EditorSession} request.
 *
 * @param ok       whether the request was applied
 * @param revision the session revision after the request
 * @param error    a stable error code (one of the constants below), or {@code null} on success
 * @param detail   a human-readable explanation, or {@code null}
 */
public record EditorResult(boolean ok, long revision, @Nullable String error, @Nullable String detail) {
	/** The request body was not valid JSON. */
	public static final String INVALID_JSON = "invalid_json";
	/** The request carried a revision older than the current one. */
	public static final String STALE_REVISION = "stale_revision";
	/** The referenced effect id is unknown. */
	public static final String UNKNOWN_EFFECT = "unknown_effect";
	/** The request was structurally invalid. */
	public static final String BAD_REQUEST = "bad_request";
	/** The game side refused the request. */
	public static final String GAME_REJECTED = "game_rejected";
}

package dev.vfxweaver.client.editor;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Holds freshly built export zips until the browser downloads them (staged browser download).
 *
 * <p>The web UI cannot POST a body while navigating to a download URL, so {@code POST /export/zip}
 * stages the bytes here and returns an opaque one-shot {@code id}; the browser then navigates to
 * {@code GET /export/download?id=<id>} which {@link #take takes} the entry and streams it.
 *
 * <p>Bounded so a repeat-exporter cannot leak memory: at most {@value #MAX_ENTRIES} live entries,
 * {@value #MAX_TOTAL_BYTES} bytes total, and each entry expires after {@value #TTL_MS} ms. Pure JDK —
 * no Minecraft or loader types.
 */
public final class EditorExport {
	/** Maximum number of staged zips retained at once. */
	private static final int MAX_ENTRIES = 8;
	/** Maximum total staged bytes; the oldest entries are evicted first. */
	private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
	/** How long an unclaimed entry stays downloadable, in milliseconds. */
	private static final long TTL_MS = 5L * 60 * 1000;

	private final Map<String, Staged> byId = new LinkedHashMap<>();
	private final SecureRandom random = new SecureRandom();
	private long totalBytes;

	/**
	 * Stage one zip for download.
	 *
	 * @param zip      the archive bytes
	 * @param filename the suggested download filename
	 * @return the opaque id to pass back to {@code GET /export/download}
	 */
	public synchronized String stage(final byte[] zip, final String filename) {
		sweep();
		while (!this.byId.isEmpty()
				&& (this.byId.size() >= MAX_ENTRIES || this.totalBytes + zip.length > MAX_TOTAL_BYTES)) {
			evictOldest();
		}
		final String id = newId();
		this.byId.put(id, new Staged(zip, filename, System.currentTimeMillis() + TTL_MS));
		this.totalBytes += zip.length;
		return id;
	}

	/**
	 * Claim and remove a staged zip. One-shot: a second request for the same id finds nothing.
	 *
	 * @param id the id returned by {@link #stage}
	 * @return the staged zip, or {@code null} when the id is unknown or expired
	 */
	public synchronized @Nullable Staged take(final @Nullable String id) {
		if (id == null) {
			return null;
		}
		sweep();
		final Staged staged = this.byId.remove(id);
		if (staged != null) {
			this.totalBytes -= staged.bytes().length;
		}
		return staged;
	}

	/** Drop entries whose TTL has elapsed; keeps the map and byte count truthful. */
	private void sweep() {
		final long now = System.currentTimeMillis();
		final Iterator<Map.Entry<String, Staged>> it = this.byId.entrySet().iterator();
		while (it.hasNext()) {
			final Staged staged = it.next().getValue();
			if (staged.expiresAtMs() <= now) {
				this.totalBytes -= staged.bytes().length;
				it.remove();
			}
		}
	}

	/** Remove the oldest staged entry (caller holds the monitor). */
	private void evictOldest() {
		final Iterator<Map.Entry<String, Staged>> it = this.byId.entrySet().iterator();
		if (it.hasNext()) {
			this.totalBytes -= it.next().getValue().bytes().length;
			it.remove();
		}
	}

	private String newId() {
		final byte[] bytes = new byte[16];
		this.random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/**
	 * One staged archive.
	 *
	 * @param bytes       the zip bytes
	 * @param filename    the suggested download filename
	 * @param expiresAtMs epoch-millis after which the entry is dropped
	 */
	public record Staged(byte[] bytes, String filename, long expiresAtMs) {
	}
}

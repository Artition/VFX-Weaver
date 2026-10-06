package dev.vfxweaver.client.editor;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server-sent-events client hub for the editor bridge.
 *
 * <p>Each {@code GET /events} connection registers its response stream with {@link #add}; the game
 * side pushes frames with {@link #broadcast}. The hub is thread-safe: the client list is a
 * {@link CopyOnWriteArrayList} and each client's stream is written under its own monitor, so a
 * failing write only drops that one client and never propagates to the caller. A daemon reaper
 * closes streams that have seen no traffic for {@value #IDLE_TIMEOUT_MS} ms. JDK only — no Minecraft
 * or loader types, so it runs on a plain JVM.
 */
public final class EditorSse {
	/** Close a connection that has received nothing for this long, in milliseconds. */
	private static final long IDLE_TIMEOUT_MS = 30_000L;
	/** How often the reaper scans for idle connections, in milliseconds. */
	private static final long REAP_INTERVAL_MS = 1_000L;

	private static final CopyOnWriteArrayList<Client> CLIENTS = new CopyOnWriteArrayList<>();
	private static final AtomicInteger NEXT_HANDLE = new AtomicInteger();
	private static final AtomicBoolean REAPER_STARTED = new AtomicBoolean();

	private EditorSse() {
	}

	/**
	 * Register a new SSE connection.
	 *
	 * @param out the response stream frames are written to
	 * @return a handle that can be passed to {@link #remove} or {@link #awaitRemoval}
	 */
	public static int add(final OutputStream out) {
		startReaper();
		final Client client = new Client(NEXT_HANDLE.incrementAndGet(), out);
		CLIENTS.add(client);
		return client.handle;
	}

	/**
	 * Drop a connection by handle. Idempotent and safe for an unknown handle.
	 *
	 * @param handle the handle returned by {@link #add}
	 */
	public static void remove(final int handle) {
		for (final Client client : CLIENTS) {
			if (client.handle == handle) {
				close(client);
				return;
			}
		}
	}

	/**
	 * Push one frame to every live connection: {@code data: {"event":...,"data":...}\n\n}.
	 *
	 * <p>A client whose write throws is removed and closed; the remaining clients still receive the
	 * frame.
	 *
	 * @param event the event name (a short string, escaped for JSON)
	 * @param json  the raw JSON value of the {@code data} field (embedded verbatim)
	 */
	public static void broadcast(final String event, final String json) {
		final byte[] frame = frame(event, json);
		for (final Client client : CLIENTS) {
			write(client, frame);
		}
	}

	/**
	 * Block the calling thread until the given connection is closed by a failed write, the idle
	 * reaper, or {@link #remove}. Used by the {@code /events} handler to keep the exchange open.
	 *
	 * @param handle the handle returned by {@link #add}
	 */
	public static void awaitRemoval(final int handle) {
		for (final Client client : CLIENTS) {
			if (client.handle == handle) {
				synchronized (client) {
					while (!client.closed) {
						try {
							client.wait();
						} catch (final InterruptedException e) {
							Thread.currentThread().interrupt();
							return;
						}
					}
				}
				return;
			}
		}
	}

	private static void write(final Client client, final byte[] frame) {
		synchronized (client) {
			if (client.closed) {
				return;
			}
			try {
				client.out.write(frame);
				client.out.flush();
				client.lastWriteMs = System.currentTimeMillis();
			} catch (final IOException e) {
				close(client);
			}
		}
	}

	private static void close(final Client client) {
		synchronized (client) {
			if (client.closed) {
				return;
			}
			client.closed = true;
			try {
				client.out.close();
			} catch (final IOException ignored) {
				// the peer is gone; nothing to report
			}
			client.notifyAll();
		}
		CLIENTS.remove(client);
	}

	private static void startReaper() {
		if (!REAPER_STARTED.compareAndSet(false, true)) {
			return;
		}
		final Thread reaper = new Thread(EditorSse::reap, "vfx-editor-sse-reaper");
		reaper.setDaemon(true);
		reaper.start();
	}

	private static void reap() {
		while (true) {
			try {
				Thread.sleep(REAP_INTERVAL_MS);
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			final long now = System.currentTimeMillis();
			for (final Client client : CLIENTS) {
				if (now - client.lastWriteMs > IDLE_TIMEOUT_MS) {
					close(client);
				}
			}
		}
	}

	private static byte[] frame(final String event, final String json) {
		final String frame = "data: {\"event\":\"" + escape(event) + "\",\"data\":" + json + "}\n\n";
		return frame.getBytes(StandardCharsets.UTF_8);
	}

	private static String escape(final String value) {
		final StringBuilder out = new StringBuilder(value.length() + 8);
		for (int i = 0; i < value.length(); i++) {
			final char c = value.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				default -> out.append(c);
			}
		}
		return out.toString();
	}

	/** One connected client. {@code closed}, {@code lastWriteMs} and the stream are guarded by this. */
	private static final class Client {
		private final int handle;
		private final OutputStream out;
		private long lastWriteMs;
		private boolean closed;

		private Client(final int handle, final OutputStream out) {
			this.handle = handle;
			this.out = out;
			this.lastWriteMs = System.currentTimeMillis();
		}
	}
}

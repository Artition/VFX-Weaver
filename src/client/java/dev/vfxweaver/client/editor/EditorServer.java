package dev.vfxweaver.client.editor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.vfxweaver.editor.VFXEffectSchema;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * Local HTTP bridge between the browser editor and the running game.
 *
 * <p>Binds {@code 127.0.0.1} on a random free port, gates every request on a {@code SecureRandom}
 * 128-bit URL-safe token, serves the static UI and (in later tasks) the REST/SSE endpoints. A simple
 * fixed-window limiter caps POST storms. JDK only — no Minecraft or loader types.
 */
public final class EditorServer {
	/** Returned for {@code GET /} until Task 12 ships the real {@code index.html}. */
	private static final String PLACEHOLDER_HTML =
			"<html><body>VFXweaver editor assets not built</body></html>";
	/** Upper bound on rate-limit buckets, so a hostile local process cannot grow the map. */
	private static final int MAX_RATE_CLIENTS = 64;
	/** Fixed rate-limit window, in milliseconds. */
	private static final long RATE_WINDOW_MS = 1000L;

	private final HttpServer server;
	private final EditorSession session;
	private final String token;
	private final int port;
	private final int rateLimitPerSec;
	private final ExecutorService executor;
	private final Map<String, Window> windows = new ConcurrentHashMap<>();

	private EditorServer(final HttpServer server, final EditorSession session, final String token,
			final int rateLimitPerSec, final ExecutorService executor) {
		this.server = server;
		this.session = session;
		this.token = token;
		this.rateLimitPerSec = rateLimitPerSec;
		this.executor = executor;
		this.port = server.getAddress().getPort();
	}

	/**
	 * Start the bridge on a random loopback port.
	 *
	 * @param session        the session requests are delegated to
	 * @param rateLimitPerSec maximum POSTs per second per client ({@code <= 0} disables the limit)
	 * @return the running server
	 */
	public static EditorServer start(final EditorSession session, final int rateLimitPerSec) {
		final String token = newToken();
		// A cached pool, not a fixed one: a long-lived SSE handler holds its thread for the life of
		// the connection (com.sun.net.httpserver closes the exchange when the handler returns, so
		// SSE cannot be handed off asynchronously), and a fixed request pool would be exhausted by a
		// few open /events tabs. Idle threads are reclaimed after 60 s, so reconnects do not leak.
		final ExecutorService executor = Executors.newCachedThreadPool(daemonThreads("vfx-editor"));
		try {
			final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			final EditorServer editor = new EditorServer(server, session, token, rateLimitPerSec, executor);
			server.createContext("/", editor::handle);
			server.setExecutor(executor);
			server.start();
			return editor;
		} catch (final IOException e) {
			executor.shutdownNow();
			throw new IllegalStateException("failed to start the editor server", e);
		}
	}

	private static ThreadFactory daemonThreads(final String prefix) {
		final AtomicInteger counter = new AtomicInteger();
		return runnable -> {
			final Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}

	/**
	 * @return the bound port
	 */
	public int port() {
		return this.port;
	}

	/**
	 * @return the URL the browser should open, token included
	 */
	public String url() {
		return "http://127.0.0.1:" + this.port + "/?token=" + this.token;
	}

	/**
	 * @return the session token
	 */
	public String token() {
		return this.token;
	}

	/**
	 * Stop accepting requests and release the port. Idempotent.
	 */
	public void stop() {
		this.server.stop(0);
		this.executor.shutdownNow();
		this.windows.clear();
	}

	private void handle(final HttpExchange exchange) {
		try {
			final String method = exchange.getRequestMethod();
			if (!"GET".equals(method) && !"POST".equals(method)) {
				respondText(exchange, 405, "method not allowed");
				return;
			}
			if (!tokenMatches(exchange)) {
				respondText(exchange, 401, "unauthorized");
				return;
			}
			if ("POST".equals(method)) {
				if (!originAllowed(exchange)) {
					respondText(exchange, 403, "forbidden origin");
					return;
				}
				if (rateLimited(exchange)) {
					respondText(exchange, 429, "rate limit exceeded");
					return;
				}
			}
			route(exchange, method);
		} catch (final Throwable t) {
			respondQuietly(exchange, 500, "internal error");
		} finally {
			exchange.close();
		}
	}

	private void route(final HttpExchange exchange, final String method) throws IOException {
		final String path = exchange.getRequestURI().getPath();
		if (!"GET".equals(method)) {
			// POST routes are added by Task 8; until then every POST is unknown.
			respondText(exchange, 404, "not found");
			return;
		}
		if (path.equals("/events")) {
			serveEvents(exchange);
			return;
		}
		if (path.equals("/schema")) {
			respond(exchange, 200, "application/json",
					VFXEffectSchema.toJson().getBytes(StandardCharsets.UTF_8));
			return;
		}
		if (path.equals("/") || path.startsWith("/assets/")) {
			final byte[] body = EditorAssets.resolve(path);
			if (body != null) {
				respond(exchange, 200, EditorAssets.contentType(path), body);
				return;
			}
			if (path.equals("/")) {
				// R9: only when the classpath asset is genuinely absent.
				respond(exchange, 200, "text/html; charset=utf-8", PLACEHOLDER_HTML.getBytes(StandardCharsets.UTF_8));
				return;
			}
		}
		respondText(exchange, 404, "not found");
	}

	/**
	 * Open a persistent SSE stream and block this handler thread until the hub drops the client (a
	 * failed write, the 30 s idle reaper, or server shutdown).
	 *
	 * @param exchange the {@code GET /events} exchange
	 * @throws IOException when the response headers cannot be sent
	 */
	private void serveEvents(final HttpExchange exchange) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-cache");
		exchange.getResponseHeaders().set("Connection", "keep-alive");
		exchange.sendResponseHeaders(200, 0);
		// Flush the response headers now: with chunked encoding HttpServer writes them lazily.
		final OutputStream out = exchange.getResponseBody();
		out.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
		out.flush();
		final int handle = EditorSse.add(out);
		try {
			EditorSse.awaitRemoval(handle);
		} finally {
			EditorSse.remove(handle);
		}
	}

	private boolean tokenMatches(final HttpExchange exchange) {
		final String presented = presentedToken(exchange);
		if (presented == null) {
			return false;
		}
		return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
				this.token.getBytes(StandardCharsets.UTF_8));
	}

	@Nullable
	private static String presentedToken(final HttpExchange exchange) {
		final String query = exchange.getRequestURI().getRawQuery();
		if (query != null) {
			for (final String pair : query.split("&")) {
				final int eq = pair.indexOf('=');
				if (eq > 0 && "token".equals(pair.substring(0, eq))) {
					return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
				}
			}
		}
		final String auth = exchange.getRequestHeaders().getFirst("Authorization");
		if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
			return auth.substring("Bearer ".length()).trim();
		}
		return null;
	}

	private boolean originAllowed(final HttpExchange exchange) {
		final String origin = exchange.getRequestHeaders().getFirst("Origin");
		if (origin != null) {
			return allowedOrigins().contains(origin);
		}
		final String referer = exchange.getRequestHeaders().getFirst("Referer");
		if (referer != null) {
			return allowedOrigins().contains(originOf(referer));
		}
		final String host = exchange.getRequestHeaders().getFirst("Host");
		return allowedHosts().contains(host);
	}

	private Set<String> allowedOrigins() {
		return Set.of("http://127.0.0.1:" + this.port, "http://localhost:" + this.port);
	}

	private Set<String> allowedHosts() {
		return Set.of("127.0.0.1:" + this.port, "localhost:" + this.port);
	}

	private static String originOf(final String url) {
		final int scheme = url.indexOf("://");
		if (scheme < 0) {
			return url;
		}
		final int slash = url.indexOf('/', scheme + 3);
		return slash < 0 ? url : url.substring(0, slash);
	}

	private boolean rateLimited(final HttpExchange exchange) {
		if (this.rateLimitPerSec <= 0) {
			return false;
		}
		final String client = clientKey(exchange);
		if (this.windows.size() >= MAX_RATE_CLIENTS && !this.windows.containsKey(client)) {
			this.windows.clear();
		}
		final long now = System.currentTimeMillis();
		final Window window = this.windows.computeIfAbsent(client, k -> new Window(now));
		synchronized (window) {
			if (now - window.startMs >= RATE_WINDOW_MS) {
				window.startMs = now;
				window.count = 0;
			}
			window.count++;
			return window.count > this.rateLimitPerSec;
		}
	}

	private static String clientKey(final HttpExchange exchange) {
		final InetSocketAddress remote = exchange.getRemoteAddress();
		return remote == null ? "?" : remote.getAddress().getHostAddress();
	}

	private static String newToken() {
		final byte[] bytes = new byte[16];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private void respondText(final HttpExchange exchange, final int status, final String body) throws IOException {
		respond(exchange, status, "text/plain; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
	}

	private void respond(final HttpExchange exchange, final int status, final String contentType, final byte[] body)
			throws IOException {
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	private void respondQuietly(final HttpExchange exchange, final int status, final String body) {
		try {
			if (exchange.getResponseCode() == -1) {
				respondText(exchange, status, body);
			}
		} catch (final IOException ignored) {
			// the response was already committed; nothing left to send
		}
	}

	/** One fixed-window counter per client. Guarded by {@code synchronized (this)}. */
	private static final class Window {
		private long startMs;
		private int count;

		private Window(final long startMs) {
			this.startMs = startMs;
		}
	}
}

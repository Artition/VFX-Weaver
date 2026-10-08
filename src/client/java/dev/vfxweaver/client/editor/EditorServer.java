package dev.vfxweaver.client.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.vfxweaver.editor.VFXEffectSchema;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
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
	/** Upper bound on a POST body, so a local client cannot exhaust memory. */
	private static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
	/** Upper bound on worlds listed by {@code GET /worlds}. */
	private static final int MAX_WORLDS = 256;
	/** Upper bound on datapacks listed by {@code GET /worlds/datapacks}. */
	private static final int MAX_DATAPACKS = 256;

	private final HttpServer server;
	private final EditorSession session;
	private final String token;
	private final int port;
	private final int rateLimitPerSec;
	private final ExecutorService executor;
	private final Map<String, Window> windows = new ConcurrentHashMap<>();
	/** Staged export zips for the one-shot browser download (bounded, TTL'd). */
	private final EditorExport exports = new EditorExport();

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
		if ("POST".equals(method)) {
			routePost(exchange, path);
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
		if (path.equals("/worlds")) {
			handleWorlds(exchange);
			return;
		}
		if (path.equals("/worlds/datapacks")) {
			handleWorldDatapacks(exchange);
			return;
		}
		if (path.equals("/export/download")) {
			handleExportDownload(exchange);
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
	 * Dispatch a token-gated {@code POST} to its action handler. Any unexpected runtime failure is
	 * reported as {@code bad_request} rather than propagated, so a handler can never throw out of the
	 * server.
	 *
	 * @param exchange the POST exchange
	 * @param path     the request path
	 * @throws IOException when the response cannot be sent
	 */
	private void routePost(final HttpExchange exchange, final String path) throws IOException {
		try {
			switch (path) {
				case "/compile" -> handleCompile(exchange);
				case "/transport" -> handleTransport(exchange);
				case "/set_param" -> handleSetParam(exchange);
				case "/set_keyframe" -> handleSetKeyframe(exchange);
				case "/import" -> handleImport(exchange);
				case "/export" -> handleExport(exchange);
				case "/export/zip" -> handleExportZip(exchange);
				case "/pick-folder" -> handlePickFolder(exchange);
				case "/preview/sync" -> handlePreviewSync(exchange);
				case "/preview/viewstate" -> handlePreviewViewstate(exchange);
				case "/preview/param" -> handlePreviewParam(exchange);
				case "/preview/transport" -> handlePreviewTransport(exchange);
				default -> respondText(exchange, 404, "not found");
			}
		} catch (final RuntimeException e) {
			try {
				respondResult(exchange, new EditorResult(false, this.session.revision(),
						EditorResult.BAD_REQUEST, "malformed request"));
			} catch (final IOException ignored) {
				// the response was already committed; nothing left to send
			}
		}
	}

	/**
	 * {@code POST /compile}: register and (optionally) play a full definition. On success the new
	 * revision is broadcast over SSE.
	 *
	 * @param exchange the {@code /compile} exchange
	 * @throws IOException when the response cannot be sent
	 */
	private void handleCompile(final HttpExchange exchange) throws IOException {
		final JsonObject body = readObject(exchange);
		if (body == null) {
			respondResult(exchange, new EditorResult(false, this.session.revision(),
					EditorResult.INVALID_JSON, "request body must be a JSON object"));
			return;
		}
		final String effectId = string(body, "effectId");
		final String json = string(body, "json");
		final String transport = string(body, "transport");
		if (effectId == null || effectId.isBlank() || json == null || transport == null) {
			respondResult(exchange, badRequest("effectId, json and transport are required"));
			return;
		}
		if (!"play".equals(transport) && !"stop".equals(transport)) {
			respondResult(exchange, badRequest("transport must be \"play\" or \"stop\""));
			return;
		}
		final EditorResult result = this.session.compile(effectId, json, transport, optionalLong(body, "revision"));
		mirror(result);
		respondResult(exchange, result);
	}

	/**
	 * {@code POST /transport}: live transport control. {@code play}/{@code pause} only advance the
	 * browser playhead locally in v1 (the game has no bare play/pause operation), so they are
	 * acknowledged; {@code stop}/{@code seek} delegate to the session.
	 *
	 * @param exchange the {@code /transport} exchange
	 * @throws IOException when the response cannot be sent
	 */
	private void handleTransport(final HttpExchange exchange) throws IOException {
		final JsonObject body = readObject(exchange);
		if (body == null) {
			respondResult(exchange, new EditorResult(false, this.session.revision(),
					EditorResult.INVALID_JSON, "request body must be a JSON object"));
			return;
		}
		final String effectId = string(body, "effectId");
		final String action = string(body, "action");
		if (effectId == null || effectId.isBlank() || action == null) {
			respondResult(exchange, badRequest("effectId and action are required"));
			return;
		}
		final EditorResult result;
		if ("stop".equals(action)) {
			result = this.session.stop(effectId);
		} else if ("seek".equals(action)) {
			result = this.session.seek(effectId, intOrDefault(body, "elapsedTicks", 0));
		} else if ("play".equals(action) || "pause".equals(action)) {
			result = new EditorResult(true, this.session.revision(), null, null);
		} else {
			respondResult(exchange, badRequest("action must be play, pause, stop or seek"));
			return;
		}
		mirror(result);
		respondResult(exchange, result);
	}

	/**
	 * {@code POST /set_param}: a revision-gated live parameter edit.
	 *
	 * @param exchange the {@code /set_param} exchange
	 * @throws IOException when the response cannot be sent
	 */
	private void handleSetParam(final HttpExchange exchange) throws IOException {
		final JsonObject body = readObject(exchange);
		if (body == null) {
			respondResult(exchange, new EditorResult(false, this.session.revision(),
					EditorResult.INVALID_JSON, "request body must be a JSON object"));
			return;
		}
		final String effectId = string(body, "effectId");
		final String name = string(body, "name");
		final Float value = floatOrNull(body, "value");
		final Long revision = optionalLong(body, "revision");
		if (effectId == null || effectId.isBlank() || name == null || name.isBlank()
				|| value == null || revision == null) {
			respondResult(exchange, badRequest("effectId, name, value and revision are required"));
			return;
		}
		final EditorResult result = this.session.setParam(effectId, name, value, revision);
		mirror(result);
		respondResult(exchange, result);
	}

	/**
	 * {@code POST /set_keyframe}: a revision-gated live keyframe edit.
	 *
	 * @param exchange the {@code /set_keyframe} exchange
	 * @throws IOException when the response cannot be sent
	 */
	private void handleSetKeyframe(final HttpExchange exchange) throws IOException {
		final JsonObject body = readObject(exchange);
		if (body == null) {
			respondResult(exchange, new EditorResult(false, this.session.revision(),
					EditorResult.INVALID_JSON, "request body must be a JSON object"));
			return;
		}
		final String effectId = string(body, "effectId");
		final String name = string(body, "name");
		final Integer time = intOrNull(body, "time");
		final Float value = floatOrNull(body, "value");
		final Long revision = optionalLong(body, "revision");
		if (effectId == null || effectId.isBlank() || name == null || name.isBlank()
				|| time == null || value == null || revision == null) {
			respondResult(exchange, badRequest("effectId, name, time, value and revision are required"));
			return;
		}
		final EditorResult result = this.session.setKeyframe(effectId, name, time, value, revision);
		mirror(result);
		respondResult(exchange, result);
	}

	/**
	 * {@code POST /import}: read a datapack folder or zip into raw effect strings. The effect JSON is
	 * never parsed here, so a corrupt entry is returned as-is rather than failing the import.
	 *
	 * @param exchange the {@code /import} exchange
	 * @throws IOException when the response cannot be sent
	 */
	private void handleImport(final HttpExchange exchange) throws IOException {
		final JsonObject body = readObject(exchange);
		if (body == null) {
			respondActionError(exchange, EditorResult.INVALID_JSON, "request body must be a JSON object");
			return;
		}
		final String path = string(body, "path");
		if (path == null || path.isBlank()) {
			respondActionError(exchange, EditorResult.BAD_REQUEST, "path is required");
			return;
		}
		try {
			final EditorPack.ImportedPack pack = EditorPack.read(Path.of(path));
			final JsonObject effects = new JsonObject();
			for (final Map.Entry<String, String> entry : pack.effectsJson().entrySet()) {
				effects.addProperty(entry.getKey(), entry.getValue());
			}
			final JsonObject out = new JsonObject();
			out.addProperty("ok", true);
			out.addProperty("namespace", pack.namespace());
			out.add("effects", effects);
			out.add("sidecar", pack.sidecarJson() == null ? JsonNull.INSTANCE : parseOrString(pack.sidecarJson()));
			respondJson(exchange, out);
		} catch (final RuntimeException | IOException e) {
			respondActionError(exchange, EditorResult.BAD_REQUEST, String.valueOf(e.getMessage()));
		}
	}

	/**
	 * {@code POST /export}: write the posted effects as a datapack, reusing {@link EditorPack}'s
	 * validated writers. {@code world} resolves {@code <path>/datapacks/<namespace>}; {@code folder}
	 * and {@code zip} treat {@code path} as the pack root / archive.
	 *
	 * @param exchange the {@code /export} exchange
	 * @throws IOException when the response cannot be sent
	 */
	private void handleExport(final HttpExchange exchange) throws IOException {
		final JsonObject body = readObject(exchange);
		if (body == null) {
			respondActionError(exchange, EditorResult.INVALID_JSON, "request body must be a JSON object");
			return;
		}
		final String destination = string(body, "destination");
		final String path = string(body, "path");
		final String namespace = string(body, "namespace");
		final JsonElement effectsElement = body.get("effects");
		if (destination == null || path == null || path.isBlank() || namespace == null
				|| effectsElement == null || !effectsElement.isJsonObject()) {
			respondActionError(exchange, EditorResult.BAD_REQUEST,
					"destination, path, namespace and effects are required");
			return;
		}
		final Path target = exportTarget(destination, path, namespace);
		if (target == null) {
			respondActionError(exchange, EditorResult.BAD_REQUEST, "destination must be world, folder or zip");
			return;
		}
		final Map<String, String> effects = effectsMap(effectsElement);
		try {
			if ("zip".equals(destination)) {
				EditorPack.writeZip(target, namespace, effects, sidecarString(body));
			} else {
				EditorPack.write(target, namespace, effects, sidecarString(body));
			}
			final JsonObject out = new JsonObject();
			out.addProperty("ok", true);
			out.addProperty("path", target.toString());
			respondJson(exchange, out);
		} catch (final RuntimeException | IOException e) {
			respondActionError(exchange, EditorResult.BAD_REQUEST, String.valueOf(e.getMessage()));
		}
	}

	/**
	 * {@code POST /export/zip}: build the posted effects into a pack zip entirely in memory and stage
	 * it for a one-shot browser download. The response is {@code {ok:true,id,filename,size}}.
	 *
	 * @param exchange the {@code /export/zip} exchange
	 * @throws IOException when the response cannot be sent
	 */
	private void handleExportZip(final HttpExchange exchange) throws IOException {
		final JsonObject body = readObject(exchange);
		if (body == null) {
			respondActionError(exchange, EditorResult.INVALID_JSON, "request body must be a JSON object");
			return;
		}
		final String namespace = string(body, "namespace");
		final JsonElement effectsElement = body.get("effects");
		if (namespace == null || namespace.isBlank() || effectsElement == null || !effectsElement.isJsonObject()) {
			respondActionError(exchange, EditorResult.BAD_REQUEST, "namespace and effects are required");
			return;
		}
		try {
			final byte[] zip = EditorPack.zipBytes(namespace, effectsMap(effectsElement), sidecarString(body));
			final String filename = namespace + ".zip";
			final String id = this.exports.stage(zip, filename);
			final JsonObject out = new JsonObject();
			out.addProperty("ok", true);
			out.addProperty("id", id);
			out.addProperty("filename", filename);
			out.addProperty("size", zip.length);
			respondJson(exchange, out);
		} catch (final RuntimeException | IOException e) {
			respondActionError(exchange, EditorResult.BAD_REQUEST, String.valueOf(e.getMessage()));
		}
	}

	/**
	 * {@code GET /export/download?id=...}: stream a staged zip with a native-save disposition. The
	 * entry is claimed (one-shot), so a repeat request 404s rather than keeping bytes alive.
	 *
	 * @param exchange the {@code /export/download} exchange
	 * @throws IOException when the response cannot be sent
	 */
	private void handleExportDownload(final HttpExchange exchange) throws IOException {
		final EditorExport.Staged staged = this.exports.take(queryParam(exchange, "id"));
		if (staged == null) {
			respondText(exchange, 404, "not found");
			return;
		}
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.getResponseHeaders().set("Content-Disposition",
				"attachment; filename=\"" + staged.filename() + "\"");
		respond(exchange, 200, "application/zip", staged.bytes());
	}

	/**
	 * {@code POST /pick-folder}: native directory chooser. The dialog is created on the AWT event
	 * dispatch thread via {@link SwingUtilities#invokeLater}; this HTTP pool thread only waits on a
	 * latch, so the Minecraft render thread is never touched. Headless JVMs report
	 * {@code {ok:false,error:"unsupported"}} and the web falls back to a manual path.
	 *
	 * @param exchange the {@code /pick-folder} exchange
	 * @throws IOException when the response cannot be sent
	 */
	private void handlePickFolder(final HttpExchange exchange) throws IOException {
		// The body is optional; drain it so the connection is reusable.
		readObject(exchange);
		if (GraphicsEnvironment.isHeadless()) {
			respondActionError(exchange, "unsupported", "no display available (headless)");
			return;
		}
		final AtomicReference<String> chosen = new AtomicReference<>();
		final CountDownLatch done = new CountDownLatch(1);
		try {
			SwingUtilities.invokeLater(() -> {
				try {
					final JFileChooser chooser = new JFileChooser();
					chooser.setDialogTitle("Choose an export folder");
					chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
					chooser.setAcceptAllFileFilterUsed(false);
					if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION
							&& chooser.getSelectedFile() != null) {
						chosen.set(chooser.getSelectedFile().getAbsolutePath());
					}
				} finally {
					done.countDown();
				}
			});
		} catch (final RuntimeException e) {
			respondActionError(exchange, "unsupported", String.valueOf(e.getMessage()));
			return;
		}
		boolean finished;
		try {
			finished = done.await(5, TimeUnit.MINUTES);
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			respondActionError(exchange, "timeout", "interrupted while waiting for the picker");
			return;
		}
		if (!finished) {
			respondActionError(exchange, "timeout", "the folder picker did not close");
			return;
		}
		final String path = chosen.get();
		final JsonObject out = new JsonObject();
		out.addProperty("ok", path != null);
		if (path != null) {
			out.addProperty("path", path);
		} else {
			out.addProperty("error", "cancelled");
		}
		respondJson(exchange, out);
	}

	/**
	 * {@code POST /preview/sync}: hand the tab's definition set and meta to the preview controller,
	 * which diffs and registers it (tombstoning removed ids), re-projects the exact view and reports
	 * the applied/rejected ids. Never throws - a controller failure is a JSON error response.
	 *
	 * @param exchange the {@code /preview/sync} exchange
	 * @throws IOException when the response cannot be sent
	 */
	private void handlePreviewSync(final HttpExchange exchange) throws IOException {
		final JsonObject body = readObject(exchange);
		if (body == null) {
			respondActionError(exchange, EditorResult.INVALID_JSON, "request body must be a JSON object");
			return;
		}
		respondPreview(exchange, EditorPreviewController.get().sync(body));
	}

	/**
	 * {@code POST /preview/viewstate}: apply order/mute/solo via instance operations only (no
	 * recompilation). Accepted only at the controller's current revision.
	 *
	 * @param exchange the {@code /preview/viewstate} exchange
	 * @throws IOException when the response cannot be sent
	 */
	private void handlePreviewViewstate(final HttpExchange exchange) throws IOException {
		final JsonObject body = readObject(exchange);
		if (body == null) {
			respondActionError(exchange, EditorResult.INVALID_JSON, "request body must be a JSON object");
			return;
		}
		respondPreview(exchange, EditorPreviewController.get().viewstate(body));
	}

	/**
	 * {@code POST /preview/param}: the fast-path live parameter edit on one child's instance,
	 * revision-gated. At ~30Hz it lands as a constant override read per frame.
	 *
	 * @param exchange the {@code /preview/param} exchange
	 * @throws IOException when the response cannot be sent
	 */
	private void handlePreviewParam(final HttpExchange exchange) throws IOException {
		final JsonObject body = readObject(exchange);
		if (body == null) {
			respondActionError(exchange, EditorResult.INVALID_JSON, "request body must be a JSON object");
			return;
		}
		respondPreview(exchange, EditorPreviewController.get().param(body));
	}

	/**
	 * {@code POST /preview/transport}: play/pause/seek/stop/setMode time control, revision-gated.
	 *
	 * @param exchange the {@code /preview/transport} exchange
	 * @throws IOException when the response cannot be sent
	 */
	private void handlePreviewTransport(final HttpExchange exchange) throws IOException {
		final JsonObject body = readObject(exchange);
		if (body == null) {
			respondActionError(exchange, EditorResult.INVALID_JSON, "request body must be a JSON object");
			return;
		}
		respondPreview(exchange, EditorPreviewController.get().transport(body));
	}

	/** Write a preview controller response at its own HTTP status as JSON. */
	private void respondPreview(final HttpExchange exchange, final EditorPreviewController.Response response) throws IOException {
		respond(exchange, response.status(), "application/json", response.body().toString().getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * {@code GET /worlds}: directory names under the client's {@code saves} folder, plus the saves
	 * root so the web can build a full world path and the world the player is currently in so the
	 * web can pre-select it. Never throws out of the handler.
	 *
	 * @param exchange the {@code /worlds} exchange
	 * @throws IOException when the response cannot be sent
	 */
	private void handleWorlds(final HttpExchange exchange) throws IOException {
		final JsonArray worlds = new JsonArray();
		String root = null;
		try {
			root = this.session.savesDir();
			if (root != null) {
				final Path dir = Path.of(root);
				if (Files.isDirectory(dir)) {
					try (Stream<Path> entries = Files.list(dir)) {
						entries.filter(Files::isDirectory)
								.map(entry -> entry.getFileName().toString())
								.sorted()
								.limit(MAX_WORLDS)
								.forEach(worlds::add);
					}
				}
			}
		} catch (final RuntimeException | IOException e) {
			// Fall through with whatever was collected; a worlds listing must never 500 the bridge.
		}
		final String current = this.session.currentWorldName();
		final JsonObject out = new JsonObject();
		out.add("worlds", worlds);
		out.addProperty("root", root == null ? "" : root);
		out.add("current", current == null ? JsonNull.INSTANCE : new JsonPrimitive(current));
		respondJson(exchange, out);
	}

	/**
	 * {@code GET /worlds/datapacks?world=<name>}: the datapack folders and zips installed in one
	 * world, so the web can open one without a filesystem path. The world name is resolved against
	 * the saves root and must name a direct child (no traversal). Never throws out of the handler.
	 *
	 * @param exchange the {@code /worlds/datapacks} exchange
	 * @throws IOException when the response cannot be sent
	 */
	private void handleWorldDatapacks(final HttpExchange exchange) throws IOException {
		final String world = queryParam(exchange, "world");
		final JsonArray datapacks = new JsonArray();
		final Path dir = worldDir(world);
		if (dir == null) {
			final JsonObject out = new JsonObject();
			out.addProperty("ok", false);
			out.addProperty("error", "unknown world");
			out.addProperty("world", world == null ? "" : world);
			out.add("datapacks", datapacks);
			respondJson(exchange, out);
			return;
		}
		try {
			final Path packs = dir.resolve("datapacks");
			if (Files.isDirectory(packs)) {
				try (Stream<Path> entries = Files.list(packs)) {
					entries.sorted()
							.limit(MAX_DATAPACKS)
							.forEach(entry -> {
								final String name = entry.getFileName().toString();
								final boolean zip = Files.isRegularFile(entry) && name.length() > 4
										&& name.regionMatches(true, name.length() - 4, ".zip", 0, 4);
								if (Files.isDirectory(entry) || zip) {
									final JsonObject pack = new JsonObject();
									pack.addProperty("name", name);
									pack.addProperty("path", entry.toString());
									pack.addProperty("kind", zip ? "zip" : "folder");
									datapacks.add(pack);
								}
							});
				}
			}
		} catch (final RuntimeException | IOException e) {
			// Fall through with whatever was collected; a datapack listing must never 500 the bridge.
		}
		final JsonObject out = new JsonObject();
		out.addProperty("ok", true);
		out.addProperty("world", world);
		out.add("datapacks", datapacks);
		respondJson(exchange, out);
	}

	/**
	 * Resolve a world name to its save directory under the client's saves root. The name must be a
	 * direct child of the saves directory: {@code resolve} + {@code normalize} then a parent check
	 * rejects {@code ..}, separators and absolute paths, so a caller cannot traverse out.
	 *
	 * @param name the world save-folder name from the request
	 * @return the world directory, or {@code null} when the name is absent, unsafe or not a directory
	 */
	@Nullable
	private Path worldDir(final @Nullable String name) {
		if (name == null || name.isBlank() || name.length() > 255) {
			return null;
		}
		final String root = this.session.savesDir();
		if (root == null) {
			return null;
		}
		try {
			final Path saves = Path.of(root).normalize();
			final Path candidate = saves.resolve(name).normalize();
			return saves.equals(candidate.getParent()) && Files.isDirectory(candidate) ? candidate : null;
		} catch (final RuntimeException e) {
			return null;
		}
	}

	/** The {@code effects} object as effect-name to JSON text (a primitive is taken verbatim). */
	private static Map<String, String> effectsMap(final JsonElement effectsElement) {
		final Map<String, String> effects = new LinkedHashMap<>();
		for (final Map.Entry<String, JsonElement> entry : effectsElement.getAsJsonObject().entrySet()) {
			final JsonElement value = entry.getValue();
			if (value != null && !value.isJsonNull()) {
				effects.put(entry.getKey(), value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
						? value.getAsString() : value.toString());
			}
		}
		return effects;
	}

	@Nullable
	private static Path exportTarget(final String destination, final String path, final String namespace) {
		return switch (destination) {
			case "world" -> EditorPack.worldPackDir(Path.of(path), namespace);
			case "folder", "zip" -> Path.of(path);
			default -> null;
		};
	}

	/** The request's {@code sidecar} as JSON text, or {@code null} when absent. */
	@Nullable
	private static String sidecarString(final JsonObject body) {
		final JsonElement element = body.get("sidecar");
		if (element == null || element.isJsonNull()) {
			return null;
		}
		return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
				? element.getAsString() : element.toString();
	}

	/** Parse stored sidecar text as JSON, falling back to a JSON string if it is not valid JSON. */
	private static JsonElement parseOrString(final String json) {
		try {
			return JsonParser.parseString(json);
		} catch (final JsonSyntaxException e) {
			return new JsonPrimitive(json);
		}
	}

	private void respondActionError(final HttpExchange exchange, final String error, final String detail)
			throws IOException {
		final JsonObject out = new JsonObject();
		out.addProperty("ok", false);
		out.addProperty("error", error);
		if (detail != null) {
			out.addProperty("detail", detail);
		}
		respondJson(exchange, out);
	}

	private void respondJson(final HttpExchange exchange, final JsonObject out) throws IOException {
		respond(exchange, 200, "application/json", out.toString().getBytes(StandardCharsets.UTF_8));
	}

	private EditorResult badRequest(final String detail) {
		return new EditorResult(false, this.session.revision(), EditorResult.BAD_REQUEST, detail);
	}

	/**
	 * Read a POST body (bounded) and parse it as a JSON object.
	 *
	 * @param exchange the exchange whose body is read
	 * @return the object, or {@code null} when the body is unreadable, too large, or not a JSON object
	 */
	@Nullable
	private static JsonObject readObject(final HttpExchange exchange) {
		final byte[] raw;
		try (InputStream in = exchange.getRequestBody()) {
			raw = in.readNBytes(MAX_BODY_BYTES + 1);
		} catch (final IOException e) {
			return null;
		}
		if (raw.length > MAX_BODY_BYTES) {
			return null;
		}
		try {
			final JsonElement parsed = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
			return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
		} catch (final JsonSyntaxException e) {
			return null;
		}
	}

	@Nullable
	private static String string(final JsonObject body, final String name) {
		final JsonElement element = body.get(name);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			return null;
		}
		return element.getAsString();
	}

	@Nullable
	private static Long optionalLong(final JsonObject body, final String name) {
		final JsonElement element = body.get(name);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			return null;
		}
		return element.getAsLong();
	}

	private static int intOrDefault(final JsonObject body, final String name, final int fallback) {
		final JsonElement element = body.get(name);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			return fallback;
		}
		return element.getAsInt();
	}

	@Nullable
	private static Integer intOrNull(final JsonObject body, final String name) {
		final JsonElement element = body.get(name);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			return null;
		}
		return element.getAsInt();
	}

	@Nullable
	private static Float floatOrNull(final JsonObject body, final String name) {
		final JsonElement element = body.get(name);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			return null;
		}
		return element.getAsFloat();
	}

	/** Serialize an outcome as the uniform response/event body. */
	private static String resultJson(final EditorResult result) {
		final JsonObject out = new JsonObject();
		out.addProperty("ok", result.ok());
		out.addProperty("revision", result.revision());
		if (result.error() != null) {
			out.addProperty("error", result.error());
		}
		if (result.detail() != null) {
			out.addProperty("detail", result.detail());
		}
		return out.toString();
	}

	/** Write an outcome as a {@code 200 application/json} body (the {@code ok} field is the result). */
	private void respondResult(final HttpExchange exchange, final EditorResult result) throws IOException {
		respond(exchange, 200, "application/json", resultJson(result).getBytes(StandardCharsets.UTF_8));
	}

	/** Push success as a {@code revision} event and failure as an {@code error} event over SSE. */
	private static void mirror(final EditorResult result) {
		EditorSse.broadcast(result.ok() ? "revision" : "error", resultJson(result));
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

	/** A decoded query-string value by name, or {@code null} when absent. */
	@Nullable
	private static String queryParam(final HttpExchange exchange, final String name) {
		final String query = exchange.getRequestURI().getRawQuery();
		if (query == null) {
			return null;
		}
		for (final String pair : query.split("&")) {
			final int eq = pair.indexOf('=');
			if (eq > 0 && name.equals(pair.substring(0, eq))) {
				return decodeQueryValue(pair.substring(eq + 1));
			}
		}
		return null;
	}

	/**
	 * Decode one query value, keeping a literal {@code +}. {@link URLDecoder} implements form
	 * decoding, where {@code +} means a space, but a raw query keeps {@code +} literal (RFC 3986) —
	 * a world folder named {@code a+b} must survive. A client that wants a space percent-encodes it.
	 */
	private static String decodeQueryValue(final String value) {
		return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
	}

	@Nullable
	private static String presentedToken(final HttpExchange exchange) {
		final String query = exchange.getRequestURI().getRawQuery();
		if (query != null) {
			for (final String pair : query.split("&")) {
				final int eq = pair.indexOf('=');
				if (eq > 0 && "token".equals(pair.substring(0, eq))) {
					return decodeQueryValue(pair.substring(eq + 1));
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

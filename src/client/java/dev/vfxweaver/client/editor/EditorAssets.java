package dev.vfxweaver.client.editor;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Classpath lookup for the editor's static assets (the vendored web UI).
 *
 * <p>Assets live under {@code assets/vfxweaver/editor/} in the client jar. This class only touches
 * the classpath, so it is free of Minecraft and loader types and runs on a plain JVM.
 */
public final class EditorAssets {
	/** Classpath directory all editor assets are read from. */
	private static final String ROOT = "assets/vfxweaver/editor/";

	private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
			Map.entry("html", "text/html; charset=utf-8"),
			Map.entry("css", "text/css; charset=utf-8"),
			Map.entry("js", "text/javascript; charset=utf-8"),
			Map.entry("mjs", "text/javascript; charset=utf-8"),
			Map.entry("json", "application/json"),
			Map.entry("map", "application/json"),
			Map.entry("svg", "image/svg+xml"),
			Map.entry("png", "image/png"),
			Map.entry("ico", "image/x-icon"),
			Map.entry("woff2", "font/woff2"));

	private EditorAssets() {
	}

	/**
	 * Read one editor asset from the classpath.
	 *
	 * @param path the request path ({@code /}, {@code /assets/app.js}, {@code /app.js}); {@code /}
	 *             maps to {@code index.html}
	 * @return the file bytes, or {@code null} when the asset does not exist
	 */
	@Nullable
	public static byte[] resolve(final String path) {
		final String resource = toResource(path);
		if (resource == null) {
			return null;
		}
		try (InputStream in = EditorAssets.class.getClassLoader().getResourceAsStream(resource)) {
			return in == null ? null : in.readAllBytes();
		} catch (final IOException e) {
			return null;
		}
	}

	/**
	 * Content type for a request path, by file extension.
	 *
	 * @param path the request path
	 * @return the MIME type, or {@code application/octet-stream} for an unknown extension
	 */
	public static String contentType(final String path) {
		String p = path;
		if (p == null || p.isEmpty() || p.equals("/")) {
			p = "index.html";
		}
		final int dot = p.lastIndexOf('.');
		if (dot < 0) {
			return "application/octet-stream";
		}
		return CONTENT_TYPES.getOrDefault(p.substring(dot + 1).toLowerCase(Locale.ROOT), "application/octet-stream");
	}

	@Nullable
	private static String toResource(final String path) {
		if (path == null) {
			return null;
		}
		String p = path;
		if (p.isEmpty() || p.equals("/")) {
			p = "index.html";
		} else {
			if (p.startsWith("/")) {
				p = p.substring(1);
			}
			if (p.startsWith("assets/")) {
				p = p.substring("assets/".length());
			}
		}
		if (p.isEmpty() || p.contains("..")) {
			return null;
		}
		return ROOT + p;
	}
}

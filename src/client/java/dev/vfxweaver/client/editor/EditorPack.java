package dev.vfxweaver.client.editor;

import dev.vfxweaver.editor.VFXPackFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * Exports editor definitions as a datapack, either to a folder (to drop into a world's
 * {@code datapacks/}) or to a zip (to ship to a server). Layout in both cases:
 *
 * <pre>
 * pack.mcmeta
 * data/&lt;namespace&gt;/vfx/&lt;name&gt;.json
 * vfxweaver_editor.vfxproj.json   (optional sidecar, editor round-trip document)
 * </pre>
 *
 * <p>Only {@code java.nio} and {@code java.util.zip}; no Minecraft or loader types, so it runs on a
 * plain JVM. The pack format comes from the per-node {@link VFXPackFormat}.
 *
 * <p><b>Trust boundary:</b> the namespace and every effect name are validated by
 * {@link #safeName(String)} before any path is resolved or zip entry is built, so a caller cannot
 * escape the pack directory or inject a {@code ../} zip entry (zip-slip). A re-export first clears
 * this pack's own {@code data/&lt;namespace&gt;/vfx/} files, so a deleted effect does not linger.
 * Effect names come from a {@link Map}, so duplicate keys are not representable.
 */
public final class EditorPack {
	/** Sidecar file name at the pack root, carrying the editor project document. */
	public static final String SIDECAR_NAME = "vfxweaver_editor.vfxproj.json";
	/** Allowed name shape: lowercase letters, digits, {@code _}, {@code .}, {@code -}. */
	private static final Pattern SAFE_NAME = Pattern.compile("[a-z0-9_.-]+");

	private EditorPack() {
	}

	/**
	 * Writes a datapack folder. Validates the namespace and effect names, then replaces the pack's
	 * own {@code data/<namespace>/vfx/} contents (a stale definition from an earlier export is
	 * removed).
	 *
	 * @param packDir     the pack root to create/populate
	 * @param namespace   the data namespace ({@code data/<namespace>/vfx/...})
	 * @param effectsJson effect name (no {@code .json}) to JSON text
	 * @param sidecarJson the editor project JSON, or {@code null} to omit the sidecar
	 * @throws IllegalArgumentException if the namespace or an effect name is unsafe
	 * @throws IOException if writing fails
	 */
	public static void write(final Path packDir, final String namespace, final Map<String, String> effectsJson,
			final @Nullable String sidecarJson) throws IOException {
		final String ns = safeName(namespace);
		for (final String name : effectsJson.keySet()) {
			safeName(name);
		}
		Files.createDirectories(packDir);
		Files.writeString(packDir.resolve("pack.mcmeta"), VFXPackFormat.mcmeta(), StandardCharsets.UTF_8);
		final Path vfx = packDir.resolve("data").resolve(ns).resolve("vfx");
		clearFiles(vfx);
		Files.createDirectories(vfx);
		for (final Map.Entry<String, String> entry : effectsJson.entrySet()) {
			Files.writeString(vfx.resolve(entry.getKey() + ".json"), entry.getValue(), StandardCharsets.UTF_8);
		}
		if (sidecarJson != null) {
			Files.writeString(packDir.resolve(SIDECAR_NAME), sidecarJson, StandardCharsets.UTF_8);
		}
	}

	/**
	 * Writes the same layout into a zip archive. Validates the namespace and effect names; missing
	 * parent directories of {@code zip} are created.
	 *
	 * @param zip         the archive to create/overwrite
	 * @param namespace   the data namespace
	 * @param effectsJson effect name to JSON text
	 * @param sidecarJson the editor project JSON, or {@code null} to omit the sidecar
	 * @throws IllegalArgumentException if the namespace or an effect name is unsafe
	 * @throws IOException if writing fails
	 */
	public static void writeZip(final Path zip, final String namespace, final Map<String, String> effectsJson,
			final @Nullable String sidecarJson) throws IOException {
		final String ns = safeName(namespace);
		for (final String name : effectsJson.keySet()) {
			safeName(name);
		}
		final Path parent = zip.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			put(out, "pack.mcmeta", VFXPackFormat.mcmeta());
			for (final Map.Entry<String, String> entry : effectsJson.entrySet()) {
				put(out, "data/" + ns + "/vfx/" + entry.getKey() + ".json", entry.getValue());
			}
			if (sidecarJson != null) {
				put(out, SIDECAR_NAME, sidecarJson);
			}
		}
	}

	/**
	 * The pack folder for a world save.
	 *
	 * @param worldSaveDir the world's save directory
	 * @param namespace    the data namespace (also the folder name under {@code datapacks/})
	 * @return {@code <worldSaveDir>/datapacks/<namespace>}
	 * @throws IllegalArgumentException if the namespace is unsafe
	 */
	public static Path worldPackDir(final Path worldSaveDir, final String namespace) {
		return worldSaveDir.resolve("datapacks").resolve(safeName(namespace));
	}

	/**
	 * Validates a caller-supplied namespace or effect name at the export trust boundary.
	 *
	 * @param name the name to check
	 * @return {@code name} unchanged
	 * @throws IllegalArgumentException if the name is null/empty, {@code .}/{@code ..}, or contains
	 *         anything outside {@code [a-z0-9_.-]} (which also rejects {@code /}, {@code \} and
	 *         absolute paths)
	 */
	private static String safeName(final @Nullable String name) {
		if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
				|| !SAFE_NAME.matcher(name).matches()) {
			throw new IllegalArgumentException(
					"unsafe pack name (allowed [a-z0-9_.-], not '.'/'..'): " + name);
		}
		return name;
	}

	/** Deletes regular files directly inside {@code dir} (this pack's own vfx output), if it exists. */
	private static void clearFiles(final Path dir) throws IOException {
		if (!Files.isDirectory(dir)) {
			return;
		}
		try (Stream<Path> entries = Files.list(dir)) {
			for (final Path entry : (Iterable<Path>) entries::iterator) {
				if (Files.isRegularFile(entry)) {
					Files.delete(entry);
				}
			}
		}
	}

	private static void put(final ZipOutputStream out, final String name, final String json) throws IOException {
		out.putNextEntry(new ZipEntry(name));
		out.write(json.getBytes(StandardCharsets.UTF_8));
		out.closeEntry();
	}
}

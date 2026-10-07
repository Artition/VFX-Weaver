package dev.vfxweaver.client.editor;

import dev.vfxweaver.editor.VFXPackFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
		final String ns = validate(namespace, effectsJson);
		final Path parent = zip.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			writeZipEntries(out, ns, effectsJson, sidecarJson);
		}
	}

	/**
	 * The same zip layout as {@link #writeZip}, built entirely in memory. Used by the staged
	 * browser-download path ({@code POST /export/zip}) so no temporary file is needed.
	 *
	 * @param namespace   the data namespace
	 * @param effectsJson effect name to JSON text
	 * @param sidecarJson the editor project JSON, or {@code null} to omit the sidecar
	 * @return the zip archive bytes
	 * @throws IllegalArgumentException if the namespace or an effect name is unsafe
	 * @throws IOException if writing fails
	 */
	public static byte[] zipBytes(final String namespace, final Map<String, String> effectsJson,
			final @Nullable String sidecarJson) throws IOException {
		final String ns = validate(namespace, effectsJson);
		final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (ZipOutputStream out = new ZipOutputStream(buffer)) {
			writeZipEntries(out, ns, effectsJson, sidecarJson);
		}
		return buffer.toByteArray();
	}

	/** Validate the namespace and every effect name, returning the validated namespace. */
	private static String validate(final String namespace, final Map<String, String> effectsJson) {
		final String ns = safeName(namespace);
		for (final String name : effectsJson.keySet()) {
			safeName(name);
		}
		return ns;
	}

	/** Write the pack's entries into an already-open zip stream (namespace/names pre-validated). */
	private static void writeZipEntries(final ZipOutputStream out, final String ns,
			final Map<String, String> effectsJson, final @Nullable String sidecarJson) throws IOException {
		put(out, "pack.mcmeta", VFXPackFormat.mcmeta());
		for (final Map.Entry<String, String> entry : effectsJson.entrySet()) {
			put(out, "data/" + ns + "/vfx/" + entry.getKey() + ".json", entry.getValue());
		}
		if (sidecarJson != null) {
			put(out, SIDECAR_NAME, sidecarJson);
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
	 * Reads a datapack folder or zip back into its raw pieces: the namespace, the effect name to
	 * JSON text, and the optional editor sidecar. This is the inverse of {@link #write} /
	 * {@link #writeZip}, so it accepts what either produced.
	 *
	 * <p><b>It never parses or validates effect JSON.</b> A corrupt or unknown {@code vfx/*.json} is
	 * returned as its exact raw string so the caller (the web model) can surface it without the whole
	 * import failing. A pack with no sidecar imports fine ({@link ImportedPack#sidecarJson()} is
	 * {@code null}).
	 *
	 * @param packDirOrZip a pack folder, or a pack zip archive
	 * @return the raw namespace, effects and sidecar
	 * @throws IOException if the folder/archive cannot be read
	 */
	public static ImportedPack read(final Path packDirOrZip) throws IOException {
		return Files.isDirectory(packDirOrZip) ? readDir(packDirOrZip) : readZip(packDirOrZip);
	}

	/** Walks {@code data/<namespace>/vfx/*.json} under a pack folder. */
	private static ImportedPack readDir(final Path packDir) throws IOException {
		final Map<String, String> effects = new LinkedHashMap<>();
		String namespace = "";
		final Path data = packDir.resolve("data");
		if (Files.isDirectory(data)) {
			try (Stream<Path> namespaces = Files.list(data)) {
				for (final Path namespaceDir : (Iterable<Path>) namespaces::iterator) {
					final Path vfx = namespaceDir.resolve("vfx");
					if (!Files.isDirectory(vfx)) {
						continue;
					}
					if (namespace.isEmpty()) {
						namespace = namespaceDir.getFileName().toString();
					}
					try (Stream<Path> files = Files.list(vfx)) {
						for (final Path file : (Iterable<Path>) files::iterator) {
							putIfEffect(effects, file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
						}
					}
				}
			}
		}
		final Path sidecar = packDir.resolve(SIDECAR_NAME);
		final String sidecarJson = Files.isRegularFile(sidecar)
				? Files.readString(sidecar, StandardCharsets.UTF_8) : null;
		return new ImportedPack(namespace, effects, sidecarJson);
	}

	/** Walks the same layout inside a zip; entries always use forward slashes. */
	private static ImportedPack readZip(final Path zip) throws IOException {
		final Map<String, String> effects = new LinkedHashMap<>();
		String namespace = "";
		String sidecarJson = null;
		try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
			ZipEntry entry;
			while ((entry = in.getNextEntry()) != null) {
				if (entry.isDirectory()) {
					continue;
				}
				final String name = entry.getName();
				if (sidecarJson == null && SIDECAR_NAME.equals(name)) {
					sidecarJson = readAll(in);
					continue;
				}
				final String[] parts = name.split("/");
				if (parts.length == 4 && "data".equals(parts[0]) && "vfx".equals(parts[2])) {
					if (namespace.isEmpty()) {
						namespace = parts[1];
					}
					putIfEffect(effects, parts[3], readAll(in));
				}
			}
		}
		return new ImportedPack(namespace, effects, sidecarJson);
	}

	/** Adds an effect when the entry is a {@code <name>.json} file; other files are ignored. */
	private static void putIfEffect(final Map<String, String> effects, final String fileName, final String json) {
		if (fileName.length() > ".json".length() && fileName.endsWith(".json")) {
			effects.put(fileName.substring(0, fileName.length() - ".json".length()), json);
		}
	}

	/** Reads the current zip entry (or a file stream) to its end, as UTF-8 text. */
	private static String readAll(final InputStream in) throws IOException {
		return new String(in.readAllBytes(), StandardCharsets.UTF_8);
	}

	/**
	 * A datapack read back into raw pieces.
	 *
	 * @param namespace   the data namespace taken from the {@code data/<namespace>/vfx} path, or
	 *                    {@code ""} when the pack has no vfx entries
	 * @param effectsJson effect name (no {@code .json}) to its exact JSON text, never parsed
	 * @param sidecarJson the sidecar project JSON, or {@code null} when absent
	 */
	public record ImportedPack(String namespace, Map<String, String> effectsJson, @Nullable String sidecarJson) {
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

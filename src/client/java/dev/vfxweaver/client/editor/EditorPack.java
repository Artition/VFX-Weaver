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
 * data/&lt;namespace&gt;/vfx_curves/&lt;name&gt;.json
 * data/&lt;namespace&gt;/vfx_particles/&lt;name&gt;.json
 * vfxweaver_editor.vfxproj.json   (optional sidecar, editor round-trip document)
 * </pre>
 *
 * <p>The {@code vfx_curves} / {@code vfx_particles} blocks are carried verbatim: like effect
 * JSON they are never parsed here, so a hand-written preset round-trips through the editor
 * instead of being dropped on re-export. A project whose tabs span several namespaces exports
 * every namespace's files in one pass via {@link #writeNamespaces}; a single-namespace project
 * writes exactly the entries it always did.
 *
 * <p>Only {@code java.nio} and {@code java.util.zip}; no Minecraft or loader types, so it runs on a
 * plain JVM. The pack format comes from the per-node {@link VFXPackFormat}.
 *
 * <p><b>Trust boundary:</b> every namespace and every file name is validated by
 * {@link #safeName(String)} before any path is resolved or zip entry is built, so a caller cannot
 * escape the pack directory or inject a {@code ../} zip entry (zip-slip). A re-export first clears
 * this pack's own {@code data/&lt;namespace&gt;/vfx/} (plus its {@code vfx_curves} /
 * {@code vfx_particles}) files, so a deleted effect or preset does not linger.
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
		write(packDir, namespace, effectsJson, Map.of(), Map.of(), sidecarJson);
	}

	/**
	 * Writes a datapack folder, including the verbatim {@code vfx_curves} / {@code vfx_particles}
	 * blocks. With empty curve/particle maps the written entries are exactly what {@link #write}
	 * always produced.
	 *
	 * @param packDir       the pack root to create/populate
	 * @param namespace     the data namespace ({@code data/<namespace>/vfx/...})
	 * @param effectsJson   effect name (no {@code .json}) to JSON text
	 * @param curvesJson    curve name (no {@code .json}) to JSON text
	 * @param particlesJson particle-preset name (no {@code .json}) to JSON text
	 * @param sidecarJson   the editor project JSON, or {@code null} to omit the sidecar
	 * @throws IllegalArgumentException if the namespace or a file name is unsafe
	 * @throws IOException if writing fails
	 */
	public static void write(final Path packDir, final String namespace, final Map<String, String> effectsJson,
			final Map<String, String> curvesJson, final Map<String, String> particlesJson,
			final @Nullable String sidecarJson) throws IOException {
		writeNamespaces(packDir, Map.of(safeName(namespace),
				new NamespaceContent(effectsJson, curvesJson, particlesJson)), sidecarJson);
	}

	/**
	 * Writes a datapack folder spanning several namespaces in one pass: every namespace's
	 * {@code vfx}, {@code vfx_curves} and {@code vfx_particles} files land under its own
	 * {@code data/<namespace>/} directory. A single-entry map writes exactly the entries the
	 * single-namespace {@link #write} always produced.
	 *
	 * @param packDir      the pack root to create/populate
	 * @param namespaces   namespace to its files, in the order to write them
	 * @param sidecarJson  the editor project JSON, or {@code null} to omit the sidecar
	 * @throws IllegalArgumentException if a namespace or a file name is unsafe
	 * @throws IOException if writing fails
	 */
	public static void writeNamespaces(final Path packDir, final Map<String, NamespaceContent> namespaces,
			final @Nullable String sidecarJson) throws IOException {
		validateNamespaces(namespaces);
		Files.createDirectories(packDir);
		Files.writeString(packDir.resolve("pack.mcmeta"), VFXPackFormat.mcmeta(), StandardCharsets.UTF_8);
		for (final Map.Entry<String, NamespaceContent> entry : namespaces.entrySet()) {
			writeNamespaceDir(packDir, entry.getKey(), entry.getValue());
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
		writeZip(zip, namespace, effectsJson, Map.of(), Map.of(), sidecarJson);
	}

	/**
	 * Writes the same layout into a zip archive, including the verbatim {@code vfx_curves} /
	 * {@code vfx_particles} blocks. Validates the namespace and file names; missing
	 * parent directories of {@code zip} are created.
	 *
	 * @param zip           the archive to create/overwrite
	 * @param namespace     the data namespace
	 * @param effectsJson   effect name to JSON text
	 * @param curvesJson    curve name to JSON text
	 * @param particlesJson particle-preset name to JSON text
	 * @param sidecarJson   the editor project JSON, or {@code null} to omit the sidecar
	 * @throws IllegalArgumentException if the namespace or a file name is unsafe
	 * @throws IOException if writing fails
	 */
	public static void writeZip(final Path zip, final String namespace, final Map<String, String> effectsJson,
			final Map<String, String> curvesJson, final Map<String, String> particlesJson,
			final @Nullable String sidecarJson) throws IOException {
		writeZipNamespaces(zip, Map.of(safeName(namespace),
				new NamespaceContent(effectsJson, curvesJson, particlesJson)), sidecarJson);
	}

	/**
	 * Writes the multi-namespace layout into a zip archive in one pass.
	 *
	 * @param zip          the archive to create/overwrite
	 * @param namespaces   namespace to its files, in the order to write them
	 * @param sidecarJson  the editor project JSON, or {@code null} to omit the sidecar
	 * @throws IllegalArgumentException if a namespace or a file name is unsafe
	 * @throws IOException if writing fails
	 */
	public static void writeZipNamespaces(final Path zip, final Map<String, NamespaceContent> namespaces,
			final @Nullable String sidecarJson) throws IOException {
		validateNamespaces(namespaces);
		final Path parent = zip.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			writeZipEntries(out, namespaces, sidecarJson);
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
		return zipBytes(namespace, effectsJson, Map.of(), Map.of(), sidecarJson);
	}

	/**
	 * The same zip layout as {@link #writeZip}, built entirely in memory, including the verbatim
	 * {@code vfx_curves} / {@code vfx_particles} blocks. Used by the staged
	 * browser-download path ({@code POST /export/zip}) so no temporary file is needed.
	 *
	 * @param namespace     the data namespace
	 * @param effectsJson   effect name to JSON text
	 * @param curvesJson    curve name to JSON text
	 * @param particlesJson particle-preset name to JSON text
	 * @param sidecarJson   the editor project JSON, or {@code null} to omit the sidecar
	 * @return the zip archive bytes
	 * @throws IllegalArgumentException if the namespace or a file name is unsafe
	 * @throws IOException if writing fails
	 */
	public static byte[] zipBytes(final String namespace, final Map<String, String> effectsJson,
			final Map<String, String> curvesJson, final Map<String, String> particlesJson,
			final @Nullable String sidecarJson) throws IOException {
		return zipBytesNamespaces(Map.of(safeName(namespace),
				new NamespaceContent(effectsJson, curvesJson, particlesJson)), sidecarJson);
	}

	/**
	 * The multi-namespace zip layout, built entirely in memory.
	 *
	 * @param namespaces   namespace to its files, in the order to write them
	 * @param sidecarJson  the editor project JSON, or {@code null} to omit the sidecar
	 * @return the zip archive bytes
	 * @throws IllegalArgumentException if a namespace or a file name is unsafe
	 * @throws IOException if writing fails
	 */
	public static byte[] zipBytesNamespaces(final Map<String, NamespaceContent> namespaces,
			final @Nullable String sidecarJson) throws IOException {
		validateNamespaces(namespaces);
		final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (ZipOutputStream out = new ZipOutputStream(buffer)) {
			writeZipEntries(out, namespaces, sidecarJson);
		}
		return buffer.toByteArray();
	}

	/** Validate every namespace and file name, so the writers below take only safe names. */
	private static void validateNamespaces(final Map<String, NamespaceContent> namespaces) {
		for (final Map.Entry<String, NamespaceContent> entry : namespaces.entrySet()) {
			safeName(entry.getKey());
			final NamespaceContent content = entry.getValue();
			if (content == null) {
				continue;
			}
			for (final String name : content.effects().keySet()) {
				safeName(name);
			}
			for (final String name : content.curves().keySet()) {
				safeName(name);
			}
			for (final String name : content.particles().keySet()) {
				safeName(name);
			}
		}
	}

	/** Write one namespace's files under {@code data/<ns>/}, clearing this pack's own stale files. */
	private static void writeNamespaceDir(final Path packDir, final String ns, final @Nullable NamespaceContent content)
			throws IOException {
		final Map<String, String> effects = content == null ? Map.of() : content.effects();
		final Map<String, String> curves = content == null ? Map.of() : content.curves();
		final Map<String, String> particles = content == null ? Map.of() : content.particles();
		final Path vfx = packDir.resolve("data").resolve(ns).resolve("vfx");
		clearFiles(vfx);
		Files.createDirectories(vfx);
		for (final Map.Entry<String, String> entry : effects.entrySet()) {
			Files.writeString(vfx.resolve(entry.getKey() + ".json"), entry.getValue(), StandardCharsets.UTF_8);
		}
		writeOptionalDir(packDir, ns, "vfx_curves", curves);
		writeOptionalDir(packDir, ns, "vfx_particles", particles);
	}

	/**
	 * Write an optional block directory ({@code vfx_curves} / {@code vfx_particles}), clearing
	 * stale files first; the directory is created only when there is something to write, so a
	 * project without presets keeps the legacy layout exactly.
	 */
	private static void writeOptionalDir(final Path packDir, final String ns, final String dir,
			final Map<String, String> files) throws IOException {
		final Path target = packDir.resolve("data").resolve(ns).resolve(dir);
		clearFiles(target);
		if (files.isEmpty()) {
			return;
		}
		Files.createDirectories(target);
		for (final Map.Entry<String, String> entry : files.entrySet()) {
			Files.writeString(target.resolve(entry.getKey() + ".json"), entry.getValue(), StandardCharsets.UTF_8);
		}
	}

	/** Write the pack's entries into an already-open zip stream (namespaces/names pre-validated). */
	private static void writeZipEntries(final ZipOutputStream out, final Map<String, NamespaceContent> namespaces,
			final @Nullable String sidecarJson) throws IOException {
		put(out, "pack.mcmeta", VFXPackFormat.mcmeta());
		for (final Map.Entry<String, NamespaceContent> entry : namespaces.entrySet()) {
			final String ns = entry.getKey();
			final NamespaceContent content = entry.getValue();
			final Map<String, String> effects = content == null ? Map.of() : content.effects();
			final Map<String, String> curves = content == null ? Map.of() : content.curves();
			final Map<String, String> particles = content == null ? Map.of() : content.particles();
			for (final Map.Entry<String, String> file : effects.entrySet()) {
				put(out, "data/" + ns + "/vfx/" + file.getKey() + ".json", file.getValue());
			}
			for (final Map.Entry<String, String> file : curves.entrySet()) {
				put(out, "data/" + ns + "/vfx_curves/" + file.getKey() + ".json", file.getValue());
			}
			for (final Map.Entry<String, String> file : particles.entrySet()) {
				put(out, "data/" + ns + "/vfx_particles/" + file.getKey() + ".json", file.getValue());
			}
		}
		if (sidecarJson != null) {
			put(out, SIDECAR_NAME, sidecarJson);
		}
	}

	/**
	 * One namespace's files inside a pack: effect definitions plus the verbatim
	 * {@code vfx_curves} / {@code vfx_particles} blocks. Every map is file base name
	 * (no {@code .json}) to exact JSON text, never parsed.
	 *
	 * @param effects   effect name to its exact JSON text
	 * @param curves    curve name to its exact JSON text
	 * @param particles particle-preset name to its exact JSON text
	 */
	public record NamespaceContent(Map<String, String> effects, Map<String, String> curves,
			Map<String, String> particles) {
		public NamespaceContent {
			effects = effects == null ? Map.of() : Map.copyOf(effects);
			curves = curves == null ? Map.of() : Map.copyOf(curves);
			particles = particles == null ? Map.of() : Map.copyOf(particles);
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
	 * JSON text, the verbatim {@code vfx_curves} / {@code vfx_particles} blocks, and the optional
	 * editor sidecar. This is the inverse of {@link #writeNamespaces} /
	 * {@link #writeZipNamespaces}, so it accepts what either produced.
	 *
	 * <p><b>It never parses or validates effect JSON.</b> A corrupt or unknown {@code vfx/*.json}
	 * (or curve/particle file) is returned as its exact raw string so the caller (the web model)
	 * can surface it without the whole import failing. A pack with no sidecar imports fine
	 * ({@link ImportedPack#sidecarJson()} is {@code null}).
	 *
	 * @param packDirOrZip a pack folder, or a pack zip archive
	 * @return the raw namespaces, effects, curves, particles and sidecar
	 * @throws IOException if the folder/archive cannot be read
	 */
	public static ImportedPack read(final Path packDirOrZip) throws IOException {
		return Files.isDirectory(packDirOrZip) ? readDir(packDirOrZip) : readZip(packDirOrZip);
	}

	/** Block directories read per namespace: effects plus the verbatim curve/particle blocks. */
	private static final Map<String, Integer> READ_DIRS = Map.of(
			"vfx", 0, "vfx_curves", 1, "vfx_particles", 2);

	/** Walks {@code data/<namespace>/{vfx,vfx_curves,vfx_particles}/*.json} under a pack folder. */
	private static ImportedPack readDir(final Path packDir) throws IOException {
		final Map<String, Map<String, String>> effects = new LinkedHashMap<>();
		final Map<String, Map<String, String>> curves = new LinkedHashMap<>();
		final Map<String, Map<String, String>> particles = new LinkedHashMap<>();
		final Path data = packDir.resolve("data");
		if (Files.isDirectory(data)) {
			try (Stream<Path> namespaces = Files.list(data)) {
				for (final Path namespaceDir : (Iterable<Path>) namespaces::iterator) {
					final String ns = namespaceDir.getFileName().toString();
					for (final Map.Entry<String, Integer> dir : READ_DIRS.entrySet()) {
						final Path block = namespaceDir.resolve(dir.getKey());
						if (!Files.isDirectory(block)) {
							continue;
						}
						final Map<String, Map<String, String>> target = switch (dir.getValue()) {
							case 1 -> curves;
							case 2 -> particles;
							default -> effects;
						};
						try (Stream<Path> files = Files.list(block)) {
							for (final Path file : (Iterable<Path>) files::iterator) {
								putIfJson(target.computeIfAbsent(ns, k -> new LinkedHashMap<>()),
										file.getFileName().toString(),
										Files.readString(file, StandardCharsets.UTF_8));
							}
						}
						// A namespace with an (empty) vfx dir but no files still counts, as before.
						if (dir.getValue() == 0) {
							effects.computeIfAbsent(ns, k -> new LinkedHashMap<>());
						}
					}
				}
			}
		}
		final Path sidecar = packDir.resolve(SIDECAR_NAME);
		final String sidecarJson = Files.isRegularFile(sidecar)
				? Files.readString(sidecar, StandardCharsets.UTF_8) : null;
		return ImportedPack.of(effects, curves, particles, sidecarJson);
	}

	/** Walks the same layout inside a zip; entries always use forward slashes. */
	private static ImportedPack readZip(final Path zip) throws IOException {
		final Map<String, Map<String, String>> effects = new LinkedHashMap<>();
		final Map<String, Map<String, String>> curves = new LinkedHashMap<>();
		final Map<String, Map<String, String>> particles = new LinkedHashMap<>();
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
				if (parts.length == 4 && "data".equals(parts[0]) && READ_DIRS.containsKey(parts[2])) {
					final Map<String, Map<String, String>> target = switch (READ_DIRS.get(parts[2])) {
						case 1 -> curves;
						case 2 -> particles;
						default -> effects;
					};
					putIfJson(target.computeIfAbsent(parts[1], k -> new LinkedHashMap<>()),
							parts[3], readAll(in));
				}
			}
		}
		return ImportedPack.of(effects, curves, particles, sidecarJson);
	}

	/** Adds a file when the entry is a {@code <name>.json} file; other files are ignored. */
	private static void putIfJson(final Map<String, String> files, final String fileName, final String json) {
		if (fileName.length() > ".json".length() && fileName.endsWith(".json")) {
			files.put(fileName.substring(0, fileName.length() - ".json".length()), json);
		}
	}

	/** Reads the current zip entry (or a file stream) to its end, as UTF-8 text. */
	private static String readAll(final InputStream in) throws IOException {
		return new String(in.readAllBytes(), StandardCharsets.UTF_8);
	}

	/**
	 * A datapack read back into raw pieces.
	 *
	 * @param namespace   the primary data namespace: the first namespace holding effects, else the
	 *                    first namespace holding anything, else {@code ""} for an empty pack
	 * @param effectsJson the primary namespace's effects: effect name (no {@code .json}) to its
	 *                    exact JSON text, never parsed
	 * @param sidecarJson the sidecar project JSON, or {@code null} when absent
	 * @param effectsByNamespace   every namespace's effects, in encounter order
	 * @param curvesByNamespace    every namespace's verbatim {@code vfx_curves} blocks
	 * @param particlesByNamespace every namespace's verbatim {@code vfx_particles} blocks
	 */
	public record ImportedPack(String namespace, Map<String, String> effectsJson, @Nullable String sidecarJson,
			Map<String, Map<String, String>> effectsByNamespace,
			Map<String, Map<String, String>> curvesByNamespace,
			Map<String, Map<String, String>> particlesByNamespace) {
		public ImportedPack {
			effectsByNamespace = copyByNamespace(effectsByNamespace);
			curvesByNamespace = copyByNamespace(curvesByNamespace);
			particlesByNamespace = copyByNamespace(particlesByNamespace);
			effectsJson = effectsJson == null ? Map.of() : Map.copyOf(effectsJson);
		}

		/** Build a pack from its per-namespace maps, deriving the legacy primary-namespace view. */
		public static ImportedPack of(final Map<String, Map<String, String>> effects,
				final Map<String, Map<String, String>> curves,
				final Map<String, Map<String, String>> particles,
				final @Nullable String sidecarJson) {
			String primary = "";
			for (final Map.Entry<String, Map<String, String>> entry : effects.entrySet()) {
				if (!entry.getValue().isEmpty()) {
					primary = entry.getKey();
					break;
				}
			}
			if (primary.isEmpty() && !effects.isEmpty()) {
				primary = effects.keySet().iterator().next();
			}
			if (primary.isEmpty() && !curves.isEmpty()) {
				primary = curves.keySet().iterator().next();
			}
			if (primary.isEmpty() && !particles.isEmpty()) {
				primary = particles.keySet().iterator().next();
			}
			final Map<String, String> primaryEffects =
					effects.getOrDefault(primary, Map.of());
			return new ImportedPack(primary, primaryEffects, sidecarJson, effects, curves, particles);
		}

		private static Map<String, Map<String, String>> copyByNamespace(
				final Map<String, Map<String, String>> namespaces) {
			if (namespaces == null) {
				return Map.of();
			}
			final Map<String, Map<String, String>> copy = new LinkedHashMap<>();
			for (final Map.Entry<String, Map<String, String>> entry : namespaces.entrySet()) {
				copy.put(entry.getKey(), entry.getValue() == null ? Map.of() : Map.copyOf(entry.getValue()));
			}
			return Map.copyOf(copy);
		}
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

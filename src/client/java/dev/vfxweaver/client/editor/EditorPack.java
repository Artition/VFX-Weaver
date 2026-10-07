package dev.vfxweaver.client.editor;

import dev.vfxweaver.editor.VFXPackFormat;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
 */
public final class EditorPack {
	/** Sidecar file name at the pack root, carrying the editor project document. */
	public static final String SIDECAR_NAME = "vfxweaver_editor.vfxproj.json";

	private EditorPack() {
	}

	/**
	 * Writes a datapack folder.
	 *
	 * @param packDir     the pack root to create/populate
	 * @param namespace   the data namespace ({@code data/<namespace>/vfx/…})
	 * @param effectsJson effect name (no {@code .json}) to JSON text
	 * @param sidecarJson the editor project JSON, or {@code null} to omit the sidecar
	 * @throws IOException if writing fails
	 */
	public static void write(final Path packDir, final String namespace, final Map<String, String> effectsJson,
			final @Nullable String sidecarJson) throws IOException {
		Files.createDirectories(packDir);
		Files.writeString(packDir.resolve("pack.mcmeta"), VFXPackFormat.mcmeta(), StandardCharsets.UTF_8);
		final Path vfx = packDir.resolve("data").resolve(namespace).resolve("vfx");
		Files.createDirectories(vfx);
		for (final Map.Entry<String, String> entry : effectsJson.entrySet()) {
			Files.writeString(vfx.resolve(entry.getKey() + ".json"), entry.getValue(), StandardCharsets.UTF_8);
		}
		if (sidecarJson != null) {
			Files.writeString(packDir.resolve(SIDECAR_NAME), sidecarJson, StandardCharsets.UTF_8);
		}
	}

	/**
	 * Writes the same layout into a zip archive.
	 *
	 * @param zip         the archive to create/overwrite
	 * @param namespace   the data namespace
	 * @param effectsJson effect name to JSON text
	 * @param sidecarJson the editor project JSON, or {@code null} to omit the sidecar
	 * @throws IOException if writing fails
	 */
	public static void writeZip(final Path zip, final String namespace, final Map<String, String> effectsJson,
			final @Nullable String sidecarJson) throws IOException {
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			put(out, "pack.mcmeta", VFXPackFormat.mcmeta());
			for (final Map.Entry<String, String> entry : effectsJson.entrySet()) {
				put(out, "data/" + namespace + "/vfx/" + entry.getKey() + ".json", entry.getValue());
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
	 */
	public static Path worldPackDir(final Path worldSaveDir, final String namespace) {
		return worldSaveDir.resolve("datapacks").resolve(namespace);
	}

	private static void put(final ZipOutputStream out, final String name, final String json) throws IOException {
		out.putNextEntry(new ZipEntry(name));
		out.write(json.getBytes(StandardCharsets.UTF_8));
		out.closeEntry();
	}
}

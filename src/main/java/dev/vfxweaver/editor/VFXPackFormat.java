package dev.vfxweaver.editor;

/**
 * The per-node {@code pack.mcmeta} text for an exported datapack.
 *
 * <p>Minecraft's pack metadata format is a per-node compile-time fact, never detected at runtime:
 * the array {@code min_format} / plain-int {@code max_format} form is emitted by every supported
 * node (confirmed by reading the vanilla built-in datapack metadata inside each node's own client
 * jar): {@code [94,1]/94} on 1.21.11, {@code [101,1]/101} on 26.1.2, {@code [107,1]/107} on 26.2. An
 * array {@code max_format} fails to parse, so {@code max} stays a plain int exactly as vanilla
 * writes it.
 *
 * <p>Stonecutter rewrites {@link #DATA_FORMAT} per node; the class is Minecraft-free.
 */
public final class VFXPackFormat {
	/** Human-readable description written into every exported pack. */
	private static final String DESCRIPTION = "VFX Weaver editor export";

	/**
	 * The data-pack format number of this node. Confirmed against each node's bundled vanilla
	 * datapacks; see the class javadoc.
	 */
	//? if >=26.2 {
	/*private static final int DATA_FORMAT = 107;
	*///?}
	//? if >=26.1 && <26.2 {
	private static final int DATA_FORMAT = 101;
	//?}
	//? if <26.1 {
	/*private static final int DATA_FORMAT = 94;
	*///?}

	private VFXPackFormat() {
	}

	/**
	 * The exact {@code pack.mcmeta} contents for this node's datapack format.
	 *
	 * @return the JSON text, no trailing newline
	 */
	public static String mcmeta() {
		return "{\"pack\":{\"min_format\":[" + DATA_FORMAT + ",1],\"max_format\":" + DATA_FORMAT
				+ ",\"description\":\"" + DESCRIPTION + "\"}}";
	}
}

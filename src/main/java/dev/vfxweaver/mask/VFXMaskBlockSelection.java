package dev.vfxweaver.mask;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A block-geometry mask selection (spec §4, expanded design). The shared module is MC-free, so
 * block ids, tags and block-state property names/values are datapack-id strings; the client
 * geometry pass ({@code VFXMaskBlockGeometry}) resolves them to {@code Block}/tag/property.
 *
 * <p>Two shapes of selection share this record, because both feed the same geometry scratch and the
 * same one-block-leaf rule:
 * <ul>
 *   <li><b>scan</b> ({@code shape: "block"}): bounded by a spherical region ({@code center} +
 *       {@code radius}), so the scan cannot be unbounded, and the item caps mirror the
 *       world-overlay rule that every datapack-fed collection is bounded (AGENTS.md);</li>
 *   <li><b>list</b> ({@code shape: "block_list"}): the author names the blocks outright, either as
 *       explicit {@code positions} or as an inclusive {@code region} box. No scan, so no search
 *       radius.</li>
 * </ul>
 *
 * <p>The list itself is deliberately <b>uncapped</b> (owner decision): every entry bakes its real
 * model quads into the per-frame vertex buffer, so the cost of a huge list is memory and upload
 * bandwidth, not lookup time - one quad list per position, reused across frames until the list or
 * the model manager changes. A {@code region} is capped by volume ({@link #MAX_REGION_BLOCKS}) for
 * the same reason: it sizes that buffer, and a mistyped corner would otherwise ask for a
 * world-sized allocation.
 *
 * @param blockIds   datapack block ids to include (empty means "any id")
 * @param tag        a datapack block tag to include, or {@code null}
 * @param properties required block-state property name → value pairs (empty = none)
 * @param center     the scan region centre (x, y, z)
 * @param radius     the scan region radius in blocks, in {@code (0, MAX_RADIUS]}
 * @param positions  explicit block positions (x, y, z each), in author order; empty for a scan or a
 *                   region selection
 * @param region     an inclusive block box {@code [x0, y0, z0, x1, y1, z1]}, or {@code null}
 */
public record VFXMaskBlockSelection(
	List<String> blockIds,
	@Nullable String tag,
	Map<String, String> properties,
	float[] center,
	float radius,
	List<float[]> positions,
	@Nullable float[] region
) {
	/** The most block ids one selection may name. */
	public static final int MAX_BLOCKS = 512;
	/** The largest selection radius in blocks. */
	public static final int MAX_RADIUS = 32;
	/** The most block-state property constraints one selection may carry. */
	public static final int MAX_PROPERTIES = 4;
	/**
	 * The most blocks one {@code region} box may cover. The list itself has no cap (owner decision);
	 * this one bounds the volume a mistyped corner can ask the geometry buffer to hold.
	 */
	public static final int MAX_REGION_BLOCKS = 65536;

	public VFXMaskBlockSelection {
		blockIds = List.copyOf(blockIds);
		properties = Map.copyOf(properties);
		center = center.clone();
		positions = List.copyOf(positions);
		region = region == null ? null : region.clone();
	}

	/**
	 * True when this selection names its blocks outright ({@code positions} or {@code region})
	 * instead of scanning a spherical region.
	 */
	public boolean listed() {
		return !this.positions.isEmpty() || this.region != null;
	}

	/**
	 * The block coordinate a {@code positions} entry or a {@code region} corner refers to: the block
	 * that <b>contains</b> the point, i.e. floor - so {@code -0.5} is block {@code -1}, not {@code 0}.
	 * The parse-time region budget and the client geometry walk both call this one method, so they
	 * can never disagree by a block on a negative corner (the budget would then guard a different box
	 * than the one that is drawn).
	 *
	 * @param value the authored coordinate
	 * @return the block coordinate, saturating at the {@code int} range
	 */
	public static int blockCoordinate(final float value) {
		return (int) Math.floor(value);
	}
}

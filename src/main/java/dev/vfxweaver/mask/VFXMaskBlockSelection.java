package dev.vfxweaver.mask;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A block-geometry mask selection (spec §4, expanded design). The shared module is MC-free, so
 * block ids, tags and block-state property names/values are datapack-id strings; the client
 * geometry pass ({@code VFXMaskBlockGeometry}) resolves them to {@code Block}/tag/property.
 *
 * <p>The selection is always bounded by a spherical region ({@code center} + {@code radius}) so the
 * scan cannot be unbounded, and the item caps mirror the world-overlay rule that every
 * datapack-fed collection is bounded (AGENTS.md).
 *
 * @param blockIds   datapack block ids to include (empty means "any id")
 * @param tag        a datapack block tag to include, or {@code null}
 * @param properties required block-state property name → value pairs (empty = none)
 * @param center     the region centre (x, y, z)
 * @param radius     the region radius in blocks, in {@code (0, MAX_RADIUS]}
 */
public record VFXMaskBlockSelection(
	List<String> blockIds,
	@Nullable String tag,
	Map<String, String> properties,
	float[] center,
	float radius
) {
	/** The most block ids one selection may name. */
	public static final int MAX_BLOCKS = 512;
	/** The largest selection radius in blocks. */
	public static final int MAX_RADIUS = 32;
	/** The most block-state property constraints one selection may carry. */
	public static final int MAX_PROPERTIES = 4;

	public VFXMaskBlockSelection {
		blockIds = List.copyOf(blockIds);
		properties = Map.copyOf(properties);
		center = center.clone();
	}
}

package dev.vfxweaver.client.postprocessing;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import dev.vfxweaver.client.render.VFXWorldOverlayRenderer;
import dev.vfxweaver.mask.VFXMaskBlockSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * The block-geometry contribution to mask coverage (expanded design). Selects blocks by id / tag /
 * block-state property inside a bounded region and (in the full design) rasterises their real baked
 * model geometry (stairs, slabs, fences, plants) into the mask's geometry scratch, reusing the
 * block-overlay path ({@code VFXWorldOverlayRenderer.getModelQuads} /
 * {@code hasBlockModelGeometry} and the {@code core/position_color} conventions). It is CPU-bounded
 * by {@link VFXMaskBlockSelection} and is never a per-pixel fragment-shader lookup.
 *
 * <p>Deferred: the rasterisation draw itself (a direct {@code CommandEncoder} vertex-buffer draw of
 * the selected model quads with the world projection) is not yet wired — it needs an in-game
 * verification of the node-specific immediate-vertex API and matrix binding, which this task's
 * environment cannot provide. {@link #render} therefore clears the geometry scratch to zero, so a
 * block leaf contributes no coverage rather than stale data. The selection below is complete and
 * ready for that draw. {@code ponytail:} block-geometry rasterisation draw deferred, neutral
 * scratch; wire when the in-game check can verify the immediate-vertex path.
 */
public final class VFXMaskBlockGeometry {
	private VFXMaskBlockGeometry() {
	}

	/**
	 * The matching block positions inside the selection region, capped at
	 * {@link VFXMaskBlockSelection#MAX_BLOCKS}. Air and block-entity-only blocks (empty baked model)
	 * are skipped, so the draw never emits nothing for a selected block.
	 *
	 * @param level     the client level to scan
	 * @param selection the parsed selection identity (ids / tag / properties)
	 * @param center    the region centre, already resolved from a binding or the selection's literal
	 * @param radius    the region radius in blocks, already clamped by the caller
	 * @return the matching positions, bounded by the selection caps
	 */
	public static List<BlockPos> select(final Level level, final VFXMaskBlockSelection selection, final float[] center, final float radius) {
		final List<BlockPos> found = new ArrayList<>();
		final int r = (int) Math.ceil(Math.min(radius, VFXMaskBlockSelection.MAX_RADIUS));
		final BlockPos centerPos = BlockPos.containing(center[0], center[1], center[2]);
		final double radiusSq = (double) radius * radius;
		for (final BlockPos pos : BlockPos.betweenClosed(centerPos.offset(-r, -r, -r), centerPos.offset(r, r, r))) {
			if (found.size() >= VFXMaskBlockSelection.MAX_BLOCKS) {
				break;
			}
			if (pos.distSqr(centerPos) > radiusSq) {
				continue;
			}
			final BlockState state = level.getBlockState(pos);
			if (state.isAir() || !matches(state, selection) || !VFXWorldOverlayRenderer.hasBlockModelGeometry(state)) {
				continue;
			}
			found.add(pos.immutable());
		}
		return found;
	}

	private static boolean matches(final BlockState state, final VFXMaskBlockSelection selection) {
		if (!selection.blockIds().isEmpty()) {
			final String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
			if (!selection.blockIds().contains(id)) {
				return false;
			}
		}
		if (selection.tag() != null) {
			final TagKey<Block> tag = TagKey.create(Registries.BLOCK, Identifier.parse(selection.tag()));
			if (!state.is(tag)) {
				return false;
			}
		}
		for (final Map.Entry<String, String> entry : selection.properties().entrySet()) {
			if (!propertyMatches(state, entry.getKey(), entry.getValue())) {
				return false;
			}
		}
		return true;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static boolean propertyMatches(final BlockState state, final String name, final String value) {
		for (final Property<?> property : state.getProperties()) {
			if (property.getName().equals(name)) {
				return state.getValue((Property) property).toString().equals(value);
			}
		}
		return false;
	}

	/**
	 * Clears the geometry scratch before the coverage prepass. The full design draws the selected
	 * blocks' model quads here with the coverage pipeline (see the class doc for the deferral).
	 */
	public static void render(final CommandEncoder encoder, final RenderTarget geometry) {
		//? if <26.2 {
		encoder.clearColorTexture(geometry.getColorTexture(), 0);
		//?} else {
		/*encoder.clearColorTexture(geometry.getColorTexture(), new org.joml.Vector4f(0.0F, 0.0F, 0.0F, 0.0F));
		*///?}
	}
}

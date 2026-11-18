package dev.vfxweaver.client.postprocessing;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
//? if <26.2 {
import com.mojang.blaze3d.vertex.VertexFormat;
//?} else {
/*import com.mojang.blaze3d.PrimitiveTopology;
*///?}
import dev.vfxweaver.client.render.VFXWorldOverlayRenderer;
import dev.vfxweaver.effect.BoundParam;
import dev.vfxweaver.effect.VFXActiveEffect;
import dev.vfxweaver.effect.VFXWorldBindings;
import dev.vfxweaver.mask.VFXMask;
import dev.vfxweaver.mask.VFXMaskBlockSelection;
import dev.vfxweaver.mask.VFXMaskFill;
import dev.vfxweaver.mask.VFXMaskPrimitive;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
//? if >=26.2 {
/*import java.util.Optional;
*///?}
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
//? if <26.1 {
/*import net.minecraft.client.renderer.block.model.BakedQuad;
*///?} else {
import net.minecraft.client.resources.model.geometry.BakedQuad;
//?}
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * The block-geometry contribution to mask coverage (expanded design). Two selections feed it:
 * a scanned region ({@code shape: "block"}: select blocks by id / tag / block-state property inside
 * a bounded sphere) and an authored list ({@code shape: "block_list"}: the positions or box the
 * author named). Both rasterise the real baked model geometry (stairs, slabs, fences, plants) into
 * the mask's geometry scratch, reusing the block-overlay path
 * ({@code VFXWorldOverlayRenderer.getModelQuads} and the {@code core/position_color} conventions).
 * It is CPU-bounded by {@link VFXMaskBlockSelection} and is never a per-pixel fragment-shader
 * lookup.
 *
 * <p>The draw is camera-relative (the selected blocks' vertices are offset by {@code -cameraPos} and
 * placed in {@code DynamicTransforms.ModelViewMat}) with an identity {@code Projection}, so the
 * world coordinates stay small enough for float precision. The whole contribution is one vertex
 * buffer of triangles written into the scratch before the coverage prepass samples it.
 *
 * <p><b>Texture alpha.</b> The geometry is drawn through the block texture atlas
 * ({@code minecraft:textures/atlas/blocks.png}) with the quads' baked atlas UVs, so the fragment
 * shader can read the sprite's own alpha: a cutout leaf, a plant's cross or a light-through pane
 * contributes only where its texture is opaque. The legacy {@code block} leaf keeps the constant
 * coverage it always had (its vertices carry the "no atlas" flag), so only the {@code block_list}
 * leaf is alpha-aware - that is the difference between the two.
 *
 * <p><b>Two passes, one scratch.</b> A {@code block_list} leaf draws at most twice per frame: the
 * outer pass (the geometry dilated by the leaf's {@code stroke_width}, block area plus outline) and,
 * for a {@code hollow} fill, the inner pass (the undilated geometry). The scratch holds them in
 * different channels - red = outer alpha, green = outer * (1 - inner) - so the coverage shader picks
 * the channel from the leaf's fill code, and a solid fill never pays for the second pass. Nothing is
 * scanned per frame: the baked quads are cached per selection + model manager, and an explicit
 * {@code positions} list only re-reads its own coordinates' block states (N lookups) so a changed
 * block re-bakes.
 *
 * <p><b>World-locked.</b> The vertices are camera-relative and the pass owns its transform
 * ({@code projection * viewRotation} in its own {@code Projection} UBO, see {@link #writeTransform}),
 * so moving the camera moves the mask with the blocks and never shifts it. The draw is submitted
 * before the coverage prepass samples the scratch, so the mask shows this frame's geometry, not last
 * frame's.
 */
public final class VFXMaskBlockGeometry {
	/**
	 * The pass's own matrix: {@code projection * viewRotation}, applied to the camera-relative
	 * vertices. Rewritten every frame into {@link #transformUniform}, so nothing here depends on the
	 * frame's {@code ModelViewMat} (which already subtracts the camera) or on the shared
	 * {@code DynamicTransforms} arena.
	 */
	private static final Matrix4f GEOMETRY_MVP = new Matrix4f();
	/** Reused across frames; balanced push/pop keeps it valid. */
	private static final PoseStack POSE = new PoseStack();

	/** CPU staging for the vertex data; reused so the per-frame build allocates no native buffer. */
	private static @Nullable ByteBufferBuilder staging;
	/** The GPU vertex buffer, grown on demand and reused across frames. */
	private static @Nullable GpuBuffer vertexBuffer;
	private static long vertexBufferCapacity;
	/** The {@code Projection} UBO (one std140 mat4), rewritten every frame by {@link #writeTransform}. */
	private static @Nullable GpuBuffer transformUniform;
	/** The {@code MaskDepth} UBO (one std140 vec4: the depth-linearisation a and b), rewritten per frame. */
	private static @Nullable GpuBuffer depthLinearUniform;
	/** CPU staging for the 64-byte matrix upload; reused so the frame allocates nothing. */
	private static final ByteBuffer TRANSFORM_BYTES = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
	/** CPU staging for the 16-byte MaskDepth upload; reused so the frame allocates nothing. */
	private static final ByteBuffer DEPTH_LINEAR_BYTES = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());

	private VFXMaskBlockGeometry() {
	}

	/** A cached selection plus the model manager it was baked against; recomputed on a key change. */
	private static final class SelectionCache {
		private final Level level;
		private final Object modelManager;
		private final float centerX;
		private final float centerY;
		private final float centerZ;
		private final float radius;
		private final List<SelectedBlock> blocks;
		/**
		 * For an explicit {@code positions} list: the block state each listed coordinate had when it
		 * was baked (parallel to {@code selection.positions()}), re-read every frame so a placed,
		 * removed or re-stated block re-bakes. {@code null} for a region or a scan, whose states are
		 * only re-read when their key changes.
		 */
		private final BlockState @Nullable [] states;
		private long lastFrame;

		private SelectionCache(
			final Level level,
			final Object modelManager,
			final float centerX,
			final float centerY,
			final float centerZ,
			final float radius,
			final List<SelectedBlock> blocks,
			final BlockState @Nullable [] states,
			final long lastFrame
		) {
			this.level = level;
			this.modelManager = modelManager;
			this.centerX = centerX;
			this.centerY = centerY;
			this.centerZ = centerZ;
			this.radius = radius;
			this.blocks = blocks;
			this.states = states;
			this.lastFrame = lastFrame;
		}

		private boolean matches(final Level level, final Object modelManager, final VFXMaskBlockSelection selection, final float[] center, final float radius) {
			if (this.level != level || this.modelManager != modelManager) {
				return false;
			}
			// A listed selection has no scanned region: its baked quads depend on nothing that can
			// animate, so an animated slot must not force a rebuild every frame.
			if (selection.listed()) {
				return true;
			}
			return this.centerX == center[0] && this.centerY == center[1] && this.centerZ == center[2] && this.radius == radius;
		}
	}

	/**
	 * One baked selection per mask, keyed by the selection's identity (a parsed mask owns exactly one
	 * block selection, so this is "per block mask"): two effects - or two masks of one definition -
	 * each keep their own quads across frames instead of evicting each other the way a single static
	 * slot did. {@link #beginFrame()} drops every entry the previous frame did not use, so the map
	 * stays bounded by the live masked set; a level change or a resource reload invalidates the
	 * entries through {@link #invalidateSelectionCache()} and through the level/model-manager match in
	 * {@link SelectionCache#matches}.
	 */
	private static final Map<VFXMaskBlockSelection, SelectionCache> selectionCaches = new IdentityHashMap<>();

	/** The frame the manager is currently drawing {@link #beginFrame()}; entries are stamped with it. */
	private static long frameIndex;

	/**
	 * Marks the start of one frame's block-mask draws and evicts the entries the previous frame did
	 * not use. Called once per frame by the manager before it walks the masked set, so the eviction
	 * rule is "unused by the last full pass over the masked set", never "unused by the last call"
	 * (which would evict mask A whenever mask B is drawn after it).
	 */
	public static void beginFrame() {
		frameIndex++;
		selectionCaches.values().removeIf(cache -> cache.lastFrame < frameIndex - 1);
	}

	/**
	 * The matching blocks (each with its baked model quads resolved once) for the current selection.
	 * A scan selection is keyed by the level, the model manager, the selection identity and the
	 * resolved centre/radius, so the O((2r+1)^3) scan runs only when the region changes or a
	 * resource reload replaces the model manager. A listed selection has no region to scan, so its
	 * key is the level, the model manager and the selection identity alone: an animated slot value
	 * never invalidates it, and the baked quads are reused for as long as the definition lives.
	 *
	 * <p>An <b>empty</b> bake is not always a result worth keeping: a mask can start before its chunks
	 * are loaded, and a cached empty list would keep {@code matches} true forever - the leaf would
	 * never appear until the definition re-parsed or the model manager changed. So an empty bake from an
	 * explicit {@code positions} list is not stored and the list is re-read on the next frame: the list
	 * is author-sized (one block read per position), so the retry is cheap and self-limiting. A
	 * {@code region} box is the opposite - it can be 65 536 blocks of air, water or sky, legitimately
	 * empty - so its empty result <b>is</b> cached, exactly like a scanned selection's, and a region
	 * never becomes a per-frame world walk.
	 *
	 * <p>A live {@code positions} list also re-checks its own coordinates every frame: the state each
	 * listed block had when it was baked is kept, and any difference (a block placed, broken or
	 * re-stated at a listed coordinate) re-bakes the list. That is N {@code getBlockState} lookups per
	 * frame per listed leaf - no chunk scan, no region walk - which is what keeps the mask in step with
	 * the world while the effect runs. A {@code region} or a scan keeps its result until its key
	 * changes, because re-reading it would be the per-frame world walk this pass exists to avoid.
	 */
	private static List<SelectedBlock> select(final Level level, final VFXMaskBlockSelection selection, final float[] center, final float radius) {
		final Object modelManager = Minecraft.getInstance().getModelManager();
		SelectionCache cached = selectionCaches.get(selection);
		if (cached != null && cached.matches(level, modelManager, selection, center, radius)
			&& (cached.states == null || !statesChanged(level, selection, cached.states))) {
			cached.lastFrame = frameIndex;
			return cached.blocks;
		}
		final boolean positionListed = !selection.positions().isEmpty();
		final BlockState[] states = positionListed ? new BlockState[selection.positions().size()] : null;
		final List<SelectedBlock> blocks = selection.listed() ? bakeListed(level, selection, states) : scan(level, selection, center, radius);
		if (positionListed && blocks.isEmpty()) {
			// Only an explicit position list is retried; see the javadoc above.
			selectionCaches.remove(selection);
			return blocks;
		}
		cached = new SelectionCache(level, modelManager, center[0], center[1], center[2], radius, blocks, states, frameIndex);
		selectionCaches.put(selection, cached);
		return cached.blocks;
	}

	/**
	 * True when any listed coordinate's current block state differs from the one the bake was made
	 * from. Reference comparison is exact: {@code Level.getBlockState} returns the interned state
	 * instance, so an unchanged block compares equal and a changed one never does.
	 */
	private static boolean statesChanged(final Level level, final VFXMaskBlockSelection selection, final BlockState[] states) {
		final List<float[]> positions = selection.positions();
		for (int i = 0; i < states.length && i < positions.size(); i++) {
			if (level.getBlockState(blockPos(positions.get(i), 0)) != states[i]) {
				return true;
			}
		}
		return false;
	}

	/** Drops every cached selection; called when the world/level or a resource reload makes them stale. */
	public static void invalidateSelectionCache() {
		selectionCaches.clear();
	}

	/**
	 * Resolves an authored {@code block_list} selection: every listed position (or every position of
	 * the region box) is read <b>once, while the bake is non-empty</b> - a cache hit costs the vertex
	 * emission plus, for a {@code positions} list, the per-frame state re-check described in
	 * {@link #select}. The one exception to the one-shot read is an explicit {@code positions} list
	 * that bakes to nothing: it is re-read next frame until it resolves, because its size is the
	 * author's and a mask can legitimately start before its blocks load. The list is uncapped by
	 * design (the cost is memory, see {@link VFXMaskBlockSelection}); duplicates are dropped so a
	 * repeated position is not baked or drawn twice, and air / block-entity-only blocks (empty baked
	 * model) are skipped exactly as the scan path skips them. Positions and corners go through
	 * {@link VFXMaskBlockSelection#blockCoordinate(float)}, the same floor the parse-time region budget
	 * uses.
	 *
	 * @param states filled with each listed coordinate's state when non-null (an explicit
	 *               {@code positions} list, whose states the cache then watches); a region passes
	 *               {@code null}
	 */
	private static List<SelectedBlock> bakeListed(final Level level, final VFXMaskBlockSelection selection, final BlockState @Nullable [] states) {
		final List<SelectedBlock> found = new ArrayList<>();
		final Set<BlockPos> seen = new HashSet<>();
		final float[] region = selection.region();
		if (region != null) {
			final BlockPos low = blockPos(region, 0);
			final BlockPos high = blockPos(region, 3);
			for (final BlockPos pos : BlockPos.betweenClosed(low, high)) {
				final BlockPos immutable = pos.immutable();
				if (seen.add(immutable)) {
					addListed(found, level, immutable);
				}
			}
			return found;
		}
		final List<float[]> positions = selection.positions();
		for (int i = 0; i < positions.size(); i++) {
			final BlockPos pos = blockPos(positions.get(i), 0);
			if (states != null && i < states.length) {
				// Recorded for every listed coordinate, air included: a block placed at a coordinate
				// that was air at bake time must be seen as a change next frame.
				states[i] = level.getBlockState(pos);
			}
			if (seen.add(pos)) {
				addListed(found, level, pos);
			}
		}
		return found;
	}

	/** The block position of a coordinate triple starting at {@code offset} (0 for a 3-float list). */
	private static BlockPos blockPos(final float[] coordinates, final int offset) {
		return new BlockPos(
			VFXMaskBlockSelection.blockCoordinate(coordinates[offset]),
			VFXMaskBlockSelection.blockCoordinate(coordinates[offset + 1]),
			VFXMaskBlockSelection.blockCoordinate(coordinates[offset + 2]));
	}

	/** Bakes one listed position into {@code found} unless it is air or bakes no model geometry. */
	private static void addListed(final List<SelectedBlock> found, final Level level, final BlockPos pos) {
		final BlockState state = level.getBlockState(pos);
		if (state.isAir()) {
			return;
		}
		final List<BakedQuad> quads = VFXWorldOverlayRenderer.getModelQuads(Minecraft.getInstance(), state);
		if (quads.isEmpty()) {
			return;
		}
		found.add(new SelectedBlock(pos, quads));
	}


	/** The one-off scan behind {@link #select}; tag/id parsing and the property set are hoisted here. */
	private static List<SelectedBlock> scan(final Level level, final VFXMaskBlockSelection selection, final float[] center, final float radius) {
		final List<SelectedBlock> found = new ArrayList<>();
		final int r = (int) Math.ceil(Math.min(radius, VFXMaskBlockSelection.MAX_RADIUS));
		final BlockPos centerPos = BlockPos.containing(center[0], center[1], center[2]);
		final double radiusSq = (double) radius * radius;
		// Hoisted out of the per-position loop: the id set, the tag key and the property count are
		// fixed for one selection.
		final Set<String> ids = new HashSet<>(selection.blockIds());
		final TagKey<Block> tag = selection.tag() == null ? null : TagKey.create(Registries.BLOCK, Identifier.parse(selection.tag()));
		for (final BlockPos pos : BlockPos.betweenClosed(centerPos.offset(-r, -r, -r), centerPos.offset(r, r, r))) {
			if (found.size() >= VFXMaskBlockSelection.MAX_BLOCKS) {
				break;
			}
			if (pos.distSqr(centerPos) > radiusSq) {
				continue;
			}
			final BlockState state = level.getBlockState(pos);
			if (state.isAir() || !matches(state, selection, ids, tag)) {
				continue;
			}
			final List<BakedQuad> quads = VFXWorldOverlayRenderer.getModelQuads(Minecraft.getInstance(), state);
			if (quads.isEmpty()) {
				continue;
			}
			found.add(new SelectedBlock(pos.immutable(), quads));
		}
		return found;
	}

	private static boolean matches(final BlockState state, final VFXMaskBlockSelection selection, final Set<String> ids, final @Nullable TagKey<Block> tag) {
		if (!ids.isEmpty() && !ids.contains(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())) {
			return false;
		}
		if (tag != null && !state.is(tag)) {
			return false;
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
	 * Clears the geometry scratch and draws the selected blocks' real model quads into it with the
	 * coverage pipeline, camera-relative, before the coverage prepass samples it. Called once per
	 * distinct block mask per frame at screen layer 0.
	 *
	 * @param encoder    the frame encoder the coverage prepass shares (so the draw is submitted first)
	 * @param geometry   the mask's geometry scratch colour target
	 * @param mainTarget the frame's main target, whose depth view occluding leaves are tested against
	 * @param mask       the parsed mask whose block leaf drives the selection
	 * @param effect     the effect owning the mask, for the animated/faded radius parameter
	 * @param depthReady true when the scene depth is usable this frame; when false, every block is
	 *                   drawn x-ray (no depth test), matching the {@code surface_pattern} depth gate
	 */
	public static void render(
		final CommandEncoder encoder,
		final RenderTarget geometry,
		final RenderTarget mainTarget,
		final VFXMask mask,
		final VFXActiveEffect effect,
		final boolean depthReady
	) {
		clearGeometry(encoder, geometry);
		final VFXShaderPrograms.ProgramInfo program = VFXShaderPrograms.blockGeometryProgram();
		final Minecraft minecraft = Minecraft.getInstance();
		if (program == null || minecraft == null || minecraft.level == null) {
			return;
		}
		// At most one block leaf (the parser enforces it), so the shared scratch has one source.
		VFXMaskPrimitive blockPrimitive = null;
		for (final VFXMaskPrimitive primitive : mask.primitives()) {
			if (primitive.family() == VFXMaskPrimitive.Family.BLOCK && primitive.blockSelection() != null) {
				blockPrimitive = primitive;
				break;
			}
		}
		if (blockPrimitive == null) {
			return;
		}
		final Level level = minecraft.level;
		final VFXMaskBlockSelection selection = blockPrimitive.blockSelection();
		// A listed selection carries its own coordinates: its centre/radius are fixed literals (no
		// slot read), which keeps an animated radius from invalidating the baked-quad cache.
		final float radius = selection.listed() ? selection.radius() : blockRadius(mask, blockPrimitive, effect, selection);
		final float[] center = selection.listed() ? selection.center() : blockCenter(mask, blockPrimitive, effect, selection);
		final List<SelectedBlock> blocks = select(level, selection, center, radius);
		if (blocks.isEmpty()) {
			return;
		}
		final boolean textured = selection.listed();
		final GpuTextureView atlas = blockAtlasView();
		if (textured && atlas == null) {
			// No block atlas means no authoritative alpha; fail closed rather than paint the whole
			// model silhouette as coverage.
			return;
		}
		// A legacy block leaf never samples the atlas, but the sampler still has to be bound to a
		// view that is not this pass's own output: the main target's colour is always safe.
		final GpuTextureView atlasBind = atlas != null ? atlas : mainTarget.getColorTextureView();
		final boolean occlude = blockPrimitive.occlude() && depthReady;
		// The depth compare is in linear view distance, so it needs this frame's linearisation terms.
		// Without them an occluding mask would compare against stale numbers (or zero, which would let
		// it paint over walls), so fail closed: the scratch was cleared above, so the mask is empty.
		if (occlude && !VFXFieldEnv.depthLinearValid()) {
			return;
		}
		final boolean hollow = textured && blockPrimitive.fill() != VFXMaskFill.SOLID;
		// The outline width in blocks: the dilation of the outer pass (block area + outline) and, for
		// a hollow fill, the band above the inner pass. Unbounded above by design (AGENTS.md): a huge
		// value only widens the silhouette, the geometry count does not change.
		final float stroke = textured
			? Math.max(VFXMaskUniforms.slotValue(mask, effect, blockPrimitive.strokeSlot(), blockPrimitive.strokeDefault()), 0.0F)
			: 0.0F;
		// At most two draws, both into the same scratch: outer (dilated) always, inner only when the
		// fill is the band. The shader reads red for a solid fill and green for the band.
		final GpuBufferSlice transform = writeTransform(encoder);
		drawPass(encoder, geometry, mainTarget, program, blocks, occlude, textured, atlasBind, true, 1.0F + 2.0F * stroke, transform);
		if (hollow) {
			drawPass(encoder, geometry, mainTarget, program, blocks, occlude, textured, atlasBind, false, 1.0F, transform);
		}
	}

	/**
	 * Writes this frame's matrix and returns the slice to bind as the pass's {@code Projection}.
	 *
	 * <p>The CPU pass emits camera-relative vertices ({@code blockPos - cameraPos}), so the matrix is
	 * {@code projection * viewRotation} - i.e. the camera-bearing inverse view-projection inverted,
	 * with the camera translation put back. It goes into this pass's own UBO rather than the frame's
	 * {@code DynamicTransforms}: that struct's {@code ModelViewMat} already contains the camera
	 * translation, so a draw that read it while its vertices are camera-relative would subtract the
	 * camera twice and the geometry would slide across the blocks as the camera moves. Owning the
	 * uniform also keeps the transform out of the shared dynamic-uniform arena, where a slice written
	 * for a later pass could otherwise be the one the draw reads.
	 */
	private static GpuBufferSlice writeTransform(final CommandEncoder encoder) {
		GEOMETRY_MVP.set(VFXFieldEnv.invViewProj()).invert()
			.translate(VFXFieldEnv.cameraX(), VFXFieldEnv.cameraY(), VFXFieldEnv.cameraZ());
		TRANSFORM_BYTES.clear();
		final Std140Builder builder = Std140Builder.intoBuffer(TRANSFORM_BYTES);
		// mat4 as four column vec4s (std140-identical to putMat4f, which is package-private).
		builder.putVec4(GEOMETRY_MVP.m00(), GEOMETRY_MVP.m01(), GEOMETRY_MVP.m02(), GEOMETRY_MVP.m03());
		builder.putVec4(GEOMETRY_MVP.m10(), GEOMETRY_MVP.m11(), GEOMETRY_MVP.m12(), GEOMETRY_MVP.m13());
		builder.putVec4(GEOMETRY_MVP.m20(), GEOMETRY_MVP.m21(), GEOMETRY_MVP.m22(), GEOMETRY_MVP.m23());
		builder.putVec4(GEOMETRY_MVP.m30(), GEOMETRY_MVP.m31(), GEOMETRY_MVP.m32(), GEOMETRY_MVP.m33());
		if (transformUniform == null) {
			transformUniform = RenderSystem.getDevice().createBuffer(
				() -> "vfxweaver mask block transform", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 64L);
		}
		final GpuBufferSlice slice = transformUniform.slice(0L, 64L);
		encoder.writeToBuffer(slice, builder.get());
		writeDepthLinear(encoder);
		return slice;
	}

	/**
	 * Writes the frame's depth-linearisation terms ({@code zNdc = a + b / distance}) into the pass's
	 * {@code MaskDepth} UBO, taken from the <b>pure</b> projection ({@link VFXFieldEnv#depthLinearA()}).
	 * The fragment stage turns both its own depth and the scene's into view distances with them, so the
	 * occlusion test can use a tolerance in world blocks - the only unit in which "the same face" and "a
	 * wall in front" are distinguishable at every distance.
	 */
	private static void writeDepthLinear(final CommandEncoder encoder) {
		DEPTH_LINEAR_BYTES.clear();
		final Std140Builder builder = Std140Builder.intoBuffer(DEPTH_LINEAR_BYTES);
		builder.putVec4(VFXFieldEnv.depthLinearA(), VFXFieldEnv.depthLinearB(), 0.0F, 0.0F);
		if (depthLinearUniform == null) {
			depthLinearUniform = RenderSystem.getDevice().createBuffer(
				() -> "vfxweaver mask depth linear", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 16L);
		}
		encoder.writeToBuffer(depthLinearUniform.slice(0L, 16L), builder.get());
	}

	/**
	 * The block atlas texture view the alpha-aware leaf samples ({@code minecraft:textures/atlas/
	 * blocks.png}). Both node lines resolve it through their own {@code AtlasManager}, but the call
	 * chain is identical - {@code getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS).getTextureView()}
	 * - so it is written once, unguarded, and the per-node class never has to be named. A resource
	 * reload re-stitches the atlas and swaps the view, which is why it is resolved per frame (a map
	 * lookup, not a stitch).
	 */
	private static @Nullable GpuTextureView blockAtlasView() {
		try {
			return Minecraft.getInstance().getAtlasManager()
				.getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS)
				.getTextureView();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** One selected block plus its baked model quads (resolved once, reused for the cached selection). */
	private record SelectedBlock(BlockPos pos, List<BakedQuad> quads) {
	}

	/** The selection radius: the leaf's bound/animated slot value, clamped to the cap. */
	private static float blockRadius(final VFXMask mask, final VFXMaskPrimitive primitive, final VFXActiveEffect effect, final VFXMaskBlockSelection selection) {
		final String[] slots = primitive.parameterSlots();
		if (slots.length == 0) {
			return Math.min(Math.max(selection.radius(), 0.0F), VFXMaskBlockSelection.MAX_RADIUS);
		}
		final float fallback = primitive.parameterDefaults().length > 0 ? primitive.parameterDefaults()[0] : selection.radius();
		final float radius = VFXMaskUniforms.slotValue(mask, effect, slots[0], fallback);
		return Math.min(Math.max(radius, 0.0F), VFXMaskBlockSelection.MAX_RADIUS);
	}

	/**
	 * The selection centre with the same precedence the coverage writer uses: a resolved point
	 * binding wins, then the animated centre slot, then the parse-time literal. Resolving the slots
	 * here makes {@code VFXAPI.sendMaskMove}/{@code maskMove}, graph-driven centre slots and live
	 * {@code setParam} reach the scan region, which previously always read the bound point or the
	 * literal.
	 */
	private static float[] blockCenter(final VFXMask mask, final VFXMaskPrimitive primitive, final VFXActiveEffect effect, final VFXMaskBlockSelection selection) {
		final BoundParam binding = primitive.centerBinding();
		if (binding != null && binding.kind() == BoundParam.Kind.POINT) {
			final float[] bound = VFXWorldBindings.evaluatePoint(binding);
			if (bound != null && bound.length >= 3) {
				return new float[]{bound[0], bound[1], bound[2]};
			}
		}
		final String[] slots = primitive.centerSlots();
		final float[] defaults = primitive.centerDefaults();
		final float x = slots.length > 0 ? VFXMaskUniforms.slotValue(mask, effect, slots[0], defaults[0]) : defaults[0];
		final float y = slots.length > 1 ? VFXMaskUniforms.slotValue(mask, effect, slots[1], defaults[1]) : (defaults.length > 1 ? defaults[1] : 0.0F);
		final float z = slots.length > 2 ? VFXMaskUniforms.slotValue(mask, effect, slots[2], defaults[2]) : (defaults.length > 2 ? defaults[2] : 0.0F);
		return new float[]{x, y, z};
	}

	/** Clears the scratch before the draw; the coverage shader samples whatever is left after. */
	private static void clearGeometry(final CommandEncoder encoder, final RenderTarget geometry) {
		//? if <26.2 {
		encoder.clearColorTexture(geometry.getColorTexture(), 0);
		//?} else {
		/*encoder.clearColorTexture(geometry.getColorTexture(), new org.joml.Vector4f(0.0F, 0.0F, 0.0F, 0.0F));
		*///?}
	}

	/**
	 * Emits one pass of the selected blocks into the geometry scratch and draws it.
	 *
	 * @param textured true for a {@code block_list} leaf: the fragment shader takes the sprite's
	 *                 alpha from {@code BlockAtlasSampler} at the quad's baked atlas UV (otherwise
	 *                 the coverage is the constant 1 the {@code block} leaf has always written)
	 * @param atlas    the view bound as {@code BlockAtlasSampler}: the block atlas, or the main
	 *                 target's colour for a leaf that never samples it (never this pass's own output)
	 * @param outer    true for the dilated pass (the block area plus the outline), false for the
	 *                 inner pass a {@code hollow} fill subtracts; the fragment shader writes the
	 *                 outer pass to the scratch's red channel and the inner pass to green
	 * @param scale    1.0 for the authored geometry, {@code 1 + 2 * stroke_width} for the dilated pass
	 * @param transform this frame's {@code Projection} slice (projection * viewRotation), written once
	 *                  per frame and shared by both passes
	 */
	private static void drawPass(
		final CommandEncoder encoder,
		final RenderTarget geometry,
		final RenderTarget mainTarget,
		final VFXShaderPrograms.ProgramInfo program,
		final List<SelectedBlock> blocks,
		final boolean occlude,
		final boolean textured,
		final @Nullable GpuTextureView atlas,
		final boolean outer,
		final float scale,
		final GpuBufferSlice transform
	) {
		final float camX = VFXFieldEnv.cameraX();
		final float camY = VFXFieldEnv.cameraY();
		final float camZ = VFXFieldEnv.cameraZ();
		final int color = passColor(occlude, textured, outer);
		//? if <26.2 {
		final BufferBuilder builder = new BufferBuilder(staging(), VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
		//?} else {
		/*final BufferBuilder builder = new BufferBuilder(staging(), PrimitiveTopology.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR);
		*///?}
		POSE.pushPose();
		for (final SelectedBlock block : blocks) {
			final List<BakedQuad> quads = block.quads();
			if (quads.isEmpty()) {
				continue;
			}
			POSE.pushPose();
			POSE.translate(block.pos().getX() - camX, block.pos().getY() - camY, block.pos().getZ() - camZ);
			for (final BakedQuad quad : quads) {
				emitTriangle(builder, quad, 0, 1, 2, color, scale);
				emitTriangle(builder, quad, 0, 2, 3, color, scale);
			}
			POSE.popPose();
		}
		POSE.popPose();

		try (MeshData mesh = builder.build()) {
			if (mesh == null) {
				return;
			}
			final int meshBytes = mesh.vertexBuffer().remaining();
			final GpuBuffer buffer = ensureVertexBuffer(meshBytes);
			encoder.writeToBuffer(buffer.slice(0L, meshBytes), mesh.vertexBuffer());
			final int vertexCount = mesh.drawState().vertexCount();

			try (RenderPass renderPass = encoder.createRenderPass(
					() -> "VFX mask block geometry",
					geometry.getColorTextureView(),
					//? if <26.2 {
					OptionalInt.empty()
					//?} else {
					/*Optional.empty()
					*///?}
				)) {
				renderPass.setPipeline(program.pipeline());
				// The whole transform rides in this pass's own `Projection` uniform: projection *
				// viewRotation, no translation, over the camera-relative vertices. The frame's
				// DynamicTransforms is not touched, so the camera cannot be subtracted twice.
				renderPass.setUniform("Projection", transform);
				renderPass.setUniform("MaskDepth", depthLinearUniform.slice(0L, 16L));
				// The scene depth an occluding leaf's fragments are tested against. NEAREST: a depth
				// texture is not filterable (depth findings); the pass runs at layer 0, so the view
				// still holds the world surface.
				renderPass.bindTexture("DepthSampler", mainTarget.getDepthTextureView(),
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
				// The block texture atlas the alpha of a listed leaf is read from. NEAREST: the UVs
				// are absolute atlas UVs, so filtering across a sprite's padded border would smear a
				// neighbour's alpha into a cutout edge.
				renderPass.bindTexture("BlockAtlasSampler", atlas,
					RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
				//? if <26.2 {
				renderPass.setVertexBuffer(0, buffer);
				renderPass.draw(0, vertexCount);
				//?} else {
				/*renderPass.setVertexBuffer(0, buffer.slice(0L, meshBytes));
				renderPass.draw(vertexCount, 1, 0, 0);
				*///?}
			}
		}
	}

	/**
	 * The vertex colour one pass carries. The fragment shader reads three bits out of it:
	 * red = "this is the outer pass" (it writes the scratch's green channel differently), blue =
	 * "sample the atlas alpha" (a listed leaf) rather than the constant 1 a {@code block} leaf
	 * writes, alpha = the leaf's occlusion flag (opaque occludes, alpha 0 is the x-ray look, also
	 * forced when depth is not trusted).
	 */
	private static int passColor(final boolean occlude, final boolean textured, final boolean outer) {
		final int alpha = occlude ? 0xFF000000 : 0x00000000;
		final int red = outer ? 0x00FF0000 : 0x00000000;
		final int blue = textured ? 0x000000FF : 0x00000000;
		return alpha | red | blue;
	}

	private static void emitTriangle(final BufferBuilder builder, final BakedQuad quad, final int a, final int b, final int c, final int color, final float scale) {
		emitVertex(builder, quad, a, color, scale);
		emitVertex(builder, quad, b, color, scale);
		emitVertex(builder, quad, c, color, scale);
	}

	/**
	 * One quad vertex with its baked atlas UV. The UV is used <b>as is</b>: the model bakery already
	 * packed the atlas-space coordinate, because {@code FaceBakery} applies the sprite's sub-rect
	 * before packing ({@code UVPair.pack(sprite.getU(localU), sprite.getV(localV))} - the same
	 * convention {@code VertexConsumer.putBulkData}/{@code putBakedQuad} rely on when vanilla draws
	 * baked quads). Running the packed value through the sprite's U/V rect a second time - what
	 * {@code VFXPostProcessingManager} must do for a {@code surface_pattern} texture, whose datapack
	 * UV rect is 0..1 sprite-local - would sample a sub-crop of the sprite and mis-place its cutout
	 * alpha, which is exactly the requirement this leaf has. {@code scale} grows the quad about the
	 * block centre for the dilated pass.
	 */
	private static void emitVertex(final BufferBuilder builder, final BakedQuad quad, final int index, final int color, final float scale) {
		final Vector3fc position = quad.position(index);
		final long uv = quad.packedUV(index);
		builder.addVertex(POSE.last(), dilated(position.x(), scale), dilated(position.y(), scale), dilated(position.z(), scale))
			.setColor(color)
			.setUv(UVPair.unpackU(uv), UVPair.unpackV(uv));
	}

	/** Grows a block-local coordinate away from the block centre (0.5, 0.5, 0.5). */
	private static float dilated(final float value, final float scale) {
		return scale == 1.0F ? value : 0.5F + (value - 0.5F) * scale;
	}

	private static ByteBufferBuilder staging() {
		if (staging == null) {
			staging = new ByteBufferBuilder(65536);
		}
		return staging;
	}

	/** Reuses one growable GPU vertex buffer; a bigger selection rebuilds it. */
	private static GpuBuffer ensureVertexBuffer(final int requiredBytes) {
		if (vertexBuffer == null || vertexBufferCapacity < requiredBytes) {
			if (vertexBuffer != null) {
				vertexBuffer.close();
			}
			final int capacity = Math.max(4096, Integer.highestOneBit(Math.max(requiredBytes, 1)) * 2);
			vertexBuffer = RenderSystem.getDevice().createBuffer(
				() -> "vfxweaver mask geometry vertices",
				GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, capacity);
			vertexBufferCapacity = capacity;
		}
		return vertexBuffer;
	}

	/** Releases the cached GPU/native resources on client shutdown. */
	public static void freeGpuResources() {
		if (vertexBuffer != null) {
			vertexBuffer.close();
			vertexBuffer = null;
			vertexBufferCapacity = 0L;
		}
		if (transformUniform != null) {
			transformUniform.close();
			transformUniform = null;
		}
		if (depthLinearUniform != null) {
			depthLinearUniform.close();
			depthLinearUniform = null;
		}
		if (staging != null) {
			staging.close();
			staging = null;
		}
		invalidateSelectionCache();
	}
}

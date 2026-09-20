package dev.vfxweaver.client.postprocessing;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
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
import dev.vfxweaver.mask.VFXMaskPrimitive;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
//? if >=26.2 {
/*import java.util.Optional;
*///?}
import java.util.OptionalInt;
import net.minecraft.client.Minecraft;
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
 * The block-geometry contribution to mask coverage (expanded design). Selects blocks by id / tag /
 * block-state property inside a bounded region and rasterises their real baked model geometry
 * (stairs, slabs, fences, plants) into the mask's geometry scratch, reusing the block-overlay path
 * ({@code VFXWorldOverlayRenderer.getModelQuads} / {@code hasBlockModelGeometry} and the
 * {@code core/position_color} conventions). It is CPU-bounded by {@link VFXMaskBlockSelection} and
 * is never a per-pixel fragment-shader lookup.
 *
 * <p>The draw is camera-relative (the selected blocks' vertices are offset by {@code -cameraPos} and
 * placed in {@code DynamicTransforms.ModelViewMat}) with an identity {@code Projection}, so the
 * world coordinates stay small enough for float precision. The whole contribution is one vertex
 * buffer of triangles written into the scratch before the coverage prepass samples it.
 */
public final class VFXMaskBlockGeometry {
	/** Camera-relative model-view-projection of the current frame; never mutated past the draw. */
	private static final Matrix4f GEOMETRY_MVP = new Matrix4f();
	private static final Vector4f IDENTITY_COLOR = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
	private static final Vector3f ZERO_OFFSET = new Vector3f();
	private static final Matrix4f IDENTITY_TEXTURE = new Matrix4f();
	/** Reused across frames; balanced push/pop keeps it valid. */
	private static final PoseStack POSE = new PoseStack();

	/** CPU staging for the vertex data; reused so the per-frame build allocates no native buffer. */
	private static @Nullable ByteBufferBuilder staging;
	/** The GPU vertex buffer, grown on demand and reused across frames. */
	private static @Nullable GpuBuffer vertexBuffer;
	private static long vertexBufferCapacity;
	/** A one-off identity {@code Projection} UBO (std140 mat4). */
	private static @Nullable GpuBuffer identityProjection;

	private VFXMaskBlockGeometry() {
	}

	/** A cached selection plus the model manager it was baked against; recomputed on a key change. */
	private static @Nullable SelectionCache selectionCache;

	/**
	 * The matching blocks inside the selection region, each with its baked model quads resolved
	 * once, capped at {@link VFXMaskBlockSelection#MAX_BLOCKS}. Air and block-entity-only blocks
	 * (empty baked model) are skipped. The result is cached keyed by the level, the model manager,
	 * the selection identity and the resolved centre/radius, so the O((2r+1)^3) scan and the model
	 * resolve run only when the region changes or a resource reload replaces the model manager.
	 */
	private static List<SelectedBlock> select(final Level level, final VFXMaskBlockSelection selection, final float[] center, final float radius) {
		final Object modelManager = Minecraft.getInstance().getModelManager();
		final SelectionCache cached = selectionCache;
		if (cached != null && cached.matches(level, modelManager, selection, center, radius)) {
			return cached.blocks();
		}
		final List<SelectedBlock> blocks = scan(level, selection, center, radius);
		selectionCache = new SelectionCache(level, modelManager, selection, center[0], center[1], center[2], radius, blocks);
		return blocks;
	}

	/** Drops the cached selection; called when the world/level or a resource reload makes it stale. */
	public static void invalidateSelectionCache() {
		selectionCache = null;
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
		final float radius = blockRadius(mask, blockPrimitive, effect, selection);
		final float[] center = blockCenter(mask, blockPrimitive, effect, selection);
		final List<SelectedBlock> blocks = select(level, selection, center, radius);
		if (blocks.isEmpty()) {
			return;
		}
		drawBlocks(encoder, geometry, mainTarget, program, blocks, blockPrimitive.occlude() && depthReady);
	}

	/** One selected block plus its baked model quads (resolved once, reused for the cached selection). */
	private record SelectedBlock(BlockPos pos, List<BakedQuad> quads) {
	}

	/** The selection key plus the blocks baked against it; an exact identity/region match reuses it. */
	private record SelectionCache(Level level, Object modelManager, VFXMaskBlockSelection selection, float centerX, float centerY, float centerZ, float radius, List<SelectedBlock> blocks) {
		private boolean matches(final Level level, final Object modelManager, final VFXMaskBlockSelection selection, final float[] center, final float radius) {
			return this.level == level && this.modelManager == modelManager && this.selection.equals(selection)
				&& this.centerX == center[0] && this.centerY == center[1] && this.centerZ == center[2] && this.radius == radius;
		}
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

	private static void drawBlocks(
		final CommandEncoder encoder,
		final RenderTarget geometry,
		final RenderTarget mainTarget,
		final VFXShaderPrograms.ProgramInfo program,
		final List<SelectedBlock> blocks,
		final boolean occlude
	) {
		final float camX = VFXFieldEnv.cameraX();
		final float camY = VFXFieldEnv.cameraY();
		final float camZ = VFXFieldEnv.cameraZ();
		// The vertex colour's alpha is the leaf's occlusion flag the fragment shader reads:
		// opaque white occludes, alpha 0 is the x-ray look (also forced when depth is not trusted).
		final int color = occlude ? 0xFFFFFFFF : 0x00FFFFFF;
		//? if <26.2 {
		final BufferBuilder builder = new BufferBuilder(staging(), VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
		//?} else {
		/*final BufferBuilder builder = new BufferBuilder(staging(), PrimitiveTopology.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
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
				emitTriangle(builder, quad, 0, 1, 2, color);
				emitTriangle(builder, quad, 0, 2, 3, color);
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

			// viewRotationProjection (no translation): the vertices are already camera-relative.
			GEOMETRY_MVP.set(VFXFieldEnv.invViewProj()).invert().translate(camX, camY, camZ);
			final GpuBufferSlice transform = RenderSystem.getDynamicUniforms()
				.writeTransform(GEOMETRY_MVP, IDENTITY_COLOR, ZERO_OFFSET, IDENTITY_TEXTURE);
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
				renderPass.setUniform("Projection", ensureIdentityProjection());
				renderPass.setUniform("DynamicTransforms", transform);
				// The scene depth an occluding leaf's fragments are tested against. NEAREST: a depth
				// texture is not filterable (depth findings); the pass runs at layer 0, so the view
				// still holds the world surface.
				renderPass.bindTexture("DepthSampler", mainTarget.getDepthTextureView(),
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

	private static void emitTriangle(final BufferBuilder builder, final BakedQuad quad, final int a, final int b, final int c, final int color) {
		emitVertex(builder, quad.position(a), color);
		emitVertex(builder, quad.position(b), color);
		emitVertex(builder, quad.position(c), color);
	}

	private static void emitVertex(final BufferBuilder builder, final Vector3fc position, final int color) {
		builder.addVertex(POSE.last(), position.x(), position.y(), position.z()).setColor(color);
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

	/** The std140 identity mat4 bound as {@code Projection} (the full transform rides in ModelViewMat). */
	private static GpuBuffer ensureIdentityProjection() {
		if (identityProjection == null) {
			final ByteBuffer data = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
			final Std140Builder builder = Std140Builder.intoBuffer(data);
			builder.putVec4(1.0F, 0.0F, 0.0F, 0.0F);
			builder.putVec4(0.0F, 1.0F, 0.0F, 0.0F);
			builder.putVec4(0.0F, 0.0F, 1.0F, 0.0F);
			builder.putVec4(0.0F, 0.0F, 0.0F, 1.0F);
			identityProjection = RenderSystem.getDevice().createBuffer(
				() -> "vfxweaver mask identity projection", GpuBuffer.USAGE_UNIFORM, builder.get());
		}
		return identityProjection;
	}

	/** Releases the cached GPU/native resources on client shutdown. */
	public static void freeGpuResources() {
		if (vertexBuffer != null) {
			vertexBuffer.close();
			vertexBuffer = null;
			vertexBufferCapacity = 0L;
		}
		if (identityProjection != null) {
			identityProjection.close();
			identityProjection = null;
		}
		if (staging != null) {
			staging.close();
			staging = null;
		}
		invalidateSelectionCache();
	}
}

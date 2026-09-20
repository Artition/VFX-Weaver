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
import dev.vfxweaver.effect.VFXActiveEffect;
import dev.vfxweaver.effect.VFXWorldBindings;
import dev.vfxweaver.mask.VFXMask;
import dev.vfxweaver.mask.VFXMaskBlockSelection;
import dev.vfxweaver.mask.VFXMaskPrimitive;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
	 * Clears the geometry scratch and draws the selected blocks' real model quads into it with the
	 * coverage pipeline, camera-relative, before the coverage prepass samples it. Called once per
	 * distinct block mask per frame at screen layer 0.
	 *
	 * @param encoder    the frame encoder the coverage prepass shares (so the draw is submitted first)
	 * @param geometry   the mask's geometry scratch colour target
	 * @param mainTarget the frame's main target, whose depth view occluding leaves are tested against
	 * @param mask       the parsed mask whose block leaves drive the selection
	 * @param effect     the effect owning the mask, for the animated/faded radius parameter
	 */
	public static void render(
		final CommandEncoder encoder,
		final RenderTarget geometry,
		final RenderTarget mainTarget,
		final VFXMask mask,
		final VFXActiveEffect effect
	) {
		if (!clearGeometry(encoder, geometry)) {
			return;
		}
		final VFXShaderPrograms.ProgramInfo program = VFXShaderPrograms.blockGeometryProgram();
		final Minecraft minecraft = Minecraft.getInstance();
		if (program == null || minecraft == null || minecraft.level == null) {
			return;
		}
		final Level level = minecraft.level;
		final List<SelectedBlock> blocks = new ArrayList<>();
		for (final VFXMaskPrimitive primitive : mask.primitives()) {
			if (primitive.family() != VFXMaskPrimitive.Family.BLOCK || primitive.blockSelection() == null) {
				continue;
			}
			final VFXMaskBlockSelection selection = primitive.blockSelection();
			final float radius = blockRadius(mask, primitive, effect, selection);
			final float[] center = blockCenter(primitive, selection);
			for (final BlockPos pos : select(level, selection, center, radius)) {
				blocks.add(new SelectedBlock(pos, primitive.occlude()));
			}
		}
		if (blocks.isEmpty()) {
			return;
		}
		drawBlocks(encoder, geometry, mainTarget, program, minecraft, blocks);
	}

	/** One selected block plus whether its leaf opts into scene-depth occlusion (`"occlude"`). */
	private record SelectedBlock(BlockPos pos, boolean occlude) {
	}

	/** The selection radius: the leaf's bound/animated slot value, clamped to the cap. */
	private static float blockRadius(final VFXMask mask, final VFXMaskPrimitive primitive, final VFXActiveEffect effect, final VFXMaskBlockSelection selection) {
		final String[] slots = primitive.parameterSlots();
		float radius = selection.radius();
		if (slots.length > 0) {
			final float fallback = primitive.parameterDefaults().length > 0 ? primitive.parameterDefaults()[0] : selection.radius();
			final VFXMask.MaskSlot slot = mask.slots().get(slots[0]);
			radius = slot != null && slot.binding() != null
				? VFXWorldBindings.evaluate(slot.binding(), fallback)
				: effect.getParam(slots[0], fallback);
		}
		return Math.min(Math.max(radius, 0.0F), VFXMaskBlockSelection.MAX_RADIUS);
	}

	/** The selection centre: a resolved point binding when present, else the literal region centre. */
	private static float[] blockCenter(final VFXMaskPrimitive primitive, final VFXMaskBlockSelection selection) {
		if (primitive.centerBinding() != null) {
			final float[] bound = VFXWorldBindings.evaluatePoint(primitive.centerBinding());
			if (bound != null && bound.length >= 3) {
				return new float[]{bound[0], bound[1], bound[2]};
			}
		}
		return selection.center();
	}

	/** Clears the scratch; returns false when the target cannot be cleared (and the draw is skipped). */
	private static boolean clearGeometry(final CommandEncoder encoder, final RenderTarget geometry) {
		//? if <26.2 {
		encoder.clearColorTexture(geometry.getColorTexture(), 0);
		//?} else {
		/*encoder.clearColorTexture(geometry.getColorTexture(), new org.joml.Vector4f(0.0F, 0.0F, 0.0F, 0.0F));
		*///?}
		return true;
	}

	private static void drawBlocks(
		final CommandEncoder encoder,
		final RenderTarget geometry,
		final RenderTarget mainTarget,
		final VFXShaderPrograms.ProgramInfo program,
		final Minecraft minecraft,
		final List<SelectedBlock> blocks
	) {
		final float camX = VFXFieldEnv.cameraX();
		final float camY = VFXFieldEnv.cameraY();
		final float camZ = VFXFieldEnv.cameraZ();
		// The vertex colour's alpha is the per-leaf occlusion flag the fragment shader reads:
		// opaque white occludes, alpha 0 is the x-ray look.
		final int occludedColor = 0xFFFFFFFF;
		final int xrayColor = 0x00FFFFFF;
		//? if <26.2 {
		final BufferBuilder builder = new BufferBuilder(staging(), VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
		//?} else {
		/*final BufferBuilder builder = new BufferBuilder(staging(), PrimitiveTopology.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
		*///?}
		POSE.pushPose();
		for (final SelectedBlock block : blocks) {
			final List<BakedQuad> quads = VFXWorldOverlayRenderer.getModelQuads(minecraft, minecraft.level.getBlockState(block.pos()));
			if (quads.isEmpty()) {
				continue;
			}
			final int color = block.occlude() ? occludedColor : xrayColor;
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
	}
}

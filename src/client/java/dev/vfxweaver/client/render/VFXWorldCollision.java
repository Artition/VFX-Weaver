package dev.vfxweaver.client.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * World-collision helper shared by the physics overlays (the {@code block_chain} verlet rope and,
 * later, the block-particle engine): pushes a point out of the solid block it overlaps.
 *
 * <p>{@link #resolve} sweeps the segment the point travelled this step (its previous position to
 * its new one) and ejects it through the face it entered. A fast joint that would otherwise land
 * past a block's far face therefore rests on the entry side instead of tunnelling through. With no
 * entry direction (no previous contact, i.e. spawned inside a block) it falls back to the
 * smallest-penetration face, since there is no entry side to prefer.
 */
public final class VFXWorldCollision {
	/** Sweep sampling step, blocks: small enough that a joint cannot cross a block between samples. */
	private static final double SWEEP_STEP = 0.25;

	private VFXWorldCollision() {
	}

	/**
	 * Resolves the overlap of a point with the solid blocks it is inside, leaving a small padding
	 * so it rests just outside the collision surface.
	 *
	 * @param level     the client level the point lives in
	 * @param pos       the point's new position this step
	 * @param padding   the gap left between the point and the collision surface
	 * @param entryHint the point's previous position (the entry side), or {@code null} when there
	 *                  is no previous contact
	 * @return the depenetrated position, or {@code pos} when it does not overlap a solid block
	 */
	public static Vec3 resolve(final ClientLevel level, final Vec3 pos, final double padding, final @Nullable Vec3 entryHint) {
		final AABB entryBox = entryHint == null ? null : collisionBox(level, entryHint, padding);
		if (entryBox != null && strictlyInside(entryBox, entryHint)) {
			// Already inside a block with no usable entry face (spawn inside): nearest-face rule.
			return resolveNearestFace(level, pos, padding);
		}
		if (entryHint == null) {
			return resolveNearestFace(level, pos, padding);
		}
		// Sweep from the entry position; the first sample inside a block is the contact, and it is
		// ejected back through the face it came in by.
		final Vec3 delta = pos.subtract(entryHint);
		final double dist = delta.length();
		if (dist < 1.0e-9) {
			return pos;
		}
		final int steps = (int) Math.ceil(dist / SWEEP_STEP);
		Vec3 from = entryHint;
		for (int s = 1; s <= steps; s++) {
			final Vec3 sample = entryHint.add(delta.scale((double) s / steps));
			final AABB box = collisionBox(level, sample, padding);
			if (box != null && box.contains(sample.x, sample.y, sample.z)) {
				return ejectThroughFaceNearestTo(box, sample, from);
			}
			from = sample;
		}
		return pos;
	}

	/** Smallest-penetration resolution: ejects {@code p} through the face nearest to {@code p} itself. */
	private static Vec3 resolveNearestFace(final ClientLevel level, final Vec3 p, final double padding) {
		final AABB box = collisionBox(level, p, padding);
		if (box == null || !box.contains(p.x, p.y, p.z)) {
			return p;
		}
		return ejectThroughFaceNearestTo(box, p, p);
	}

	/** Collision box of the block at {@code p}, inflated by {@code padding}, or null in open space. */
	private static @Nullable AABB collisionBox(final ClientLevel level, final Vec3 p, final double padding) {
		final BlockPos bp = BlockPos.containing(p.x, p.y, p.z);
		final VoxelShape shape = level.getBlockState(bp).getCollisionShape(level, bp);
		if (shape.isEmpty()) {
			return null;
		}
		// ponytail: the collision shape's union bounding box approximates multi-box shapes (stairs,
		// slabs, fences, walls). Switch to per-voxel shapes if it ever shows in game.
		return shape.bounds().move(bp).inflate(padding);
	}

	/** True when {@code p} is inside the box and not on its surface (so it can be treated as entry). */
	private static boolean strictlyInside(final AABB box, final Vec3 p) {
		return p.x > box.minX && p.x < box.maxX
			&& p.y > box.minY && p.y < box.maxY
			&& p.z > box.minZ && p.z < box.maxZ;
	}

	/** Pushes {@code p} out through the box face closest to {@code reference} (the entry side). */
	private static Vec3 ejectThroughFaceNearestTo(final AABB box, final Vec3 p, final Vec3 reference) {
		final double dxMin = Math.abs(reference.x - box.minX);
		final double dxMax = Math.abs(box.maxX - reference.x);
		final double dyMin = Math.abs(reference.y - box.minY);
		final double dyMax = Math.abs(box.maxY - reference.y);
		final double dzMin = Math.abs(reference.z - box.minZ);
		final double dzMax = Math.abs(box.maxZ - reference.z);
		double best = dxMin;
		Vec3 out = new Vec3(box.minX, p.y, p.z);
		if (dxMax < best) {
			best = dxMax;
			out = new Vec3(box.maxX, p.y, p.z);
		}
		if (dyMin < best) {
			best = dyMin;
			out = new Vec3(p.x, box.minY, p.z);
		}
		if (dyMax < best) {
			best = dyMax;
			out = new Vec3(p.x, box.maxY, p.z);
		}
		if (dzMin < best) {
			best = dzMin;
			out = new Vec3(p.x, p.y, box.minZ);
		}
		if (dzMax < best) {
			out = new Vec3(p.x, p.y, box.maxZ);
		}
		return out;
	}
}

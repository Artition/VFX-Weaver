package dev.vfxweaver.client.render;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * World-collision helper shared by the physics overlays (the {@code block_chain} verlet rope and,
 * later, the block-particle engine): pushes a point out of the solid blocks it overlaps.
 *
 * <p>{@link #resolve} queries the level's real collision set through
 * {@code CollisionGetter.getBlockCollisions(null, box)} and resolves against the individual
 * {@link VoxelShape} AABBs, so multi-box shapes (stairs, slabs, fences, walls) are handled per
 * voxel instead of through a union bounding box. A moving joint is swept from its previous
 * position and ejected through the face it entered (a fast joint therefore rests on the entry
 * side instead of tunnelling); a joint embedded with no usable entry direction is lifted upward
 * (jumping above every overlapping shape when that spot is clear, otherwise climbing one shape
 * per pass) because the rope is born hanging into the ground, and only falls back to the face
 * needing the smallest correction when nothing is above it. The resolution runs a couple of
 * passes per call so a joint caught between two blocks settles instead of oscillating.
 */
public final class VFXWorldCollision {
	/** Sweep sampling step, blocks: small enough that a joint cannot cross a block between samples. */
	private static final double SWEEP_STEP = 0.25;
	/** Extra gap past the collision surface so the query box does not exactly touch the shape. */
	private static final double SKIN = 1.0e-4;
	/** Depenetration passes per call; a joint between two blocks settles instead of oscillating. */
	private static final int RESOLVE_PASSES = 2;

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
		final boolean usableEntry = entryHint != null
			&& pos.distanceToSqr(entryHint) > 1.0e-12
			&& queryBoxes(level, entryHint, padding).isEmpty();
		if (usableEntry) {
			// Moving joint: sweep prev->pos and eject at the first contact through the face it came
			// in by (this also catches a joint that tunnelled through a thin shape into open space),
			// then settle any remaining overlap with smallest-correction passes.
			Vec3 current = pos;
			final Vec3 contact = firstContact(level, entryHint, pos, padding);
			if (contact != null) {
				current = contact;
			} else if (queryBoxes(level, pos, padding).isEmpty()) {
				return pos;
			}
			for (int pass = 0; pass < RESOLVE_PASSES; pass++) {
				final List<AABB> boxes = queryBoxes(level, current, padding);
				if (boxes.isEmpty()) {
					break;
				}
				current = ejectSmallest(boxes, current, padding);
			}
			return current;
		}
		if (queryBoxes(level, pos, padding).isEmpty()) {
			return pos;
		}
		// Embedded joint (born inside solid ground, no entry direction): lift upward, never sink.
		Vec3 current = pos;
		for (int pass = 0; pass < RESOLVE_PASSES; pass++) {
			final List<AABB> boxes = queryBoxes(level, current, padding);
			if (boxes.isEmpty()) {
				break;
			}
			current = liftOrSmallest(level, boxes, current, padding);
		}
		return current;
	}

	/**
	 * Sweeps {@code from -> to} in {@link #SWEEP_STEP} samples and ejects at the first sample whose
	 * padded point box overlaps the real collision set, through the face nearest the previous
	 * sample. Returns {@code null} when the sweep never contacts a shape.
	 */
	private static @Nullable Vec3 firstContact(final ClientLevel level, final Vec3 from, final Vec3 to, final double padding) {
		final Vec3 delta = to.subtract(from);
		final double dist = delta.length();
		if (dist < 1.0e-9) {
			return null;
		}
		final int steps = Math.max(1, (int) Math.ceil(dist / SWEEP_STEP));
		Vec3 previous = from;
		for (int s = 1; s <= steps; s++) {
			final Vec3 sample = from.add(delta.scale((double) s / steps));
			final List<AABB> boxes = queryBoxes(level, sample, padding);
			if (!boxes.isEmpty()) {
				return ejectToward(boxes, sample, previous, padding);
			}
			previous = sample;
		}
		return null;
	}

	/** Lifts {@code p} out upward, else takes the smallest correction (only a ceiling has no +Y). */
	private static Vec3 liftOrSmallest(final ClientLevel level, final List<AABB> boxes, final Vec3 p, final double padding) {
		double top = -Double.MAX_VALUE;
		for (final AABB box : boxes) {
			top = Math.max(top, box.maxY);
		}
		// Preferred: jump straight above every overlapping shape when that spot is clear.
		final Vec3 lifted = new Vec3(p.x, top + padding + SKIN, p.z);
		if (queryBoxes(level, lifted, padding).isEmpty()) {
			return lifted;
		}
		// Terrain continues above: climb through the +Y face of the nearest shape above the point
		// (never sink; the rope is born hanging into the ground, so down is the wrong escape).
		double best = Double.MAX_VALUE;
		Vec3 up = null;
		for (final AABB box : boxes) {
			final double dy = box.maxY + padding + SKIN - p.y;
			if (dy > 1.0e-9 && dy < best) {
				best = dy;
				up = new Vec3(p.x, box.maxY + padding + SKIN, p.z);
			}
		}
		if (up != null) {
			return up;
		}
		return ejectSmallest(boxes, p, padding);
	}

	/** Pushes {@code p} out through the face closest to {@code reference} (the entry side). */
	private static Vec3 ejectToward(final List<AABB> boxes, final Vec3 p, final Vec3 reference, final double padding) {
		double best = Double.MAX_VALUE;
		Vec3 out = p;
		for (final AABB box : boxes) {
			final double dxMin = Math.abs(reference.x - (box.minX - padding));
			final double dxMax = Math.abs(reference.x - (box.maxX + padding));
			final double dyMin = Math.abs(reference.y - (box.minY - padding));
			final double dyMax = Math.abs(reference.y - (box.maxY + padding));
			final double dzMin = Math.abs(reference.z - (box.minZ - padding));
			final double dzMax = Math.abs(reference.z - (box.maxZ + padding));
			if (dxMin < best) {
				best = dxMin;
				out = new Vec3(box.minX - padding - SKIN, p.y, p.z);
			}
			if (dxMax < best) {
				best = dxMax;
				out = new Vec3(box.maxX + padding + SKIN, p.y, p.z);
			}
			if (dyMin < best) {
				best = dyMin;
				out = new Vec3(p.x, box.minY - padding - SKIN, p.z);
			}
			if (dyMax < best) {
				best = dyMax;
				out = new Vec3(p.x, box.maxY + padding + SKIN, p.z);
			}
			if (dzMin < best) {
				best = dzMin;
				out = new Vec3(p.x, p.y, box.minZ - padding - SKIN);
			}
			if (dzMax < best) {
				best = dzMax;
				out = new Vec3(p.x, p.y, box.maxZ + padding + SKIN);
			}
		}
		return out;
	}

	/** Ejects {@code p} through whichever real shape face needs the smallest outward correction. */
	private static Vec3 ejectSmallest(final List<AABB> boxes, final Vec3 p, final double padding) {
		double best = Double.MAX_VALUE;
		Vec3 out = p;
		for (final AABB box : boxes) {
			final double dxMin = p.x - (box.minX - padding);
			final double dxMax = (box.maxX + padding) - p.x;
			final double dyMin = p.y - (box.minY - padding);
			final double dyMax = (box.maxY + padding) - p.y;
			final double dzMin = p.z - (box.minZ - padding);
			final double dzMax = (box.maxZ + padding) - p.z;
			if (dxMin > 1.0e-9 && dxMin < best) {
				best = dxMin;
				out = new Vec3(box.minX - padding - SKIN, p.y, p.z);
			}
			if (dxMax > 1.0e-9 && dxMax < best) {
				best = dxMax;
				out = new Vec3(box.maxX + padding + SKIN, p.y, p.z);
			}
			if (dyMin > 1.0e-9 && dyMin < best) {
				best = dyMin;
				out = new Vec3(p.x, box.minY - padding - SKIN, p.z);
			}
			if (dyMax > 1.0e-9 && dyMax < best) {
				best = dyMax;
				out = new Vec3(p.x, box.maxY + padding + SKIN, p.z);
			}
			if (dzMin > 1.0e-9 && dzMin < best) {
				best = dzMin;
				out = new Vec3(p.x, p.y, box.minZ - padding - SKIN);
			}
			if (dzMax > 1.0e-9 && dzMax < best) {
				best = dzMax;
				out = new Vec3(p.x, p.y, box.maxZ + padding + SKIN);
			}
		}
		return out;
	}

	/** Real collision AABBs overlapping the padded point box, or an empty list in open space. */
	private static List<AABB> queryBoxes(final ClientLevel level, final Vec3 p, final double padding) {
		final AABB query = new AABB(
			p.x - padding, p.y - padding, p.z - padding,
			p.x + padding, p.y + padding, p.z + padding
		);
		final List<AABB> boxes = new ArrayList<>();
		for (final VoxelShape shape : level.getBlockCollisions(null, query)) {
			if (!shape.isEmpty()) {
				boxes.addAll(shape.toAabbs());
			}
		}
		return boxes;
	}
}

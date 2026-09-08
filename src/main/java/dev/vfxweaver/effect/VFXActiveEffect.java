package dev.vfxweaver.effect;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.jspecify.annotations.Nullable;

/**
 * A single running instance of a {@link VFXDefinition}. Holds a {@link VFXTimeline} that is
 * advanced every frame with the current elapsed tick time; the driving clock is maintained
 * by the client (tick counter + partial tick) but the class itself is side-agnostic.
 */
public class VFXActiveEffect {
	private final Identifier id;
	private final VFXEffectType type;
	private final long instanceId;
	/** Per-instance random seed so that generated noise (camera shake, {@code expr} params) differs between calls. */
	private final long instanceSeed;
	private final float startTime;
	private final VFXTimeline timeline;
	private final int fadeTicks;
	private final boolean loop;
	private final List<BlockPos> positions;
	private final List<UUID> entityUuids;
	private final List<ResolvedAnchor> anchors;
	private final @Nullable String particleId;
	private final @Nullable String shape;
	private final @Nullable String blockId;
	private float elapsed;
	private float age;
	private float fadeOutStart = Float.NEGATIVE_INFINITY;
	/**
	 * Runtime-mutable world position the instance was moved to (network MOVE action), read by
	 * the world overlay renderer to re-anchor its drawing every frame.
	 */
	private @Nullable Vec3 movePosition;

	/**
	 * Creates a new effect instance starting at the given time.
	 *
	 * @param id        effect id
	 * @param type      effect kind
	 * @param startTime start time in ticks on the driving clock
	 * @param timeline  pre-built animation timeline
	 */
	public VFXActiveEffect(final Identifier id, final VFXEffectType type, final float startTime, final VFXTimeline timeline) {
		this(id, type, 0L, ThreadLocalRandom.current().nextLong(), startTime, timeline, 0, false, List.of(), List.of());
	}

	/**
	 * Creates a new effect instance with an optional fade duration and looping.
	 *
	 * @param fadeTicks ticks the effect takes to fade in on start and out when stopped
	 * @param loop      true when the timeline restarts once it reaches its duration
	 */
	public VFXActiveEffect(final Identifier id, final VFXEffectType type, final float startTime, final VFXTimeline timeline, final int fadeTicks, final boolean loop) {
		this(id, type, 0L, ThreadLocalRandom.current().nextLong(), startTime, timeline, fadeTicks, loop, List.of(), List.of());
	}

	/**
	 * Creates a new effect instance with fade, looping and world positions.
	 */
	public VFXActiveEffect(final Identifier id, final VFXEffectType type, final float startTime, final VFXTimeline timeline, final int fadeTicks, final boolean loop, final List<BlockPos> positions) {
		this(id, type, 0L, ThreadLocalRandom.current().nextLong(), startTime, timeline, fadeTicks, loop, positions, List.of());
	}

	/**
	 * Creates a new effect instance with a caller-assigned instance id (used to address one of
	 * several concurrent instances of the same effect, e.g. for {@code /vfx stop} or the network
	 * stop action).
	 */
	public VFXActiveEffect(final Identifier id, final VFXEffectType type, final long instanceId, final float startTime, final VFXTimeline timeline, final int fadeTicks, final boolean loop, final List<BlockPos> positions) {
		this(id, type, instanceId, ThreadLocalRandom.current().nextLong(), startTime, timeline, fadeTicks, loop, positions, List.of());
	}

	/**
	 * Creates a new effect instance with a caller-assigned instance id and noise seed.
	 *
	 * @param instanceSeed per-instance seed used to drive generated noise (camera shake, {@code expr})
	 */
	public VFXActiveEffect(final Identifier id, final VFXEffectType type, final long instanceId, final long instanceSeed, final float startTime, final VFXTimeline timeline, final int fadeTicks, final boolean loop, final List<BlockPos> positions) {
		this(id, type, instanceId, instanceSeed, startTime, timeline, fadeTicks, loop, positions, List.of());
	}

	/**
	 * Creates a new effect instance with an instance id, noise seed and entity UUID targets.
	 *
	 * @param instanceSeed per-instance seed used to drive generated noise (camera shake, {@code expr})
	 * @param entityUuids  entity UUIDs this effect applies to (for entity tint/outline)
	 */
	public VFXActiveEffect(final Identifier id, final VFXEffectType type, final long instanceId, final long instanceSeed, final float startTime, final VFXTimeline timeline, final int fadeTicks, final boolean loop, final List<BlockPos> positions, final List<UUID> entityUuids) {
		this(id, type, instanceId, instanceSeed, startTime, timeline, fadeTicks, loop, positions, entityUuids, List.of(), null, null, null);
	}

	/**
	 * Creates a new effect instance with an instance id, noise seed, entity UUID targets and
	 * resolved entity anchors (for world overlays whose {@code positions} entries are anchored
	 * to live entities).
	 *
	 * @param instanceSeed per-instance seed used to drive generated noise (camera shake, {@code expr})
	 * @param entityUuids  entity UUIDs this effect applies to (for entity tint/outline, or the
	 *                     resolved anchor entities of a world overlay)
	 * @param anchors      entity-anchored position slots (empty when all positions are static)
	 */
	public VFXActiveEffect(final Identifier id, final VFXEffectType type, final long instanceId, final long instanceSeed, final float startTime, final VFXTimeline timeline, final int fadeTicks, final boolean loop, final List<BlockPos> positions, final List<UUID> entityUuids, final List<ResolvedAnchor> anchors) {
		this(id, type, instanceId, instanceSeed, startTime, timeline, fadeTicks, loop, positions, entityUuids, anchors, null, null, null);
	}

	/**
	 * Creates a new effect instance with the full state, including the definition-level string
	 * fields used by {@code particles}/{@code block_chain} effects.
	 *
	 * @param particleId vanilla particle id for {@code particles} effects ({@code null} = renderer default)
	 * @param shape      emission shape for {@code particles} effects ({@code null} = renderer default)
	 * @param blockId    block id for {@code block_chain} effects ({@code null} = renderer default)
	 */
	public VFXActiveEffect(final Identifier id, final VFXEffectType type, final long instanceId, final long instanceSeed, final float startTime, final VFXTimeline timeline, final int fadeTicks, final boolean loop, final List<BlockPos> positions, final List<UUID> entityUuids, final List<ResolvedAnchor> anchors, final @Nullable String particleId, final @Nullable String shape, final @Nullable String blockId) {
		this.id = id;
		this.type = type;
		this.instanceId = instanceId;
		this.instanceSeed = instanceSeed;
		this.startTime = startTime;
		this.timeline = timeline;
		this.fadeTicks = fadeTicks;
		this.loop = loop;
		this.positions = List.copyOf(positions);
		this.entityUuids = List.copyOf(entityUuids);
		this.anchors = List.copyOf(anchors);
		this.particleId = particleId;
		this.shape = shape;
		this.blockId = blockId;
		this.elapsed = 0.0F;
		this.age = 0.0F;
	}

	/**
	 * Advances the effect to the given clock time (in ticks). When looping, the timeline time
	 * wraps around the timeline duration; {@link #getAge()} keeps the unwrapped time for fades.
	 *
	 * @param now current clock time in ticks
	 */
	public void update(final float now) {
		float raw = Math.max(0.0F, now - this.startTime);
		this.age = raw;
		if (this.loop) {
			this.elapsed = raw % Math.max(this.timeline.getDuration(), 1.0F);
		} else {
			this.elapsed = raw;
		}
		this.timeline.update(this.elapsed);
	}

	/**
	 * Marks this instance as fading out (called when it is stopped).
	 */
	public void beginFadeOut(final float now) {
		if (!this.isFadingOut()) {
			this.fadeOutStart = now;
		}
	}

	/**
	 * True after {@link #beginFadeOut(float)} was called.
	 */
	public boolean isFadingOut() {
		return this.fadeOutStart != Float.NEGATIVE_INFINITY;
	}

	/**
	 * Current fade weight in {@code [0, 1]}: ramps up over {@code fadeTicks} after the start and
	 * ramps back down once the instance is fading out.
	 */
	public float getWeight() {
		float weight = 1.0F;
		if (this.fadeTicks > 0) {
			weight = Math.min(1.0F, this.age / this.fadeTicks);
		}
		if (this.isFadingOut()) {
			if (this.fadeTicks <= 0) {
				return 0.0F;
			}
			float since = this.age - (this.fadeOutStart - this.startTime);
			weight = Math.min(weight, Math.max(0.0F, 1.0F - since / this.fadeTicks));
		}
		return weight;
	}

	/**
	 * True when the effect reached the end of its timeline, finished fading out, or never ends
	 * because it loops (looping instances only end once stopped).
	 */
	public boolean isFinished() {
		if (this.isFadingOut()) {
			return this.getWeight() <= 0.0F;
		}
		if (this.loop) {
			return false;
		}
		// A non-looping instance whose runtime edits have all animated to zero is invisible:
		// remove it instead of letting it linger (persistent instances would never end otherwise).
		if (this.timeline.isZeroedOut()) {
			return true;
		}
		return this.timeline.isFinished();
	}

	/**
	 * Reads the current value of a parameter, falling back to {@code fallback} when absent.
	 */
	public float getParam(final String name, final float fallback) {
		return this.timeline.getValue(name, fallback);
	}

	/**
	 * Normalized progress in {@code [0, 1]}.
	 */
	public float getProgress() {
		return this.timeline.getProgress();
	}

	public Identifier getId() {
		return this.id;
	}

	public long getInstanceId() {
		return this.instanceId;
	}

	public long getInstanceSeed() {
		return this.instanceSeed;
	}

	public VFXEffectType getType() {
		return this.type;
	}

	public float getStartTime() {
		return this.startTime;
	}

	public float getElapsed() {
		return this.elapsed;
	}

	/**
	 * Unwrapped time since the effect started (does not wrap when looping).
	 */
	public float getAge() {
		return this.age;
	}

	public float getDuration() {
		return this.timeline.getDuration();
	}

	/**
	 * True when the timeline restarts once it reaches its duration.
	 */
	public boolean isLooping() {
		return this.loop;
	}

	public VFXTimeline getTimeline() {
		return this.timeline;
	}

	public int getFadeTicks() {
		return this.fadeTicks;
	}

	/**
	 * Moves this instance to a new world position (network MOVE action). The world overlay
	 * renderer picks the new position up on its next frame; spatial bindings are re-anchored
	 * separately via {@link VFXTimeline#rebindPositions(double, double, double)}.
	 */
	public void movePosition(final Vec3 worldPos) {
		this.movePosition = worldPos;
	}

	/**
	 * The world position this instance was last moved to, or {@code null} when it was never moved.
	 */
	public @Nullable Vec3 getMovePosition() {
		return this.movePosition;
	}

	/**
	 * World-space positions this effect applies to (for block highlighting effects).
	 */
	public List<BlockPos> getPositions() {
		return this.positions;
	}

	/**
	 * Entity UUIDs this effect applies to (for entity tint/outline effects). Empty for non-entity effects.
	 */
	public List<UUID> getEntityUuids() {
		return this.entityUuids;
	}

	/**
	 * Entity-anchored position slots of a world overlay: the placeholder slot index, the tracked
	 * entity (resolved by the server at play time) and the offset from its feet position. Empty
	 * when all positions are static.
	 */
	public List<ResolvedAnchor> getAnchors() {
		return this.anchors;
	}

	/**
	 * Vanilla particle id for {@code particles} effects (from the definition, {@code null} = renderer default).
	 */
	public @Nullable String getParticleId() {
		return this.particleId;
	}

	/**
	 * Emission shape for {@code particles} effects (from the definition, {@code null} = renderer default).
	 */
	public @Nullable String getShape() {
		return this.shape;
	}

	/**
	 * Block id for {@code block_chain} effects (from the definition, {@code null} = renderer default).
	 */
	public @Nullable String getBlockId() {
		return this.blockId;
	}

	/**
	 * A resolved entity anchor for one {@code positions} slot: the renderer substitutes the
	 * tracked entity's current anchor-point position (optionally pushed along an entity
	 * direction) plus the offset for the placeholder block every frame.
	 *
	 * @param slot     index into the effect's position list
	 * @param uuid     tracked entity UUID
	 * @param offset   offset from the resolved anchor point
	 * @param point    reference point on the entity: {@code feet}, {@code center} or {@code eyes}
	 * @param dir      entity direction push: {@code none} or {@code look}
	 * @param distance how far to push along {@code dir} (blocks)
	 */
	public record ResolvedAnchor(int slot, UUID uuid, Vec3 offset, String point, String dir, double distance) {
	}
}

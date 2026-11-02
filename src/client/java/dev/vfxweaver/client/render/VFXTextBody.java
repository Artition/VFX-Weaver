package dev.vfxweaver.client.render;

import dev.vfxweaver.effect.VFXBlockParticleSpec;
import net.minecraft.world.phys.Vec3;

/**
 * One falling unit's body for the {@code text_overlay} exit: a character treated as a light point
 * that falls, bounces once off the ground under it and comes to rest, integrated once per client tick
 * exactly as {@link VFXBlockParticleEngine} integrates a block particle.
 *
 * <p>The state is deliberately tiny - two offsets, two velocities, an angle and an angular velocity,
 * plus the two flags the rest and the floor need - because a text block can carry up to 256 units and
 * every one of them would otherwise pay for a box, an inertia tensor and a world query per tick. It
 * is the model of a 2D character physics pass (MusicSubtitles' {@code SubtitleBubble$CharPhysics})
 * lifted into the world: the only thing the body ever asks the world is <strong>which Y it lands
 * on</strong>, and it asks once, when the unit detaches, through a single downward raycast supplied
 * by the renderer. From then on a step is arithmetic on six numbers.
 *
 * <p>That is the whole trade, and it is stated rather than hidden: a letter lands on the ground
 * <em>under the spot it detached from</em> and it cannot tip over an edge, slide down a slope, wedge
 * itself on a wall or tumble through a gap, because it never looks at the world again. What it does
 * give you is a fall that reads as a fall - it drops at the authored `gravity`, drags, drifts
 * sideways, spins in its own plane at a rate that follows how fast it is drifting, kicks back once on
 * landing and then lies still - for a cost that does not grow with the terrain under it.
 *
 * <p>The angle turns the glyph about its own plane normal, so it can never end up edge-on and
 * invisible; `tumble` picks its direction per unit. Orientation is left to the caller: the body
 * reports an angle, and the renderer composes it with the block rotation it already has.
 *
 * <p><b>Not verified in game.</b>
 */
public final class VFXTextBody {
	/**
	 * Margin, in blocks, below the level's minimum build height at which a still-falling body counts
	 * as having left the world. Nothing exists below that floor - there is no block to land on and
	 * no surface to see - so the margin only keeps a borderline tick from flickering the release; it
	 * is not a distance the effect authors and it is never measured against a surface the letter
	 * stood on. The renderer starts the same {@code hold} + {@code fade_out} ramp from that moment,
	 * so a body that cannot land - dropped out of the world, or a unit with no floor under it at all
	 * - still reaches zero and releases its units.
	 */
	private static final double RELEASE_BELOW_WORLD = 8.0;
	/** Linear speed (blocks/tick) under which the body counts as still. */
	private static final double REST_LINEAR_SPEED = 0.01;
	/** Angular speed (radians/tick) under which the body counts as still. */
	private static final float REST_ANGULAR_SPEED = 0.01F;
	/** Consecutive still ticks before the body is declared resting; 0.2 s at 20 Hz. */
	private static final int REST_TICKS = 4;
	/**
	 * Cap on the roll, radians per tick (about 1.6 turns a second). The rate is derived from the
	 * drift, so a letter thrown across the world would otherwise spin absurdly fast - a guard on a
	 * rate, not a size, and it is the only number here that clips anything.
	 */
	static final float MAX_SPIN = 0.5F;
	/** Floor for the roll radius, blocks: a zero-sized glyph would divide by zero. */
	private static final float MIN_ROLL_RADIUS = 1.0e-3F;

	private final Vec3 rest;
	/** The Y the unit lands on, or NaN when there is nothing under it (no raycast hit, or no collision). */
	private final double floorY;
	/** Radians of roll per block of horizontal travel: a glyph rolls over its own half-height. */
	private final float rollGain;
	/** The authored spin, radians per tick, signed; `tumble` has already picked its sign. */
	private final float spinSeed;

	private double extraX;
	private double extraY;
	private double prevExtraX;
	private double prevExtraY;
	private double velX;
	private double velY;
	private float rot;
	private float prevRot;
	private float spin;
	private boolean touched;
	private boolean resting;
	private int stillTicks;

	/**
	 * Creates one body at its detach state. The offsets and the angle start at zero - the body's
	 * position <em>is</em> the rest position until it moves - so the first frame interpolates from
	 * where the letter stands instead of from nowhere.
	 *
	 * @param position  world position of the display when it detaches (the bottom of the glyph, since
	 *                  a display draws upward from its position)
	 * @param velocity  blocks per tick; only X and Y are used, the model being a 2D fall
	 * @param spinSeed  the authored spin, radians per tick, already signed by {@code tumble} and
	 *                  scaled by the unit's jitter
	 * @param radius    the glyph's half-height in blocks, the radius it rolls over; floored so a
	 *                  degenerate unit cannot divide by zero
	 * @param floorY    the Y the glyph lands on, or {@link Double#NaN} when nothing is under it
	 */
	public VFXTextBody(final Vec3 position, final Vec3 velocity, final float spinSeed,
			final float radius, final double floorY) {
		this.rest = position;
		this.velX = velocity.x;
		this.velY = velocity.y;
		this.spinSeed = spinSeed;
		this.rollGain = 1.0F / Math.max(radius, MIN_ROLL_RADIUS);
		this.floorY = floorY;
	}

	/**
	 * The position to display this frame: a lerp from where the body was at the start of its last
	 * tick to where it ended, exactly as {@link VFXBlockParticleEngine} interpolates a block
	 * particle, so 20 Hz physics still moves smoothly. A resting body has no tick to interpolate
	 * and hands its settled position back.
	 *
	 * @param fraction the frame's leftover tick fraction, {@code 0..1} (clamped)
	 * @return a fresh vector; the caller may keep or mutate it
	 */
	public Vec3 pos(final float fraction) {
		final float t = fraction < 0.0F ? 0.0F : (fraction > 1.0F ? 1.0F : fraction);
		return new Vec3(
			this.rest.x + this.prevExtraX + (this.extraX - this.prevExtraX) * t,
			this.rest.y + this.prevExtraY + (this.extraY - this.prevExtraY) * t,
			this.rest.z);
	}

	/**
	 * The roll to draw this frame, in radians about the glyph's own plane normal: a lerp between the
	 * angle at the start of the last tick and the one it ended with, for the same reason the
	 * position is lerped.
	 *
	 * @param fraction the frame's leftover tick fraction, {@code 0..1} (clamped)
	 * @return the angle in radians; the caller composes it with the block rotation
	 */
	public float angle(final float fraction) {
		final float t = fraction < 0.0F ? 0.0F : (fraction > 1.0F ? 1.0F : fraction);
		return this.prevRot + (this.rot - this.prevRot) * t;
	}

	/** @return {@code true} once the body has settled; {@link #step} then only returns */
	public boolean resting() {
		return this.resting;
	}

	/**
	 * Whether the body has left the world: it fell below the level's minimum build height (plus
	 * {@link #RELEASE_BELOW_WORLD}) and so can never land. A resting body is never stranded: its
	 * landing, however low, is a landing and the normal rest rule already starts its fade.
	 *
	 * @param minY the level's minimum build height, which is the lowest Y anything can rest at
	 * @return {@code true} once a non-resting body is further than {@link #RELEASE_BELOW_WORLD}
	 *         blocks below {@code minY}
	 */
	public boolean stranded(final double minY) {
		return !this.resting && this.rest.y + this.extraY < minY - RELEASE_BELOW_WORLD;
	}

	/**
	 * Advances the body by one client tick: gravity, air drag, the move, the floor, then the roll.
	 *
	 * <p>Gravity adds {@code GRAVITY_STEP * gravity} to the downward velocity and the air drag
	 * multiplies <em>both</em> velocities, exactly as the block-particle engine does, then the
	 * offsets are integrated by them. When the raycast found a floor and the glyph has reached it,
	 * the first touch <strong>bounces once</strong> ({@code velY = -velY * bounce}), and every
	 * contact - the first one included - damps the horizontal drift by {@code friction} and stops
	 * the roll, because a letter lying on the ground does not keep turning. After the first touch
	 * the vertical velocity is simply dropped, so one `bounce` is one hop and not a trampoline.
	 *
	 * <p>The roll is derived from the horizontal velocity the way the reference model does - a glyph
	 * rolling over its own half-height turns by {@code drift / radius} radians per block travelled -
	 * on top of the authored spin, capped at {@link #MAX_SPIN}; once the body has touched down the
	 * angle stops drifting and stays wherever it landed.
	 *
	 * @param params the physics for this tick
	 */
	public void step(final Params params) {
		if (this.resting) {
			return;
		}
		this.prevExtraX = this.extraX;
		this.prevExtraY = this.extraY;
		this.prevRot = this.rot;
		this.velY -= VFXBlockParticleSpec.GRAVITY_STEP * params.gravity();
		final double drag = params.friction();
		this.velX *= drag;
		this.velY *= drag;
		this.extraX += this.velX;
		this.extraY += this.velY;
		if (!Double.isNaN(this.floorY) && this.rest.y + this.extraY <= this.floorY) {
			this.extraY = this.floorY - this.rest.y;
			if (this.touched) {
				this.velY = 0.0;
			} else {
				this.touched = true;
				this.velY = -this.velY * params.bounce();
			}
			this.velX *= drag;
			this.spin = 0.0F;
		}
		if (!this.touched) {
			final float roll = this.spinSeed + (float) this.velX * this.rollGain;
			this.spin = roll > MAX_SPIN ? MAX_SPIN : (roll < -MAX_SPIN ? -MAX_SPIN : roll);
		}
		this.rot += this.spin;
		settle();
	}

	/**
	 * Rest rule: once both speeds have stayed under their thresholds for four consecutive ticks the
	 * body is declared resting, its motion is zeroed and its pose frozen. That is the whole of
	 * "settled" here - the angle it happens to have is the final one, and the renderer starts its
	 * {@code hold} + {@code fade_out} ramp from the tick {@link #resting()} turns true. A body that
	 * never slows down (free fall, or a perfectly elastic, frictionless bounce) keeps being stepped
	 * until its effect stops it or it leaves the world - {@link #stranded} for the depth, the
	 * renderer's own release time for a fall that never ends.
	 */
	private void settle() {
		final double linear = Math.sqrt(this.velX * this.velX + this.velY * this.velY);
		if (linear < REST_LINEAR_SPEED && Math.abs(this.spin) < REST_ANGULAR_SPEED) {
			this.stillTicks++;
			if (this.stillTicks >= REST_TICKS) {
				this.resting = true;
				this.velX = 0.0;
				this.velY = 0.0;
				this.spin = 0.0F;
				// The rest pose is the final one: a settled body is no longer moved, so 'prev'
				// would otherwise stay one step behind and every frame would interpolate the body
				// back and forth across that last move.
				this.prevExtraX = this.extraX;
				this.prevExtraY = this.extraY;
				this.prevRot = this.rot;
			}
		} else {
			this.stillTicks = 0;
		}
	}

	/**
	 * The physics one step needs, all of it owned by the caller: the effect's params, read the same
	 * way the block-particle engine reads them.
	 *
	 * @param gravity  downward velocity added per tick, as a multiple of
	 *                 {@link VFXBlockParticleSpec#GRAVITY_STEP} — the block-particle spec's reading,
	 *                 so the two engines agree. A caller holding blocks per second squared divides by
	 *                 {@code 400 * GRAVITY_STEP} (= 16); zero is weightless, and there is no upper
	 *                 limit.
	 * @param friction air drag per tick, {@code 0..1} (1 = none); also the share of the horizontal
	 *                 drift kept on a contact, so it is the same damping the engine applies
	 * @param bounce   restitution of the one rebound the landing gets, {@code 0..1}; {@code 0}
	 *                 lands dead, and only a value of exactly {@code 1} keeps a unit hopping
	 */
	public record Params(float gravity, float friction, float bounce) {
		public Params {
			// Friction and restitution are normalized fractions, so they are clamped to their range;
			// gravity is taken as authored.
			friction = friction < 0.0F ? 0.0F : (friction > 1.0F ? 1.0F : friction);
			bounce = bounce < 0.0F ? 0.0F : (bounce > 1.0F ? 1.0F : bounce);
		}
	}
}

package dev.vfxweaver.client.render;

import dev.vfxweaver.effect.VFXBlockParticleSpec;
import net.minecraft.world.phys.Vec3;

/**
 * One falling unit's body for the {@code text_overlay} exit: a character treated as a light point
 * that falls, bounces off the surface under it and comes to rest, integrated once per client tick
 * exactly as {@link VFXBlockParticleEngine} integrates a block particle.
 *
 * <p>The state is deliberately tiny - two offsets, two velocities, an angle and an angular velocity,
 * plus the flags the rest and the floor need - because a text block can carry up to 256 units and
 * every one of them would otherwise pay for a box, an inertia tensor and a world query per tick. It
 * is the model of a 2D character physics pass (MusicSubtitles' {@code SubtitleBubble$CharPhysics})
 * lifted into whatever space the caller draws in: the only thing the body ever asks the world is
 * <strong>which Y it lands on</strong>, and it asks once, when the unit detaches, through a single
 * downward raycast supplied by the renderer. From then on a step is arithmetic on a handful of
 * numbers.
 *
 * <p>The same body drives the HUD exit, in screen pixels and through {@link Bounds#frame}: there the
 * four edges of the game window are the floor, the two walls and the ceiling, so a flat letter
 * inherits the landing, the rebound, the edge collisions and the roll instead of re-deriving them
 * from the clock. Only the space differs - the body never learns which one it is in beyond the
 * {@code ySign} of its bounds, which tells it on which side of its Y axis the floor lies.
 *
 * <p>That is the whole trade, and it is stated rather than hidden: in the world a letter lands on
 * the ground <em>under the spot it detached from</em> and it cannot tip over an edge, slide down a
 * slope, wedge itself on a wall or tumble through a gap, because it never looks at the world again.
 * What it does give you is a fall that reads as a fall - it drops along the authored gravity
 * direction, drags, drifts sideways, spins in its own plane at a rate that follows how fast it is
 * drifting, kicks back on landing and then lies still - for a cost that does not grow with the
 * terrain under it.
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
	/** Linear speed under which the body counts as still, in the unit of the space it lives in. */
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
	/** Floor for the roll radius: a zero-sized glyph would divide by zero. */
	private static final float MIN_ROLL_RADIUS = 1.0e-3F;
	/**
	 * How far a landed glyph rests off the surface it hit, in the space's own unit: two centimetres
	 * in the world. Landing exactly in the surface plane puts the glyph's quad coplanar with the
	 * block's top face, so the two fight for the same depth values and the letter shimmers with
	 * z-fighting from a shallow angle. In screen pixels the same number is a fraction of a pixel and
	 * changes nothing; the lift is a rendering fix, not a physics one.
	 */
	private static final double LANDING_LIFT = 0.02;

	/**
	 * The surface a body lives against, in the body's own space.
	 *
	 * @param ySign   {@code +1} when the Y axis grows <em>towards</em> the floor (screen space, where
	 *                y grows downwards) and {@code -1} when it grows away from it (world space)
	 * @param floorY  the coordinate the glyph lands on, i.e. the floor; {@link Double#NaN} for no
	 *                floor at all, which is a free fall
	 * @param roofY   the opposite edge, a ceiling it bounces off; {@link Double#NaN} when the space
	 *                has none above the fall, as the world has not
	 * @param minX    the left wall, or {@link Double#NaN} for none
	 * @param maxX    the right wall, or {@link Double#NaN} for none
	 */
	public record Bounds(double ySign, double floorY, double roofY, double minX, double maxX) {
		/** No edges at all: a free fall that nothing can stop. */
		public static Bounds free() {
			return new Bounds(-1.0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
		}

		/**
		 * Only the ground, as the world fall has: a floor under the glyph and nothing else - no
		 * wall to stop a drift and no ceiling over a rebound.
		 *
		 * @param floorY the Y the glyph lands on, or {@link Double#NaN} when nothing is under it
		 * @return the world-space bounds
		 */
		public static Bounds ground(final double floorY) {
			return new Bounds(-1.0, floorY, Double.NaN, Double.NaN, Double.NaN);
		}

		/**
		 * The four edges of a box, as the HUD exit has: the floor, the two walls and the ceiling.
		 * Screen space, so the floor is the bottom edge and the Y axis grows towards it.
		 *
		 * @param floorY the bottom edge the glyph lands on
		 * @param roofY  the top edge it rebounds from
		 * @param minX   the left edge
		 * @param maxX   the right edge
		 * @return the box bounds
		 */
		public static Bounds frame(final double floorY, final double roofY, final double minX,
				final double maxX) {
			return new Bounds(1.0, floorY, roofY, minX, maxX);
		}
	}

	private final Vec3 rest;
	private final Bounds bounds;
	/** Radians of roll per unit of horizontal travel: a glyph rolls over its own half-height. */
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
	private boolean pinned;
	private boolean resting;
	private int stillTicks;

	/**
	 * Creates one body at its detach state. The offsets and the angle start at zero - the body's
	 * position <em>is</em> the rest position until it moves - so the first frame interpolates from
	 * where the letter stands instead of from nowhere.
	 *
	 * @param position  the reference point of the glyph when it detaches: the world-space bottom of
	 *                  the display (a display draws upward from its position) or, on the HUD, the
	 *                  centre of the unit's box in screen pixels
	 * @param velocity  units per tick; only X and Y are used, the model being a 2D fall
	 * @param spinSeed  the authored spin, radians per tick, already signed by {@code tumble} and
	 *                  scaled by the unit's jitter
	 * @param radius    the glyph's half-height, the radius it rolls over; floored so a degenerate
	 *                  unit cannot divide by zero
	 * @param bounds    the edges this body lives against
	 */
	public VFXTextBody(final Vec3 position, final Vec3 velocity, final float spinSeed,
			final float radius, final Bounds bounds) {
		this.rest = position;
		this.velX = velocity.x;
		this.velY = velocity.y;
		this.spinSeed = spinSeed;
		this.rollGain = 1.0F / Math.max(radius, MIN_ROLL_RADIUS);
		this.bounds = bounds;
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
	 * landing, however low, is a landing and the normal rest rule already starts its fade. Only
	 * meaningful for a body bounded by the world; the HUD's box keeps its letters inside.
	 *
	 * @param minY the level's minimum build height, which is the lowest Y anything can rest at
	 * @return {@code true} once a non-resting body is further than {@link #RELEASE_BELOW_WORLD}
	 *         units below {@code minY}
	 */
	public boolean stranded(final double minY) {
		return !this.resting && this.rest.y + this.extraY < minY - RELEASE_BELOW_WORLD;
	}

	/**
	 * Advances the body by one client tick: acceleration, air drag, the move, the edges, then the
	 * roll.
	 *
	 * <p>The acceleration is a <strong>vector</strong>, not a downward scalar: it adds
	 * {@code GRAVITY_STEP * (gravityX, gravityY)} to the velocity, so an angled - and animated -
	 * gravity bends the whole trajectory instead of only pulling harder. The air drag multiplies
	 * <em>both</em> velocities afterwards, exactly as the block-particle engine does, and the
	 * offsets are integrated by them.
	 *
	 * <p>When there is a floor and the glyph has reached it, the first touch <strong>bounces
	 * once</strong> ({@code velY = -velY * bounce}), and every contact - the first one included -
	 * damps the horizontal drift by {@code friction} and stops the roll, because a letter lying on
	 * the ground does not keep turning. The next contact after that rebound <strong>pins</strong>
	 * the body: from then on the floor holds it vertically and it simply keeps the floor height,
	 * while the horizontal part of the gravity keeps dragging it along - that is what makes an
	 * angled gravity visible in a settled line. That also removes the tremble: a pinned body can no
	 * longer be pushed a tick's worth of fall below the surface and snapped back up. It lands
	 * {@link #LANDING_LIFT} off the surface rather than in it, so the glyph is never coplanar with
	 * the face it hit.
	 *
	 * <p>The walls and the roof - the HUD frame - reflect the velocity by {@code bounce} and clamp
	 * the offset back inside, and they are checked whether or not the body is pinned, so a letter
	 * dragged along the bottom edge stops at the side of the picture instead of leaving it.
	 *
	 * <p>The roll is derived from the horizontal velocity the way the reference model does - a glyph
	 * rolling over its own half-height turns by {@code drift / radius} radians per unit travelled -
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
		final double accelX = VFXBlockParticleSpec.GRAVITY_STEP * params.gravityX();
		final double accelY = VFXBlockParticleSpec.GRAVITY_STEP * params.gravityY();
		final double drag = params.friction();
		if (this.pinned && !liftsOff(accelY)) {
			// In contact: the floor holds the glyph vertically, so there is no vertical integration.
			// The offset is written back to the landing height every tick, so 'prev' and 'extra'
			// agree and the drawn glyph is a still letter instead of one wobbling across the
			// surface. The horizontal part of the gravity still drags it along the floor.
			this.velY = 0.0;
			this.extraY = landingOffset();
			this.velX = (this.velX + accelX) * drag;
			this.extraX += this.velX;
			collideX(params.bounce(), accelX);
			this.spin = 0.0F;
			settle();
			return;
		}
		if (this.pinned) {
			// The gravity now points away from the floor: the letter lifts off and falls again.
			this.pinned = false;
			this.touched = false;
		}
		this.velX = (this.velX + accelX) * drag;
		this.velY = (this.velY + accelY) * drag;
		this.extraX += this.velX;
		this.extraY += this.velY;
		collideX(params.bounce(), accelX);
		collideRoof(params.bounce(), accelY);
		final double floorY = this.bounds.floorY();
		if (!Double.isNaN(floorY)
			&& this.bounds.ySign() * (this.rest.y + this.extraY - floorY) >= -LANDING_LIFT) {
			this.extraY = landingOffset();
			if (this.touched) {
				// The rebound is over and the body is back on the floor: pin it there.
				this.pinned = true;
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

	// Whether a pinned body's acceleration points away from the floor, so it has to lift off. The
	// body's Y axis grows towards the floor when ySign is +1 (screen space), so 'away' is -ySign*Y.
	private boolean liftsOff(final double accelY) {
		return -this.bounds.ySign() * accelY > 0.0;
	}

	// Clamps the horizontal offset inside the two walls and reflects the drift off them by
	// 'bounce', so a letter thrown at the side of the frame comes back instead of leaving it. A wall
	// the gravity keeps pushing into holds the letter instead of bouncing it, which is what lets a
	// dragged line come to rest against an edge and start its fade. Both walls are NaN in the world,
	// where the fall has no sides.
	private void collideX(final float bounce, final double accelX) {
		final double minX = this.bounds.minX();
		if (!Double.isNaN(minX) && this.rest.x + this.extraX < minX) {
			this.extraX = minX - this.rest.x;
			this.velX = accelX < 0.0 ? 0.0 : Math.abs(this.velX) * bounce;
		}
		final double maxX = this.bounds.maxX();
		if (!Double.isNaN(maxX) && this.rest.x + this.extraX > maxX) {
			this.extraX = maxX - this.rest.x;
			this.velX = accelX > 0.0 ? 0.0 : -Math.abs(this.velX) * bounce;
		}
	}

	// The roof, the mirror of the floor: a letter bounced upward (or pulled up by an angled gravity)
	// stops at the top edge and comes back down. NaN in the world, which has no ceiling. A roof the
	// gravity keeps pressing the letter into holds it there, the same way a wall does.
	private void collideRoof(final float bounce, final double accelY) {
		final double roofY = this.bounds.roofY();
		if (Double.isNaN(roofY)
			|| this.bounds.ySign() * (this.rest.y + this.extraY - roofY) > LANDING_LIFT) {
			return;
		}
		this.extraY = roofY + this.bounds.ySign() * LANDING_LIFT - this.rest.y;
		this.velY = liftsOff(accelY) ? 0.0 : this.bounds.ySign() * Math.abs(this.velY) * bounce;
		this.spin = 0.0F;
	}

	// The offset of the glyph while it stands on its floor: the floor itself plus LANDING_LIFT,
	// measured from the rest position the offsets are relative to.
	private double landingOffset() {
		return this.bounds.floorY() + this.bounds.ySign() * LANDING_LIFT - this.rest.y;
	}

	/**
	 * Rest rule: once both speeds have stayed under their thresholds for four consecutive ticks the
	 * body is declared resting, its motion is zeroed and its pose frozen. That is the whole of
	 * "settled" here - the angle it happens to have is the final one, and the renderer starts its
	 * {@code hold} + {@code fade_out} ramp from the tick {@link #resting()} turns true. A body that
	 * never slows down (free fall, a perfectly elastic, frictionless bounce, or a gravity that keeps
	 * dragging it along a floor) keeps being stepped until its effect stops it, it leaves the world
	 * or the renderer's own release time ends it - {@link #stranded} for the depth.
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
	 * @param gravityX the acceleration along the body's own +X per tick, as a multiple of
	 *                 {@link VFXBlockParticleSpec#GRAVITY_STEP}; the caller resolves 'gravity' and
	 *                 'gravity_angle' into it, so the body never has to know the angle
	 * @param gravityY the acceleration along the body's own +Y per tick, in the same unit - negative
	 *                 in the world (where +Y is up) and positive in screen space (where it is down),
	 *                 which is the one sign the caller has to flip. Unlike the old scalar it is not
	 *                 floored at zero: an upward pull is a legal gravity direction. There is no
	 *                 upper limit on either component.
	 * @param friction air drag per tick, {@code 0..1} (1 = none); also the share of the horizontal
	 *                 drift kept on a contact, so it is the same damping the engine applies
	 * @param bounce   restitution of the one rebound the landing gets and of every wall and roof
	 *                 bounce, {@code 0..1}; {@code 0} lands dead, and only a value of exactly
	 *                 {@code 1} keeps a unit hopping
	 */
	public record Params(float gravityX, float gravityY, float friction, float bounce) {
		public Params {
			// Friction and restitution are normalized fractions, so they are clamped to their range;
			// both gravity components are taken as authored - the sum is what the angle says.
			friction = friction < 0.0F ? 0.0F : (friction > 1.0F ? 1.0F : friction);
			bounce = bounce < 0.0F ? 0.0F : (bounce > 1.0F ? 1.0F : bounce);
		}
	}
}

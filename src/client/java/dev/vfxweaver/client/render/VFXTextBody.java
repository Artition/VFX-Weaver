package dev.vfxweaver.client.render;

import dev.vfxweaver.effect.VFXBlockParticleSpec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * One rigid body for the {@code text_overlay} exit: a character treated as a flat rectangle that
 * falls, bounces off the world and comes to rest, integrated once per client tick exactly as
 * {@link VFXBlockParticleEngine} integrates a block particle.
 *
 * <p>The state is the <em>whole</em> frame, not a closed-form offset from the effect clock:
 * {@link #step} advances a velocity through gravity and air drag, moves the body, resolves the move
 * against the level's real collision set with {@link VFXWorldCollision#resolve}, turns the impact
 * into bounce plus contact roll, then integrates the orientation about the angular-velocity axis. A
 * replay from the effect's start therefore matches frame for frame, while jumping into the middle of
 * a run does not reproduce the same resting arrangement — the same trade the block particles make.
 *
 * <p>The body holds no level and no effect of its own, so a renderer owns one per unit and drives
 * it directly: the constructor seeds position, velocity, orientation and angular velocity,
 * {@link #step} takes the level and the physics in {@link Params}, and {@link #pos(float)},
 * {@link #orientation(float)}, {@link #resting()} and {@link #settled()} are all the renderer reads
 * back.
 *
 * <p><b>Not verified in game.</b>
 */
public final class VFXTextBody {
	/** Smallest collision query padding, so a tiny glyph still depenetrates (the engine's floor). */
	private static final double MIN_COLLISION_PADDING = 0.01;
	/** Slip cap for the contact roll, so a fast slide cannot spin a glyph absurdly (engine parity). */
	private static final float MAX_ROLL_SLIP = 1.0F;
	/**
	 * Fraction of the tangential slip that becomes contact roll, in {@code 0..1}: the block-particle
	 * spec's default {@code spin_roll}, so a glyph that lands skidding rolls instead of sliding flat.
	 */
	private static final float ROLL_TRANSFER = 0.5F;
	/** Linear speed (blocks/tick) under which the body counts as still. */
	private static final double REST_LINEAR_SPEED = 0.01;
	/** Angular speed (radians/tick) under which the body counts as still. */
	private static final float REST_ANGULAR_SPEED = 0.01F;
	/** Consecutive still ticks before the body is declared resting; 0.2 s at 20 Hz. */
	private static final int REST_TICKS = 4;
	/**
	 * Ticks the orientation takes to lie down after the body is declared resting - nine, i.e. about
	 * 0.45 s at 20 Hz, so the tip reads as a letter falling over instead of as a snap into the fade.
	 * Each tick turns an equal share of what is still left (the slerp factor is one over the ticks
	 * remaining), so the topple runs at a steady rate and the last tick lands exactly on
	 * {@link #FLAT} - and because both ends of every tick are written back, the frame interpolation
	 * cannot fight it.
	 */
	private static final int REST_ORIENT_TICKS = 9;
	/**
	 * Margin, in blocks, below the level's minimum build height at which a still-falling body counts
	 * as having left the world. Nothing exists below that floor - there is no block to land on and
	 * no surface to see - so the margin only keeps a borderline tick from flickering the release; it
	 * is not a distance the effect authors and it is never measured against a surface the letter
	 * stood on. The renderer starts the same {@code hold} + {@code fade_out} ramp from that moment,
	 * so a body that cannot rest - dropped out of the world, or bouncing forever with {@code bounce}
	 * 1 - still reaches zero and releases its units.
	 */
	private static final double RELEASE_BELOW_WORLD = 8.0;
	/**
	 * The lying pose: the glyph plane horizontal with its face turned to the sky (local +Z, the
	 * display's front, rotated onto +Y). Shared and never mutated — it is copied into the body's own
	 * quaternions, never handed out.
	 */
	private static final Quaternionf FLAT = new Quaternionf().rotateX((float) (-Math.PI / 2.0));

	private Vec3 pos;
	private Vec3 prev;
	private Vec3 velocity;
	private final Quaternionf orientation;
	private final Quaternionf prevOrientation;
	private final Vector3f angularVelocity;
	private boolean resting;
	private int stillTicks;
	private int restOrientTicks;
	private boolean restPoseFrozen;

	/**
	 * Creates one body at its detach state. {@code prev} and {@code prevOrientation} start equal to
	 * the given values, so the first frame interpolates from the rest pose instead of from nowhere.
	 *
	 * @param position        world position of the body when it detaches
	 * @param velocity        blocks per tick
	 * @param orientation     the full rotation the body starts with (the block rotation plus whatever
	 *                        the caller's spin seed composed onto it)
	 * @param angularVelocity radians per tick; its direction is the body-fixed spin axis
	 */
	public VFXTextBody(final Vec3 position, final Vec3 velocity, final Quaternionf orientation,
			final Vector3f angularVelocity) {
		this.pos = position;
		this.prev = position;
		this.velocity = velocity;
		this.orientation = new Quaternionf(orientation);
		this.prevOrientation = new Quaternionf(orientation);
		this.angularVelocity = new Vector3f(angularVelocity);
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
		return new Vec3(this.prev.x + (this.pos.x - this.prev.x) * t,
			this.prev.y + (this.pos.y - this.prev.y) * t,
			this.prev.z + (this.pos.z - this.prev.z) * t);
	}

	/**
	 * The orientation to display this frame: a slerp from the orientation at the start of the last
	 * tick to the one it ended with.
	 *
	 * @param fraction the frame's leftover tick fraction, {@code 0..1} (clamped)
	 * @return a fresh quaternion; the caller may keep or mutate it
	 */
	public Quaternionf orientation(final float fraction) {
		final float t = fraction < 0.0F ? 0.0F : (fraction > 1.0F ? 1.0F : fraction);
		return new Quaternionf(this.prevOrientation).slerp(this.orientation, t);
	}

	/** @return {@code true} once the body has settled; {@link #step} then only finishes the pose */
	public boolean resting() {
		return this.resting;
	}

	/**
	 * Whether the lie-down has finished: the body is resting <em>and</em> its pose has been frozen,
	 * so it is exactly {@link #FLAT} - face up on the floor - with both ends of the tick written to
	 * that pose. The renderer starts its {@code hold} + {@code fade_out} timeline from the tick this
	 * turns true, not from {@link #resting}: a fade that began at the rest instant would dissolve the
	 * letter while it is still tipping over.
	 *
	 * @return {@code true} once the rest turn into the lying pose is complete
	 */
	public boolean settled() {
		return this.restPoseFrozen;
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
		return !this.resting && this.pos.y < minY - RELEASE_BELOW_WORLD;
	}

	/**
	 * Advances the body by one client tick, mirroring {@link VFXBlockParticleEngine}: gravity, air
	 * drag, the move, an optional collision response (drop the inward normal velocity, damp the rest
	 * by friction, reflect the normal part by bounce, turn the tangential slip into roll about
	 * {@code n x v_t}), let the touched block's own friction damp the spin on <em>every</em> contact
	 * whatever {@code tumble} says, then the orientation about the angular-velocity axis. A resting
	 * body keeps no physics, but it still runs the short turn into the lying pose.
	 *
	 * @param level  the client level the body moves through (collision source)
	 * @param params the physics for this tick
	 */
	public void step(final ClientLevel level, final Params params) {
		if (this.resting) {
			lieDown();
			return;
		}
		this.prev = this.pos;
		this.prevOrientation.set(this.orientation);
		double vx = this.velocity.x;
		double vy = this.velocity.y - VFXBlockParticleSpec.GRAVITY_STEP * params.gravity();
		double vz = this.velocity.z;
		final float friction = params.friction();
		vx *= friction;
		vy *= friction;
		vz *= friction;
		Vec3 next = new Vec3(this.pos.x + vx, this.pos.y + vy, this.pos.z + vz);
		if (params.collide()) {
			final double padding = Math.max(MIN_COLLISION_PADDING, params.padding());
			final Vec3 resolved = VFXWorldCollision.resolve(level, next, padding, this.prev);
			if (resolved.distanceToSqr(next) > 1.0e-12) {
				final Vec3 normal = resolved.subtract(next).normalize();
				final double vn = vx * normal.x + vy * normal.y + vz * normal.z;
				if (vn < 0.0) {
					// Drop the inward normal component, damp the tangential one by friction and
					// reflect the normal part by bounce - the engine's contact response.
					final double tx = vx - normal.x * vn;
					final double ty = vy - normal.y * vn;
					final double tz = vz - normal.z * vn;
					final double retain = friction;
					final double bounce = params.bounce();
					vx = tx * retain - normal.x * vn * bounce;
					vy = ty * retain - normal.y * vn * bounce;
					vz = tz * retain - normal.z * vn * bounce;
					// The tangential slip becomes roll about n x v_t, capped so a fast slide cannot
					// spin a glyph up absurdly; zero slip adds nothing. This gain is tumble-only: a
					// glyph that does not tumble must not gain spin from sliding.
					if (params.tumble()) {
						final double slip = Math.sqrt(tx * tx + ty * ty + tz * tz);
						if (slip > 1.0e-4) {
							final float invSlip = (float) (1.0 / slip);
							final float ax = (float) (normal.y * tz - normal.z * ty) * invSlip;
							final float ay = (float) (normal.z * tx - normal.x * tz) * invSlip;
							final float az = (float) (normal.x * ty - normal.y * tx) * invSlip;
							final float gain = (float) Math.min(slip, MAX_ROLL_SLIP) * ROLL_TRANSFER;
							this.angularVelocity.x += gain * ax;
							this.angularVelocity.y += gain * ay;
							this.angularVelocity.z += gain * az;
						}
					}
				}
				// The touched block's own friction damps the spin (stone grips, ice glides) on every
				// contact, whatever 'tumble' says: a letter whose spin is left alone never reaches
				// the angular rest threshold, so it would keep turning on the floor for ever and
				// never be declared resting - and so never lie down.
				final float surfaceFriction = (float) VFXWorldOverlayRenderer
					.contactFriction(level, resolved, normal);
				this.angularVelocity.mul(surfaceFriction);
				next = resolved;
			}
		}
		this.velocity = new Vec3(vx, vy, vz);
		this.pos = next;
		// Integrate the orientation about the body-fixed axis: angle = |w| * 1 tick, and joml's
		// rotateAxis normalises the axis itself, so the raw angular-velocity vector works.
		final float spin = this.angularVelocity.length();
		if (spin > 1.0e-6F) {
			this.orientation.rotateAxis(spin, this.angularVelocity.x, this.angularVelocity.y,
				this.angularVelocity.z);
		}
		settle();
	}

	/**
	 * Rest rule: once both speeds have stayed under their thresholds for four consecutive ticks
	 * the body is declared resting, its motion is zeroed and its orientation begins to turn into
	 * {@link #FLAT} (face up) over {@link #REST_ORIENT_TICKS} ticks - see {@link #lieDown}. That is
	 * what "lies on the floor" means here - there is no floor scan, no landing lift and no frozen
	 * spin to patch it. A body that never slows down (free fall, or a perfectly elastic,
	 * frictionless bounce) keeps being stepped until its effect stops it or it leaves the world -
	 * {@link #stranded} for the depth, the renderer's own release time for a fall that never ends.
	 */
	private void settle() {
		if (this.velocity.length() < REST_LINEAR_SPEED
			&& this.angularVelocity.length() < REST_ANGULAR_SPEED) {
			this.stillTicks++;
			if (this.stillTicks >= REST_TICKS) {
				this.resting = true;
				this.velocity = Vec3.ZERO;
				this.angularVelocity.set(0.0F, 0.0F, 0.0F);
				// The rest pose is the final one: a settled body is no longer moved, so 'prev'
				// would otherwise stay one step behind and every frame would interpolate the body
				// back and forth across that last move.
				this.prev = this.pos;
				// The orientation is not snapped here: 'lieDown' turns it into FLAT over a few
				// ticks, writing 'prev' and 'prevOrientation' each one, so the settle is drawn
				// rather than popped.
				this.restOrientTicks = REST_ORIENT_TICKS;
			}
		} else {
			this.stillTicks = 0;
		}
	}

	/**
	 * Finishes the resting pose: the first {@link #REST_ORIENT_TICKS} resting ticks slerp the
	 * orientation into {@link #FLAT}, each ending exactly where the frame interpolation expects, and
	 * the tick after the blend freezes both ends of the tick to FLAT so the display cannot swing off
	 * the floor while it fades - that freeze is what {@link #settled()} reports, and so what the
	 * renderer waits for before it starts the fade. Until that freeze, the last blend tick's
	 * interpolation window starts at the pre-blend pose and ends at the flat one - the turn is drawn,
	 * not snapped.
	 */
	private void lieDown() {
		if (this.restOrientTicks > 0) {
			this.prev = this.pos;
			this.prevOrientation.set(this.orientation);
			// Whatever is left, divided by the ticks left: a third, then a half, then the rest, so
			// the final tick lands on FLAT exactly.
			this.orientation.slerp(FLAT, 1.0F / this.restOrientTicks);
			this.restOrientTicks--;
		} else if (!this.restPoseFrozen) {
			this.prev = this.pos;
			this.prevOrientation.set(FLAT);
			this.orientation.set(FLAT);
			this.restPoseFrozen = true;
		}
	}

	/**
	 * The physics one step needs, all of it owned by the caller: the effect's params plus the
	 * geometry the glyph occupies.
	 *
	 * @param gravity  downward velocity added per tick, as a multiple of
	 *                 {@link VFXBlockParticleSpec#GRAVITY_STEP} — the block-particle spec's reading,
	 *                 so the two engines agree. A caller holding blocks per second squared divides by
	 *                 {@code 400 * GRAVITY_STEP} (= 16); zero is weightless, and there is no upper
	 *                 limit.
	 * @param friction air drag per tick, {@code 0..1} (1 = none); also the tangential velocity kept
	 *                 on contact, so it is the same damping the engine applies
	 * @param bounce   restitution of the normal velocity on contact, {@code 0..1}
	 * @param collide  whether the world is resolved at all; off means the body falls through it
	 * @param padding  the gap kept between the body and a collision surface: the glyph's own
	 *                 half-<em>thickness</em> in blocks, because a glyph is a thin flat quad and
	 *                 {@link VFXWorldCollision#resolve} depenetrates a box of that radius - its
	 *                 height is the wrong quantity here and would hold the letter that far above
	 *                 the floor. Floored so a tiny glyph still depenetrates, no upper limit
	 * @param tumble   whether contact turns slip into roll; off keeps the body from gaining spin by
	 *                 sliding, like the engine's yaw/none modes. The touched surface damps the spin
	 *                 either way - otherwise a landed letter never reaches the angular rest
	 *                 threshold and would turn on the floor for ever
	 */
	public record Params(float gravity, float friction, float bounce, boolean collide,
			double padding, boolean tumble) {
		public Params {
			// Friction and restitution are normalized fractions, so they are clamped to their range;
			// gravity, the padding and the switches are taken as authored.
			friction = friction < 0.0F ? 0.0F : (friction > 1.0F ? 1.0F : friction);
			bounce = bounce < 0.0F ? 0.0F : (bounce > 1.0F ? 1.0F : bounce);
		}
	}
}

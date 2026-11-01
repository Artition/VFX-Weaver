package dev.vfxweaver.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.vfxweaver.effect.VFXActiveEffect;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
//? if >=26.2 {
/*import com.mojang.math.Transformation;
import dev.vfxweaver.effect.VFXTextSpec;
import dev.vfxweaver.util.VFXLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
*///?}

/**
 * Draws a {@code text_overlay} effect's {@code world} mode: one client-side
 * {@code Display.TextDisplay} per character, standing where the author put the block.
 *
 * <p>The characters are laid out once by {@link VFXTextLayout} at unit scale and then placed every
 * frame from the animatable params - {@code pos_x/pos_y/pos_z} for the origin, {@code rot_x/rot_y/rot_z}
 * for the orientation, {@code scale} for the size of the whole block, the spec's {@code align} and
 * {@code anchor} for which point of the block sits on that origin - so moving, rotating or scaling an
 * already-running effect moves the whole block without respawning anything. {@code colour_r/g/b} and
 * {@code shadow} are re-applied per frame, but only written to the entities when the value changed.
 *
 * <p>{@code wrap_width} and {@code line_height} shape the layout, which is measured once when the
 * block is spawned, so they are sampled at that moment and are not animatable.
 *
 * <p>{@code reveal} is a fraction of the characters that are visible: character {@code i} is opaque
 * while {@code i < reveal * count} and fades over the single character at the boundary, which is what
 * gives a typed-in / wiped-in line instead of a hard pop.
 *
 * <p>The exit is computed from the clock, never accumulated, so the same tick always yields the same
 * frame. Character {@code index} starts leaving at {@code exit_at + index * exit_stagger} seconds;
 * with {@code exit < 0.5} it only fades in place, with {@code exit >= 0.5} it falls, spins and - when
 * {@code collision} is on - lands on the first block below it, rebounds once and settles. Once every
 * character has faded, the block is marked finished and its entities are released.
 *
 * <p>The whole body is gated on {@code >=26.2}: {@code Display.TextDisplay}'s setters are only
 * public there, and {@link VFXBlockParticleEngine#nextLocalEntityId()} (needed because 26.2's
 * {@code ClientLevel.addEntity} reads {@code Entity.getId()}) only exists there. On older nodes this
 * class compiles to empty no-ops.
 *
 * <p><b>Not verified in game.</b>
 */
public final class VFXTextOverlayRenderer {
	private VFXTextOverlayRenderer() {
	}

	//? if >=26.2 {
	/*private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/text-overlay");
	// Hard cap on the display entities of one effect: every character is a real entity, and a
	// longer string than this would flood the frame with entities nobody asked for.
	private static final int MAX_CHARS = 256;
	// The letters want nothing behind them. Display.TextDisplay.INITIAL_BACKGROUND is the vanilla
	// translucent black plate; 0 is fully transparent, so the block reads as text floating in air.
	private static final int TRANSPARENT_BACKGROUND = 0;

	// Characters of one live text effect, in layout order. 'signature' is the content plus the
	// structural inputs, because an instance id can be reused by a replay with different text.
	// 'layout' is measured at unit scale; placeBlock multiplies it by the current 'scale'.
	// 'lastColour' and 'lastFlags' are the values last written to the entities, so the per-frame
	// colour and shadow refresh only touches a display when the value actually changed.
	// 'floors' is the one per-character value the exit stores: the top of the first collider below
	// the character's rest position, or NEGATIVE_INFINITY when it has nothing to land on. It is a
	// pure function of the world, so it is resolved once at spawn and the fall itself stays
	// closed-form. 'finished' says every character has faded out and been released, which keeps
	// renderWorld from respawning the block through 'ensure' every frame for ever.
	private static final class LiveText {
		private final long instanceId;
		private final String signature;
		private final List<Display.TextDisplay> entities;
		private final VFXTextLayout layout;
		private final float[] floors;
		private int lastColour;
		private byte lastFlags;
		private boolean finished;

		private LiveText(final long instanceId, final String signature,
				final List<Display.TextDisplay> entities, final VFXTextLayout layout,
				final float[] floors, final int lastColour, final byte lastFlags) {
			this.instanceId = instanceId;
			this.signature = signature;
			this.entities = entities;
			this.layout = layout;
			this.floors = floors;
			this.lastColour = lastColour;
			this.lastFlags = lastFlags;
		}
	}

	private static final Map<Long, LiveText> LIVE = new HashMap<>();
	private static @Nullable ClientLevel lastLevel;

	// Draws (and on the first frame spawns) the world-mode block of one text effect. Called from the
	// world overlay dispatch; a no-op for a faded-out effect or one without a text block.
	//
	// effect - the running effect, its 'world' text already resolved into a spec
	// level  - the client level the display entities are added to
	// pose   - the overlay pose stack (unused for now: the entities carry their own transform)
	public static void renderWorld(final VFXActiveEffect effect, final ClientLevel level, final PoseStack pose) {
		if (effect.getWeight() <= 0.0F) {
			return;
		}
		final VFXTextSpec spec = effect.getTextSpec();
		if (spec == null) {
			VFXLog.warnOnce(LOGGER, "text_overlay:spec:" + effect.getId(),
				"text_overlay effect '{}' has no text block; nothing is drawn", effect.getId());
			return;
		}
		handleLevelChange(level);
		// 2D ('flat') mode has no renderer yet, and the flat spec must not fall through to the
		// world path: that would draw 3D glyphs at 0,0,0 for every definition without "mode".
		if (spec.mode() != VFXTextSpec.Mode.WORLD) {
			VFXLog.warnOnce(LOGGER, "text_overlay:flat:" + effect.getId(),
				"text_overlay effect '{}' is mode 'flat': the 2D layer is not implemented yet, nothing is drawn",
				effect.getId());
			return;
		}
		final LiveText live = ensure(effect, level, spec);
		// A finished block has already released its characters and has nothing left to place; the
		// pool entry only stays so 'ensure' does not spawn the same line again on the next frame.
		if (live == null || live.finished) {
			return;
		}
		placeBlock(effect, live, spec);
	}

	// Per-frame housekeeping for the text pool: drops the characters of effects that are no longer
	// running and the whole pool when the client level changes. The per-character exit (fall) is the
	// next task's job; until then this only owns the lifecycle.
	//
	// level           - the client level being drawn
	// activeInstances - instance ids of the running world-mode text effects
	public static void tick(final ClientLevel level, final Set<Long> activeInstances) {
		handleLevelChange(level);
		LIVE.entrySet().removeIf(entry -> {
			if (activeInstances.contains(entry.getKey())) {
				return false;
			}
			release(entry.getValue());
			return true;
		});
	}

	// Removes one effect's characters, by instance id.
	public static void remove(final long instanceId) {
		final LiveText live = LIVE.remove(instanceId);
		if (live != null) {
			release(live);
		}
	}

	// Removes every character of every text effect (used when the client level goes away).
	public static void clear() {
		for (final LiveText live : LIVE.values()) {
			release(live);
		}
		LIVE.clear();
	}

	// Builds the display entities of a world-mode effect the first time it is drawn.
	private static @Nullable LiveText ensure(final VFXActiveEffect effect, final ClientLevel level,
			final VFXTextSpec spec) {
		final float scale = effect.getParam("scale", 1.0F);
		final float lineHeight = effect.getParam("line_height", 1.2F);
		final float wrapWidth = effect.getParam("wrap_width", 0.0F);
		// A replay can hand out an instance id again with other text or other structural inputs, so
		// the pooled characters are only reused when they were built from the same ones. 'scale' is
		// deliberately absent: it is animatable and applied per frame instead of baked in here.
		final String signature = spec.content() + '\u0000' + spec.mode() + '\u0000' + spec.align()
			+ '\u0000' + spec.anchor();
		final LiveText existing = LIVE.get(effect.getInstanceId());
		if (existing != null) {
			if (existing.signature.equals(signature)) {
				return existing;
			}
			release(existing);
			LIVE.remove(effect.getInstanceId());
		}
		final Font font = Minecraft.getInstance().font;
		// Measured once at unit scale so an animated 'scale' grows the block including its spacing
		// without respawning; the wrap width is expressed in the same units, hence the division.
		final VFXTextLayout layout = VFXTextLayout.measure(font, spec.content(), 1.0F, lineHeight,
			wrapWidth / Math.max(scale, 1e-4F));
		final List<Display.TextDisplay> entities = new ArrayList<>(layout.glyphs().size());
		final int colour = colour(effect);
		final byte flags = effect.getParam("shadow", 1.0F) >= 0.5F ? Display.TextDisplay.FLAG_SHADOW : (byte) 0;
		final Style style = Style.EMPTY.withColor(TextColor.fromRgb(colour));
		for (final VFXTextLayout.Glyph glyph : layout.glyphs()) {
			if (entities.size() >= MAX_CHARS) {
				VFXLog.warnOnce(LOGGER, "text_overlay:cap:" + effect.getId(),
					"text_overlay '{}': more than {} characters, the tail is dropped",
					effect.getId(), MAX_CHARS);
				break;
			}
			final Display.TextDisplay display = new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, level);
			display.setText(Component.literal(String.valueOf(glyph.ch())).withStyle(style));
			display.setBackgroundColor(TRANSPARENT_BACKGROUND);
			display.setFlags(flags);
			display.setPos(0.0, 0.0, 0.0);
			// 26.2's ClientLevel.addEntity reads Entity.getId(), and a locally created entity has
			// none (only ServerLevel overrides getNextEntityId), so the id must be set first.
			display.setId(VFXBlockParticleEngine.nextLocalEntityId());
			level.addEntity(display);
			entities.add(display);
		}
		final LiveText live = new LiveText(effect.getInstanceId(), signature, entities, layout,
			floors(effect, level, spec, layout, entities.size()), colour, flags);
		LIVE.put(effect.getInstanceId(), live);
		return live;
	}

	// The top of the first collider below each character, looked up once when the block spawns: a
	// floor never moves for the life of one block, so the fall can stay closed-form instead of
	// running a collision query per character per frame. NEGATIVE_INFINITY means "nothing below";
	// POSITIVE_INFINITY is not a valid sentinel here, because it reads as 'already landed'.
	//
	// effect - the running effect, for the origin and rot_* the rest pose is built from
	// level  - the client level the colliders are queried from
	// spec   - the authored block, for the align/anchor shifts
	// layout - the unit-scale layout the characters come from
	// count  - how many characters actually got an entity (the cap drops the tail)
	private static float[] floors(final VFXActiveEffect effect, final ClientLevel level,
			final VFXTextSpec spec, final VFXTextLayout layout, final int count) {
		final Vec3 origin = new Vec3(effect.getParam("pos_x", 0.0F), effect.getParam("pos_y", 0.0F),
			effect.getParam("pos_z", 0.0F));
		final float scale = effect.getParam("scale", 1.0F);
		final Quaternionf rotation = blockRotation(effect);
		final float alignShift = alignOffset(spec, layout.width() * scale);
		final float anchorShiftY = anchorOffset(spec, layout.height() * scale);
		final float[] result = new float[count];
		for (int i = 0; i < count; i++) {
			final Vector3f local = restOffset(layout.glyphs().get(i), scale, alignShift,
				anchorShiftY, rotation);
			result[i] = groundBelow(level, origin.x + local.x, origin.y + local.y, origin.z + local.z);
		}
		return result;
	}

	// Top of the first collider below a character's rest position, or NEGATIVE_INFINITY when there is
	// nothing to land on. The query is the same one the collision helper makes: a narrow column from
	// the character's feet down to -64 blocks, so a tall drop still finds the island.
	private static float groundBelow(final ClientLevel level, final double x, final double restY,
			final double z) {
		final AABB query = new AABB(x - 0.05, restY - 64.0, z - 0.05, x + 0.05, restY + 0.05, z + 0.05);
		float top = Float.NEGATIVE_INFINITY;
		for (final VoxelShape shape : level.getBlockCollisions(null, query)) {
			if (shape.isEmpty()) {
				continue;
			}
			for (final AABB box : shape.toAabbs()) {
				// Only surfaces under the character can catch it; a block the glyph is already inside
				// (a letter authored into the ground) has no landing at all.
				if (box.maxY <= restY && box.maxY > top) {
					top = (float) box.maxY;
				}
			}
		}
		return top;
	}

	// The effect's colour_r/g/b (0..1 each) as one packed 0xRRGGBB integer, white when unset.
	private static int colour(final VFXActiveEffect effect) {
		final int red = Math.round(clamp01(effect.getParam("color_r", 1.0F)) * 255.0F);
		final int green = Math.round(clamp01(effect.getParam("color_g", 1.0F)) * 255.0F);
		final int blue = Math.round(clamp01(effect.getParam("color_b", 1.0F)) * 255.0F);
		return (red << 16) | (green << 8) | blue;
	}

	// Positions every character of the block: unit layout times the current 'scale', rotated by
	// rot_*, anchored, displaced by the exit, and refreshed for the animatable colour and shadow.
	private static void placeBlock(final VFXActiveEffect effect, final LiveText live, final VFXTextSpec spec) {
		final Vec3 origin = new Vec3(effect.getParam("pos_x", 0.0F), effect.getParam("pos_y", 0.0F),
			effect.getParam("pos_z", 0.0F));
		final float scale = effect.getParam("scale", 1.0F);
		final VFXTextLayout layout = live.layout;
		final float alignShift = alignOffset(spec, layout.width() * scale);
		final float anchorShiftY = anchorOffset(spec, layout.height() * scale);
		final Quaternionf rotation = blockRotation(effect);
		// Colour and shadow are animatable but the entity's text is baked at spawn, so the style is
		// rebuilt here - and only written when it differs from what the entities already carry.
		final int colour = colour(effect);
		final byte flags = effect.getParam("shadow", 1.0F) >= 0.5F ? Display.TextDisplay.FLAG_SHADOW : (byte) 0;
		final Style style = Style.EMPTY.withColor(TextColor.fromRgb(colour));
		final boolean restyle = colour != live.lastColour;
		final boolean reflag = flags != live.lastFlags;
		live.lastColour = colour;
		live.lastFlags = flags;
		final float revealed = reveal(effect) * layout.glyphs().size();
		// The block only finishes when every character has faded out; until then this stays above 0.
		float brightest = 0.0F;
		for (int i = 0; i < live.entities.size(); i++) {
			final VFXTextLayout.Glyph glyph = layout.glyphs().get(i);
			final Vector3f local = restOffset(glyph, scale, alignShift, anchorShiftY, rotation);
			final Display.TextDisplay display = live.entities.get(i);
			// The rest height is the anchor's Y plus the laid-out offset; the floor was resolved for
			// that same pose when the block spawned, so the fall can be read straight off the clock.
			final float restY = (float) (origin.y + local.y);
			final float floorY = i < live.floors.length ? live.floors[i] : Float.NEGATIVE_INFINITY;
			final Vector3f exit = exitOffset(effect, i, restY, floorY);
			final float since = exitSince(effect, i);
			// Position carries the offset (the rest pose plus the exit's displacement); the
			// transformation's translation stays zero, otherwise the offset is applied twice and the
			// block flies away from its anchor.
			display.setPos(origin.x + local.x + exit.x, origin.y + local.y + exit.y,
				origin.z + local.z + exit.z);
			final Quaternionf orientation = since < 0.0F ? rotation : spin(effect, i, since, rotation);
			display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(orientation),
				new Vector3f(scale, scale, scale), new Quaternionf()));
			if (restyle) {
				display.setText(Component.literal(String.valueOf(glyph.ch())).withStyle(style));
			}
			if (reflag) {
				display.setFlags(flags);
			}
			// reveal is a fraction of the characters, so this character is fully shown, hidden, or
			// the single one at the wipe boundary caught mid-fade; the exit fades a character that is
			// leaving, and the effect weight fades the whole block instead of popping it at the stop.
			final float exitAlpha = exitAlpha(effect, i, restY, floorY);
			brightest = Math.max(brightest, exitAlpha);
			display.setTextOpacity((byte) Math.round(
				clamp01(revealed - i) * exitAlpha * effect.getWeight() * 255.0F));
		}
		// Every character has faded and there is still an exit pending: the block is gone. Only an
		// authored exit can get here, so a block with 'reveal' 0 (invisible, not leaving) is not
		// mistaken for a finished one and keeps waiting for its characters.
		if (brightest <= 0.0F && !live.entities.isEmpty() && detachTime(effect, 0) >= 0.0F) {
			finish(live);
		}
	}

	// The block's own orientation, straight from rot_*.
	private static Quaternionf blockRotation(final VFXActiveEffect effect) {
		return new Quaternionf()
			.rotateX((float) Math.toRadians(effect.getParam("rot_x", 0.0F)))
			.rotateY((float) Math.toRadians(effect.getParam("rot_y", 0.0F)))
			.rotateZ((float) Math.toRadians(effect.getParam("rot_z", 0.0F)));
	}

	// Where a character sits, relative to the block origin: unit layout times the current 'scale',
	// shifted by the spec's align and anchor, then turned by rot_*. Shared by the per-frame placement
	// and the one-off floor lookup, so the floor is always probed under the pose it will fall from.
	private static Vector3f restOffset(final VFXTextLayout.Glyph glyph, final float scale,
			final float alignShift, final float anchorShiftY, final Quaternionf rotation) {
		return new Vector3f(glyph.x() * scale + alignShift, glyph.y() * scale + anchorShiftY, 0.0F)
			.rotate(rotation);
	}

	private static float alignOffset(final VFXTextSpec spec, final float width) {
		return switch (spec.align()) {
			case LEFT -> 0.0F;
			case CENTER -> -width / 2.0F;
			case RIGHT -> -width;
		};
	}

	private static float anchorOffset(final VFXTextSpec spec, final float height) {
		return switch (spec.anchor()) {
			case TOP_LEFT, TOP, TOP_RIGHT -> 0.0F;
			case LEFT, CENTER, RIGHT -> -height / 2.0F;
			case BOTTOM_LEFT, BOTTOM, BOTTOM_RIGHT -> -height;
		};
	}

	// Seconds since the effect started, from the effect clock.
	private static float age(final VFXActiveEffect effect) {
		return effect.getElapsed() / 20.0F;
	}

	// When character 'index' starts leaving; -1 when the text never leaves on its own.
	private static float detachTime(final VFXActiveEffect effect, final int index) {
		final float at = effect.getParam("exit_at", -1.0F);
		if (at < 0.0F) {
			return -1.0F;
		}
		return at + index * effect.getParam("exit_stagger", 0.0F);
	}

	// Seconds character 'index' has been leaving, or -1 while it is still standing in the line.
	private static float exitSince(final VFXActiveEffect effect, final int index) {
		final float detach = detachTime(effect, index);
		if (detach < 0.0F) {
			return -1.0F;
		}
		final float since = age(effect) - detach;
		return since < 0.0F ? -1.0F : since;
	}

	// Where character 'index' is this frame, relative to its rest pose: zero while it is standing,
	// and zero for a fade exit - the two exits differ in this one branch and nothing else.
	private static Vector3f exitOffset(final VFXActiveEffect effect, final int index,
			final float restY, final float floorY) {
		final float since = exitSince(effect, index);
		if (since < 0.0F || effect.getParam("exit", 0.0F) < 0.5F) {
			return new Vector3f();
		}
		return fallOffset(effect, index, since, restY, floorY);
	}

	// Where the character is 'since' seconds after it detached. Closed form, so the same tick always
	// yields the same frame - a replay and a screenshot agree.
	private static Vector3f fallOffset(final VFXActiveEffect effect, final int index,
			final float since, final float restY, final float floorY) {
		// Gravity divides the fall, so zero would divide by zero: a lower bound only, because there
		// is no upper limit - a huge gravity just makes the drop instant, it does not need a cap.
		final float gravity = Math.max(effect.getParam("gravity", 18.0F), 1.0e-3F);
		final float speed = effect.getParam("exit_speed", 0.0F) * jitter(effect, index);
		final float drop = restY - floorY;
		// Without a floor, or with collision off, the letter simply falls for ever.
		if (effect.getParam("collision", 1.0F) < 0.5F || !Float.isFinite(floorY)) {
			return new Vector3f(0.0F, speed * since - 0.5F * gravity * since * since, 0.0F);
		}
		// Time to reach the floor: the positive root of restY + v0 t - g t^2 / 2 = floorY.
		final float impact = (speed + (float) Math.sqrt(speed * speed + 2.0F * gravity * drop)) / gravity;
		if (since <= impact) {
			return new Vector3f(0.0F, speed * since - 0.5F * gravity * since * since, 0.0F);
		}
		// One rebound with restitution 'bounce', then settle: the hop is the same parabola with the
		// reversed impact speed, so the landing still has a closed form and costs nothing.
		final float landing = -drop;
		final float reboundSpeed = Math.max(0.0F, -speed + gravity * impact) * Math.min(1.0F, effect.getParam("bounce", 0.15F));
		final float hop = 2.0F * reboundSpeed / gravity;
		final float after = since - impact;
		if (after >= hop) {
			return new Vector3f(0.0F, landing, 0.0F);
		}
		return new Vector3f(0.0F, landing + reboundSpeed * after - 0.5F * gravity * after * after, 0.0F);
	}

	// Deterministic per-character jitter in [0.85, 1.15], from the instance seed and the index.
	private static float jitter(final VFXActiveEffect effect, final int index) {
		final long h = effect.getInstanceId() * 0x9E3779B97F4A7C15L ^ (index * 0xBF58476D1CE4E5B9L);
		final int bits = (int) (h ^ (h >>> 31));
		return 0.85F + 0.30F * ((bits & 0xFFFF) / 65535.0F);
	}

	// Opacity of character 'index' from its exit timeline: 1 until it has started leaving, then the
	// fade exit's ramp, or - for a fall - 1 until it has landed and the ramp after that.
	private static float exitAlpha(final VFXActiveEffect effect, final int index,
			final float restY, final float floorY) {
		final float since = exitSince(effect, index);
		if (since < 0.0F) {
			return 1.0F;
		}
		// A zero fade would divide by zero; the lower bound is the only guard needed here.
		final float fadeOut = Math.max(effect.getParam("fade_out", 0.5F), 1e-4F);
		if (effect.getParam("exit", 0.0F) < 0.5F) {
			return 1.0F - clamp01(since / fadeOut);
		}
		// The rule: without a floor, or with collision off, there is no landing to wait for, so the
		// letter fades from the moment it detaches over 'fade_out' instead of holding until impact.
		final float landing = landedAt(effect, index, restY, floorY);
		if (landing < 0.0F) {
			// Nothing to land on: the letter falls through the world, so it fades with the fall
			// instead of waiting for a landing that never comes - and still reaches zero, or the
			// block would never be released.
			return 1.0F - clamp01(since / fadeOut);
		}
		return since <= landing ? 1.0F : 1.0F - clamp01((since - landing) / fadeOut);
	}

	// When the fall comes to rest, in seconds since it detached: the impact plus the rebound hop, or
	// -1 when there is nothing to land on. Mirrors the landing test inside fallOffset, so the fade
	// and the position always agree on where and when the letter stopped.
	private static float landedAt(final VFXActiveEffect effect, final int index,
			final float restY, final float floorY) {
		final float drop = restY - floorY;
		if (effect.getParam("collision", 1.0F) < 0.5F || !Float.isFinite(floorY) || drop <= 0.0F) {
			return -1.0F;
		}
		final float gravity = effect.getParam("gravity", 18.0F);
		final float speed = effect.getParam("exit_speed", 0.0F) * jitter(effect, index);
		final float impact = (speed + (float) Math.sqrt(speed * speed + 2.0F * gravity * drop)) / gravity;
		if (!Float.isFinite(impact)) {
			return -1.0F;
		}
		final float reboundSpeed = Math.max(0.0F, -speed + gravity * impact)
			* Math.min(1.0F, effect.getParam("bounce", 0.15F));
		return reboundSpeed > 0.0F ? impact + 2.0F * reboundSpeed / gravity : impact;
	}

	// A character's orientation while it is leaving: the block's rot_* quaternion composed with a
	// rotation of |spin| * since degrees about spin_x/y/z. 'tumble' takes the axis from the
	// per-character jitter instead, so the letters tumble off-axis instead of all wheeling about the
	// same world axis. A spin vector of length zero has no axis to rotate about - normalising it
	// would be a NaN - so the rest orientation is handed back untouched.
	private static Quaternionf spin(final VFXActiveEffect effect, final int index, final float since,
			final Quaternionf rotation) {
		final Vector3f axis = new Vector3f(effect.getParam("spin_x", 0.0F),
			effect.getParam("spin_y", 0.0F), effect.getParam("spin_z", 0.0F));
		float speed = axis.length();
		if (speed < 1e-4F) {
			return new Quaternionf(rotation);
		}
		if (effect.getParam("tumble", 0.0F) >= 0.5F) {
			// Three different readings of the same jitter: a deterministic, never-zero direction.
			final float j = jitter(effect, index);
			axis.set(j - 1.0F, 1.0F - j, 0.5F * j - 0.5F);
			speed = axis.length();
			if (speed < 1e-4F) {
				return new Quaternionf(rotation);
			}
		}
		return new Quaternionf(rotation)
			.rotateAxis((float) Math.toRadians(speed * since), axis.div(speed));
	}

	// The block is over: every character has faded out. Its characters are released now, and the pool
	// entry stays behind marked finished, so 'ensure' does not spawn the whole line again next frame.
	private static void finish(final LiveText live) {
		live.finished = true;
		release(live);
		live.entities.clear();
	}


	// The animated fraction of characters that are visible, 1 when the param is unset.
	private static float reveal(final VFXActiveEffect effect) {
		return clamp01(effect.getParam("reveal", 1.0F));
	}

	private static float clamp01(final float value) {
		return value < 0.0F ? 0.0F : (value > 1.0F ? 1.0F : value);
	}

	// Discards an effect's display entities; the level forgets them, only the pool needs clearing.
	private static void release(final LiveText live) {
		for (final Display.TextDisplay display : live.entities) {
			VFXBlockParticleEngine.removeDisplay(display);
		}
	}

	// Drops the pool when the client level changes: the old level's entities went with it.
	private static void handleLevelChange(final ClientLevel level) {
		if (lastLevel == level) {
			return;
		}
		lastLevel = level;
		clear();
	}
	*///?} else {
	// <26.2: Display.TextDisplay's setters are not public, so the whole feature is a no-op here.
	// The methods stay so callers do not need a version guard of their own.

	public static void renderWorld(final VFXActiveEffect effect, final ClientLevel level, final PoseStack pose) {
	}

	public static void tick(final ClientLevel level, final Set<Long> activeInstances) {
	}

	public static void remove(final long instanceId) {
	}

	public static void clear() {
	}
	//?}
}

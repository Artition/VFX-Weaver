package dev.vfxweaver.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.vfxweaver.effect.VFXActiveEffect;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
//? if >=26.2 {
/*import com.mojang.math.Transformation;
import dev.vfxweaver.effect.VFXBlockParticleSpec;
import dev.vfxweaver.effect.VFXTextSpec;
import dev.vfxweaver.util.VFXLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2fStack;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
*///?}

/**
 * Draws a {@code text_overlay} effect: {@code world} mode as one client-side
 * {@code Display.TextDisplay} per <em>unit</em> of the block standing where the author put it -
 * a unit being one character, one word or the whole block, as the spec's {@code split} says -
 * {@code flat} mode as the same laid-out block drawn onto the HUD through the GUI graphics.
 *
 * <p>The units are laid out once by {@link VFXTextLayout} at unit scale and then placed every
 * frame from the animatable params - the frame's first world slot (a runtime move, a {@code positions}
 * entry or an entity anchor) as the origin, or {@code pos_x/pos_y/pos_z} alone as absolute
 * coordinates when the definition has no slot at all - {@code rot_x/rot_y/rot_z}
 * for the orientation, {@code scale} for the size of the whole block, the spec's {@code align} and
 * {@code anchor} for which point of the block sits on that origin - so moving, rotating or scaling an
 * already-running effect moves the whole block without respawning anything. {@code billboard} picks
 * how the block follows the camera (a fixed code, read once per display) and {@code brightness} picks
 * the light the text is drawn in ({@code -1} = the world light, {@code 0..15} = an override).
 * {@code colour_r/g/b}, {@code shadow} and {@code brightness} are re-applied per frame, but only
 * written to the entities when the value changed.
 *
 * <p>The back of a block pinned in world orientation is a <strong>second display that is the mirror
 * image of the first</strong>: {@code two_sided} (on unless it is below 0.5) spawns one twin per
 * unit, placed at the far edge of that unit's own advance - the original position plus the block
 * rotation applied to {@code (unitWidth, 0, 0)}, the layout's measured width for the unit - carrying
 * the same block rotation as the original with a transformation scale of
 * {@code (-scale, scale, scale)}. A negative X scale has a negative determinant: it mirrors the glyph
 * and flips the triangle winding, so the twin is culled from the front and drawn only from behind,
 * where it fills exactly the same rectangle and reads the letter reversed - the back of a solid
 * sign, never a readable second copy - and nothing is drawn through terrain. A {@code center}
 * billboard already faces the camera from every side, so it needs no twin. Both copies are driven by
 * the same pose every frame - the block rotation while the unit stands, its body's position and
 * orientation once it falls - and share the opacity, the brightness and the release, so a falling or
 * tumbling pair never separates; the pair counts against the entity cap, so a two-sided block spends
 * two entities per unit.
 * {@code through_blocks} stays only as an option, for text meant to be seen through walls, and is
 * <strong>off</strong> by default.
 *
 * <p>{@code wrap_width} and {@code line_height} shape the layout, which is measured once when the
 * block is spawned, so they are sampled at that moment and are not animatable.
 *
 * <p>The spec's {@code split} decides what a display entity is: one per character ({@code char},
 * the historical behaviour), one per word ({@code word}) or one for the whole text ({@code block}).
 * Everything below that animates a display - the reveal wipe, the scramble band, the exit schedule,
 * the physics and the landing - is computed per unit, so a coarse mode animates its words or its
 * single block instead of its characters. In {@code block} mode the unit carries the content with
 * its newlines and is handed {@code wrap_width} as the display's line width (200 font pixels when
 * wrapping is off), so vanilla breaks the lines itself; the layout counts that unit's lines with the
 * same {@code Font.split} call at the same width, so a long word breaks where the display breaks it
 * and the unit's height - the anchor and the flat block - agrees with the display.
 * The reveal is then all-or-nothing.
 *
 * <p>In {@code flat} mode the block is laid out at unit scale, cached by instance id, and drawn onto
 * the HUD: {@code x} and {@code y} are fractions of the GUI size and place the block's anchor,
 * {@code angle} turns the whole block about that anchor and {@code scale} multiplies the font's own
 * pixel size. The reveal wipe, the scramble and the exit work exactly as they do in the world: they
 * are indexed by unit, so a coarse split wipes, decodes, spins and drifts its words (or its single
 * block) as one piece, and its glyphs are drawn inside that one pose.
 *
 * <p>The flat exit is the world exit's timeline in screen space: the same {@code exit_at},
 * {@code exit_stagger}, {@code exit}, {@code exit_speed}, {@code gravity}, {@code hold} and
 * {@code fade_out}, but
 * {@code gravity} and the speeds are read in screen heights per second, so every offset is multiplied
 * by the GUI height and the fall looks the same at GUI scale 2 and 4. With {@code collision} on, a
 * falling character is stopped by the bottom edge and fades out holding there, and its sideways drift
 * is folded back into the frame so it bounces off the left and right edges; with {@code collision} off
 * it falls straight out through the bottom while fading. A leaving character may only turn about z, so
 * its own rotation is {@code angle + spin_z * since}, applied about the middle of its own font box.
 *
 * <p>{@code reveal} is a fraction of the units that are visible: unit {@code i} is opaque
 * while {@code i < reveal * count} and fades over the single unit at the boundary, which is what
 * gives a typed-in / wiped-in line instead of a hard pop. In {@code char} mode that is one character
 * at a time; a coarse split reveals a whole word, or the whole block at once.
 *
 * <p>{@code scramble} turns both transitions into a decode. While it is above half, the units
 * inside the {@code scramble_band} window ahead of the reveal frontier, and the units inside the
 * {@code scramble_time} seconds before they detach, draw a noise glyph instead of their own text:
 * a hash of the instance id, the unit index and the swap number into {@code A-Z 0-9 #$%&*+=-<>/?},
 * so the same tick always draws the same noise and a replay agrees with a screenshot. The glyph swaps
 * {@code scramble_rate} times a second, the noise fades out towards the far end of the band - rising
 * towards the frontier - and a unit snaps back to itself the moment the frontier passes it or the
 * exit takes over. Both windows are read from the clock and the params, never accumulated. The world
 * path rewrites a display's text only when the glyph it shows actually changed; the flat path simply
 * draws the noise character. With {@code scramble} at 0 every frame is the one it was before.
 *
 * <p>A fading exit is computed from the clock, never accumulated, so the same tick always yields the
 * same frame: unit {@code index} starts leaving at {@code exit_at + index * exit_stagger} seconds,
 * and with {@code exit < 0.5} it only fades in place. With {@code exit >= 0.5} the unit is handed to
 * a {@link VFXTextBody} the moment it detaches - one rigid rectangle per unit, seeded with the rest
 * pose it had then, the {@code spread} scatter and {@code exit_speed} upward as its velocity, and
 * the {@code spin_*} / {@code tumble} angular velocity with its per-unit jitter - and from then on it
 * <strong>falls, bounces and settles in real world geometry</strong>: the body is integrated once per
 * client tick by the same step {@link VFXBlockParticleEngine} uses and drawn at the interpolation
 * between the last two ticks, so 20 Hz physics still looks smooth. That per-tick state is the one
 * place this renderer is not a closed form of the clock: a replay from the effect's start matches
 * frame for frame, while jumping into the middle of a run does not reproduce the same resting
 * arrangement - the trade the block particles make.
 *
 * <p>{@code flat} mode keeps the closed-form timeline of the same params: the HUD has no geometry to
 * hit, so a 2D block keeps the analytic fall and needs no body.
 *
 * <p>A unit that falls stays opaque while it is in the air: once its body reports resting, {@code
 * hold} seconds pass and then {@code fade_out} runs. Free fall never rests, so with {@code
 * collision} off the fade runs from the detach (and with {@code fade_out} 0 the letters simply keep
 * falling until the effect stops). A body that has collision but cannot rest - dropped out of the
 * world, or bouncing forever - is released the same way when it falls below the level's minimum build
 * height or after ten seconds without coming to rest, so no unit can stay spawned and fully opaque
 * until someone stops the effect. Once
 * every unit has faded, the block is marked finished at the age it finished and its entities are
 * released; when the effect loops or is replayed and the clock goes back, the block is rebuilt. An
 * effect that stops always releases its units, whatever the physics was doing.
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
	// Hard cap on the display entities of one effect: every unit is a real entity, and a longer
	// string than this would flood the frame with entities nobody asked for.
	private static final int MAX_CHARS = 256;
	// The entity count that actually matters is the unit count: 'word' and 'block' modes spawn far
	// fewer entities than they have characters, so a long text has to be capped on its own. The cap
	// is on display entities, not on units, because a two-sided block spends two per unit - a
	// 256-unit block must not quietly become 512 entities.
	private static final int MAX_UNITS = 256;
	// The letters want nothing behind them. Display.TextDisplay.INITIAL_BACKGROUND is the vanilla
	// translucent black plate; 0 is fully transparent, so the block reads as text floating in air.
	private static final int TRANSPARENT_BACKGROUND = 0;
	// The world size of one font pixel. net.minecraft.client.renderer.entity
	// .DisplayRenderer$TextDisplayRenderer scales a glyph by -0.025f before it draws it, so a layout
	// measured in font pixels is only in world units once it is multiplied by this.
	private static final float WORLD_UNITS_PER_FONT_PIXEL = 0.025F;
	// Release guarantee, not a gameplay limit: how long a fall may go on without the body coming to
	// rest before the block is released anyway. Ten seconds is several times the longest honest drop
	// in a world - the build range is a few hundred blocks and a body at the block-particle terminal
	// speed crosses it in a couple of seconds - so no real fall reaches it; it exists only so a fall
	// that can never land (a bounce of 1, a friction all but 1, a slow drift out of the world) still
	// reaches zero, lets 'finish' run and releases its displays. Chosen well above any fall a level
	// can produce, and raised only if a level can honestly outlast it.
	private static final float RELEASE_SECONDS = 10.0F;
	// The pool a scrambled character draws its noise from: no lowercase, so a decode reads as machine
	// output, and a fixed order, so the hash's index into it is stable between runs.
	private static final String NOISE_GLYPHS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#$%&*+=-<>/?";
	// Independent salts for the per-character draws: the instance id and unit index are mixed with one
	// of these, so the X scatter and the Z scatter disagree. The zero salt is today's speed jitter,
	// kept so an existing definition's fall speed and spin are unchanged.
	private static final long SALT_SPEED = 0L;
	private static final long SALT_SCATTER_X = 0xD6E8FEB86659FD93L;
	private static final long SALT_SCATTER_Z = 0xA0761D6478BD642FL;

	// Units of one live text effect, in layout order: one display entity each. 'signature' is the
	// content plus the structural inputs, because an instance id can be reused by a replay with
	// other text. 'layout' is measured at unit scale; placeBlock multiplies it by the current
	// 'scale'. 'lastColour' and 'lastFlags' are the values last written to the entities, so the
	// per-frame colour and shadow refresh only touches a display when the value actually changed;
	// 'lastFlags' is per unit because a multi-glyph unit also carries its alignment bit.
	// 'lastTexts' is the string last written to each display - its unit's own text, or the noise
	// glyph of the last swap - so a scrambled unit is rewritten only when its noise changes and is
	// handed its own text back when the band passes it, whatever the colour cache says.
	// 'falls' is the one per-unit state the exit keeps: the body a falling unit was handed when it
	// detached (null while the unit stands in the line), and the 'since' at which that body came to
	// rest (-1 until it has). 'twins' holds one extra display per unit when the block is two-sided -
	// the same unit mirrored by a negative X transformation scale - and is empty for a one-sided (or
	// center-billboard) block, so the original and its twin are paired by index. 'finishedAt' is the
	// age at which every unit had
	// faded out and been released, or -1
	// while the block is live; a record whose age is now below that value is stale, because a
	// looping effect wraps 'elapsed' back to 0 and a replay reuses the instance id.
	private static final class LiveText {
		private final long instanceId;
		private final String signature;
		private final List<Display.TextDisplay> entities;
		private final List<Display.TextDisplay> twins;
		private final VFXTextLayout layout;
		private final Fall[] falls;
		private final String[] ownTexts;
		private final String[] lastTexts;
		private int lastColour;
		private int lastBrightness;
		private final byte[] lastFlags;
		private float finishedAt = -1.0F;

		private LiveText(final long instanceId, final String signature,
				final List<Display.TextDisplay> entities, final List<Display.TextDisplay> twins,
				final VFXTextLayout layout, final String[] ownTexts,
				final String[] lastTexts, final int lastColour, final int lastBrightness,
				final byte[] lastFlags) {
			this.instanceId = instanceId;
			this.signature = signature;
			this.entities = entities;
			this.twins = twins;
			this.layout = layout;
			this.falls = new Fall[entities.size()];
			this.ownTexts = ownTexts;
			this.lastTexts = lastTexts;
			this.lastColour = lastColour;
			this.lastBrightness = lastBrightness;
			this.lastFlags = lastFlags;
		}
	}

	// One unit's fall: the body that carries it and the physics it is stepped with, both built once
	// when the unit detaches, plus the 'since' at which it came to rest or was released for falling
	// out of sight. A unit without a Fall still stands in the line, or left by a fade, and is placed
	// from its rest pose.
	private static final class Fall {
		private final VFXTextBody body;
		private final VFXTextBody.Params params;
		private float restSince = -1.0F;
		private float releaseSince = -1.0F;

		private Fall(final VFXTextBody body, final VFXTextBody.Params params) {
			this.body = body;
			this.params = params;
		}
	}

	// One flat-mode block's measured layout, cached by instance id: the HUD pass runs every frame,
	// so a long line is measured once per instance instead of sixty times a second. 'signature' is
	// structural only - content, mode, split, align, anchor - because that is what the layout is a
	// function of once it has been measured; 'line_height' and 'wrap_width' are sampled at that
	// moment, the same way the world pool samples them, so an animated 'line_height' does not
	// re-measure. 'texts' holds the one-character string of each glyph, built once here instead of
	// on every frame.
	private record FlatText(String signature, VFXTextLayout layout, List<String> texts) {
	}

	// The offset of a character that is not leaving. Shared, because a standing line must not
	// allocate one vector per character per frame; the callers only read it.
	private static final Vector2f ZERO_OFFSET = new Vector2f();

	private static final Map<Long, LiveText> LIVE = new HashMap<>();
	private static final Map<Long, FlatText> FLAT = new HashMap<>();
	private static @Nullable ClientLevel lastLevel;
	// The fall is stepped at 20 Hz from the shared effect clock, exactly like VFXBlockParticleEngine:
	// 'lastClock' is the clock of the previous frame, 'accumulator' the ticks it owes, and
	// 'lastFraction' the leftover fraction of the last one - the value the displays interpolate by.
	// A hitch drops the backlog instead of replaying it, and at most MAX_STEPS_PER_FRAME ticks are
	// integrated per frame, so a slow frame cannot spend the whole budget on physics.
	private static final int MAX_STEPS_PER_FRAME = 4;
	private static final float MAX_CATCHUP_TICKS = 8.0F;
	private static float lastClock = Float.NaN;
	private static float accumulator;
	private static float lastFraction;

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
		// Flat ('2D') blocks belong to the HUD pass, which calls renderFlat; they must not fall
		// through to the world path, or every definition without a "mode" would spawn 3D glyphs
		// at 0,0,0.
		if (spec.mode() != VFXTextSpec.Mode.WORLD) {
			return;
		}
		// The block hangs from this frame's first slot - a runtime move, a 'positions' entry, or an
		// entity anchor resolved to where the entity is now. An anchor whose entity is untracked
		// this frame resolves to no slot at all, and like every other world effect the text then
		// draws nothing instead of standing at a stale place.
		final List<Vec3> slots = VFXWorldOverlayRenderer.effectPositions(effect, level);
		if (slots.isEmpty()) {
			return;
		}
		final Vec3 origin = origin(effect, slots.get(0));
		final LiveText live = ensure(effect, level, spec, origin);
		// A finished block has already released its characters and has nothing left to place; the
		// pool entry only stays so 'ensure' does not spawn the same line again on the next frame.
		if (live == null || live.finishedAt >= 0.0F) {
			return;
		}
		placeBlock(effect, live, spec, origin);
	}

	// The point the block is measured from. When the definition has a slot - a runtime move, a
	// 'positions' entry, an entity anchor or the play-time position - that slot IS the origin and
	// pos_x/pos_y/pos_z are ignored, exactly the rule every other world effect follows: a played-at
	// position wins over the authored coordinates instead of adding to them. A definition with no
	// slot at all keeps the absolute pos_*; 'slot' is then only the resolver's echo of those same
	// params, centred on X/Z for the other effects, so it is not used here.
	private static Vec3 origin(final VFXActiveEffect effect, final Vec3 slot) {
		final boolean hasSlot = effect.getMovePosition() != null || !effect.getPositions().isEmpty()
			|| !effect.getAnchors().isEmpty();
		if (!hasSlot) {
			return new Vec3(effect.getParam("pos_x", 0.0F), effect.getParam("pos_y", 0.0F),
				effect.getParam("pos_z", 0.0F));
		}
		return slot;
	}

	// Draws the flat-mode block of one text effect onto the HUD. Called once per frame per flat
	// effect from the client render hooks; a no-op for a faded-out effect or one without a block.
	//
	// The block is laid out at unit scale and then turned into screen space by hand: 'x' and 'y' are
	// fractions of the GUI size and place the block's anchor point, 'angle' turns the whole block
	// about that point, and 'scale' multiplies the font's own pixel size. That is why the measured
	// layout does not carry the scale - it can animate without re-measuring anything. Each unit then
	// gets its own pose, and its glyphs are drawn inside that one pose, so a word or a block that is
	// leaving turns and drifts as one piece, exactly as its display entity does in the world.
	//
	// effect   - the running effect, its 'flat' text already resolved into a spec
	// graphics - the HUD graphics of the current frame (also its size, and its pose)
	public static void renderFlat(final VFXActiveEffect effect, final GuiGraphicsExtractor graphics) {
		if (effect.getWeight() <= 0.0F) {
			return;
		}
		final VFXTextSpec spec = effect.getTextSpec();
		if (spec == null) {
			VFXLog.warnOnce(LOGGER, "text_overlay:spec:" + effect.getId(),
				"text_overlay effect '{}' has no text block; nothing is drawn", effect.getId());
			return;
		}
		final FlatText flat = flatLayout(effect, spec);
		final VFXTextLayout layout = flat.layout();
		if (layout.glyphs().isEmpty()) {
			return;
		}
		final Font font = Minecraft.getInstance().font;
		final float guiWidth = graphics.guiWidth();
		final float guiHeight = graphics.guiHeight();
		final float anchorX = effect.getParam("x", 0.5F) * guiWidth;
		final float anchorY = effect.getParam("y", 0.5F) * guiHeight;
		final float scale = effect.getParam("scale", 1.0F);
		final float angle = effect.getParam("angle", 0.0F);
		final float alignShift = alignOffset(spec, layout.width());
		final float anchorShiftY = anchorOffset(spec, layout.height());
		// 'angle' turns the block about its anchor, so the rest positions are turned here instead of
		// by the pose - the pose below is per unit and carries 'angle + spin_z * since'.
		final float radians = (float) Math.toRadians(angle);
		final float cos = (float) Math.cos(radians);
		final float sin = (float) Math.sin(radians);
		final int rgb = colour(effect);
		final boolean shadow = effect.getParam("shadow", 1.0F) >= 0.5F;
		final List<VFXTextLayout.Glyph> glyphs = layout.glyphs();
		final List<VFXTextLayout.Unit> units = layout.units();
		final int count = units.size();
		// The same fraction the world path reads: 'reveal' is a fraction of the units, so a coarse
		// split wipes in its words (or its single block) instead of its characters.
		final float revealed = reveal(effect) * count;
		final Matrix3x2fStack pose = graphics.pose();
		for (int u = 0; u < count; u++) {
			final VFXTextLayout.Unit unit = units.get(u);
			// The unit's own box, read off the glyphs it covers: from its first glyph's left edge
			// to the right edge of its last one, and one font line per line it spans. Walking them
			// allocates nothing - the strings are the cached ones - and it is what lets a word turn
			// and leave as one solid piece instead of letter by letter.
			final int first = unit.firstGlyph();
			final int end = u + 1 < count ? units.get(u + 1).firstGlyph() : glyphs.size();
			float unitRight = unit.x();
			for (int g = first; g < end; g++) {
				final VFXTextLayout.Glyph glyph = glyphs.get(g);
				// A space advances the cursor but draws nothing, so it is left out of the box: a
				// word's trailing space must not pull its centre half a space to the right.
				if (glyph.ch() != ' ') {
					unitRight = Math.max(unitRight, glyph.x() + glyph.advance());
				}
			}
			final float halfWidth = Math.max(0.0F, unitRight - unit.x()) / 2.0F;
			final float unitHeight = font.lineHeight * unit.lines();
			final float halfHeight = unitHeight / 2.0F;
			final float since = exitSince(effect, u);
			final float localX = (unit.x() + alignShift) * scale;
			final float localY = (unit.y() + anchorShiftY) * scale;
			final float restX = anchorX + localX * cos - localY * sin;
			final float restY = anchorY + localX * sin + localY * cos;
			// The bottom edge this unit rests on: solved against the bottom of its own box, so a
			// landed unit stands on the frame instead of having fallen through it.
			final float floorY = guiHeight - unitHeight * scale;
			final Vector2f exit = flatExitOffset(effect, u, since, restX, restY, floorY, guiWidth, guiHeight);
			// Same wipe and scramble as the world path, both indexed by unit: 'reveal' is a
			// fraction of the units, the single one at the boundary is caught mid-fade, the exit
			// fades a unit that is leaving, and the effect weight fades the whole block. A plain
			// 0xRRGGBB colour has alpha 0 here, so it must be packed. A scrambled unit is drawn at
			// the noise's own opacity instead, which rises towards the frontier.
			final float noise = scrambleAmount(effect, u, count);
			final float revealAlpha = noise > 0.0F ? noise : clamp01(revealed - u);
			final float alpha = revealAlpha
				* flatExitAlpha(effect, u, since, restY, floorY, guiHeight) * effect.getWeight();
			final int argb = (Math.round(alpha * 255.0F) << 24) | rgb;
			// The gate is on the byte that actually reaches the text, not on the float: a weight that
			// rounds to alpha 0 draws nothing anyway, and submitting it only costs a draw.
			if ((argb >>> 24) == 0) {
				continue;
			}
			// The unit turns about the middle of its own font box, so a spin never drags it out of
			// its place in the line and a resting line is exactly where 'angle' puts it.
			pose.pushMatrix();
			pose.translate(restX + exit.x + halfWidth * scale, restY + exit.y + halfHeight * scale);
			pose.rotate((float) Math.toRadians(angle + flatSpin(effect, u, since)));
			pose.scale(scale, scale);
			if (noise > 0.0F) {
				// One noise glyph for the whole unit, drawn from the unit's left edge: that is what
				// the world path does with a display's text, so both modes decode the same way.
				graphics.text(font, noiseGlyph(effect, u), -Math.round(halfWidth),
					-Math.round(halfHeight), argb, shadow);
			} else {
				// The unit's own glyphs, each at its laid-out offset inside the unit and all under
				// the one pose above, so the cached strings are reused and nothing is allocated. A
				// 'block' unit's offsets already carry vanilla's line breaks, because its layout was
				// measured with the same 'Font.split' call the world display draws with.
				for (int g = first; g < end; g++) {
					final VFXTextLayout.Glyph glyph = glyphs.get(g);
					graphics.text(font, flat.texts().get(glyph.index()),
						-Math.round(halfWidth - (glyph.x() - unit.x())),
						-Math.round(halfHeight - (glyph.y() - unit.y())), argb, shadow);
				}
			}
			pose.popMatrix();
		}
	}

	// Drops the cached layouts of flat-mode effects that are no longer running: the cache is keyed
	// by instance id, so without this every finished effect would leave its layout behind.
	public static void pruneFlat(final Set<Long> activeInstances) {
		FLAT.keySet().removeIf(id -> !activeInstances.contains(id));
	}

	// The measured layout of a flat block, from the cache when the structural inputs are unchanged.
	// 'line_height' and 'wrap_width' shape the layout, so they are sampled here, when it is measured,
	// and not per frame: animating them must not re-measure the line sixty times a second. The wrap
	// width is expressed in layout units, hence the division by the current 'scale' - the same
	// conversion the world pool makes.
	private static FlatText flatLayout(final VFXActiveEffect effect, final VFXTextSpec spec) {
		// A replay can hand out an instance id again with other text, so the cached layout is only
		// reused when it was measured from the same text and the same structural inputs.
		final String signature = spec.content() + '\u0000' + spec.mode() + '\u0000' + spec.split()
			+ '\u0000' + spec.align() + '\u0000' + spec.anchor();
		final FlatText existing = FLAT.get(effect.getInstanceId());
		if (existing != null && existing.signature().equals(signature)) {
			return existing;
		}
		final float scale = effect.getParam("scale", 1.0F);
		final VFXTextLayout layout = VFXTextLayout.measure(Minecraft.getInstance().font,
			spec.content(), 1.0F, effect.getParam("line_height", 1.2F),
			effect.getParam("wrap_width", 0.0F) / Math.max(scale, 1e-4F), spec.split());
		final List<String> texts = new ArrayList<>(layout.glyphs().size());
		for (final VFXTextLayout.Glyph glyph : layout.glyphs()) {
			texts.add(String.valueOf(glyph.ch()));
		}
		final FlatText measured = new FlatText(signature, layout, List.copyOf(texts));
		FLAT.put(effect.getInstanceId(), measured);
		return measured;
	}

	// Per-frame housekeeping for the text pool: drops the characters of effects that are no longer
	// running, the whole pool when the client level changes, and steps every falling unit's body by
	// the ticks the shared clock owes - once per client tick, never once per frame, so the physics is
	// the 20 Hz integration the block particles get and the frames in between only interpolate.
	//
	// level           - the client level being drawn
	// clock           - the shared effect clock, in ticks
	// activeInstances - instance ids of the running world-mode text effects
	public static void tick(final ClientLevel level, final float clock, final Set<Long> activeInstances) {
		handleLevelChange(level);
		LIVE.entrySet().removeIf(entry -> {
			if (activeInstances.contains(entry.getKey())) {
				return false;
			}
			release(entry.getValue());
			return true;
		});
		if (Float.isNaN(lastClock)) {
			lastClock = clock;
			return;
		}
		final float delta = clock - lastClock;
		lastClock = clock;
		if (delta > 0.0F) {
			accumulator += delta;
			if (delta > MAX_CATCHUP_TICKS) {
				// A hitch: the backlog is stale motion nobody saw, so it is dropped rather than
				// replayed as a burst of steps.
				accumulator = 0.0F;
			}
			final int steps = Math.min((int) accumulator, MAX_STEPS_PER_FRAME);
			accumulator -= steps;
			for (int step = 0; step < steps; step++) {
				for (final LiveText live : LIVE.values()) {
					stepBodies(level, live);
				}
			}
		}
		// What the displays interpolate by this frame: the fraction of a tick the clock is into its
		// last one. It is kept on the class because placeBlock - which runs after this in the same
		// frame - is what positions the displays.
		lastFraction = clamp01(accumulator);
	}

	// Integrates every body of one block by one tick. A settled body is not stepped at all (the body
	// itself refuses), so a line that has come to rest costs nothing.
	private static void stepBodies(final ClientLevel level, final LiveText live) {
		for (final Fall fall : live.falls) {
			if (fall != null) {
				fall.body.step(level, fall.params);
			}
		}
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
			final VFXTextSpec spec, final Vec3 origin) {
		final float scale = effect.getParam("scale", 1.0F);
		final float lineHeight = effect.getParam("line_height", 1.2F);
		final float wrapWidth = effect.getParam("wrap_width", 0.0F);
		// A replay can hand out an instance id again with other text or other structural inputs, so
		// the pooled units are only reused when they were built from the same ones. 'scale' is
		// deliberately absent: it is animatable and applied per frame instead of baked in here.
		final String signature = spec.content() + '\u0000' + spec.mode() + '\u0000' + spec.split()
			+ '\u0000' + spec.align() + '\u0000' + spec.anchor();
		final LiveText existing = LIVE.get(effect.getInstanceId());
		if (existing != null) {
			// A finished record that the clock has gone back past is stale: a looping effect wraps
			// 'elapsed' and a replay reuses the instance id, so the line has to come back - it is
			// released and rebuilt here, in this same frame.
			final boolean stale = existing.finishedAt >= 0.0F && age(effect) < existing.finishedAt;
			if (existing.signature.equals(signature) && !stale) {
				return existing;
			}
			release(existing);
			LIVE.remove(effect.getInstanceId());
		}
		final Font font = Minecraft.getInstance().font;
		// Measured once at unit scale so an animated 'scale' grows the block including its spacing
		// without respawning; the wrap width is expressed in the same units, hence the division.
		final VFXTextLayout layout = VFXTextLayout.measure(font, spec.content(), 1.0F, lineHeight,
			wrapWidth / Math.max(scale, 1e-4F), spec.split());
		final List<Display.TextDisplay> entities = new ArrayList<>(layout.units().size());
		final int colour = colour(effect);
		final byte shadowFlag = effect.getParam("shadow", 1.0F) >= 0.5F
			? Display.TextDisplay.FLAG_SHADOW : (byte) 0;
		// 'through_blocks' is vanilla's x-ray mode: it draws the same quads from both sides, and, as
		// part of that same pipeline, through terrain. It is off by default, because the back of a
		// pinned block is a second display glued to the front one, not a see-through quad.
		final byte throughFlag = effect.getParam("through_blocks", 0.0F) >= 0.5F
			? Display.TextDisplay.FLAG_SEE_THROUGH : (byte) 0;
		final Style style = Style.EMPTY.withColor(TextColor.fromRgb(colour));
		// The rest pose of the block, built from exactly the maths placeBlock uses - minus the exit
		// displacement, because a character that has not started leaving stands in the line. A
		// display is registered by the client level at the position it carries when it is added, so
		// it must be spawned where it stands: added at the origin it is tracked far from the camera
		// and the first per-frame transform drags it in from there.
		final float spawnAlignShift = alignOffset(spec, layout.width() * scale);
		final float spawnAnchorShiftY = anchorOffset(spec, layout.height() * scale);
		final Quaternionf spawnRotation = blockRotation(effect);
		// The orientation rule is a param now, not a side effect of rot_*: 'billboard' names what the
		// display does, and 0 keeps the historical rule - face the camera unless a rot_* pins the
		// block. Structural, so it is read here with the rest pose: a display carries it while the
		// block lives, and a change rebuilds nothing until the effect is respawned.
		final Display.BillboardConstraints billboard = billboard(effect);
		// The world light darkens the text in a shadow, and a title usually has to read anywhere, so
		// 'brightness' defaults to full block and sky light; -1 hands the text back to the world
		// light. It is animatable, so placeBlock re-reads it per frame and only writes a change.
		final int brightness = brightnessLevel(effect);
		// 'wrap_width' is a layout-unit width, and the layout measures in font pixels (scale 1), so
		// the same width in font pixels is the param divided by the current 'scale'. Without
		// wrapping, vanilla's own default width is used, which is what a 'block' unit is laid out at -
		// the layout counts the block's lines at this very width, so its height matches the display.
		final float wrapFontPixels = wrapWidth / Math.max(scale, 1e-4F);
		final int lineWidth = VFXTextLayout.lineWidth(wrapFontPixels);
		// A text display draws its single-sided quad one way round, so a block pinned in the world
		// needs a second display drawn from the other side: 'two_sided' (the default) spawns one twin
		// per unit, glued to the same rectangle, carrying the same block rotation as the original and
		// a transformation scale of (-scale, scale, scale). The negative X scale has a negative
		// determinant, which mirrors the glyph and flips the triangle winding, so the twin is culled
		// from the front and drawn only from behind: the letter seen reversed, the way the back of a
		// physical sign reads. A 'center' billboard already turns to face the camera from every side,
		// so it needs no twin and would only double the entities for nothing. Structural, read once
		// here like 'billboard'.
		final boolean twoSided = effect.getParam("two_sided", 1.0F) >= 0.5F
			&& billboard != Display.BillboardConstraints.CENTER;
		// Glyphs spent so far: 'MAX_CHARS' still caps the characters even when they share entities,
		// and a unit is only spawned whole, so a block never appears half cut off.
		int spawnedGlyphs = 0;
		// Display entities spent so far: the cap counts entities, because a two-sided block spends
		// two per unit and must not slip past it as a pair.
		int spawnedEntities = 0;
		final List<Display.TextDisplay> twins = new ArrayList<>(twoSided ? layout.units().size() : 0);
		for (int u = 0; u < layout.units().size(); u++) {
			final VFXTextLayout.Unit unit = layout.units().get(u);
			if (spawnedEntities + (twoSided ? 2 : 1) > MAX_UNITS) {
				VFXLog.warnOnce(LOGGER, "text_overlay:units:" + effect.getId(),
					"text_overlay '{}': more than {} display entities{}, the tail is dropped",
					effect.getId(), MAX_UNITS, twoSided ? " (two_sided doubles each unit)" : "");
				break;
			}
			final int unitGlyphs = glyphsInUnit(layout, u);
			if (spawnedGlyphs + unitGlyphs > MAX_CHARS) {
				// A unit is spawned whole or not at all, so what the cap drops depends on the split:
				// the remaining units in 'char' and 'word' - the tail of the text - or the single
				// unit that is the whole block.
				VFXLog.warnOnce(LOGGER, "text_overlay:cap:" + effect.getId(),
					"text_overlay '{}': more than {} characters, {}",
					effect.getId(), MAX_CHARS,
					spec.split() == VFXTextSpec.Split.BLOCK
						? "the block is dropped" : "the tail is dropped");
				break;
			}
			// The shadow bit, the see-through bit plus the alignment bit a unit needs in every split
			// but 'char': the twin carries exactly the same flags, so it aligns the same way from the
			// other side.
			final byte unitFlags = (byte) (shadowFlag | throughFlag | alignFlags(unit, spec.split()));
			final Display.TextDisplay display = spawnDisplay(effect, level, unit, spec.split(), style,
				unitFlags, lineWidth, billboard, brightness, origin,
				restOffset(unit.x(), unit.y(), scale, spawnAlignShift, spawnAnchorShiftY,
					spawnRotation));
			// Stop here rather than skipping one: the entities are paired with the layout by index,
			// so a hole in the middle would shift every later unit onto the wrong text. Adding an
			// entity is a whole-level operation, so the rest would fail too.
			if (display == null) {
				break;
			}
			spawnedEntities++;
			entities.add(display);
			// The twin: the same unit through its own origin, carrying the same block rotation and
			// standing one unit width further on along that rotation - where its mirrored scale makes
			// the text run back over the original's rectangle.
			if (twoSided) {
				final Display.TextDisplay twin = spawnDisplay(effect, level, unit, spec.split(), style,
					unitFlags, lineWidth, billboard, brightness, origin,
					twinRestOffset(unit, spec.split(), scale, spawnAlignShift, spawnAnchorShiftY,
						spawnRotation));
				if (twin == null) {
					break;
				}
				spawnedEntities++;
				twins.add(twin);
			}
			spawnedGlyphs += unitGlyphs;
		}
		// One line per spawn, so the log says where the block actually went: a spawn that lands in
		// the wrong place is then visible in the log instead of only on screen.
		LOGGER.info("text_overlay '{}': spawned {} display(s) for {} unit(s) ({} two-sided twin(s))"
			+ " at ({}, {}, {})", effect.getId(), entities.size() + twins.size(),
			layout.units().size(), twins.size(), origin.x, origin.y, origin.z);
		// The text of each spawned unit, built once here instead of on every frame, then copied
		// into the "what the display shows" array.
		final String[] ownTexts = new String[entities.size()];
		for (int i = 0; i < ownTexts.length; i++) {
			ownTexts[i] = layout.units().get(i).text();
		}
		// What each display currently shows. Spawned with its own text; placeBlock rewrites an
		// entry only when the text it should show changes, which is what a noise swap is.
		final String[] lastTexts = ownTexts.clone();
		// The flags each display was spawned with: the shadow bit, the see-through bit plus the
		// alignment bit a unit needs in every split but 'char'. placeBlock rewrites a display's flags
		// only when the shadow or see-through param flips, so a standing line writes nothing.
		final byte[] lastFlags = new byte[entities.size()];
		for (int i = 0; i < lastFlags.length; i++) {
			lastFlags[i] = (byte) (shadowFlag | throughFlag
				| alignFlags(layout.units().get(i), spec.split()));
		}
		final LiveText live = new LiveText(effect.getInstanceId(), signature, entities, twins, layout,
			ownTexts, lastTexts, colour, brightness, lastFlags);
		LIVE.put(effect.getInstanceId(), live);
		return live;
	}

	// Builds one display of a unit and adds it to the client level at 'local' - the unit's rest
	// offset from the block origin - carrying everything the entity must be born with: the text and
	// its style, the alignment and shadow flags, the block's line width, the billboard constraint,
	// the brightness and the one-tick interpolation. Shared by a unit and its two-sided twin, so the
	// pair cannot disagree about any of it. Returns null when the level refused the entity; the
	// caller stops the whole spawn, because the rest would fail for the same reason.
	private static Display.TextDisplay spawnDisplay(final VFXActiveEffect effect,
			final ClientLevel level, final VFXTextLayout.Unit unit, final VFXTextSpec.Split split,
			final Style style, final byte flags, final int lineWidth,
			final Display.BillboardConstraints billboard, final int brightness, final Vec3 origin,
			final Vector3f local) {
		final Display.TextDisplay display = new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, level);
		display.setText(Component.literal(unit.text()).withStyle(style));
		display.setBackgroundColor(TRANSPARENT_BACKGROUND);
		display.setFlags(flags);
		// A 'block' unit is the whole text in one display, so the line breaking vanilla does for
		// it is the block's wrapping; every other mode already laid its units out here.
		if (split == VFXTextSpec.Split.BLOCK) {
			display.setLineWidth(lineWidth);
		}
		// The orientation is decided once, before the display is added: a billboard constraint is a
		// synced-data value the renderer reads per frame, not something to rewrite sixty times a
		// second for a value that only a respawn can change.
		display.setBillboardConstraints(billboard);
		applyBrightness(display, brightness);
		// The transform is refreshed every frame by placeBlock, so the display must interpolate
		// it over one tick; without this the render state is only rebuilt on the entity tick and
		// a moved block renders stepped at ~20 Hz.
		display.setTransformationInterpolationDuration(1);
		display.setTransformationInterpolationDelay(0);
		display.setPos(origin.x + local.x, origin.y + local.y, origin.z + local.z);
		// 26.2's ClientLevel.addEntity reads Entity.getId(), and a locally created entity has
		// none (only ServerLevel overrides getNextEntityId), so the id must be set first.
		display.setId(VFXBlockParticleEngine.nextLocalEntityId());
		// The spawn position is the entity's starting position, not a move to interpolate from.
		display.setOldPosAndRot();
		try {
			level.addEntity(display);
		} catch (Exception e) {
			VFXLog.warnOnce(LOGGER, "text_overlay:spawn:" + effect.getId(),
				"text_overlay '{}': could not add a display to the client level; dropping the unit",
				effect.getId(), e);
			display.discard();
			return null;
		}
		return display;
	}

	// How many glyphs one unit covers: up to the next unit's first glyph, so the spaces between
	// words and the characters of a block are counted where they belong. 'MAX_CHARS' caps characters,
	// and it has to see the whole text even in a mode that spawns fewer entities than it has glyphs.
	private static int glyphsInUnit(final VFXTextLayout layout, final int unitIndex) {
		final int start = layout.units().get(unitIndex).firstGlyph();
		final int end = unitIndex + 1 < layout.units().size()
			? layout.units().get(unitIndex + 1).firstGlyph() : layout.glyphs().size();
		return Math.max(0, end - start);
	}

	// Display.TextDisplay lays its own text out around the entity position: nothing set means
	// vanilla centres the whole cached block on it, FLAG_ALIGN_LEFT (0x08) starts a line at the
	// entity and FLAG_ALIGN_RIGHT (0x10) ends it there. The mod already places a unit at the left
	// edge of its own first glyph - 'align' shifts the whole block by half or all of its widest
	// line, never a single unit - so a unit drawn with vanilla's centred default would sit half its
	// own width to the left of where it belongs: a 'block' unit by half the block's width, every
	// word (a one-letter word included - its glyph is narrower than the unit cell) by half its own.
	// Pinning such a unit to its left edge is what lines it up with the block anchor again. Only a
	// single-glyph 'char' unit fills its own cell and keeps vanilla's default.
	private static byte alignFlags(final VFXTextLayout.Unit unit, final VFXTextSpec.Split split) {
		return split != VFXTextSpec.Split.CHAR || unit.text().length() > 1
			? Display.TextDisplay.FLAG_ALIGN_LEFT : (byte) 0;
	}

	// Builds the body of a unit that has just detached: a rigid rectangle standing at the rest pose
	// the display carries this frame - a move or an animated rot_* lands here, so the fall starts
	// exactly where the letter stood - moving the way 'exit_speed' (upward) and 'spread' (sideways)
	// say. Both are authored in blocks per second and the body integrates in blocks per tick, hence
	// the division by 20. 'gravity' is blocks per second squared, and the body reads it in the
	// block-particle unit (a multiple of GRAVITY_STEP, i.e. 400 * GRAVITY_STEP per second squared),
	// so the two engines agree on what a gravity of 18 means. The padding is half the glyph's own
	// height in blocks: the letter's footprint, which is what a collider has to catch.
	private static Fall createFall(final VFXActiveEffect effect, final int index,
			final VFXTextLayout.Unit unit, final Vec3 origin, final Vector3f local,
			final Quaternionf rotation, final float scale) {
		final Vec3 rest = new Vec3(origin.x + local.x, origin.y + local.y, origin.z + local.z);
		final Vector3f scatter = scatterVelocity(effect, index);
		final Vec3 velocity = new Vec3(scatter.x / 20.0,
			effect.getParam("exit_speed", 0.0F) * jitter(effect, index) / 20.0, scatter.z / 20.0);
		final double glyphHeight = Minecraft.getInstance().font.lineHeight * unit.lines() * scale
			* WORLD_UNITS_PER_FONT_PIXEL;
		// 'friction' is air drag per tick and the tangential velocity kept on contact, exactly as in
		// the block-particle spec - and it has to be below 1 for a glyph that lands skidding to stop
		// skidding, which is what lets the body come to rest and the fade start.
		final VFXTextBody.Params params = new VFXTextBody.Params(
			gravityOf(effect) / (400.0F * VFXBlockParticleSpec.GRAVITY_STEP),
			effect.getParam("friction", 0.94F),
			effect.getParam("bounce", 0.15F),
			effect.getParam("collision", 1.0F) >= 0.5F,
			0.5 * glyphHeight,
			effect.getParam("tumble", 0.0F) >= 0.5F);
		return new Fall(new VFXTextBody(rest, velocity, rotation,
			initialAngularVelocity(effect, index)), params);
	}

	// The angular velocity a unit starts its fall with, in radians per tick: the authored
	// 'spin_x/y/z' axis at |spin| times the unit's own jitter (degrees per second, so a twentieth of
	// that per tick). 'tumble' replaces the axis with the same per-unit off-axis direction the
	// closed form used, at the same speed, so the letters do not all wheel about one world axis. A
	// spin of zero has no axis to turn about, so the body keeps its detach orientation.
	private static Vector3f initialAngularVelocity(final VFXActiveEffect effect, final int index) {
		final Vector3f axis = new Vector3f(effect.getParam("spin_x", 0.0F),
			effect.getParam("spin_y", 0.0F), effect.getParam("spin_z", 0.0F));
		final float length = axis.length();
		final float speed = length * jitter(effect, index);
		if (speed < 1e-4F) {
			return new Vector3f();
		}
		if (effect.getParam("tumble", 0.0F) >= 0.5F) {
			final float j = jitter(effect, index);
			axis.set(j - 1.0F, 1.0F - j, 0.5F * j - 0.5F);
			if (axis.lengthSquared() < 1e-8F) {
				return new Vector3f();
			}
			axis.normalize();
		} else {
			axis.div(length);
		}
		return axis.mul((float) Math.toRadians(speed) / 20.0F);
	}

	// The effect's colour_r/g/b (0..1 each) as one packed 0xRRGGBB integer, white when unset.
	private static int colour(final VFXActiveEffect effect) {
		final int red = Math.round(clamp01(effect.getParam("color_r", 1.0F)) * 255.0F);
		final int green = Math.round(clamp01(effect.getParam("color_g", 1.0F)) * 255.0F);
		final int blue = Math.round(clamp01(effect.getParam("color_b", 1.0F)) * 255.0F);
		return (red << 16) | (green << 8) | blue;
	}

	// Positions every unit of the block: unit layout times the current 'scale', rotated by rot_*,
	// anchored, displaced by the exit, and refreshed for the animatable colour and shadow. The
	// schedule that drives the exit is indexed by unit, so a coarse split moves whole words (or the
	// whole block) instead of its characters.
	private static void placeBlock(final VFXActiveEffect effect,
			final LiveText live, final VFXTextSpec spec, final Vec3 origin) {
		final float scale = effect.getParam("scale", 1.0F);
		final VFXTextLayout layout = live.layout;
		final float alignShift = alignOffset(spec, layout.width() * scale);
		final float anchorShiftY = anchorOffset(spec, layout.height() * scale);
		final Quaternionf rotation = blockRotation(effect);
		// Colour and shadow are animatable but the entity's text is baked at spawn, so the style is
		// rebuilt here - and only written when it differs from what the entities already carry.
		final int colour = colour(effect);
		final byte shadowFlag = effect.getParam("shadow", 1.0F) >= 0.5F
			? Display.TextDisplay.FLAG_SHADOW : (byte) 0;
		// The see-through bit is rebuilt here like the shadow one. Off by default: it is vanilla's
		// x-ray mode for the same quad, not the two-display back this effect builds.
		final byte throughFlag = effect.getParam("through_blocks", 0.0F) >= 0.5F
			? Display.TextDisplay.FLAG_SEE_THROUGH : (byte) 0;
		final Style style = Style.EMPTY.withColor(TextColor.fromRgb(colour));
		final boolean restyle = colour != live.lastColour;
		live.lastColour = colour;
		// 'brightness' is animatable, so it is re-read here like the colour and written to every
		// display of the block only when the level actually changed - a standing line writes nothing,
		// and a keyframed brightness costs one synced-data write per step instead of per frame.
		final int brightness = brightnessLevel(effect);
		if (brightness != live.lastBrightness) {
			for (final Display.TextDisplay display : live.entities) {
				applyBrightness(display, brightness);
			}
			// The twins carry the same light as their originals, in the same one-shot refresh.
			for (final Display.TextDisplay twin : live.twins) {
				applyBrightness(twin, brightness);
			}
			live.lastBrightness = brightness;
		}
		// The two-sided twin's rest pose is the block's own rotation: the copy is the same letter
		// mirrored by its transformation scale, not a second, differently turned display. False when
		// this block has no twins, so a one-sided or center-billboard line never touches one.
		final boolean hasTwin = !live.twins.isEmpty();
		final int count = layout.units().size();
		final float revealed = reveal(effect) * count;
		// Noise is only computed when 'scramble' is on: with it off every unit shows its own cached
		// text, and the line costs no hash and no param lookup per frame.
		final boolean scrambleOn = effect.getParam("scramble", 0.0F) > 0.0F;
		// The block only finishes when every unit has faded out; until then this stays above 0.
		float brightest = 0.0F;
		for (int i = 0; i < live.entities.size(); i++) {
			final VFXTextLayout.Unit unit = layout.units().get(i);
			final Vector3f local = restOffset(unit.x(), unit.y(), scale, alignShift, anchorShiftY, rotation);
			final Display.TextDisplay display = live.entities.get(i);
			final float since = exitSince(effect, i);
			// The unit's body, built on the first frame it has detached and kept from then on. It is
			// seeded with the rest pose that frame gives it - a move or an animated rot_* lands here,
			// so the fall starts exactly where the letter stood - and its own state takes over from
			// there: the exit's offset is no longer a function of the clock at all. A unit that is
			// standing again - a looping effect wrapped the clock, or 'exit' was animated away from
			// a fall - hands its body back, so the line comes back whole and the next detach seeds a
			// fresh fall from wherever it then stands.
			Fall fall = live.falls[i];
			if (since < 0.0F || effect.getParam("exit", 0.0F) < 0.5F) {
				live.falls[i] = null;
				fall = null;
			} else if (fall == null) {
				fall = createFall(effect, i, unit, origin, local, rotation, scale);
				live.falls[i] = fall;
			}
			// The pose this frame: the rest pose while the unit stands, and the body's interpolated
			// pose once it falls - the position lerped and the orientation slerped by the leftover
			// tick fraction, so 20 Hz physics is drawn smoothly.
			final Vec3 rest = new Vec3(origin.x + local.x, origin.y + local.y, origin.z + local.z);
			final Vec3 position = fall == null ? rest : fall.body.pos(lastFraction);
			final Quaternionf orientation = fall == null ? new Quaternionf(rotation)
				: fall.body.orientation(lastFraction);
			// How far the body has carried the unit away from its rest pose. The twin is placed from
			// its own rest offset plus this same displacement, so the pair cannot separate.
			final double exitX = position.x - rest.x;
			final double exitY = position.y - rest.y;
			final double exitZ = position.z - rest.z;
			// Position carries the pose; the transformation's translation stays zero, otherwise the
			// offset is applied twice and the block flies away from its anchor.
			display.setPos(position.x, position.y, position.z);
			// The pose above is this frame's, not a move to interpolate from: pinning the previous
			// position to it makes the render state draw exactly the computed spot instead of
			// sweeping from the stale tick position, which read as stepping.
			display.setOldPosAndRot();
			display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(orientation),
				new Vector3f(scale, scale, scale), new Quaternionf()));
			if (fall != null) {
				// Re-anchor the one-tick interpolation window to this frame, as the block-particle
				// engine's updateDisplay does, so the display slerps from the pose it last rendered
				// to this one instead of holding it until the next entity tick.
				display.setTransformationInterpolationDelay(0);
			}
			// A scrambled unit shows a noise glyph instead of its own text, and is handed its own
			// text back as soon as the band has passed it. The text is written when the style
			// changed or when the text it should show changed - never once per frame - so a
			// standing line costs nothing and a decode only touches the units that are swapping.
			final float noise = scrambleOn ? scrambleAmount(effect, i, count) : 0.0F;
			final String text = noise > 0.0F ? noiseGlyph(effect, i) : live.ownTexts[i];
			final boolean textChanged = restyle || !text.equals(live.lastTexts[i]);
			if (textChanged) {
				display.setText(Component.literal(text).withStyle(style));
				live.lastTexts[i] = text;
			}
			// The shadow and see-through bits are animatable and the alignment bit is structural, so
			// the set is rebuilt per frame and written only when it differs from what this display
			// carries.
			final byte flags = (byte) (shadowFlag | throughFlag | alignFlags(unit, spec.split()));
			final boolean flagsChanged = flags != live.lastFlags[i];
			if (flagsChanged) {
				display.setFlags(flags);
				live.lastFlags[i] = flags;
			}
			// reveal is a fraction of the units, so this unit is fully shown, hidden, or the single
			// one at the wipe boundary caught mid-fade; the exit fades a unit that is leaving, and
			// the effect weight fades the whole block instead of popping it at the stop. A scrambled
			// unit is drawn at the noise's own opacity, which rises towards the frontier, so it
			// lights up as the sweep reaches it instead of waiting at zero.
			final float exitAlpha = exitAlpha(effect, fall, since);
			final float revealAlpha = noise > 0.0F ? noise : clamp01(revealed - i);
			brightest = Math.max(brightest, exitAlpha);
			final byte opacity = (byte) Math.round(
				clamp01(revealAlpha * exitAlpha * effect.getWeight()) * 255.0F);
			display.setTextOpacity(opacity);
			// The twin is the same unit seen from behind: it stands at the far edge of this unit's
			// advance, takes exactly this frame's pose - the body's position and orientation once the
			// unit falls, its block rotation while it stands - and differs only in its transformation
			// scale, (-scale, scale, scale). That negative X scale is the mirror: it flips the glyph
			// and the triangle winding, so the copy is culled from the front and drawn from behind,
			// showing the letter reversed exactly where the original stands. It shares the same
			// displacement, brightness and opacity, and its text and flags are written on exactly the
			// conditions the original's are, so a standing line writes neither copy and a decode
			// touches both where it touched one.
			if (hasTwin && i < live.twins.size()) {
				final Display.TextDisplay twin = live.twins.get(i);
				final Vector3f twinLocal = twinRestOffset(unit, spec.split(), scale, alignShift,
					anchorShiftY, rotation);
				twin.setPos(origin.x + twinLocal.x + exitX, origin.y + twinLocal.y + exitY,
					origin.z + twinLocal.z + exitZ);
				twin.setOldPosAndRot();
				twin.setTransformation(new Transformation(new Vector3f(),
					new Quaternionf(orientation), new Vector3f(-scale, scale, scale),
					new Quaternionf()));
				if (fall != null) {
					twin.setTransformationInterpolationDelay(0);
				}
				if (textChanged) {
					twin.setText(Component.literal(text).withStyle(style));
				}
				if (flagsChanged) {
					twin.setFlags(flags);
				}
				twin.setTextOpacity(opacity);
			}
		}
		// Every character has faded and there is still an exit pending: the block is gone. Only an
		// authored exit can get here, so a block with 'reveal' 0 (invisible, not leaving) is not
		// mistaken for a finished one and keeps waiting for its characters.
		if (brightest <= 0.0F && !live.entities.isEmpty() && detachTime(effect, 0) >= 0.0F) {
			finish(effect, live);
		}
	}

	// The block's own orientation, straight from rot_*.
	private static Quaternionf blockRotation(final VFXActiveEffect effect) {
		return new Quaternionf()
			.rotateX((float) Math.toRadians(effect.getParam("rot_x", 0.0F)))
			.rotateY((float) Math.toRadians(effect.getParam("rot_y", 0.0F)))
			.rotateZ((float) Math.toRadians(effect.getParam("rot_z", 0.0F)));
	}

	// What the block does with the camera, from the float code of 'billboard': 0 decides from the
	// rest of the definition, 1..4 name one constraint outright. 0 is the historical rule, so a
	// definition that never mentions the param renders exactly as it did: a display's billboard
	// defaults to FIXED - one one-sided quad, which a block lying in the player's own XZ plane is
	// seen edge-on and reads as no text at all - and with no authored rotation there is no
	// orientation to preserve, so the block faces the camera; any rot_* pins it in world orientation,
	// which is what those params mean.
	private static Display.BillboardConstraints billboard(final VFXActiveEffect effect) {
		return switch (Math.round(effect.getParam("billboard", 0.0F))) {
			case 1 -> Display.BillboardConstraints.FIXED;
			case 2 -> Display.BillboardConstraints.CENTER;
			case 3 -> Display.BillboardConstraints.VERTICAL;
			case 4 -> Display.BillboardConstraints.HORIZONTAL;
			default -> {
				final boolean pinned = Math.abs(effect.getParam("rot_x", 0.0F)) >= 1e-4F
					|| Math.abs(effect.getParam("rot_y", 0.0F)) >= 1e-4F
					|| Math.abs(effect.getParam("rot_z", 0.0F)) >= 1e-4F;
				yield pinned ? Display.BillboardConstraints.FIXED : Display.BillboardConstraints.CENTER;
			}
		};
	}

	// The 'brightness' param as a light level, with exactly the block-particle convention: -1 means
	// the world light at the text's own position, and 0..15 is an override of both the block and the
	// sky light. The bounds are the light-level range itself, not a cap on an authored value, and
	// the default is full so a title reads in a dark room.
	private static int brightnessLevel(final VFXActiveEffect effect) {
		final int level = Math.round(effect.getParam("brightness", 15.0F));
		return level < 0 ? -1 : Math.min(level, 15);
	}

	// Writes one brightness level to a display: a packed block/sky override for 0..15, and no
	// override at all for -1, which is what hands the text back to the world light. Shared by the
	// spawn and the per-frame refresh so the two cannot disagree about what -1 means.
	private static void applyBrightness(final Display.TextDisplay display, final int level) {
		display.setBrightnessOverride(level >= 0 ? new Brightness(level, level) : null);
	}

	// Where a unit sits, relative to the block origin: the layout's font pixels times the current
	// 'scale', shifted by the spec's align and anchor, turned into world units by
	// WORLD_UNITS_PER_FONT_PIXEL, then turned by rot_*. Shared by the spawn-time rest pose and the
	// per-frame placement, so both agree on where the unit is - the text itself is drawn at the
	// renderer's own 0.025 scale, so only the spacing would drift if this factor were skipped, and
	// the shifts above must carry it too or a centred line would hang off its anchor.
	private static Vector3f restOffset(final float x, final float y, final float scale,
			final float alignShift, final float anchorShiftY, final Quaternionf rotation) {
		return new Vector3f(
			(x * scale + alignShift) * WORLD_UNITS_PER_FONT_PIXEL,
			(y * scale + anchorShiftY) * WORLD_UNITS_PER_FONT_PIXEL, 0.0F)
			.rotate(rotation);
	}

	// Where a two-sided twin stands, relative to the block origin: the original unit's own rest
	// offset plus the block rotation applied to (unitWidth, 0, 0). A display draws its text from the
	// position it is given along its local +X, and the twin's negative X scale makes it run that way
	// backwards, so the twin's origin is the far edge of the original's advance: its text covers the
	// same rectangle from that far edge back to the original origin, mirrored about the plane.
	// A unit vanilla draws centred instead - a single-glyph 'char' unit owns no alignment bit - needs
	// no shift: a centred quad spans [-width/2, +width/2] about its origin, and a mirror about that
	// same origin maps the interval onto itself, so it lands on the identical cell.
	// Shared by the spawn-time rest pose and the per-frame placement, so the pair cannot drift apart.
	private static Vector3f twinRestOffset(final VFXTextLayout.Unit unit,
			final VFXTextSpec.Split split, final float scale, final float alignShift,
			final float anchorShiftY, final Quaternionf rotation) {
		final float originX = alignFlags(unit, split) != 0 ? unit.x() + unit.width() : unit.x();
		return restOffset(originX, unit.y(), scale, alignShift, anchorShiftY, rotation);
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

	// The one reading of 'gravity' both halves of the exit use: it divides the fall and the landing
	// test, so zero would divide by zero. A lower bound only - there is no upper limit, a huge
	// gravity just makes the drop instant.
	private static float gravityOf(final VFXActiveEffect effect) {
		return Math.max(effect.getParam("gravity", 18.0F), 1.0e-3F);
	}

	// Deterministic per-character draw in [0, 1], from the instance seed, the unit index and a salt:
	// a different salt is an independent draw, so the X and the Z scatter point different ways.
	private static float unitHash(final VFXActiveEffect effect, final int index, final long salt) {
		final long h = effect.getInstanceId() * 0x9E3779B97F4A7C15L
			^ (index * 0xBF58476D1CE4E5B9L) ^ salt;
		final long mixed = h ^ (h >>> 31);
		return (mixed & 0xFFFFL) / 65535.0F;
	}

	// Deterministic per-character jitter in [0.85, 1.15], from the instance seed and the index.
	private static float jitter(final VFXActiveEffect effect, final int index) {
		return 0.85F + 0.30F * unitHash(effect, index, SALT_SPEED);
	}

	// A per-character horizontal scatter, in blocks per second: 'spread' is the half-range and two
	// independent draws pick the direction on X and on Z. It is a distance per second, not a
	// fraction, so 'spread' has no upper limit - it only widens the fan.
	private static Vector3f scatterVelocity(final VFXActiveEffect effect, final int index) {
		final float spread = effect.getParam("spread", 0.6F);
		return new Vector3f(
			spread * (2.0F * unitHash(effect, index, SALT_SCATTER_X) - 1.0F), 0.0F,
			spread * (2.0F * unitHash(effect, index, SALT_SCATTER_Z) - 1.0F));
	}

	// Opacity of unit 'index' from its exit timeline: 1 while it stands, then the fade exit's ramp,
	// or - for a fall - 1 until the body reports resting, the 'hold' seconds after that, and then
	// 'fade_out'. The rest time is recorded from the body itself, on the first frame it reports
	// settled, so the hold and the ramp are timed from the tick the physics actually stopped - there
	// is no closed form left to ask. A body that cannot rest (free fall, a bounce of 1 or a friction
	// so close to 1 it never sheds its drift) would otherwise never reach the ramp, so the same hold
	// + ramp starts the moment it is released: when it has left the world (below the level's minimum
	// build height, where nothing can catch it) or when 'RELEASE_SECONDS' have passed without it
	// coming to rest. Both are release guarantees, not gameplay limits - an honest fall lands or
	// leaves the world long before either fires - and they let the block reach zero so 'finish'
	// releases it whatever the physics is doing. With 'collision' off there is no floor at all, so
	// 'fade_out' runs from the detach instead (and 0 keeps it opaque), which is the free-fall rule
	// this renderer has always had.
	private static float exitAlpha(final VFXActiveEffect effect, final Fall fall, final float since) {
		if (since < 0.0F) {
			return 1.0F;
		}
		if (effect.getParam("exit", 0.0F) < 0.5F) {
			return fade(effect, since, 0.0F);
		}
		if (fall == null || effect.getParam("collision", 1.0F) < 0.5F) {
			return fade(effect, since, 0.0F);
		}
		if (fall.releaseSince < 0.0F && fall.restSince < 0.0F
			&& (stranded(fall.body) || since >= RELEASE_SECONDS)) {
			fall.releaseSince = since;
		}
		if (fall.restSince < 0.0F && fall.body.resting()) {
			fall.restSince = since;
		}
		final float from = fall.releaseSince >= 0.0F ? fall.releaseSince : fall.restSince;
		if (from < 0.0F) {
			return 1.0F;
		}
		return fade(effect, since, from + holdOf(effect));
	}

	// Whether this body has left the world, i.e. is below the level's minimum build height and can
	// no longer land on anything. Read from the level the pool is stepped with, so a level change
	// (which clears the pool anyway) cannot leave a stale floor behind.
	private static boolean stranded(final VFXTextBody body) {
		return lastLevel != null && body.stranded(lastLevel.getMinY());
	}

	// The fade ramp itself, shared by both modes and both exits: fully opaque up to 'from' seconds
	// after the detach, then falling linearly to zero over 'fade_out'. A 'fade_out' of 0 is not a
	// fade: the character never reaches zero and stays visible until the effect stops, which is what
	// keeps the letters on screen. The small lower bound is applied to the division only, so it
	// guards against a divide by zero without ever reinterpreting an authored 0 as "instant".
	private static float fade(final VFXActiveEffect effect, final float since, final float from) {
		final float fadeOut = effect.getParam("fade_out", 0.5F);
		if (fadeOut <= 0.0F) {
			return 1.0F;
		}
		return 1.0F - clamp01((since - from) / Math.max(fadeOut, 1.0e-4F));
	}

	// Seconds a landed character stays fully opaque before its fade starts. A duration, so it has
	// no upper limit; a negative value would run the fade backwards before the landing, so it is
	// floored at zero.
	private static float holdOf(final VFXActiveEffect effect) {
		return Math.max(effect.getParam("hold", 0.0F), 0.0F);
	}

	// Where a flat character is this frame, relative to its rest position on the HUD: zero while it
	// stands in the line, zero for a fade exit, and otherwise a sideways drift plus a drop. Closed
	// form, so the same tick always yields the same frame, and the same 'exit_at', 'exit_stagger',
	// 'exit', 'exit_speed', 'gravity' and 'fade_out' the world path reads - only the space differs:
	// speeds are in screen heights per second, hence the multiplications by the GUI height that make
	// GUI scale 2 and 4 look the same.
	//
	// effect    - the running effect
	// index     - the character's place in the line, for its share of the jitter
	// since     - seconds since the character detached, or -1 while it still stands
	// restX     - its resting screen X, the axis the drift folds around
	// restY     - its resting screen Y, the height the fall starts from
	// floorY    - the screen Y it rests on once it has landed (the bottom edge, minus its own box)
	// guiWidth  - the frame width, the span the drift bounces inside
	// guiHeight - the frame height, the unit the physics is expressed in
	private static Vector2f flatExitOffset(final VFXActiveEffect effect, final int index,
			final float since, final float restX, final float restY, final float floorY,
			final float guiWidth, final float guiHeight) {
		if (since < 0.0F || effect.getParam("exit", 0.0F) < 0.5F) {
			// The shared constant, not a fresh vector: this is the branch almost every character of
			// almost every frame takes, and the caller only reads it.
			return ZERO_OFFSET;
		}
		// Sideways: a small drift taken from the same jitter the world fall uses, so the characters of
		// one line never leave in the same direction, folded back into the frame so the letter bounces
		// off the left and right edges. Subtracting the fold of the rest position keeps the letter on
		// its own spot at 'since' 0, whatever the author placed.
		final float drift = (jitter(effect, index) - 1.0F) * guiHeight * since;
		final float sideways = reflect(restX + drift, guiWidth) - reflect(restX, guiWidth);
		final float impact = flatImpact(effect, index, restY, floorY, guiHeight);
		if (impact < 0.0F) {
			// No frame to hit: the letter falls straight out through the bottom while it fades.
			return new Vector2f(sideways, flatDrop(effect, index, since, guiHeight));
		}
		if (since <= impact) {
			return new Vector2f(sideways, flatDrop(effect, index, since, guiHeight));
		}
		// Landing: the letter holds on the bottom edge and fades there. A fold back up would never
		// settle, and a letter that never settles reads as jitter instead of as a landing. 'bounce' is
		// a world-mode parameter - there is no rebound against a frame edge, only the sideways fold.
		return new Vector2f(sideways, floorY - restY);
	}

	// How far a flat character has dropped 'since' seconds after it detached, in screen pixels: the
	// same parabola the world fall uses, with its speed and gravity read in screen heights.
	private static float flatDrop(final VFXActiveEffect effect, final int index, final float since,
			final float guiHeight) {
		final float speed = effect.getParam("exit_speed", 0.0F) * jitter(effect, index);
		return (speed * since - 0.5F * gravityOf(effect) * since * since) * guiHeight;
	}

	// When a flat character reaches the bottom edge, in seconds since it detached, or -1 when there
	// is nothing to reach: collision off, a letter that already rests on the edge, or a frame whose
	// height would divide by zero. The drop is measured in screen heights, so the shared 'gravity' and
	// 'exit_speed' keep their meaning at any GUI scale.
	private static float flatImpact(final VFXActiveEffect effect, final int index,
			final float restY, final float floorY, final float guiHeight) {
		if (effect.getParam("collision", 1.0F) < 0.5F || !Float.isFinite(floorY) || guiHeight <= 0.0F) {
			return -1.0F;
		}
		final float drop = (floorY - restY) / guiHeight;
		if (drop <= 0.0F) {
			return -1.0F;
		}
		final float gravity = gravityOf(effect);
		final float speed = effect.getParam("exit_speed", 0.0F) * jitter(effect, index);
		final float impact = (speed + (float) Math.sqrt(speed * speed + 2.0F * gravity * drop)) / gravity;
		return Float.isFinite(impact) ? impact : -1.0F;
	}

	// Opacity of flat character 'index' from the same timeline: 1 until it detaches, then the fade
	// exit's ramp, or - for a fall - 1 until it has hit the bottom edge, held for 'hold', and the
	// ramp while it holds there.
	private static float flatExitAlpha(final VFXActiveEffect effect, final int index,
			final float since, final float restY, final float floorY, final float guiHeight) {
		if (since < 0.0F) {
			return 1.0F;
		}
		if (effect.getParam("exit", 0.0F) < 0.5F) {
			return fade(effect, since, 0.0F);
		}
		final float impact = flatImpact(effect, index, restY, floorY, guiHeight);
		if (impact < 0.0F) {
			// Nothing to land on: there is no impact to wait for, so the letter fades with the fall
			// unless 'fade_out' is 0, where it falls on until the effect stops.
			return fade(effect, since, 0.0F);
		}
		return fade(effect, since, impact + holdOf(effect));
	}

	// A flat character's extra rotation while it leaves, in degrees: a 2D pose can only turn about z,
	// so the spin is spin_z * since. With 'tumble' the direction flips per character from the jitter,
	// so the letters do not turn as one rigid sheet. It is added to 'angle' by the caller.
	private static float flatSpin(final VFXActiveEffect effect, final int index, final float since) {
		if (since < 0.0F) {
			return 0.0F;
		}
		final float spin = effect.getParam("spin_z", 0.0F) * since;
		if (effect.getParam("tumble", 0.0F) < 0.5F) {
			return spin;
		}
		return jitter(effect, index) >= 1.0F ? spin : -spin;
	}

	// Folds a coordinate back inside [0, size] as if it bounced off both edges. Closed form - a fold,
	// not a loop over frames - so a replay and a screenshot agree on where the letter is.
	private static float reflect(final float value, final float size) {
		final float span = Math.max(size, 1.0F);
		final float folded = Math.abs(value % (2.0F * span));
		return folded > span ? 2.0F * span - folded : folded;
	}

	// The block is over: every character has faded out. Its characters are released now, and the
	// pool entry stays behind carrying the age it finished at, so 'ensure' neither respawns the line
	// next frame nor keeps it dead once the effect loops or is replayed.
	//
	// A block that never fades ('fade_out' 0) never gets here, and that is fine: it is not leaked.
	// Its characters are released by 'tick' when the effect stops being active and by
	// 'handleLevelChange' when the level goes away, and a looping effect simply reuses them - the
	// clock wraps, 'since' turns negative again and the standing line comes back at full opacity.
	private static void finish(final VFXActiveEffect effect, final LiveText live) {
		live.finishedAt = age(effect);
		release(live);
		live.entities.clear();
		live.twins.clear();
	}


	// The animated fraction of characters that are visible, 1 when the param is unset.
	private static float reveal(final VFXActiveEffect effect) {
		return clamp01(effect.getParam("reveal", 1.0F));
	}

	// How much of unit 'index' is noise this frame, in 0..1: zero when it shows its own text, which
	// is every unit of every frame while 'scramble' is off. Two windows produce noise - the band just
	// ahead of the reveal frontier, fading out as the unit is further from it, and the 'scramble_time'
	// seconds before the unit detaches. Both are read from the clock and the params alone, so nothing
	// accumulates and the same tick always yields the same frame.
	//
	// effect - the running effect
	// index  - the unit's place in the block
	// count  - the units in the block, the span 'reveal' is a fraction of
	private static float scrambleAmount(final VFXActiveEffect effect, final int index,
			final int count) {
		final float on = clamp01(effect.getParam("scramble", 0.0F));
		if (on <= 0.0F) {
			return 0.0F;
		}
		// The exit window: noise while the character still stands in the line, then it leaves by the
		// exit that is set, showing its own character again. A negative window would run backwards,
		// so it is floored; there is no upper limit - a long window just scrambles longer.
		final float detach = detachTime(effect, index);
		final float window = Math.max(effect.getParam("scramble_time", 0.35F), 0.0F);
		if (detach >= 0.0F && window > 0.0F) {
			final float age = age(effect);
			if (age >= detach - window && age < detach) {
				return on;
			}
		}
		// The reveal band: full noise at the frontier, none 'scramble_band' characters ahead of it, so
		// the line decodes towards the wipe. A band of 0 is the switch with no reveal noise, and a
		// huge band simply scrambles the whole tail of the line.
		final float band = effect.getParam("scramble_band", 4.0F);
		if (band <= 0.0F) {
			return 0.0F;
		}
		final float ahead = index - reveal(effect) * count;
		return ahead < 0.0F ? 0.0F : on * clamp01(1.0F - ahead / band);
	}

	// The noise glyph character 'index' shows this frame, as a one-character string. The swap number
	// is the clock read in units of 'scramble_rate', and the glyph is a hash of the instance id, the
	// index and that swap into the fixed pool: the same tick therefore always draws the same glyph,
	// so a replay and a screenshot agree, and a glyph only changes when the rate says it should.
	private static String noiseGlyph(final VFXActiveEffect effect, final int index) {
		// A negative rate would walk the swaps backwards; the floor is the only guard needed, and a
		// large rate simply swaps on every frame.
		final float rate = Math.max(effect.getParam("scramble_rate", 15.0F), 0.0F);
		final long swap = (long) Math.floor(age(effect) * rate);
		long h = effect.getInstanceId() * 0x9E3779B97F4A7C15L;
		h ^= index * 0xBF58476D1CE4E5B9L;
		h ^= swap * 0x94D049BB133111EBL;
		h ^= h >>> 31;
		h *= 0x9E3779B97F4A7C15L;
		h ^= h >>> 29;
		final int slot = (int) Math.floorMod(h, (long) NOISE_GLYPHS.length());
		return String.valueOf(NOISE_GLYPHS.charAt(slot));
	}

	private static float clamp01(final float value) {
		return value < 0.0F ? 0.0F : (value > 1.0F ? 1.0F : value);
	}

	// Discards an effect's display entities; the level forgets them, only the pool needs clearing.
	// The two-sided twins die with the units they belong to, in the same pass.
	private static void release(final LiveText live) {
		for (final Display.TextDisplay display : live.entities) {
			VFXBlockParticleEngine.removeDisplay(display);
		}
		for (final Display.TextDisplay twin : live.twins) {
			VFXBlockParticleEngine.removeDisplay(twin);
		}
	}

	// Drops the pool when the client level changes: the old level's entities went with it. The clock
	// baseline goes too, so the first tick on the new level does not integrate a backlog of ticks
	// that belong to the level that is gone - the same reset the block-particle engine makes.
	private static void handleLevelChange(final ClientLevel level) {
		if (lastLevel == level) {
			return;
		}
		lastLevel = level;
		lastClock = Float.NaN;
		accumulator = 0.0F;
		lastFraction = 0.0F;
		clear();
	}
	*///?} else {
	// <26.2: Display.TextDisplay's setters are not public, so the whole feature is a no-op here.
	// The methods stay so callers do not need a version guard of their own.

	public static void renderWorld(final VFXActiveEffect effect, final ClientLevel level, final PoseStack pose) {
	}

	public static void tick(final ClientLevel level, final float clock, final Set<Long> activeInstances) {
	}

	public static void remove(final long instanceId) {
	}

	public static void clear() {
	}
	//?}
}

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
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
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
 * {@code Display.TextDisplay} per character standing where the author put the block, {@code flat}
 * mode as the same laid-out block drawn onto the HUD through the GUI graphics.
 *
 * <p>The characters are laid out once by {@link VFXTextLayout} at unit scale and then placed every
 * frame from the animatable params - the frame's first world slot (a runtime move, a {@code positions}
 * entry or an entity anchor) as the origin, or {@code pos_x/pos_y/pos_z} alone as absolute
 * coordinates when the definition has no slot at all - {@code rot_x/rot_y/rot_z}
 * for the orientation, {@code scale} for the size of the whole block, the spec's {@code align} and
 * {@code anchor} for which point of the block sits on that origin - so moving, rotating or scaling an
 * already-running effect moves the whole block without respawning anything. {@code colour_r/g/b} and
 * {@code shadow} are re-applied per frame, but only written to the entities when the value changed.
 *
 * <p>{@code wrap_width} and {@code line_height} shape the layout, which is measured once when the
 * block is spawned, so they are sampled at that moment and are not animatable.
 *
 * <p>In {@code flat} mode the block is laid out at unit scale, cached by instance id, and drawn onto
 * the HUD: {@code x} and {@code y} are fractions of the GUI size and place the block's anchor,
 * {@code angle} turns the whole block about that anchor and {@code scale} multiplies the font's own
 * pixel size. The reveal wipe and the effect weight work exactly as they do in the world.
 *
 * <p>The flat exit is the world exit's timeline in screen space: the same {@code exit_at},
 * {@code exit_stagger}, {@code exit}, {@code exit_speed}, {@code gravity} and {@code fade_out}, but
 * {@code gravity} and the speeds are read in screen heights per second, so every offset is multiplied
 * by the GUI height and the fall looks the same at GUI scale 2 and 4. With {@code collision} on, a
 * falling character is stopped by the bottom edge and fades out holding there, and its sideways drift
 * is folded back into the frame so it bounces off the left and right edges; with {@code collision} off
 * it falls straight out through the bottom while fading. A leaving character may only turn about z, so
 * its own rotation is {@code angle + spin_z * since}, applied about the middle of its own font box.
 *
 * <p>{@code reveal} is a fraction of the characters that are visible: character {@code i} is opaque
 * while {@code i < reveal * count} and fades over the single character at the boundary, which is what
 * gives a typed-in / wiped-in line instead of a hard pop.
 *
 * <p>{@code scramble} turns both transitions into a decode. While it is above half, the characters
 * inside the {@code scramble_band} window ahead of the reveal frontier, and the characters inside the
 * {@code scramble_time} seconds before they detach, draw a noise glyph instead of their own character:
 * a hash of the instance id, the character index and the swap number into {@code A-Z 0-9 #$%&*+=-<>/?},
 * so the same tick always draws the same noise and a replay agrees with a screenshot. The glyph swaps
 * {@code scramble_rate} times a second, the noise fades out towards the far end of the band - rising
 * towards the frontier - and a character snaps back to itself the moment the frontier passes it or the
 * exit takes over. Both windows are read from the clock and the params, never accumulated. The world
 * path rewrites a display's text only when the glyph it shows actually changed; the flat path simply
 * draws the noise character. With {@code scramble} at 0 every frame is the one it was before.
 *
 * <p>The exit is computed from the clock, never accumulated, so the same tick always yields the same
 * frame. Character {@code index} starts leaving at {@code exit_at + index * exit_stagger} seconds;
 * with {@code exit < 0.5} it only fades in place, with {@code exit >= 0.5} it falls, spins and - when
 * {@code collision} is on - lands on the first block below it, rebounds once and settles - or, in
 * {@code flat} mode, on the bottom edge of the frame and holds there. Once every
 * character has faded, the block is marked finished at the age it finished and its entities are
 * released; when the effect loops or is replayed and the clock goes back, the block is rebuilt.
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
	// The world size of one font pixel. net.minecraft.client.renderer.entity
	// .DisplayRenderer$TextDisplayRenderer scales a glyph by -0.025f before it draws it, so a layout
	// measured in font pixels is only in world units once it is multiplied by this.
	private static final float WORLD_UNITS_PER_FONT_PIXEL = 0.025F;
	// The pool a scrambled character draws its noise from: no lowercase, so a decode reads as machine
	// output, and a fixed order, so the hash's index into it is stable between runs.
	private static final String NOISE_GLYPHS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#$%&*+=-<>/?";

	// Characters of one live text effect, in layout order. 'signature' is the content plus the
	// structural inputs, because an instance id can be reused by a replay with different text.
	// 'layout' is measured at unit scale; placeBlock multiplies it by the current 'scale'.
	// 'lastColour' and 'lastFlags' are the values last written to the entities, so the per-frame
	// colour and shadow refresh only touches a display when the value actually changed.
	// 'lastTexts' is the character string last written to each display - its own glyph, or the noise
	// glyph of the last swap - so a scrambled character is rewritten only when its noise changes and
	// is handed its own character back when the band passes it, whatever the colour cache says.
	// 'floors' is the one per-character value the exit stores: the top of the first collider found
	// under that character's own column, the character's own rest Y when its rest point is already
	// embedded in terrain (so it does not fall at all), or NEGATIVE_INFINITY when there is nothing
	// to land on. An entry starts as NaN - unresolved - and is filled with one downward scan on the
	// frame the character detaches, so the probe follows the world under the word instead of a
	// single spawn-time query. 'finishedAt' is the age at which every character had faded out and been
	// released, or -1 while the block is live; a record whose age is now below that value is
	// stale, because a looping effect wraps 'elapsed' back to 0 and a replay reuses the instance id.
	private static final class LiveText {
		private final long instanceId;
		private final String signature;
		private final List<Display.TextDisplay> entities;
		private final VFXTextLayout layout;
		private final float[] floors;
		private final String[] ownTexts;
		private final String[] lastTexts;
		private int lastColour;
		private byte lastFlags;
		private float finishedAt = -1.0F;

		private LiveText(final long instanceId, final String signature,
				final List<Display.TextDisplay> entities, final VFXTextLayout layout,
				final float[] floors, final String[] ownTexts, final String[] lastTexts,
				final int lastColour, final byte lastFlags) {
			this.instanceId = instanceId;
			this.signature = signature;
			this.entities = entities;
			this.layout = layout;
			this.floors = floors;
			this.ownTexts = ownTexts;
			this.lastTexts = lastTexts;
			this.lastColour = lastColour;
			this.lastFlags = lastFlags;
		}
	}

	// One flat-mode block's measured layout, cached by instance id: the HUD pass runs every frame,
	// so a long line is measured once per instance instead of sixty times a second. 'signature' is
	// structural only - content, mode, align, anchor - because that is what the layout is a function
	// of once it has been measured; 'line_height' and 'wrap_width' are sampled at that moment, the
	// same way the world pool samples them, so an animated 'line_height' does not re-measure. 'texts'
	// holds the one-character string of each glyph, built once here instead of on every frame.
	private record FlatText(String signature, VFXTextLayout layout, List<String> texts) {
	}

	// The offset of a character that is not leaving. Shared, because a standing line must not
	// allocate one vector per character per frame; the callers only read it.
	private static final Vector2f ZERO_OFFSET = new Vector2f();

	private static final Map<Long, LiveText> LIVE = new HashMap<>();
	private static final Map<Long, FlatText> FLAT = new HashMap<>();
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
		placeBlock(effect, level, live, spec, origin);
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
	// layout does not carry the scale - it can animate without re-measuring anything. Each character
	// then gets its own pose, because a character that is leaving also turns about its own centre.
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
		// The bottom edge, as the screen Y a character's own box rests on when it has landed: the
		// fall is solved against the bottom of the letter, not the top of its cell, so a landed line
		// reads as standing on the frame instead of having fallen through it.
		final float floorY = guiHeight - font.lineHeight * scale;
		// 'angle' turns the block about its anchor, so the rest positions are turned here instead of
		// by the pose - the pose below is per character and carries 'angle + spin_z * since'.
		final float radians = (float) Math.toRadians(angle);
		final float cos = (float) Math.cos(radians);
		final float sin = (float) Math.sin(radians);
		final int rgb = colour(effect);
		final boolean shadow = effect.getParam("shadow", 1.0F) >= 0.5F;
		final float revealed = reveal(effect) * layout.glyphs().size();
		final Matrix3x2fStack pose = graphics.pose();
		for (int i = 0; i < layout.glyphs().size(); i++) {
			final VFXTextLayout.Glyph glyph = layout.glyphs().get(i);
			final float since = exitSince(effect, i);
			final float localX = (glyph.x() + alignShift) * scale;
			final float localY = (glyph.y() + anchorShiftY) * scale;
			final float restX = anchorX + localX * cos - localY * sin;
			final float restY = anchorY + localX * sin + localY * cos;
			final Vector2f exit = flatExitOffset(effect, i, since, restX, restY, floorY, guiWidth, guiHeight);
			// Same wipe as the world path: 'reveal' is a fraction of the characters, and the single
			// one at the boundary is caught mid-fade; the exit fades a character that is leaving, and
			// the effect weight fades the whole block when the effect is stopped. A plain 0xRRGGBB
			// colour has alpha 0 here, so it must be packed. A scrambled character is drawn at the
			// noise's own opacity instead, which rises towards the frontier.
			final float noise = scrambleAmount(effect, i, layout.glyphs().size());
			final float revealAlpha = noise > 0.0F ? noise : clamp01(revealed - i);
			final float alpha = revealAlpha
				* flatExitAlpha(effect, i, since, restY, floorY, guiHeight) * effect.getWeight();
			final int argb = (Math.round(alpha * 255.0F) << 24) | rgb;
			// The gate is on the byte that actually reaches the text, not on the float: a weight that
			// rounds to alpha 0 draws nothing anyway, and submitting it only costs a draw.
			if ((argb >>> 24) == 0) {
				continue;
			}
			// The noise character is drawn in the real character's cell, so it is measured from the
			// real one: a swap then never shifts a letter sideways. Only the string handed to the
			// font changes, so a scrambled frame costs no more than a plain one.
			final String text = noise > 0.0F ? noiseGlyph(effect, i) : flat.texts().get(i);
			// The character turns about the middle of its own font box, so a spin never drags the
			// letter out of its slot in the line and a resting line is exactly where 'angle' puts it.
			final float halfWidth = font.width(flat.texts().get(i)) / 2.0F;
			final float halfHeight = font.lineHeight / 2.0F;
			pose.pushMatrix();
			pose.translate(restX + exit.x + halfWidth * scale, restY + exit.y + halfHeight * scale);
			pose.rotate((float) Math.toRadians(angle + flatSpin(effect, i, since)));
			pose.scale(scale, scale);
			graphics.text(font, text, -Math.round(halfWidth), -Math.round(halfHeight), argb, shadow);
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
		final String signature = spec.content() + '\u0000' + spec.mode() + '\u0000' + spec.align()
			+ '\u0000' + spec.anchor();
		final FlatText existing = FLAT.get(effect.getInstanceId());
		if (existing != null && existing.signature().equals(signature)) {
			return existing;
		}
		final float scale = effect.getParam("scale", 1.0F);
		final VFXTextLayout layout = VFXTextLayout.measure(Minecraft.getInstance().font,
			spec.content(), 1.0F, effect.getParam("line_height", 1.2F),
			effect.getParam("wrap_width", 0.0F) / Math.max(scale, 1e-4F));
		final List<String> texts = new ArrayList<>(layout.glyphs().size());
		for (final VFXTextLayout.Glyph glyph : layout.glyphs()) {
			texts.add(String.valueOf(glyph.ch()));
		}
		final FlatText measured = new FlatText(signature, layout, List.copyOf(texts));
		FLAT.put(effect.getInstanceId(), measured);
		return measured;
	}

	// Per-frame housekeeping for the text pool: drops the characters of effects that are no longer
	// running and the whole pool when the client level changes. The per-character exit is placed by
	// placeBlock from the effect clock; this only owns the lifecycle.
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
			final VFXTextSpec spec, final Vec3 origin) {
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
			wrapWidth / Math.max(scale, 1e-4F));
		final List<Display.TextDisplay> entities = new ArrayList<>(layout.glyphs().size());
		final int colour = colour(effect);
		final byte flags = effect.getParam("shadow", 1.0F) >= 0.5F ? Display.TextDisplay.FLAG_SHADOW : (byte) 0;
		final Style style = Style.EMPTY.withColor(TextColor.fromRgb(colour));
		// The rest pose of the block, built from exactly the maths placeBlock uses - minus the exit
		// displacement, because a character that has not started leaving stands in the line. A
		// display is registered by the client level at the position it carries when it is added, so
		// it must be spawned where it stands: added at the origin it is tracked far from the camera
		// and the first per-frame transform drags it in from there.
		final float spawnAlignShift = alignOffset(spec, layout.width() * scale);
		final float spawnAnchorShiftY = anchorOffset(spec, layout.height() * scale);
		final Quaternionf spawnRotation = blockRotation(effect);
		// A display's billboard defaults to FIXED, which is one one-sided quad: a block that lies in
		// the player's own XZ plane is then seen edge-on and reads as no text at all. With no authored
		// rotation there is no orientation to preserve, so the characters are left facing the camera;
		// any rot_* pins the block in world orientation, which is what those params mean.
		final boolean cameraFacing = Math.abs(effect.getParam("rot_x", 0.0F)) < 1e-4F
			&& Math.abs(effect.getParam("rot_y", 0.0F)) < 1e-4F
			&& Math.abs(effect.getParam("rot_z", 0.0F)) < 1e-4F;
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
			if (cameraFacing) {
				display.setBillboardConstraints(Display.BillboardConstraints.CENTER);
			}
			// The transform is refreshed every frame by placeBlock, so the display must interpolate
			// it over one tick; without this the render state is only rebuilt on the entity tick and
			// a moved block renders stepped at ~20 Hz.
			display.setTransformationInterpolationDuration(1);
			display.setTransformationInterpolationDelay(0);
			final Vector3f spawnLocal = restOffset(glyph, scale, spawnAlignShift,
				spawnAnchorShiftY, spawnRotation);
			display.setPos(origin.x + spawnLocal.x, origin.y + spawnLocal.y,
				origin.z + spawnLocal.z);
			// 26.2's ClientLevel.addEntity reads Entity.getId(), and a locally created entity has
			// none (only ServerLevel overrides getNextEntityId), so the id must be set first.
			display.setId(VFXBlockParticleEngine.nextLocalEntityId());
			// The spawn position is the entity's starting position, not a move to interpolate from.
			display.setOldPosAndRot();
			try {
				level.addEntity(display);
			} catch (Exception e) {
				VFXLog.warnOnce(LOGGER, "text_overlay:spawn:" + effect.getId(),
					"text_overlay '{}': could not add a character display to the client level; dropping the character",
					effect.getId(), e);
				display.discard();
				// Stop here rather than skipping one: the entities are paired with the layout by
				// index, so a hole in the middle would shift every later character onto the wrong
				// glyph. Adding an entity is a whole-level operation, so the rest would fail too.
				break;
			}
			entities.add(display);
		}
		// One line per spawn, so the log says where the block actually went: a spawn that lands in
		// the wrong place is then visible in the log instead of only on screen.
		LOGGER.info("text_overlay '{}': spawned {} character display(s) at ({}, {}, {})",
			effect.getId(), entities.size(), origin.x, origin.y, origin.z);
		// The one-character string of each spawned glyph, built once here instead of on every frame
		// (the flat path caches the same thing), then copied into the "what the display shows" array.
		final String[] ownTexts = new String[entities.size()];
		for (int i = 0; i < ownTexts.length; i++) {
			ownTexts[i] = String.valueOf(layout.glyphs().get(i).ch());
		}
		// What each display currently shows. Spawned with its own character; placeBlock rewrites an
		// entry only when the character it should show changes, which is what a noise swap is.
		final String[] lastTexts = ownTexts.clone();
		final LiveText live = new LiveText(effect.getInstanceId(), signature, entities, layout,
			unresolvedFloors(entities.size()), ownTexts, lastTexts, colour, flags);
		LIVE.put(effect.getInstanceId(), live);
		return live;
	}

	// Half-width of the shaft probed under a character: the glyph itself has no world footprint, so a
	// narrow column is what a falling letter actually occupies.
	private static final double FLOOR_PROBE_HALF_WIDTH = 0.05;
	// The downward walk is sampled in quarter blocks. That step is the correctness of this scan - a
	// collider is a box on a grid, so a quarter block cannot skip one the letter would have hit -
	// not an arbitrary resolution.
	private static final double FLOOR_SCAN_STEP = 0.25;
	// How far down one scan looks before giving up: 64 blocks, the reach the old single query had.
	// This is a perf bound (64 / 0.25 = 256 queries, once per character when it detaches), not a
	// cap on anything a definition authors. Past it the character falls free, as it does over a void.
	private static final double FLOOR_SCAN_DEPTH = 64.0;

	// Every character starts with no floor: NaN marks "not resolved yet", because the scan runs when
	// the character actually detaches - the ground under the word can differ from character to
	// character (a step, a slope), and a spawn-time probe of the whole block cannot see that.
	private static float[] unresolvedFloors(final int count) {
		final float[] result = new float[count];
		for (int i = 0; i < count; i++) {
			result[i] = Float.NaN;
		}
		return result;
	}

	// The floor of character 'index' this frame, resolved on first use and cached with the block, so
	// the fall stays closed-form from then on and a still-standing line probes nothing. A fade exit
	// and 'collision: 0' have no landing to find, so they never scan at all.
	private static float floorFor(final VFXActiveEffect effect, final LiveText live, final ClientLevel level,
			final int index, final float since, final double x, final float restY, final double z) {
		if (since < 0.0F || effect.getParam("exit", 0.0F) < 0.5F
			|| effect.getParam("collision", 1.0F) < 0.5F || index >= live.floors.length) {
			return Float.NEGATIVE_INFINITY;
		}
		final float cached = live.floors[index];
		if (!Float.isNaN(cached)) {
			return cached;
		}
		final float resolved = resolveFloor(level, x, restY, z);
		live.floors[index] = resolved;
		return resolved;
	}

	// Top of the first surface under one character's own column, walking down from its rest point in
	// quarter blocks and taking the first slab that holds a collider. A rest point already inside a
	// collider means the letter is embedded in terrain (authored into a wall, or the ground rose
	// under it): the character then does not fall at all, so its own rest Y is returned and the drop
	// is zero. NEGATIVE_INFINITY means "nothing below within FLOOR_SCAN_DEPTH", and the character
	// falls free exactly as it does with no floor.
	private static float resolveFloor(final ClientLevel level, final double x, final float restY,
			final double z) {
		final AABB at = new AABB(x - FLOOR_PROBE_HALF_WIDTH, restY - 0.001, z - FLOOR_PROBE_HALF_WIDTH,
			x + FLOOR_PROBE_HALF_WIDTH, restY + 0.001, z + FLOOR_PROBE_HALF_WIDTH);
		for (final VoxelShape shape : level.getBlockCollisions(null, at)) {
			if (!shape.isEmpty()) {
				return restY;
			}
		}
		for (double y = restY; y >= restY - FLOOR_SCAN_DEPTH; y -= FLOOR_SCAN_STEP) {
			// The slab covers everything between this step and the one above it, so a surface the
			// walk crosses cannot be stepped over; only its top counts, because that is what catches
			// the letter.
			final AABB slab = new AABB(x - FLOOR_PROBE_HALF_WIDTH, y - FLOOR_SCAN_STEP,
				z - FLOOR_PROBE_HALF_WIDTH, x + FLOOR_PROBE_HALF_WIDTH, y, z + FLOOR_PROBE_HALF_WIDTH);
			float top = Float.NEGATIVE_INFINITY;
			for (final VoxelShape shape : level.getBlockCollisions(null, slab)) {
				if (shape.isEmpty()) {
					continue;
				}
				for (final AABB box : shape.toAabbs()) {
					if (box.maxY <= y && box.maxY > top) {
						top = (float) box.maxY;
					}
				}
			}
			if (top > Float.NEGATIVE_INFINITY) {
				return top;
			}
		}
		return Float.NEGATIVE_INFINITY;
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
	private static void placeBlock(final VFXActiveEffect effect, final ClientLevel level,
			final LiveText live, final VFXTextSpec spec, final Vec3 origin) {
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
		final int count = layout.glyphs().size();
		final float revealed = reveal(effect) * count;
		// A display draws its glyph upwards from the entity position, so a character whose position
		// sits exactly on a surface has its lower part buried in the block. The landing is lifted by
		// the character's own height instead - one font line in pixels, in world units, at the
		// current 'scale' - so a settled character stands on the surface rather than in it.
		final float glyphLift = Minecraft.getInstance().font.lineHeight
			* WORLD_UNITS_PER_FONT_PIXEL * scale;
		// Noise is only computed when 'scramble' is on: with it off every character shows its own
		// cached one-character string, and the line costs no hash and no param lookup per frame.
		final boolean scrambleOn = effect.getParam("scramble", 0.0F) > 0.0F;
		// The block only finishes when every character has faded out; until then this stays above 0.
		float brightest = 0.0F;
		for (int i = 0; i < live.entities.size(); i++) {
			final VFXTextLayout.Glyph glyph = layout.glyphs().get(i);
			final Vector3f local = restOffset(glyph, scale, alignShift, anchorShiftY, rotation);
			final Display.TextDisplay display = live.entities.get(i);
			final float since = exitSince(effect, i);
			// The rest height is the anchor's Y plus the laid-out offset. The floor under this
			// character is resolved on its own column the first frame it leaves, then cached, and the
			// settle point is that floor plus the glyph's own height so the letter rests on top of it.
			final float restY = (float) (origin.y + local.y);
			final float floorY = floorFor(effect, live, level, i, since,
				origin.x + local.x, restY, origin.z + local.z) + glyphLift;
			final Vector3f exit = exitOffset(effect, i, restY, floorY);
			// Position carries the offset (the rest pose plus the exit's displacement); the
			// transformation's translation stays zero, otherwise the offset is applied twice and the
			// block flies away from its anchor.
			display.setPos(origin.x + local.x + exit.x, origin.y + local.y + exit.y,
				origin.z + local.z + exit.z);
			// The pose above is this frame's, not a move to interpolate from: pinning the previous
			// position to it makes the render state draw exactly the computed spot instead of
			// sweeping from the stale tick position, which read as stepping.
			display.setOldPosAndRot();
			final Quaternionf orientation = since < 0.0F ? rotation : spin(effect, i, since, rotation);
			display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(orientation),
				new Vector3f(scale, scale, scale), new Quaternionf()));
			// A scrambled character shows a noise glyph instead of its own, and is handed its own
			// character back as soon as the band has passed it. The text is written when the style
			// changed or when the character it should show changed - never once per frame - so a
			// standing line costs nothing and a decode only touches the glyphs that are swapping.
			final float noise = scrambleOn ? scrambleAmount(effect, i, count) : 0.0F;
			final String text = noise > 0.0F ? noiseGlyph(effect, i) : live.ownTexts[i];
			if (restyle || !text.equals(live.lastTexts[i])) {
				display.setText(Component.literal(text).withStyle(style));
				live.lastTexts[i] = text;
			}
			if (reflag) {
				display.setFlags(flags);
			}
			// reveal is a fraction of the characters, so this character is fully shown, hidden, or
			// the single one at the wipe boundary caught mid-fade; the exit fades a character that is
			// leaving, and the effect weight fades the whole block instead of popping it at the stop.
			// A scrambled character is drawn at the noise's own opacity, which rises towards the
			// frontier, so it lights up as the sweep reaches it instead of waiting at zero.
			final float exitAlpha = exitAlpha(effect, i, restY, floorY);
			final float revealAlpha = noise > 0.0F ? noise : clamp01(revealed - i);
			brightest = Math.max(brightest, exitAlpha);
			display.setTextOpacity((byte) Math.round(
				clamp01(revealAlpha * exitAlpha * effect.getWeight()) * 255.0F));
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

	// Where a character sits, relative to the block origin: the layout's font pixels times the current
	// 'scale', shifted by the spec's align and anchor, turned into world units by
	// WORLD_UNITS_PER_FONT_PIXEL, then turned by rot_*. Shared by the spawn-time rest pose and the
	// per-frame placement, so both agree on where the character is - the glyph itself is drawn at the
	// renderer's own 0.025 scale, so only the spacing would drift
	// if this factor were skipped, and the shifts above must carry it too or a centred line would
	// hang off its anchor.
	private static Vector3f restOffset(final VFXTextLayout.Glyph glyph, final float scale,
			final float alignShift, final float anchorShiftY, final Quaternionf rotation) {
		return new Vector3f(
			(glyph.x() * scale + alignShift) * WORLD_UNITS_PER_FONT_PIXEL,
			(glyph.y() * scale + anchorShiftY) * WORLD_UNITS_PER_FONT_PIXEL, 0.0F)
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
		final float gravity = gravityOf(effect);
		final float speed = effect.getParam("exit_speed", 0.0F) * jitter(effect, index);
		final float drop = restY - floorY;
		// Without a floor, or with collision off, the letter simply falls for ever.
		if (effect.getParam("collision", 1.0F) < 0.5F || !Float.isFinite(floorY)) {
			return new Vector3f(0.0F, speed * since - 0.5F * gravity * since * since, 0.0F);
		}
		// An animated pos_y can lift the rest height below the floor that was resolved when the
		// character detached. There
		// is no drop left to run then, and the square root below would take a negative and hand a
		// NaN to setPos, so the character simply keeps its rest pose.
		if (drop <= 0.0F) {
			return new Vector3f();
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

	// The one reading of 'gravity' both halves of the exit use: it divides the fall and the landing
	// test, so zero would divide by zero. A lower bound only - there is no upper limit, a huge
	// gravity just makes the drop instant.
	private static float gravityOf(final VFXActiveEffect effect) {
		return Math.max(effect.getParam("gravity", 18.0F), 1.0e-3F);
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
		final float gravity = gravityOf(effect);
		final float speed = effect.getParam("exit_speed", 0.0F) * jitter(effect, index);
		final float impact = (speed + (float) Math.sqrt(speed * speed + 2.0F * gravity * drop)) / gravity;
		if (!Float.isFinite(impact)) {
			return -1.0F;
		}
		final float reboundSpeed = Math.max(0.0F, -speed + gravity * impact)
			* Math.min(1.0F, effect.getParam("bounce", 0.15F));
		return reboundSpeed > 0.0F ? impact + 2.0F * reboundSpeed / gravity : impact;
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
	// exit's ramp, or - for a fall - 1 until it has hit the bottom edge and the ramp while it holds.
	private static float flatExitAlpha(final VFXActiveEffect effect, final int index,
			final float since, final float restY, final float floorY, final float guiHeight) {
		if (since < 0.0F) {
			return 1.0F;
		}
		// A zero fade would divide by zero; the lower bound is the only guard needed here.
		final float fadeOut = Math.max(effect.getParam("fade_out", 0.5F), 1e-4F);
		if (effect.getParam("exit", 0.0F) < 0.5F) {
			return 1.0F - clamp01(since / fadeOut);
		}
		final float impact = flatImpact(effect, index, restY, floorY, guiHeight);
		if (impact < 0.0F) {
			// Nothing to land on: there is no impact to wait for, so the letter fades with the fall
			// and still reaches zero instead of hanging in the frame for ever.
			return 1.0F - clamp01(since / fadeOut);
		}
		return since <= impact ? 1.0F : 1.0F - clamp01((since - impact) / fadeOut);
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

	// A character's orientation while it is leaving: the block's rot_* quaternion composed with a
	// rotation of |spin| * since degrees about spin_x/y/z. 'tumble' replaces the axis with one taken
	// from the per-character jitter, so the letters tumble off-axis instead of all wheeling about the
	// same world axis, and leaves the speed at |spin|. A spin vector of length zero has no axis to
	// rotate about - normalising it would be a NaN - so the rest orientation is handed back untouched.
	private static Quaternionf spin(final VFXActiveEffect effect, final int index, final float since,
			final Quaternionf rotation) {
		final Vector3f axis = new Vector3f(effect.getParam("spin_x", 0.0F),
			effect.getParam("spin_y", 0.0F), effect.getParam("spin_z", 0.0F));
		final float speed = axis.length();
		if (speed < 1e-4F) {
			return new Quaternionf(rotation);
		}
		if (effect.getParam("tumble", 0.0F) >= 0.5F) {
			// Tumble swaps the axis only - the angular speed stays |spin|, so an authored
			// 'spin_z 180' with tumble on still turns at 180 degrees per second, off-axis. Three
			// readings of the same jitter give a deterministic direction.
			final float j = jitter(effect, index);
			axis.set(j - 1.0F, 1.0F - j, 0.5F * j - 0.5F);
			if (axis.lengthSquared() < 1e-8F) {
				return new Quaternionf(rotation);
			}
			axis.normalize();
		} else {
			axis.div(speed);
		}
		return new Quaternionf(rotation)
			.rotateAxis((float) Math.toRadians(speed * since), axis);
	}

	// The block is over: every character has faded out. Its characters are released now, and the
	// pool entry stays behind carrying the age it finished at, so 'ensure' neither respawns the line
	// next frame nor keeps it dead once the effect loops or is replayed.
	private static void finish(final VFXActiveEffect effect, final LiveText live) {
		live.finishedAt = age(effect);
		release(live);
		live.entities.clear();
	}


	// The animated fraction of characters that are visible, 1 when the param is unset.
	private static float reveal(final VFXActiveEffect effect) {
		return clamp01(effect.getParam("reveal", 1.0F));
	}

	// How much of character 'index' is noise this frame, in 0..1: zero when it shows its own
	// character, which is every character of every frame while 'scramble' is off. Two windows produce
	// noise - the band just ahead of the reveal frontier, fading out as the character is further from
	// it, and the 'scramble_time' seconds before the character detaches. Both are read from the clock
	// and the params alone, so nothing accumulates and the same tick always yields the same frame.
	//
	// effect - the running effect
	// index  - the character's place in the line
	// count  - the characters in the block, the span 'reveal' is a fraction of
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

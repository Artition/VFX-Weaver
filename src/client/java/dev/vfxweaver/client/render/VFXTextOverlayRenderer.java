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
import net.minecraft.world.phys.Vec3;
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
 * gives a typed-in / wiped-in line instead of a hard pop. The exit (fade and fall) is not implemented
 * here yet; {@link #tick} and {@link #remove} exist so the next step has somewhere to hang its
 * per-character state and its cleanup.
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
	private static final class LiveText {
		private final long instanceId;
		private final String signature;
		private final List<Display.TextDisplay> entities;
		private final VFXTextLayout layout;
		private int lastColour;
		private byte lastFlags;

		private LiveText(final long instanceId, final String signature,
				final List<Display.TextDisplay> entities, final VFXTextLayout layout,
				final int lastColour, final byte lastFlags) {
			this.instanceId = instanceId;
			this.signature = signature;
			this.entities = entities;
			this.layout = layout;
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
		if (live == null) {
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
		final LiveText live = new LiveText(effect.getInstanceId(), signature, entities, layout, colour, flags);
		LIVE.put(effect.getInstanceId(), live);
		return live;
	}

	// The effect's colour_r/g/b (0..1 each) as one packed 0xRRGGBB integer, white when unset.
	private static int colour(final VFXActiveEffect effect) {
		final int red = Math.round(clamp01(effect.getParam("color_r", 1.0F)) * 255.0F);
		final int green = Math.round(clamp01(effect.getParam("color_g", 1.0F)) * 255.0F);
		final int blue = Math.round(clamp01(effect.getParam("color_b", 1.0F)) * 255.0F);
		return (red << 16) | (green << 8) | blue;
	}

	// Positions every character of the block: unit layout times the current 'scale', rotated by
	// rot_*, anchored, and refreshed for the animatable colour and shadow.
	private static void placeBlock(final VFXActiveEffect effect, final LiveText live, final VFXTextSpec spec) {
		final Vec3 origin = new Vec3(effect.getParam("pos_x", 0.0F), effect.getParam("pos_y", 0.0F),
			effect.getParam("pos_z", 0.0F));
		final float scale = effect.getParam("scale", 1.0F);
		final VFXTextLayout layout = live.layout;
		final float width = layout.width() * scale;
		final float height = layout.height() * scale;
		final float alignShift = switch (spec.align()) {
			case LEFT -> 0.0F;
			case CENTER -> -width / 2.0F;
			case RIGHT -> -width;
		};
		final float anchorShiftY = switch (spec.anchor()) {
			case TOP_LEFT, TOP, TOP_RIGHT -> 0.0F;
			case LEFT, CENTER, RIGHT -> -height / 2.0F;
			case BOTTOM_LEFT, BOTTOM, BOTTOM_RIGHT -> -height;
		};
		final Quaternionf rotation = new Quaternionf()
			.rotateX((float) Math.toRadians(effect.getParam("rot_x", 0.0F)))
			.rotateY((float) Math.toRadians(effect.getParam("rot_y", 0.0F)))
			.rotateZ((float) Math.toRadians(effect.getParam("rot_z", 0.0F)));
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
		for (int i = 0; i < live.entities.size(); i++) {
			final VFXTextLayout.Glyph glyph = layout.glyphs().get(i);
			final Vector3f local = new Vector3f(
				glyph.x() * scale + alignShift,
				glyph.y() * scale + anchorShiftY,
				0.0F).rotate(rotation);
			final Display.TextDisplay display = live.entities.get(i);
			// Position carries the offset; the transformation's translation stays zero, otherwise the
			// offset is applied twice and the block flies away from its anchor.
			display.setPos(origin.x + local.x, origin.y + local.y, origin.z + local.z);
			display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(rotation),
				new Vector3f(scale, scale, scale), new Quaternionf()));
			if (restyle) {
				display.setText(Component.literal(String.valueOf(glyph.ch())).withStyle(style));
			}
			if (reflag) {
				display.setFlags(flags);
			}
			// reveal is a fraction of the characters, so this character is fully shown, hidden, or
			// the single one at the wipe boundary caught mid-fade; the effect weight fades the whole
			// block out instead of popping it at the stop.
			display.setTextOpacity((byte) Math.round(clamp01(revealed - i) * effect.getWeight() * 255.0F));
		}
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

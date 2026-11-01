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
 * <p>The characters are laid out once by {@link VFXTextLayout} (content, {@code scale},
 * {@code line_height}, {@code wrap_width}) and then placed every frame from the animatable params -
 * {@code pos_x/pos_y/pos_z} for the origin, {@code rot_x/rot_y/rot_z} for the orientation, the spec's
 * {@code align} and {@code anchor} for which point of the block sits on that origin - so moving or
 * rotating an already-running effect moves the whole block without respawning anything.
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

	// Characters of one live text effect, in layout order.
	private record LiveText(long instanceId, List<Display.TextDisplay> entities, VFXTextLayout layout) {
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
		final LiveText existing = LIVE.get(effect.getInstanceId());
		if (existing != null) {
			return existing;
		}
		final Font font = Minecraft.getInstance().font;
		final float scale = effect.getParam("scale", 1.0F);
		final VFXTextLayout layout = VFXTextLayout.measure(font, spec.content(), scale,
			effect.getParam("line_height", 1.2F), effect.getParam("wrap_width", 0.0F));
		final List<Display.TextDisplay> entities = new ArrayList<>(layout.glyphs().size());
		final byte flags = effect.getParam("shadow", 1.0F) >= 0.5F ? Display.TextDisplay.FLAG_SHADOW : (byte) 0;
		for (final VFXTextLayout.Glyph glyph : layout.glyphs()) {
			if (entities.size() >= MAX_CHARS) {
				VFXLog.warnOnce(LOGGER, "text_overlay:cap:" + effect.getId(),
					"text_overlay '{}': more than {} characters, the tail is dropped",
					effect.getId(), MAX_CHARS);
				break;
			}
			final Display.TextDisplay display = new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, level);
			display.setText(Component.literal(String.valueOf(glyph.ch())));
			display.setBackgroundColor(TRANSPARENT_BACKGROUND);
			display.setFlags(flags);
			display.setPos(0.0, 0.0, 0.0);
			// 26.2's ClientLevel.addEntity reads Entity.getId(), and a locally created entity has
			// none (only ServerLevel overrides getNextEntityId), so the id must be set first.
			display.setId(VFXBlockParticleEngine.nextLocalEntityId());
			level.addEntity(display);
			entities.add(display);
		}
		final LiveText live = new LiveText(effect.getInstanceId(), entities, layout);
		LIVE.put(effect.getInstanceId(), live);
		return live;
	}

	// Positions every character of the block: layout units scaled, rotated by rot_*, anchored.
	private static void placeBlock(final VFXActiveEffect effect, final LiveText live, final VFXTextSpec spec) {
		final Vec3 origin = new Vec3(effect.getParam("pos_x", 0.0F), effect.getParam("pos_y", 0.0F),
			effect.getParam("pos_z", 0.0F));
		final float scale = effect.getParam("scale", 1.0F);
		final float alignShift = switch (spec.align()) {
			case LEFT -> 0.0F;
			case CENTER -> -live.layout().width() / 2.0F;
			case RIGHT -> -live.layout().width();
		};
		final float anchorShiftY = switch (spec.anchor()) {
			case TOP_LEFT, TOP, TOP_RIGHT -> 0.0F;
			case LEFT, CENTER, RIGHT -> -live.layout().height() / 2.0F;
			case BOTTOM_LEFT, BOTTOM, BOTTOM_RIGHT -> -live.layout().height();
		};
		final Quaternionf rotation = new Quaternionf()
			.rotateX((float) Math.toRadians(effect.getParam("rot_x", 0.0F)))
			.rotateY((float) Math.toRadians(effect.getParam("rot_y", 0.0F)))
			.rotateZ((float) Math.toRadians(effect.getParam("rot_z", 0.0F)));
		final float revealed = reveal(effect) * live.layout().glyphs().size();
		for (int i = 0; i < live.entities().size(); i++) {
			final VFXTextLayout.Glyph glyph = live.layout().glyphs().get(i);
			final Vector3f local = new Vector3f(
				(glyph.x() + alignShift) * scale,
				(glyph.y() + anchorShiftY) * scale,
				0.0F).rotate(rotation);
			final Display.TextDisplay display = live.entities().get(i);
			// Position carries the offset; the transformation's translation stays zero, otherwise the
			// offset is applied twice and the block flies away from its anchor.
			display.setPos(origin.x + local.x, origin.y + local.y, origin.z + local.z);
			display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(rotation),
				new Vector3f(scale, scale, scale), new Quaternionf()));
			// reveal is a fraction of the characters, so this character is fully shown, hidden, or
			// the single one at the wipe boundary caught mid-fade.
			display.setTextOpacity((byte) Math.round(clamp01(revealed - i) * 255.0F));
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
		for (final Display.TextDisplay display : live.entities()) {
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

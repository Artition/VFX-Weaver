package dev.vfxweaver.client.audio;

import dev.vfxweaver.effect.VFXActiveEffect;
import dev.vfxweaver.effect.VFXEffectType;
import dev.vfxweaver.effect.VFXWorldBindings;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The lifecycle bridge between the {@code hum} effects and their synthesised voices: one
 * {@link VFXHumVoice} per active instance, reconciled against the live set every frame.
 *
 * <p><b>Open / close, driven by the active set.</b> {@link #reconcile(List, float)} is
 * called at the end of {@code VFXEffectManager.update()}, so a voice follows its effect
 * through every removal path (stop, fade-out expiry, {@code stopAll}, a replay seek)
 * without each path having to know about voices. Unlike
 * {@code VFXWindowManager.reconcile}, a fading-out owner is kept: its falling
 * {@link VFXActiveEffect#getWeight()} ramps the gain to silence (the {@code volume}
 * neutral is {@code 0.0F}, so {@code fade_ticks} fades the hum), and the voice closes
 * once {@code update()} removes the finished effect.
 *
 * <p><b>Per-frame drive.</b> Each bound voice is ticked with the shared effect clock, the
 * animated {@code frequency} and {@code volume} (scaled by the fade weight), positioned
 * at the effect's move position, first static position, first entity anchor, authored
 * {@code pos_x/y/z} triple, or — when none is authored — the local-player snapshot, with
 * a linear distance falloff from the camera. An effect whose entity anchor is untracked
 * this frame is silent.
 *
 * <p><b>Render thread only.</b> Synthesis writes into a {@code SourceDataLine} from the
 * calling thread, so every method here must run on the render thread.
 */
public final class VFXVoiceManager {
	private static final float DEFAULT_FREQUENCY = 440.0F;
	private static final float FULL_VOLUME_DISTANCE = 8.0F;
	private static final float SILENT_DISTANCE = 48.0F;

	private static final VFXVoiceManager INSTANCE = new VFXVoiceManager();

	private final Map<Long, VFXHumVoice> voices = new HashMap<>();

	private VFXVoiceManager() {
	}

	/**
	 * The one manager instance. Must be used on the render thread.
	 *
	 * @return the process-wide manager
	 */
	public static VFXVoiceManager get() {
		return INSTANCE;
	}

	/**
	 * Opens a voice for every active {@code hum} effect that has none yet, ticks every
	 * bound voice with the current clock and parameters, and closes every voice whose
	 * effect is no longer active. Must run on the render thread.
	 *
	 * @param active     the current active effects (an instance owns its voice while it is in the list)
	 * @param clockTicks the shared effect clock, in ticks
	 */
	public void reconcile(final List<VFXActiveEffect> active, final float clockTicks) {
		final Map<Long, VFXActiveEffect> owners = new HashMap<>();
		for (final VFXActiveEffect effect : active) {
			if (effect.getType() != VFXEffectType.HUM) {
				continue;
			}
			owners.putIfAbsent(effect.getInstanceId(), effect);
		}
		for (final Long id : owners.keySet()) {
			this.voices.computeIfAbsent(id, ignored -> new VFXHumVoice());
		}
		final Iterator<Map.Entry<Long, VFXHumVoice>> iterator = this.voices.entrySet().iterator();
		while (iterator.hasNext()) {
			final Map.Entry<Long, VFXHumVoice> entry = iterator.next();
			final VFXActiveEffect effect = owners.get(entry.getKey());
			if (effect == null) {
				entry.getValue().close();
				iterator.remove();
				continue;
			}
			entry.getValue().tick(clockTicks, frequency(effect), gain(effect));
		}
	}

	/**
	 * Closes and drops every voice, run on a client dispose and on {@code stopAll} (which
	 * cuts immediately, mirroring the aux-window close) so no hum outlives the game session.
	 * Must run on the render thread.
	 */
	public void stopAll() {
		for (final VFXHumVoice voice : this.voices.values()) {
			voice.close();
		}
		this.voices.clear();
	}

	private static float frequency(final VFXActiveEffect effect) {
		final float frequency = effect.getParam("frequency", DEFAULT_FREQUENCY);
		return Float.isFinite(frequency) ? Math.max(0.0F, frequency) : 0.0F;
	}

	private static float gain(final VFXActiveEffect effect) {
		float volume = effect.getParam("volume", 0.0F);
		volume = Float.isFinite(volume) ? Math.max(0.0F, volume) : 0.0F;
		final Vec3 position = resolvePosition(effect);
		if (position == null) {
			return 0.0F;
		}
		final float[] camera = VFXWorldBindings.cameraPosition();
		final double distance = position.distanceTo(new Vec3(camera[0], camera[1], camera[2]));
		final float attenuation = distance <= FULL_VOLUME_DISTANCE ? 1.0F
			: (float) Math.min(1.0, Math.max(0.0, (SILENT_DISTANCE - distance) / (SILENT_DISTANCE - FULL_VOLUME_DISTANCE)));
		return volume * effect.getWeight() * attenuation;
	}

	/**
	 * The world point a hum is heard from: the runtime move position, the first static
	 * position (block-centred), the first entity anchor's live position, the authored
	 * {@code pos_x/y/z} triple, or the local-player snapshot when nothing is authored.
	 * {@code null} only when the single anchor source is untracked (a dead, out-of-range
	 * or not-yet-spawned entity), in which case the voice stays silent this frame.
	 */
	private static @Nullable Vec3 resolvePosition(final VFXActiveEffect effect) {
		final Vec3 moved = effect.getMovePosition();
		if (moved != null) {
			return moved;
		}
		final List<VFXActiveEffect.ResolvedAnchor> anchors = effect.getAnchors();
		if (!anchors.isEmpty()) {
			if (Minecraft.getInstance().level == null) {
				return null;
			}
			final VFXActiveEffect.ResolvedAnchor anchor = anchors.get(0);
			final Entity entity = Minecraft.getInstance().level.getEntity(anchor.uuid());
			if (entity == null) {
				return null;
			}
			Vec3 point = switch (anchor.point()) {
				case "center" -> entity.position().add(0.0, entity.getBbHeight() / 2.0, 0.0);
				case "eyes" -> entity.position().add(0.0, entity.getEyeHeight(), 0.0);
				default -> entity.position();
			};
			if ("look".equals(anchor.dir())) {
				point = point.add(entity.getLookAngle().scale(anchor.distance()));
			}
			return point.add(anchor.offset());
		}
		final List<BlockPos> positions = effect.getPositions();
		if (!positions.isEmpty()) {
			final BlockPos pos = positions.get(0);
			return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		}
		final float x = effect.getParam("pos_x", Float.NaN);
		final float y = effect.getParam("pos_y", Float.NaN);
		final float z = effect.getParam("pos_z", Float.NaN);
		if (!Float.isNaN(x) && !Float.isNaN(y) && !Float.isNaN(z)) {
			// Bound/expression-driven positions are exact sub-block anchors; the block-centre
			// (+0.5) convention only applies to static block coordinates.
			final boolean dynamic = effect.getTimeline().getBindings().containsKey("pos_x")
				|| effect.getTimeline().getExpressions().containsKey("pos_x")
				|| effect.getTimeline().getMultipliers().containsKey("pos_x");
			final double offset = dynamic ? 0.0 : 0.5;
			return new Vec3(x + offset, y, z + offset);
		}
		final VFXWorldBindings.PlayerState player = VFXWorldBindings.playerState();
		if (player != null) {
			return new Vec3(player.px(), player.py(), player.pz());
		}
		final float[] camera = VFXWorldBindings.cameraPosition();
		return new Vec3(camera[0], camera[1], camera[2]);
	}
}

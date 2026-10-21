package dev.vfxweaver.client.audio;

import dev.vfxweaver.effect.VFXActiveEffect;
import dev.vfxweaver.effect.VFXEffectType;
import dev.vfxweaver.effect.VFXSoundChannel;
import dev.vfxweaver.effect.VFXWorldBindings;
import dev.vfxweaver.util.VFXLog;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The lifecycle bridge between the {@code hum}/{@code noise}/{@code heartbeat} effects and their synthesised
 * voices, plus the {@code volume_duck} effect and the vanilla sound categories it scales:
 * one {@link VFXHumVoice}, {@link VFXNoiseVoice} or {@link VFXHeartbeatVoice} per active instance, reconciled
 * against the live set every frame.
 *
 * <p><b>Open / close, driven by the active set.</b> {@link #reconcile(List, float)} is
 * called at the end of {@code VFXEffectManager.update()}, so a voice follows its effect
 * through every removal path (stop, fade-out expiry, {@code stopAll}, a replay seek)
 * without each path having to know about voices. Unlike
 * {@code VFXWindowManager.reconcile}, a fading-out owner is kept: its falling
 * {@link VFXActiveEffect#getWeight()} ramps the gain to silence (the {@code volume}
 * neutral is {@code 0.0F}, so {@code fade_ticks} fades the voice), and the voice closes
 * once {@code update()} removes the finished effect.
 *
 * <p><b>Per-frame drive.</b> Each bound voice is ticked with the shared effect clock, its
 * animated params ({@code frequency} for a hum; {@code brightness}/{@code jitter}/
 * {@code jitter_rate}/{@code seed} for a noise; {@code rate}/{@code pitch} for a
 * heartbeat) with {@code volume} scaled by the fade
 * weight, positioned at the effect's move position, first static position, first entity
 * anchor, authored {@code pos_x/y/z} triple, or — when none is authored — the
 * local-player snapshot, with a linear distance falloff from the camera. An effect whose
 * entity anchor is untracked this frame is silent.
 *
 * <p><b>Render thread only.</b> Synthesis writes into a {@code SourceDataLine} from the
 * calling thread, so every method here must run on the render thread.
 */
public final class VFXVoiceManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/voice");
	private static final float DEFAULT_FREQUENCY = 440.0F;
	private static final float FULL_VOLUME_DISTANCE = 8.0F;
	private static final float SILENT_DISTANCE = 48.0F;

	private static final VFXVoiceManager INSTANCE = new VFXVoiceManager();

	private final Map<Long, VFXHumVoice> hums = new HashMap<>();
	private final Map<Long, VFXNoiseVoice> noises = new HashMap<>();
	private final Map<Long, VFXHeartbeatVoice> heartbeats = new HashMap<>();
	/**
	 * The pre-duck category levels, one entry per currently ducked channel. Empty while no
	 * {@code volume_duck} is active; every entry is restored (then dropped) once its channel
	 * is no longer ducked, so user slider moves on untouched channels are never stomped.
	 */
	private final Map<SoundSource, Float> duckBase = new EnumMap<>(SoundSource.class);

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
	 * Opens a voice for every active {@code hum}/{@code noise}/{@code heartbeat} effect that has none yet,
	 * ticks every bound voice with the current clock and parameters, closes every voice whose
	 * effect is no longer active, and applies every active {@code volume_duck} to its vanilla
	 * sound category. Must run on the render thread.
	 *
	 * @param active     the current active effects (an instance owns its voice while it is in the list)
	 * @param clockTicks the shared effect clock, in ticks
	 */
	public void reconcile(final List<VFXActiveEffect> active, final float clockTicks) {
		final Map<Long, VFXActiveEffect> humOwners = new HashMap<>();
		final Map<Long, VFXActiveEffect> noiseOwners = new HashMap<>();
		final Map<Long, VFXActiveEffect> heartbeatOwners = new HashMap<>();
		for (final VFXActiveEffect effect : active) {
			if (effect.getType() == VFXEffectType.HUM) {
				humOwners.putIfAbsent(effect.getInstanceId(), effect);
			} else if (effect.getType() == VFXEffectType.NOISE) {
				noiseOwners.putIfAbsent(effect.getInstanceId(), effect);
			} else if (effect.getType() == VFXEffectType.HEARTBEAT) {
				heartbeatOwners.putIfAbsent(effect.getInstanceId(), effect);
			}
		}
		for (final Long id : humOwners.keySet()) {
			this.hums.computeIfAbsent(id, ignored -> new VFXHumVoice());
		}
		final Iterator<Map.Entry<Long, VFXHumVoice>> hums = this.hums.entrySet().iterator();
		while (hums.hasNext()) {
			final Map.Entry<Long, VFXHumVoice> entry = hums.next();
			final VFXActiveEffect effect = humOwners.get(entry.getKey());
			if (effect == null) {
				entry.getValue().close();
				hums.remove();
				continue;
			}
			entry.getValue().tick(clockTicks, frequency(effect), gain(effect));
		}
		for (final Long id : noiseOwners.keySet()) {
			this.noises.computeIfAbsent(id, ignored -> new VFXNoiseVoice());
		}
		final Iterator<Map.Entry<Long, VFXNoiseVoice>> noises = this.noises.entrySet().iterator();
		while (noises.hasNext()) {
			final Map.Entry<Long, VFXNoiseVoice> entry = noises.next();
			final VFXActiveEffect effect = noiseOwners.get(entry.getKey());
			if (effect == null) {
				entry.getValue().close();
				noises.remove();
				continue;
			}
			final NoiseParams params = noiseParams(effect);
			entry.getValue().tick(clockTicks, gain(effect), params.brightness(),
				params.jitter(), params.jitterRate(), params.seed());
		}
		for (final Long id : heartbeatOwners.keySet()) {
			this.heartbeats.computeIfAbsent(id, ignored -> new VFXHeartbeatVoice());
		}
		final Iterator<Map.Entry<Long, VFXHeartbeatVoice>> heartbeats = this.heartbeats.entrySet().iterator();
		while (heartbeats.hasNext()) {
			final Map.Entry<Long, VFXHeartbeatVoice> entry = heartbeats.next();
			final VFXActiveEffect effect = heartbeatOwners.get(entry.getKey());
			if (effect == null) {
				entry.getValue().close();
				heartbeats.remove();
				continue;
			}
			entry.getValue().tick(clockTicks, gain(effect), heartbeatRate(effect), heartbeatPitch(effect));
		}
		this.reconcileDuck(active);
	}

	/**
	 * Closes and drops every voice and restores every ducked vanilla category, run on a client
	 * dispose and on {@code stopAll} (which cuts immediately, mirroring the aux-window close)
	 * so no voice outlives the game session and no category stays ducked after one.
	 * Must run on the render thread.
	 */
	public void stopAll() {
		for (final VFXHumVoice voice : this.hums.values()) {
			voice.close();
		}
		this.hums.clear();
		for (final VFXNoiseVoice voice : this.noises.values()) {
			voice.close();
		}
		this.noises.clear();
		for (final VFXHeartbeatVoice voice : this.heartbeats.values()) {
			voice.close();
		}
		this.heartbeats.clear();
		this.restoreDuckedVolumes();
	}

	/**
	 * Scales every {@code volume_duck} effect's category to its pre-duck level times the
	 * effect's fade-aware scale ({@code 1 - (1 - volume) * weight}, so {@code fade_ticks}
	 * fades the duck in and out). Several ducks on one channel resolve last-writer-wins in
	 * active-list order. A channel whose duck is gone is restored at once, so a single stop,
	 * a replay seek, {@code stopAll} and a world exit (the render hook stops everything when
	 * the level unloads) all hand the sliders back. Only ducked channels are ever written.
	 *
	 * @param active the current active effects (an instance ducks its channel while it is in the list)
	 */
	private void reconcileDuck(final List<VFXActiveEffect> active) {
		final Map<SoundSource, Float> scales = new EnumMap<>(SoundSource.class);
		for (final VFXActiveEffect effect : active) {
			if (effect.getType() == VFXEffectType.VOLUME_DUCK) {
				final SoundSource category = duckChannel(effect);
				if (category != null) {
					scales.put(category, duckScale(effect));
				}
			}
		}
		final Minecraft minecraft = Minecraft.getInstance();
		final Iterator<Map.Entry<SoundSource, Float>> bases = this.duckBase.entrySet().iterator();
		while (bases.hasNext()) {
			final Map.Entry<SoundSource, Float> base = bases.next();
			if (!scales.containsKey(base.getKey())) {
				minecraft.options.getSoundSourceOptionInstance(base.getKey()).set(base.getValue().doubleValue());
				bases.remove();
			}
		}
		for (final Map.Entry<SoundSource, Float> scale : scales.entrySet()) {
			this.duckBase.computeIfAbsent(scale.getKey(),
				category -> minecraft.options.getSoundSourceVolume(category));
			final float base = this.duckBase.get(scale.getKey());
			minecraft.options.getSoundSourceOptionInstance(scale.getKey())
				.set((double) (base * scale.getValue()));
		}
	}

	/** Restores every ducked category to its pre-duck level and forgets the snapshot. */
	private void restoreDuckedVolumes() {
		final Minecraft minecraft = Minecraft.getInstance();
		for (final Map.Entry<SoundSource, Float> base : this.duckBase.entrySet()) {
			minecraft.options.getSoundSourceOptionInstance(base.getKey()).set(base.getValue().doubleValue());
		}
		this.duckBase.clear();
	}

	/**
	 * The {@code volume_duck} channel: a {@link VFXSoundChannel} name (case-insensitive) or the
	 * historical {@code 0..10} ordinal, rounded and clamped into range. A non-matching name
	 * warns once and resolves to {@code null}, so the duck safely no-ops instead of ducking
	 * the wrong category.
	 */
	private static @Nullable SoundSource duckChannel(final VFXActiveEffect effect) {
		final float channel = effect.getParam("channel", 0.0F);
		if (!Float.isFinite(channel)) {
			VFXLog.warnOnce(LOGGER, "voice:duck-channel",
				"volume_duck has an unknown channel name; use master/music/records/weather/blocks/hostile/neutral/players/ambient/voice/ui or 0..10");
			return null;
		}
		final SoundSource[] categories = SoundSource.values();
		return categories[Math.min(categories.length - 1, Math.max(0, Math.round(channel)))];
	}

	/** The {@code volume_duck} multiplier: {@code volume} blended toward 1 (no duck) by the fade weight. */
	private static float duckScale(final VFXActiveEffect effect) {
		float volume = effect.getParam("volume", 1.0F);
		volume = Float.isFinite(volume) ? Math.min(1.0F, Math.max(0.0F, volume)) : 1.0F;
		final float weight = Math.min(1.0F, Math.max(0.0F, effect.getWeight()));
		return 1.0F - (1.0F - volume) * weight;
	}

	private static float frequency(final VFXActiveEffect effect) {
		final float frequency = effect.getParam("frequency", DEFAULT_FREQUENCY);
		return Float.isFinite(frequency) ? Math.max(0.0F, frequency) : 0.0F;
	}

	/** The animated {@code noise} params, read through {@code getParam} so keyframes/expr/bind all work. */
	private static NoiseParams noiseParams(final VFXActiveEffect effect) {
		return new NoiseParams(effect.getParam("brightness", 0.5F), effect.getParam("jitter", 0.0F),
			effect.getParam("jitter_rate", 1.0F), effect.getParam("seed", 0.0F));
	}

	private record NoiseParams(float brightness, float jitter, float jitterRate, float seed) {
	}

	/** The animated {@code heartbeat} params, read through {@code getParam} so keyframes/expr/bind all work. */
	private static float heartbeatRate(final VFXActiveEffect effect) {
		final float rate = effect.getParam("rate", 60.0F);
		return Float.isFinite(rate) ? Math.max(0.0F, rate) : 0.0F;
	}

	/** The {@code pitch} param is a playback-speed multiplier for the bundled recording, 1 = as recorded. */
	private static float heartbeatPitch(final VFXActiveEffect effect) {
		final float pitch = effect.getParam("pitch", 1.0F);
		return Float.isFinite(pitch) ? Math.max(0.0F, pitch) : 0.0F;
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
	 * The world point a voice is heard from: the runtime move position, the first static
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

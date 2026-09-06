package dev.vfxweaver.client.effect;

import dev.vfxweaver.effect.AnimatedValue;
import dev.vfxweaver.effect.EasingFunction;
import dev.vfxweaver.effect.EasingType;
import dev.vfxweaver.effect.VFXActiveEffect;
import dev.vfxweaver.effect.VFXDefinition;
import dev.vfxweaver.effect.VFXEffectType;
import dev.vfxweaver.effect.VFXTimeline;
import dev.vfxweaver.resource.VFXDefinitionManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side store of running {@link VFXActiveEffect}s together with the shared effect clock
 * (in ticks, advanced each rendered frame). Post-processing effects are consumed by the
 * {@code VFXPostProcessingManager}; {@code camera_shake} effects are consumed by the
 * {@code CameraShakeManager}.
 */
public class VFXEffectManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/effects");
	private static final VFXEffectManager INSTANCE = new VFXEffectManager();
	private static final int MAX_COLLECTION_DEPTH = 4;
	private static final int MAX_ACTIVE_EFFECTS = 64;
	private static final int MAX_SCHEDULED_EFFECTS = 128;
	private static final int MAX_DURATION_TICKS = 20 * 60 * 60; // 1 real hour, safety cap on server-supplied duration

	private final List<VFXActiveEffect> active = new ArrayList<>();
	private final List<ScheduledPlay> scheduled = new ArrayList<>();
	/**
	 * Inverted index: entity UUID → active entity effects targeting it. Rebuilt once per
	 * {@link #update()} so per-entity lookups during rendering are O(1) instead of scanning
	 * every active effect. Read-only from the render thread between updates.
	 */
	private Map<UUID, List<VFXActiveEffect>> entityEffectsIndex = Map.of();
	private final AtomicLong instanceCounter = new AtomicLong();
	private float clock;

	private VFXEffectManager() {
	}

	public static VFXEffectManager get() {
		return INSTANCE;
	}

	/**
	 * Allocates a fresh instance id (used by the client dispatcher to return an id to the
	 * caller before the actual play is scheduled on the render thread).
	 */
	public long allocateInstanceId() {
		return this.instanceCounter.incrementAndGet();
	}

	/**
	 * Advances the shared effect clock by the given number of ticks.
	 */
	public void advance(final float deltaTicks) {
		this.clock += Math.max(0.0F, deltaTicks);
	}

	/**
	 * Removes finished effects, triggers due scheduled collection children and advances the
	 * remaining effects to the current clock time.
	 */
	public void update() {
		this.active.removeIf(VFXActiveEffect::isFinished);
		if (!this.scheduled.isEmpty()) {
			List<ScheduledPlay> due = new ArrayList<>();
			this.scheduled.removeIf(play -> {
				if (play.at() <= this.clock) {
					due.add(play);
					return true;
				}
				return false;
			});
			for (ScheduledPlay play : due) {
				this.play(play.effectId(), play.durationTicks(), 0L, play.position(), List.of(), play.params(), play.easing(), play.depth(), 0);
			}
		}
		for (VFXActiveEffect effect : this.active) {
			effect.update(this.clock);
		}
		this.rebuildEntityEffectsIndex();
	}

	/**
	 * Rebuilds the entity UUID → effects inverted index from the current {@link #active} list.
	 * Only entity-targeted effects with at least one target UUID are indexed.
	 */
	private void rebuildEntityEffectsIndex() {
		Map<UUID, List<VFXActiveEffect>> index = new HashMap<>();
		for (VFXActiveEffect effect : this.active) {
			if (!isEntityTargeted(effect.getType())) {
				continue;
			}
			for (UUID uuid : effect.getEntityUuids()) {
				index.computeIfAbsent(uuid, ignored -> new ArrayList<>()).add(effect);
			}
		}
		this.entityEffectsIndex = Map.copyOf(index);
	}

	/** True for effect types that target specific entities by UUID. */
	private static boolean isEntityTargeted(final VFXEffectType type) {
		return type == VFXEffectType.ENTITY_TINT
			|| type == VFXEffectType.ENTITY_OUTLINE
			|| type == VFXEffectType.ENTITY_DISPLACE
			|| type == VFXEffectType.GOD_RAYS;
	}

	/**
	 * Starts an effect and returns the id of the created instance. When the effect id is
	 * unknown, the effect is ignored with a warning and {@code 0} is returned.
	 *
	 * @param effectId      effect id (built-in or datapack-defined)
	 * @param durationTicks duration in ticks (0 uses the definition default, negative = persistent)
	 * @param params        parameter overrides
	 * @param easing        easing curve (may be null for the definition default)
	 * @return the instance id, or {@code 0} when the effect was ignored
	 */
	public long play(final Identifier effectId, final int durationTicks, final Map<String, Float> params, final EasingType easing) {
		return this.play(effectId, durationTicks, 0L, null, List.of(), params, EasingFunction.builtIn(easing), 0, 0);
	}

	/**
	 * Starts an effect with an explicit instance id and an optional world position. When
	 * {@code instanceId} is non-zero the created instance adopts it (used by the network stop
	 * action to target one of several concurrent instances of the same effect); when zero a new
	 * id is allocated. The position, when present, re-anchors spatial world bindings
	 * ({@code screen_x/y}, {@code proximity}) to that point.
	 *
	 * @param effectId      effect id (built-in or datapack-defined)
	 * @param durationTicks duration in ticks (0 uses the definition default, negative = persistent)
	 * @param instanceId    explicit instance id (0 = allocate a new one)
	 * @param position      world position to anchor spatial bindings to (may be null)
	 * @param params        parameter overrides
	 * @param easing        easing curve (may be null for the definition default)
	 * @return the instance id, or {@code 0} when the effect was ignored
	 */
	public long play(final Identifier effectId, final int durationTicks, final long instanceId, final @Nullable Vec3 position, final Map<String, Float> params, final EasingFunction easing) {
		return this.play(effectId, durationTicks, instanceId, position, List.of(), params, easing, 0, 0);
	}

	/**
	 * Starts an effect with an explicit instance id, world position, entity UUID targets and
	 * easing function (used by the network receiver and by scheduled collection children).
	 */
	public long play(final Identifier effectId, final int durationTicks, final long instanceId, final @Nullable Vec3 position, final List<UUID> entityUuids, final Map<String, Float> params, final EasingFunction easing) {
		return this.play(effectId, durationTicks, instanceId, position, entityUuids, params, easing, 0, 0);
	}

	/**
	 * Same as {@link #play(Identifier, int, long, Vec3, List, Map, EasingFunction)} but resumes
	 * the timeline from the given tick offset instead of its start (used when the server
	 * re-applies an effect after a reconnect so it continues where it left off).
	 *
	 * @param elapsedTicks how far into the timeline to seek, in ticks (0 = start fresh)
	 */
	public long play(final Identifier effectId, final int durationTicks, final int elapsedTicks, final long instanceId, final @Nullable Vec3 position, final List<UUID> entityUuids, final Map<String, Float> params, final EasingFunction easing) {
		return this.play(effectId, durationTicks, instanceId, position, entityUuids, params, easing, 0, elapsedTicks);
	}

	private long play(final Identifier effectId, final int durationTicks, final long instanceId, final @Nullable Vec3 position, final List<UUID> entityUuids, final Map<String, Float> params, final EasingFunction easing, final int depth, final int elapsedTicks) {
		VFXDefinition definition = VFXDefinitionManager.get().get(effectId);
		VFXEffectType type = definition != null ? definition.getType() : VFXEffectType.fromString(effectId.getPath());
		if (type == null) {
			LOGGER.warn("Ignoring unknown VFX effect '{}'", effectId);
			return 0L;
		}
		if (type == VFXEffectType.COLLECTION) {
			if (definition == null || depth >= MAX_COLLECTION_DEPTH) {
				LOGGER.warn("Ignoring collection '{}' (unknown or nested too deeply)", effectId);
				return 0L;
			}
			int scheduledCount = 0;
			for (VFXDefinition.ChildEffect child : definition.getChildren()) {
				if (this.scheduled.size() >= MAX_SCHEDULED_EFFECTS) {
					LOGGER.warn("Scheduled VFX effect limit ({}) reached; dropping remaining collection children", MAX_SCHEDULED_EFFECTS);
					break;
				}
				this.scheduled.add(new ScheduledPlay(this.clock + child.delay(), child.effect(), child.duration(), position, child.params(), child.easing(), depth + 1));
				scheduledCount++;
			}
			LOGGER.info("Scheduled {} child effect(s) from collection '{}'", scheduledCount, effectId);
			if (definition.getSound() != null) {
				// Collections have no timeline of their own, so volume/pitch/position use defaults.
				playSound(definition.getSound(), 1.0F, 1.0F, null);
			}
			return 0L;
		}

		long id = instanceId != 0L ? instanceId : this.instanceCounter.incrementAndGet();
		boolean loop = definition != null && definition.isLoop();
		// Cap server-supplied durations: a negative (persistent) value or an absurdly long one
		// from a hostile/buggy server would otherwise pin an effect forever. Definition-driven
		// persistent/loop effects still run forever as intended.
		boolean persistentFromServer = definition == null && durationTicks < 0;
		boolean persistent = definition != null && (definition.isPersistent() || loop);
		int clampedTicks = Math.min(Math.max(durationTicks, 0), MAX_DURATION_TICKS);
		int duration = persistent
			? (loop ? definition.getDefaultDuration() : Integer.MAX_VALUE)
			: (persistentFromServer ? Integer.MAX_VALUE : (clampedTicks > 0 ? clampedTicks : (definition != null ? definition.getDefaultDuration() : 40)));
		EasingFunction effectiveEasing = easing != null ? easing : (definition != null ? definition.getDefaultEasing() : EasingFunction.builtIn(EasingType.LINEAR));
		long instanceSeed = ThreadLocalRandom.current().nextLong();
		VFXTimeline timeline = definition != null
			? definition.createTimeline(duration, params, effectiveEasing, instanceSeed)
			: createConstantTimeline(duration, params);

		int fadeTicks = definition != null ? definition.getFadeTicks() : 0;
		List<BlockPos> positions = definition != null ? definition.getPositions() : List.of();
		BlockPos payloadPos = position != null
			? new BlockPos((int) Math.floor(position.x()), (int) Math.floor(position.y()), (int) Math.floor(position.z()))
			: payloadPosition(params);
		if (payloadPos != null) {
			// Explicit position overrides (e.g. /vfx playat or a network play with a position)
			// win over definition positions and re-anchor any spatial world bindings to that position.
			positions = List.of(payloadPos);
			double px = position != null ? position.x() : payloadPos.getX();
			double py = position != null ? position.y() : payloadPos.getY();
			double pz = position != null ? position.z() : payloadPos.getZ();
			timeline.rebindPositions(px, py, pz);
		}
		VFXActiveEffect effect = new VFXActiveEffect(effectId, type, id, instanceSeed, this.clock, timeline, fadeTicks, loop, positions, entityUuids);
		// Same-id replays stack as independent instances (e.g. several dents at once);
		// /vfx stop removes every instance of the id, stop(instanceId) removes one. MAX_ACTIVE_EFFECTS caps the total.
		while (this.active.size() >= MAX_ACTIVE_EFFECTS) {
			LOGGER.warn("Active VFX effect limit ({}) reached; removing oldest effect '{}'", MAX_ACTIVE_EFFECTS, this.active.get(0).getId());
			this.active.remove(0);
		}
		this.active.add(effect);
		if (elapsedTicks > 0) {
			// Resume: fast-forward the fresh instance so reconnects continue mid-animation
			// instead of restarting from the first keyframe.
			effect.update(this.clock + elapsedTicks);
		}
		if (definition != null && definition.getSound() != null) {
			// Volume/pitch come from reserved effect parameters (constant, bound or expression),
			// evaluated once at start time — matching the "one-shot sound" behaviour.
			// When sound_pos_x/y/z are present the sound is played at those world coordinates
			// (vanilla positional playback); otherwise it plays directly to the player.
			float spx = effect.getParam("sound_pos_x", Float.NaN);
			float spy = effect.getParam("sound_pos_y", Float.NaN);
			float spz = effect.getParam("sound_pos_z", Float.NaN);
			BlockPos soundPos = Float.isNaN(spx) || Float.isNaN(spy) || Float.isNaN(spz)
				? null
				: new BlockPos((int) spx, (int) spy, (int) spz);
			playSound(definition.getSound(), effect.getParam("volume", 1.0F), effect.getParam("pitch", 1.0F), soundPos);
		}
		if (LOGGER.isInfoEnabled()) {
			StringBuilder snapshot = new StringBuilder();
			for (String name : timeline.getValues().keySet()) {
				if (!snapshot.isEmpty()) {
					snapshot.append(", ");
				}
				snapshot.append(name).append('=').append(timeline.getValue(name, Float.NaN));
			}
			for (String name : timeline.getBindings().keySet()) {
				if (!snapshot.isEmpty()) {
					snapshot.append(", ");
				}
				snapshot.append(name).append("=bind(").append(timeline.getBindings().get(name).kind()).append(')');
			}
			for (String name : timeline.getMultipliers().keySet()) {
				if (!snapshot.isEmpty()) {
					snapshot.append(", ");
				}
				snapshot.append(name).append("=bind(").append(timeline.getMultipliers().get(name).kind()).append(")");
			}
			LOGGER.info("Started VFX effect '{}' (instance {}) for {} ticks: {}", effectId, id, persistent ? "forever" : duration, snapshot);
		}
		return id;
	}

	/**
	 * Stops all running instances of the given effect. Persistent instances fade out over their
	 * definition's fade duration instead of disappearing instantly.
	 */
	public void stop(final Identifier effectId) {
		this.scheduled.removeIf(play -> play.effectId().equals(effectId));
		this.active.removeIf(effect -> {
			if (!effect.getId().equals(effectId)) {
				return false;
			}
			if (effect.getFadeTicks() > 0 && !effect.isFadingOut()) {
				effect.beginFadeOut(this.clock);
				return false;
			}
			return true;
		});
	}

	/**
	 * Stops one specific instance of an effect (identified by the id returned from
	 * {@link #play(Identifier, int, Map, EasingType)} or the network stop action). Persistent
	 * instances fade out over their definition's fade duration instead of disappearing instantly.
	 *
	 * @param instanceId the instance id to stop
	 * @return {@code true} when an instance with that id was found
	 */
	public boolean stop(final long instanceId) {
		for (VFXActiveEffect effect : this.active) {
			if (effect.getInstanceId() == instanceId) {
				if (effect.getFadeTicks() > 0 && !effect.isFadingOut()) {
					effect.beginFadeOut(this.clock);
				} else {
					this.active.remove(effect);
				}
				return true;
			}
		}
		return false;
	}

	/**
	 * Stops one specific instance of an effect, but only when that instance actually belongs to
	 * the given effect id. Used by the network stop action so a server-supplied instance id
	 * cannot be used to stop an unrelated instance.
	 *
	 * @param effectId   the effect the instance must belong to
	 * @param instanceId the instance id to stop
	 * @return {@code true} when a matching instance was found and stopped
	 */
	public boolean stop(final Identifier effectId, final long instanceId) {
		for (VFXActiveEffect effect : this.active) {
			if (effect.getInstanceId() == instanceId) {
				if (!effect.getId().equals(effectId)) {
					return false;
				}
				if (effect.getFadeTicks() > 0 && !effect.isFadingOut()) {
					effect.beginFadeOut(this.clock);
				} else {
					this.active.remove(effect);
				}
				return true;
			}
		}
		return false;
	}

	/**
	 * Live-overrides a parameter of every running instance of the effect, without restarting
	 * its timeline (used by {@code /vfx set}). When no instance is running, a persistent
	 * instance is started with the override baked in, so the command works standalone.
	 *
	 * @return {@code true} when the effect is known and was applied or started
	 */
	public boolean setParam(final Identifier effectId, final String name, final float value) {
		boolean applied = false;
		for (VFXActiveEffect effect : this.active) {
			if (effect.getId().equals(effectId)) {
				effect.getTimeline().setOverride(name, value);
				applied = true;
			}
		}
		if (!applied) {
			VFXDefinition definition = VFXDefinitionManager.get().get(effectId);
			if (definition == null) {
				return false;
			}
			// No running instance: start one with the definition's own duration so it ends on
			// schedule like any normal play (a -1 here used to create an immortal instance
			// whose animation was stretched over Integer.MAX_VALUE ticks — frozen on frame one).
			this.play(effectId, definition.getDefaultDuration(), Map.of(name, value), null);
		}
		return true;
	}

	/**
	 * Adds or replaces a keyframe of a parameter on every running instance of the effect
	 * (used by {@code VFXAPI.sendKeyframe}).
	 *
	 * @return {@code true} when at least one running instance was found and updated
	 */
	public boolean setKeyframe(final Identifier effectId, final String name, final float time, final float value, final EasingFunction easing) {
		boolean applied = false;
		for (VFXActiveEffect effect : this.active) {
			if (effect.getId().equals(effectId)) {
				effect.getTimeline().setKeyframe(name, time, value, easing);
				applied = true;
			}
		}
		return applied;
	}

	/**
	 * Live-overrides a parameter with a built-in easing type (wraps it into an easing function).
	 */
	public boolean setKeyframe(final Identifier effectId, final String name, final float time, final float value, final EasingType easing) {
		return setKeyframe(effectId, name, time, value, EasingFunction.builtIn(easing));
	}

	/**
	 * Stops all running effects (persistent ones fade out).
	 */
	public void stopAll() {
		this.scheduled.clear();
		this.active.removeIf(effect -> {
			if (effect.getFadeTicks() > 0 && !effect.isFadingOut()) {
				effect.beginFadeOut(this.clock);
				return false;
			}
			return true;
		});
	}

	public List<VFXActiveEffect> getActive() {
		return List.copyOf(this.active);
	}

	public List<VFXActiveEffect> getActivePostEffects() {
		return this.active.stream().filter(e -> e.getType().isPostProcessing()).toList();
	}

	public List<VFXActiveEffect> getActiveWorldEffects() {
		return this.active.stream().filter(e -> e.getType().isWorldOverlay()).toList();
	}

	/**
	 * The active entity effects targeting the given entity UUID (entity tint/outline). O(1)
	 * lookup via the inverted index rebuilt each {@link #update()}.
	 */
	public List<VFXActiveEffect> getActiveEntityEffects(final UUID uuid) {
		List<VFXActiveEffect> effects = this.entityEffectsIndex.get(uuid);
		return effects != null ? List.copyOf(effects) : List.of();
	}

	public List<VFXActiveEffect> getActiveShakes() {
		return this.active.stream().filter(e -> e.getType() == VFXEffectType.CAMERA_SHAKE).toList();
	}

	/**
	 * The active {@code camera_roll} effects.
	 */
	public List<VFXActiveEffect> getActiveCameraRolls() {
		return this.active.stream().filter(e -> e.getType() == VFXEffectType.CAMERA_ROLL).toList();
	}

	public float getActiveFovDelta() {
		float delta = 0.0F;
		for (VFXActiveEffect effect : this.active) {
			if (effect.getType() == VFXEffectType.FOV_MODIFIER) {
				delta += effect.getParam("fov_delta", 0.0F) * effect.getWeight();
			}
		}
		return delta;
	}

	public float getClock() {
		return this.clock;
	}
	/** Reads a {@code pos_x/pos_y/pos_z} override triple into a block position, or null. */
	private static BlockPos payloadPosition(final Map<String, Float> params) {
		Float x = params.get("pos_x");
		Float y = params.get("pos_y");
		Float z = params.get("pos_z");
		if (x == null || y == null || z == null) {
			return null;
		}
		return new BlockPos(x.intValue(), y.intValue(), z.intValue());
	}

	private static void playSound(final Identifier soundId, final float volume, final float pitch, final @Nullable BlockPos pos) {
		try {
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft.level != null) {
				SoundEvent soundEvent = SoundEvent.createVariableRangeEvent(soundId);
				if (pos != null) {
					// Positional playback via the vanilla SimpleSoundInstance, like
					// /playsound ... x y z — loud near the position, fading with distance.
					minecraft.getSoundManager().play(new SimpleSoundInstance(
						soundEvent, SoundSource.BLOCKS, volume, pitch, SoundInstance.createUnseededRandom(), pos
					));
				} else {
					// Play directly to the player with no world position.
					// forUI(SoundEvent, pitch, volume) — note the argument order.
					minecraft.getSoundManager().play(SimpleSoundInstance.forUI(soundEvent, pitch, volume));
				}
			}
		} catch (Exception e) {
			LOGGER.warn("Failed to play VFX sound '{}'", soundId, e);
		}
	}

	private static VFXTimeline createConstantTimeline(final float duration, final Map<String, Float> params) {
		Map<String, AnimatedValue> values = new LinkedHashMap<>();
		for (Map.Entry<String, Float> entry : params.entrySet()) {
			values.put(entry.getKey(), AnimatedValue.constant(entry.getValue()));
		}
		return new VFXTimeline(duration, values);
	}

	/**
	 * A child effect waiting for its delay to elapse.
	 */
	private record ScheduledPlay(float at, Identifier effectId, int durationTicks, @Nullable Vec3 position, Map<String, Float> params, EasingFunction easing, int depth) {
	}
}

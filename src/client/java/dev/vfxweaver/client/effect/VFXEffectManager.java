package dev.vfxweaver.client.effect;

import dev.vfxweaver.effect.AnimatedValue;
import dev.vfxweaver.effect.EasingFunction;
import dev.vfxweaver.effect.EasingType;
import dev.vfxweaver.effect.VFXActiveEffect;
import dev.vfxweaver.effect.VFXDefinition;
import dev.vfxweaver.effect.VFXEffectType;
import dev.vfxweaver.effect.VFXReplayClock;
import dev.vfxweaver.effect.VFXTimeline;
import dev.vfxweaver.resource.VFXDefinitionManager;
import dev.vfxweaver.util.VFXFogModifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
	/** Timeline duration used for an id that resolves a type but has no definition (no default to read). */
	private static final int DEFAULT_PARAM_DURATION = 40;

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
	 * Sets the shared effect clock to an absolute time (in ticks). Used while a Flashback replay
	 * drives the effects: the replay's own time position is the clock, so pausing and seeking the
	 * replay pause and move the effects with it.
	 *
	 * @param now absolute clock time in ticks
	 */
	public void setClock(final float now) {
		this.clock = now;
	}

	/**
	 * True when a recorded effect with the given duration is in its ACTIVE phase at {@code now}.
	 * Resolves the definition's loop/persistent flags and effective duration the same way
	 * {@link #play} does, so the replay controller never starts an already-ended effect.
	 *
	 * @param effectId      effect id
	 * @param durationTicks recorded duration in ticks (0 uses the definition default)
	 * @param startTick     replay tick the effect was triggered at
	 * @param now           current replay time in ticks
	 * @return true when the effect should be playing at {@code now}
	 */
	public boolean replayEffectActive(final Identifier effectId, final int durationTicks, final float startTick, final float now) {
		VFXDefinition definition = VFXDefinitionManager.get().get(effectId);
		boolean loop = definition != null && definition.isLoop();
		boolean persistent = (definition != null && (definition.isPersistent() || loop)) || (definition == null && durationTicks < 0);
		int clampedTicks = Math.min(Math.max(durationTicks, 0), MAX_DURATION_TICKS);
		int definitionDuration = definition != null ? definition.getDefaultDuration() : DEFAULT_PARAM_DURATION;
		int duration = resolveTimelineDuration(loop, clampedTicks, definitionDuration);
		return VFXReplayClock.phaseAt(now, startTick, duration, loop, persistent) == VFXReplayClock.Phase.ACTIVE;
	}

	/**
	 * Starts an effect on the replay timeline: the instance's start time is the recorded trigger
	 * tick, so its age at any replay time is {@code replayTick - triggerTick} and seeking back and
	 * forth shows the same frame instead of restarting the effect.
	 *
	 * @param effectId      effect id
	 * @param durationTicks recorded duration in ticks (0 uses the definition default)
	 * @param startTick     replay tick the effect was triggered at
	 * @param instanceId    stable instance id owned by the replay event
	 * @param position      world anchor recorded with the play (may be null)
	 * @param entityUuids   entity targets recorded with the play (empty for non-entity effects)
	 * @param params        recorded parameter values
	 * @param easing        recorded easing (may be null)
	 * @param playSound     false when rebuilding after a seek (never re-trigger the sound)
	 * @return the instance id, or {@code 0} when the effect was ignored
	 */
	public long playReplay(final Identifier effectId, final int durationTicks, final float startTick, final long instanceId, final @Nullable Vec3 position, final List<UUID> entityUuids, final Map<String, Float> params, final @Nullable EasingFunction easing, final boolean playSound) {
		return this.play(effectId, durationTicks, instanceId, position, entityUuids, params, easing, 0, 0, null, List.of(), startTick, playSound);
	}

	/**
	 * Removes the given instances immediately (no fade), used when a replay seek rebuilds the
	 * effect set from the recorded timeline.
	 *
	 * @param instanceIds the instance ids to remove
	 */
	public void removeInstances(final Set<Long> instanceIds) {
		if (instanceIds.isEmpty()) {
			return;
		}
		this.active.removeIf(effect -> instanceIds.contains(effect.getInstanceId()));
	}

	/**
	 * Removes every active instance immediately (no fade), used when a replay seek rebuilds the
	 * effect set from the recorded timeline. A seek stops everything - including an instance a
	 * replay play action did not place on the timeline (a re-delivered network trigger or a
	 * snapshot) - and then re-places only the effects whose recorded trigger is at or before the new
	 * replay time, so the timeline state is rebuilt without touching anything between seeks.
	 */
	public void removeAllInstances() {
		this.active.clear();
	}

	/**
	 * Removes one instance immediately (no fade), used by the replay controller when the replay
	 * time moves back before an effect's trigger.
	 *
	 * @param instanceId the instance id to remove
	 */
	public void removeInstance(final long instanceId) {
		this.active.removeIf(effect -> effect.getInstanceId() == instanceId);
	}

	/**
	 * Drops every pending collection child. Used when a replay seek rebuilds the effect set, so a
	 * re-scheduled collection does not stack duplicate children on the ones already queued.
	 */
	public void clearScheduled() {
		this.scheduled.clear();
	}

	/**
	 * Live-overrides a parameter on every running instance of the effect without starting one when
	 * none is running (unlike {@link #setParam}). Used to replay a recorded {@code set-param} edit
	 * at its recorded time without materialising an effect that the replay never triggered.
	 *
	 * @return {@code true} when at least one running instance was updated
	 */
	public boolean applyParam(final Identifier effectId, final String name, final float value) {
		boolean applied = false;
		for (VFXActiveEffect effect : this.active) {
			if (effect.getId().equals(effectId)) {
				effect.getTimeline().setOverride(name, value);
				applied = true;
			}
		}
		return applied;
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
				this.play(play.definition().getId(), play.durationTicks(), 0L, play.position(), List.of(), Map.of(), play.easing(), play.depth(), 0, play.definition(), play.collections());
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
			|| type == VFXEffectType.ENTITY_DISPLACE;
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
		return this.play(effectId, durationTicks, 0L, null, List.of(), params, easing == null ? null : EasingFunction.builtIn(easing), 0, 0, null, List.of());
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
		return this.play(effectId, durationTicks, instanceId, position, List.of(), params, easing, 0, 0, null, List.of());
	}

	/**
	 * Starts an effect with an explicit instance id, world position, entity UUID targets and
	 * easing function (used by the network receiver and by scheduled collection children).
	 */
	public long play(final Identifier effectId, final int durationTicks, final long instanceId, final @Nullable Vec3 position, final List<UUID> entityUuids, final Map<String, Float> params, final EasingFunction easing) {
		return this.play(effectId, durationTicks, instanceId, position, entityUuids, params, easing, 0, 0, null, List.of());
	}

	/**
	 * Same as {@link #play(Identifier, int, long, Vec3, List, Map, EasingFunction)} but resumes
	 * the timeline from the given tick offset instead of its start (used when the server
	 * re-applies an effect after a reconnect so it continues where it left off).
	 *
	 * @param elapsedTicks how far into the timeline to seek, in ticks (0 = start fresh)
	 */
	public long play(final Identifier effectId, final int durationTicks, final int elapsedTicks, final long instanceId, final @Nullable Vec3 position, final List<UUID> entityUuids, final Map<String, Float> params, final EasingFunction easing) {
		return this.play(effectId, durationTicks, instanceId, position, entityUuids, params, easing, 0, elapsedTicks, null, List.of());
	}

	/**
	 * The timeline duration a play builds. A loop's period is always the definition duration;
	 * otherwise a positive payload duration wins, and with neither the definition default is used.
	 * A persistent instance is kept alive by its lifecycle flag, so its timeline may still be a
	 * normal finite duration (it animates once and holds the final value).
	 *
	 * @param loop               whether the definition loops
	 * @param clampedTicks       the payload duration clamped to {@code [0, MAX_DURATION_TICKS]}
	 * @param definitionDuration the definition's default duration (or the fallback for a definition-less id)
	 * @return the timeline duration in ticks
	 */
	static int resolveTimelineDuration(final boolean loop, final int clampedTicks, final int definitionDuration) {
		if (loop) {
			return definitionDuration;
		}
		return clampedTicks > 0 ? clampedTicks : definitionDuration;
	}

	private long play(final Identifier effectId, final int durationTicks, final long instanceId, final @Nullable Vec3 position, final List<UUID> entityUuids, final Map<String, Float> params, final EasingFunction easing, final int depth, final int elapsedTicks, final @Nullable VFXDefinition predefined, final List<Identifier> collections) {
		return this.play(effectId, durationTicks, instanceId, position, entityUuids, params, easing, depth, elapsedTicks, predefined, collections, Float.NaN, true);
	}

	/**
	 * Full play path. {@code startTime} overrides the driving clock as the instance's start time
	 * (used by the Flashback replay controller to place an effect at its recorded trigger tick);
	 * {@code NaN} means "start at the current clock". {@code playSound} is false when a replay is
	 * rebuilt after a seek, so seeking never re-triggers the effect's sound.
	 */
	private long play(final Identifier effectId, final int durationTicks, final long instanceId, final @Nullable Vec3 position, final List<UUID> entityUuids, final Map<String, Float> params, final EasingFunction easing, final int depth, final int elapsedTicks, final @Nullable VFXDefinition predefined, final List<Identifier> collections, final float startTime, final boolean playSound) {
		VFXDefinition definition = predefined != null ? predefined : VFXDefinitionManager.get().get(effectId);
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
			// Ancestor chain of collections that scheduled these children: lets `/vfx stop <collection>`
			// cancel the whole pending subtree (including nested collections) by its id.
			List<Identifier> childCollections = new ArrayList<>(collections);
			childCollections.add(effectId);
			for (VFXDefinition.ChildEffect child : definition.getChildren()) {
				if (this.scheduled.size() >= MAX_SCHEDULED_EFFECTS) {
					LOGGER.warn("Scheduled VFX effect limit ({}) reached; dropping remaining collection children", MAX_SCHEDULED_EFFECTS);
					break;
				}
				VFXDefinition childDef = VFXDefinitionManager.get().get(child.effect());
				if (childDef == null) {
					LOGGER.warn("Collection '{}' references unknown child effect '{}'", effectId, child.effect());
					continue;
				}
				if (!child.params().isEmpty()) {
					// Full parameter specs on the child (bind/expr/keyframes) must live in the
					// child definition, so merge them into a derived copy instead of passing
					// constant overrides.
					childDef = childDef.withParams(child.params());
				}
				this.scheduled.add(new ScheduledPlay((Float.isNaN(startTime) ? this.clock : startTime) + child.delay(), childDef, child.duration(), position, child.easing(), depth + 1, childCollections));
				scheduledCount++;
			}
			LOGGER.debug("Scheduled {} child effect(s) from collection '{}'", scheduledCount, effectId);
			if (playSound && definition.getSound() != null) {
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
		boolean persistent = persistentFromServer || (definition != null && (definition.isPersistent() || loop));
		int clampedTicks = Math.min(Math.max(durationTicks, 0), MAX_DURATION_TICKS);
		int definitionDuration = definition != null ? definition.getDefaultDuration() : DEFAULT_PARAM_DURATION;
		// "Never ends" is the lifecycle flag (see VFXActiveEffect.isFinished), not an enormous
		// duration: a 2^31-tick timeline froze start/end animation at the start value and divided
		// particle emission budgets to ~0. A persistent non-loop instance instead animates
		// start->end over the definition duration, then holds the final value forever.
		int duration = resolveTimelineDuration(loop, clampedTicks, definitionDuration);
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
		// Entity-anchored positions: the server resolved each definition anchor selector into one
		// UUID (in anchor order) and shipped them in entityUuids; zip them with the definition's
		// anchor slots. With a payload position override the anchors are ignored (the override
		// wins, same as static definition positions).
		List<VFXActiveEffect.ResolvedAnchor> anchors = List.of();
		if (payloadPos == null && definition != null && !definition.getEntityAnchors().isEmpty()) {
			List<VFXActiveEffect.ResolvedAnchor> built = new ArrayList<>();
			List<VFXDefinition.EntityAnchor> specs = definition.getEntityAnchors();
			for (int i = 0; i < specs.size() && i < entityUuids.size(); i++) {
				VFXDefinition.EntityAnchor spec = specs.get(i);
				built.add(new VFXActiveEffect.ResolvedAnchor(spec.slot(), entityUuids.get(i), new Vec3(spec.ox(), spec.oy(), spec.oz()), spec.point(), spec.dir(), spec.distance()));
			}
			if (built.size() < specs.size()) {
				LOGGER.warn("Effect '{}' has {} entity-anchored positions but only {} entity UUID(s) arrived; unanchored slots are skipped while rendering", effectId, specs.size(), built.size());
			}
			if (built.isEmpty()) {
				// No anchor UUIDs at all (e.g. client-local play without targets): without them the
				// placeholder slots would render at the world origin — reject the play instead.
				LOGGER.warn("Effect '{}' has entity-anchored positions but no entity UUIDs arrived; play ignored", effectId);
				return 0L;
			}
			anchors = List.copyOf(built);
		}
		// An explicit instance id is a caller-assigned handle: restart that slot instead of stacking
		// a duplicate, so a later stop(instanceId)/move and the spark/block buckets (keyed by
		// instance id) can never address the wrong instance. Auto-allocated ids always stack
		// (several dents at once); /vfx stop still removes every instance of an id.
		if (instanceId != 0L) {
			this.active.removeIf(existing -> existing.getInstanceId() == instanceId);
		}
		final float effectiveStart = Float.isNaN(startTime) ? this.clock : startTime;
		VFXActiveEffect effect = new VFXActiveEffect(effectId, type, id, instanceSeed, effectiveStart, timeline, fadeTicks, loop, persistent, positions, entityUuids, anchors, definition != null ? definition.getParticleId() : null, definition != null ? definition.getShape() : null, definition != null ? definition.getBlockId() : null, definition != null ? definition.getItemId() : null);
		// Same-id replays with auto-allocated ids stack as independent instances; MAX_ACTIVE_EFFECTS caps the total.
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
		if (playSound && definition != null && definition.getSound() != null) {
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
			LOGGER.debug("Started VFX effect '{}' (instance {}) for {} ticks: {}", effectId, id, persistent ? "forever" : duration, snapshot);
		}
		return id;
	}

	/**
	 * Stops all running instances of the given effect. Persistent instances fade out over their
	 * definition's fade duration instead of disappearing instantly.
	 */
	public void stop(final Identifier effectId) {
		this.scheduled.removeIf(play -> play.definition().getId().equals(effectId) || play.collections().contains(effectId));
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
	 * A null type means linear.
	 */
	public boolean setKeyframe(final Identifier effectId, final String name, final float time, final float value, final EasingType easing) {
		return setKeyframe(effectId, name, time, value, easing == null ? EasingFunction.builtIn(EasingType.LINEAR) : EasingFunction.builtIn(easing));
	}

	/**
	 * Live-replaces a parameter with a compiled math expression on every running instance of the
	 * effect (used by {@code VFXAPI.sendSetParamExpr}). The expression is compiled per instance
	 * with that instance's seed, so {@code random()}/{@code noise()} stay instance-local; the new
	 * expression replaces whatever the parameter had before (keyframes, binding or previous
	 * expression). Invalid expressions fall back to a constant {@code 0}.
	 *
	 * @param exprSource expression source (may be null, which falls back to {@code 0})
	 * @return {@code true} when at least one running instance was found and updated
	 */
	public boolean setExpression(final Identifier effectId, final String name, final String exprSource) {
		boolean applied = false;
		for (VFXActiveEffect effect : this.active) {
			if (effect.getId().equals(effectId)) {
				effect.getTimeline().setExpression(name, exprSource, effect.getInstanceSeed());
				applied = true;
			}
		}
		return applied;
	}

	/**
	 * Moves one specific running instance of an effect to a new world position: the instance's
	 * runtime move position is stored (consumed by the world overlay renderer) and its spatial
	 * world bindings are re-anchored. Used by the network MOVE action.
	 *
	 * @param effectId   the effect the instance must belong to
	 * @param instanceId the instance id to move
	 * @param worldPos   the new world position
	 * @return {@code true} when a matching instance was found and moved
	 */
	public boolean move(final Identifier effectId, final long instanceId, final Vec3 worldPos) {
		for (VFXActiveEffect effect : this.active) {
			if (effect.getInstanceId() == instanceId) {
				if (!effect.getId().equals(effectId)) {
					return false;
				}
				effect.movePosition(worldPos);
				effect.getTimeline().rebindPositions(worldPos.x(), worldPos.y(), worldPos.z());
				return true;
			}
		}
		return false;
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

	/**
	 * The active {@code fog_modifier} instances as per-frame contributions, each already scaled by
	 * its fade weight. The client fog mixin combines them with {@link VFXFogModifier#combine} and
	 * writes the result into the vanilla fog. Absent params keep their neutral value (scales
	 * {@code 1.0}, colour {@code NaN} = "not authored"), so an empty list leaves the fog untouched.
	 */
	public List<VFXFogModifier.Contribution> getActiveFogContributions() {
		List<VFXFogModifier.Contribution> contributions = new ArrayList<>();
		for (VFXActiveEffect effect : this.active) {
			if (effect.getType() == VFXEffectType.FOG_MODIFIER) {
				contributions.add(new VFXFogModifier.Contribution(
					effect.getParam("fog_start_scale", 1.0F),
					effect.getParam("fog_end_scale", 1.0F),
					effect.getParam("fog_r", Float.NaN),
					effect.getParam("fog_g", Float.NaN),
					effect.getParam("fog_b", Float.NaN),
					effect.getParam("fog_color_amount", Float.NaN),
					effect.getWeight()));
			}
		}
		return contributions;
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
	 * A child effect waiting for its delay to elapse. Carries the (possibly derived) child
	 * definition directly, so collection-level parameter specs are already merged in, plus the
	 * ancestor collection ids that scheduled it (outermost first) so {@code stop(collectionId)}
	 * can cancel the whole pending subtree.
	 */
	private record ScheduledPlay(float at, VFXDefinition definition, int durationTicks, @Nullable Vec3 position, EasingFunction easing, int depth, List<Identifier> collections) {
	}
}

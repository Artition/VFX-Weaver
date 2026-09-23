package dev.vfxweaver.client.flashback;

import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.effect.EasingFunction;
import dev.vfxweaver.effect.EasingType;
import dev.vfxweaver.effect.VFXActiveEffect;
import dev.vfxweaver.effect.VFXCurveManager;
import dev.vfxweaver.effect.VFXDefinition;
import dev.vfxweaver.effect.VFXEffectType;
import dev.vfxweaver.effect.VFXTimeline;
import dev.vfxweaver.network.VFXTriggerPayload;
import dev.vfxweaver.platform.VFXPlatform;
import dev.vfxweaver.resource.VFXDefinitionManager;
import dev.vfxweaver.util.VFXLog;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Soft-dependency bridge to Flashback (https://modrinth.com/mod/flashback): VFX plays are written
 * into the replay stream as custom {@code Action}s and re-triggered during playback. The mod works
 * fully without Flashback — nothing here runs when it is absent, and all Flashback classes are
 * reached through reflection so the mod has no compile-time dependency on it (only
 * {@code suggests: flashback} in {@code fabric.mod.json}).
 *
 * <p>Recording: {@link #recordPlay}/{@link #recordStop} queue a {@code Recorder.submitCustomTask}
 * that writes the effect trigger into the current replay, and the recording-start snapshot writes
 * the synced datapack definitions/curves plus every already-running effect. Playback: the
 * registered action's {@code handle} decodes the payloads and re-triggers everything through
 * {@link VFXEffectManager} on the render thread (the handler runs on the replay server thread).
 * Everything travels through a <b>single</b> action: plays, stops and live edits are told apart by
 * a sentinel in the duration field, and the definitions snapshot by the reserved
 * {@code vfxweaver:definitions} id - registering a second action is not possible, because
 * Flashback keys its action registry by the proxy class and both proxies would share one class.
 *
 * <p>Both client-local plays and server-triggered ones are recorded - Flashback does not replay
 * unknown custom payload packets on its own, so without this the server-triggered effects would be
 * missing from replays entirely (especially after the server-side mod has been removed).
 *
 * <p><b>Version tolerance.</b> The playback-state symbols are resolved individually and tolerantly,
 * because Flashback changes their visibility between builds (Flashback 0.39.9 for 1.21.11 declares
 * {@code ReplayServer.jumpToTick} {@code private}, while 0.43.x for 26.2 declares it {@code public}).
 * A symbol that is missing, renamed or not public only degrades the feature that needs it and is
 * reported once through {@code VFXLog.warnOnce}, naming the symbol and the installed Flashback
 * version - it never aborts the whole integration. The old all-or-nothing init turned a single
 * {@code NoSuchFieldException} into a silent no-op, which is why the replay clock was inert on
 * 1.21.11. The recording symbols (the action registry, the recorder and the replay writer) are
 * required: if one of those is absent the integration is disabled with a stack trace.
 */
public final class FlashbackCompat {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/flashback");
	private static final Identifier ACTION_NAME = Identifier.fromNamespaceAndPath("vfxweaver", "effect_trigger");
	/** Reserved id written as the first field of a payload that carries the definitions snapshot. */
	private static final Identifier ACTION_DEFS_NAME = Identifier.fromNamespaceAndPath("vfxweaver", "definitions");
	/**
	 * Safety cap on the number of params decoded from a replay file. Kept in step with
	 * {@code VFXTriggerPayload.MAX_PARAMS} (raised to 256 for mask dynamic data): a snapshot of a
	 * large mask must not be truncated. Old recordings (at most 32 params) still decode.
	 */
	private static final int MAX_PARAMS = 256;
	/** Safety cap on the total characters of synced definition/curve JSON written into a replay. */
	private static final int MAX_DEFS_CHARS = 2_000_000;

	private static boolean enabled;
	private static @Nullable Class<?> actionClass;
	private static @Nullable Class<?> registryClass;
	private static @Nullable Class<?> recorderClass;
	private static @Nullable Class<?> replayWriterClass;
	private static @Nullable Class<?> flashbackClass;
	private static @Nullable Class<?> replayServerClass;
	private static @Nullable Object action;
	// Reflective handles resolved once during init to avoid per-call getMethod/getField lookups.
	private static @Nullable Field recorderField;
	private static @Nullable Method readyToWriteMethod;
	private static @Nullable Method submitCustomTaskMethod;
	private static @Nullable Method startActionMethod;
	private static @Nullable Method finishActionMethod;
	private static @Nullable Method friendlyByteBufMethod;
	// Playback-state handles: the live ReplayServer, its replay time and pause flag, and the tick
	// the action currently being handled was recorded at.
	private static @Nullable Method getReplayServerMethod;
	private static @Nullable Method getPartialReplayTickMethod;
	private static @Nullable Field replayPausedField;
	private static @Nullable Field currentTickField;
	/**
	 * {@code ReplayServer.jumpToTick}: the pending seek target set by {@code goToReplayTick} and
	 * applied to {@code targetTick} by the replay server tick. While the replay is paused the
	 * server is frozen, so the polled replay time can lag behind a scrub; reading the pending
	 * target lets the effect clock follow the scrub immediately.
	 */
	private static @Nullable Field jumpToTickField;
	/** The last {@code Flashback.RECORDER} instance seen, to detect a new recording start. */
	private static @Nullable Object lastRecorder;
	/** True once the snapshot of already-active effects has been written for the current recording. */
	private static boolean snapshotWritten;

	private FlashbackCompat() {
	}

	/**
	 * Looks up the Flashback classes and registers the replay actions. Safe to call multiple times;
	 * a no-op when Flashback is not installed. Must run after Flashback itself is on the classpath.
	 */
	public static void init() {
		if (enabled || !VFXPlatform.isModLoaded("flashback")) {
			return;
		}
		try {
			actionClass = Class.forName("com.moulberry.flashback.action.Action");
			registryClass = Class.forName("com.moulberry.flashback.action.ActionRegistry");
			recorderClass = Class.forName("com.moulberry.flashback.record.Recorder");
			replayWriterClass = Class.forName("com.moulberry.flashback.io.ReplayWriter");
			flashbackClass = Class.forName("com.moulberry.flashback.Flashback");
			replayServerClass = Class.forName("com.moulberry.flashback.playback.ReplayServer");
			action = Proxy.newProxyInstance(actionClass.getClassLoader(), new Class<?>[]{actionClass}, new ActionHandler());
			// Exactly ONE action is registered on purpose: Flashback keys its action registry by the
			// proxy class, and two proxies with the same interfaces share one generated class, so a
			// second registration fails ("Action already registered") and silently disables replay
			// recording. The definitions snapshot therefore travels through this action too, marked
			// by a reserved id in the payload.
			Method register = registryClass.getMethod("register", actionClass);
			register.invoke(null, action);
			recorderField = flashbackClass.getField("RECORDER");
			readyToWriteMethod = recorderClass.getMethod("readyToWrite");
			submitCustomTaskMethod = recorderClass.getMethod("submitCustomTask", Consumer.class);
			startActionMethod = replayWriterClass.getMethod("startAction", actionClass);
			finishActionMethod = replayWriterClass.getMethod("finishAction", actionClass);
			friendlyByteBufMethod = replayWriterClass.getMethod("friendlyByteBuf");
		} catch (Throwable t) {
			enabled = false;
			LOGGER.warn("Failed to initialize Flashback compatibility; effects won't be recorded into replays", t);
			return;
		}
		// The playback-state handles are resolved separately and tolerantly, because a Flashback
		// build can expose a symbol with a different visibility or name between versions: 1.21.11's
		// Flashback 0.39.9 has ReplayServer.jumpToTick as a *private* field while 0.43.x has it
		// public, so a bare getField threw NoSuchFieldException and the old all-or-nothing init
		// aborted the whole integration - recording silently stopped and the effects ran on the wall
		// clock. Each handle is now resolved on its own and a miss only degrades the feature that
		// needs it, with a once-per-symbol warning naming the symbol and the installed version.
		getReplayServerMethod = resolveMethod(flashbackClass, "getReplayServer");
		getPartialReplayTickMethod = resolveMethod(replayServerClass, "getPartialReplayTick");
		replayPausedField = resolveField(replayServerClass, "replayPaused");
		currentTickField = resolveField(replayServerClass, "currentTick");
		jumpToTickField = resolveField(replayServerClass, "jumpToTick");
		enabled = true;
		LOGGER.info("Flashback compatibility enabled: VFX effects are recorded into replays");
	}

	/**
	 * Resolves a field by name, tolerating a visibility change between Flashback builds: a public
	 * field is read directly, otherwise the declared field is made accessible. A miss is reported
	 * once (naming the symbol and the installed Flashback version) and returns {@code null} instead
	 * of aborting the integration.
	 *
	 * @param owner the class that owns the field
	 * @param name  the field name
	 * @return the field, or {@code null} when Flashback does not expose it
	 */
	private static @Nullable Field resolveField(final Class<?> owner, final String name) {
		try {
			return owner.getField(name);
		} catch (NoSuchFieldException notPublic) {
			// Not public (a version-dependent visibility): fall through to the declared lookup.
		}
		try {
			final Field field = owner.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (Throwable t) {
			warnMissingSymbol(owner, name, t);
			return null;
		}
	}

	/**
	 * Resolves a public no-argument method by name, with the same once-per-symbol diagnostic as
	 * {@link #resolveField(Class, String)}.
	 *
	 * @param owner the class that owns the method
	 * @param name  the method name
	 * @return the method, or {@code null} when Flashback does not expose it
	 */
	private static @Nullable Method resolveMethod(final Class<?> owner, final String name) {
		try {
			return owner.getMethod(name);
		} catch (Throwable t) {
			warnMissingSymbol(owner, name, t);
			return null;
		}
	}

	/**
	 * Warns once per symbol that Flashback does not expose it, naming the symbol and the installed
	 * Flashback version, so a version mismatch is diagnosable instead of silently no-oping the
	 * replay-timeline integration. The affected handle is left {@code null} and the callers degrade
	 * gracefully.
	 *
	 * @param owner the class the symbol was looked up on
	 * @param name  the missing symbol
	 * @param cause the lookup failure, for its exception type
	 */
	private static void warnMissingSymbol(final Class<?> owner, final String name, final Throwable cause) {
		VFXLog.warnOnce(LOGGER, "flashback-symbol-" + owner.getSimpleName() + "." + name,
			"Flashback {} does not expose {}.{} ({}); the replay-timeline integration will not work correctly - update Flashback or report the symbol mismatch",
			VFXPlatform.modVersion("flashback"), owner.getSimpleName(), name, cause.getClass().getSimpleName());
	}

	/**
	 * Watches {@code Flashback.RECORDER} each tick. When a recording just started and became ready
	 * (its initial world snapshot has been written), snapshots every effect that is already running
	 * so it appears in the replay from the first tick instead of being lost.
	 *
	 * <p>Called once per client tick by {@code VFXClientRenderHooks}.
	 */
	public static void detectRecordingStart() {
		if (!enabled) {
			return;
		}
		try {
			Object recorder = recorderField.get(null);
			if (recorder == null) {
				lastRecorder = null;
				snapshotWritten = false;
				return;
			}
			if (recorder != lastRecorder) {
				lastRecorder = recorder;
				snapshotWritten = false;
			}
			if (snapshotWritten) {
				return;
			}
			if ((Boolean) readyToWriteMethod.invoke(recorder)) {
				snapshotWritten = true;
				writeDefinitionsSnapshot(recorder);
				writeActiveEffectsSnapshot(recorder);
			}
		} catch (Throwable t) {
			LOGGER.warn("Failed to detect Flashback recording start", t);
		}
	}

	/**
	 * {@code true} when a Flashback replay world is currently open (playing or paused). The effect
	 * clock is driven by the replay's own time while this is true.
	 */
	public static boolean isReplayActive() {
		return replayServer() != null;
	}

	/** The live {@code ReplayServer}, or {@code null} outside a replay. */
	public static @Nullable Object replayServer() {
		if (!enabled || getReplayServerMethod == null) {
			return null;
		}
		try {
			return getReplayServerMethod.invoke(null);
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * The replay's current time in ticks, fractional between ticks (Flashback's
	 * {@code getPartialReplayTick}); it holds still while the replay is paused.
	 *
	 * <p>While paused, a scrub is delivered through {@code goToReplayTick} into the pending
	 * {@code jumpToTick} and only reaches {@code targetTick} on the next replay server tick (the
	 * server is frozen while paused), so {@code getPartialReplayTick} can report the pre-scrub
	 * position for a frame. The pending target is preferred while paused so the effect clock and
	 * the replay controller see the seek immediately and rebuild instead of holding stale effects.
	 *
	 * @return the replay time in ticks, or {@code 0} outside a replay
	 */
	public static double getReplayTimeTicks() {
		Object server = replayServer();
		if (server == null || getPartialReplayTickMethod == null) {
			return 0.0;
		}
		try {
			if (isReplayPaused() && jumpToTickField != null) {
				int pending = jumpToTickField.getInt(server);
				if (pending >= 0) {
					return pending;
				}
			}
			return ((Number) getPartialReplayTickMethod.invoke(server)).doubleValue();
		} catch (Throwable t) {
			return 0.0;
		}
	}

	/**
	 * Whether the replay is currently paused. A paused replay's time does not advance, so the
	 * effects hold their state.
	 *
	 * @return {@code true} when the replay is paused
	 */
	public static boolean isReplayPaused() {
		Object server = replayServer();
		if (server == null || replayPausedField == null) {
			return false;
		}
		try {
			return replayPausedField.getBoolean(server);
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Clears replay-created effects and the recorded timeline once no replay is open, so a replay
	 * never leaves its effects running in normal gameplay. Called once per client tick.
	 */
	public static void tickReplayState() {
		if (!enabled) {
			return;
		}
		if (replayServer() == null) {
			VFXReplayController.get().clear();
		}
	}

	/**
	 * The replay tick the action currently being handled was recorded at, read from Flashback's
	 * private {@code ReplayServer.currentTick}. Must be read synchronously on the replay server
	 * thread inside {@link #handlePlayback} - the value moves on before the render-thread hop.
	 *
	 * @return the recorded tick, or {@code -1} when it cannot be read
	 */
	private static int currentActionTick() {
		Object server = replayServer();
		if (server == null || currentTickField == null) {
			return -1;
		}
		try {
			return currentTickField.getInt(server);
		} catch (Throwable t) {
			return -1;
		}
	}

	/**
	 * Writes the synced datapack definitions and curves into the replay as the first action, so
	 * datapack-defined effect ids resolve during playback even when the server-side mod (and its
	 * datapack) no longer exists. Skipped when there is nothing synced or the JSON exceeds
	 * {@link #MAX_DEFS_CHARS}.
	 */
	private static void writeDefinitionsSnapshot(final Object recorder) {
		Map<Identifier, String> definitions = VFXDefinitionManager.get().getRawDefinitions();
		Map<Identifier, String> curves = VFXCurveManager.get().getRawCurves();
		if (definitions.isEmpty() && curves.isEmpty()) {
			return;
		}
		int totalChars = 0;
		for (String json : definitions.values()) {
			totalChars += json.length();
		}
		for (String json : curves.values()) {
			totalChars += json.length();
		}
		if (totalChars > MAX_DEFS_CHARS) {
			LOGGER.warn("Synced VFX definitions/curves are too large to embed into a Flashback replay ({} chars); datapack-defined effects will not replay", totalChars);
			return;
		}
		try {
			submitCustomTaskMethod.invoke(recorder, (Consumer<Object>) writer -> {
				try {
					boolean started = false;
					try {
						startActionMethod.invoke(writer, action);
						started = true;
						RegistryFriendlyByteBuf buf = (RegistryFriendlyByteBuf) friendlyByteBufMethod.invoke(writer);
						// Reserved marker id: tells the reader this payload is the definitions snapshot.
						buf.writeIdentifier(ACTION_DEFS_NAME);
						buf.writeVarInt(definitions.size());
						for (Map.Entry<Identifier, String> entry : definitions.entrySet()) {
							buf.writeIdentifier(entry.getKey());
							buf.writeUtf(entry.getValue());
						}
						buf.writeVarInt(curves.size());
						for (Map.Entry<Identifier, String> entry : curves.entrySet()) {
							buf.writeIdentifier(entry.getKey());
							buf.writeUtf(entry.getValue());
						}
					} finally {
						if (started) {
							finishActionMethod.invoke(writer, action);
						}
					}
				} catch (Throwable t) {
					LOGGER.warn("Failed to write VFX definitions into Flashback replay", t);
				}
			});
		} catch (Throwable t) {
			LOGGER.warn("Failed to queue VFX definitions for Flashback replay", t);
		}
	}

	/**
	 * Writes one replay action per already-running effect into the given recording, using each
	 * effect's current parameter values so it replays in the same state. Looping and persistent
	 * effects are snapshotted too: the replay controller keeps such a play alive until a recorded
	 * stop (or for the whole replay when it was never stopped), which is exactly how it ran
	 * originally — so an infinite effect reproduces like a finite one instead of being lost.
	 * Collections are skipped (they own no timeline; their children are separate effects) and
	 * camera shakes are skipped as before.
	 */
	private static void writeActiveEffectsSnapshot(final Object recorder) {
		try {
			for (VFXActiveEffect effect : VFXEffectManager.get().getActive()) {
				Identifier id = effect.getId();
				VFXTimeline timeline = effect.getTimeline();
				if (effect.getType() == VFXEffectType.COLLECTION || effect.getType() == VFXEffectType.CAMERA_SHAKE) {
					continue;
				}
				int duration = Math.max(1, (int) Math.ceil(timeline.getDuration() - timeline.getElapsed()));
				Map<String, Float> params = snapshotParams(id, timeline);
				List<UUID> entityUuids = effect.getEntityUuids();
				submitCustomTaskMethod.invoke(recorder, (Consumer<Object>) writer -> {
					try {
						writeAction(writer, id, duration, params, EasingType.LINEAR, null, entityUuids);
					} catch (Throwable t) {
						LOGGER.warn("Failed to write snapshot of running VFX effect '{}' into Flashback replay", id, t);
					}
				});
			}
		} catch (Throwable t) {
			LOGGER.warn("Failed to snapshot running VFX effects into Flashback replay", t);
		}
	}

	/**
	 * Collects the current value of every timeline parameter (values, bindings, multipliers,
	 * expressions and live overrides) into a constant map, preserving the effect's on-screen
	 * state. A parameter the definition animates (keyframes, start/end, {@code expr}, a world
	 * binding or a graph input) is deliberately <b>not</b> snapshotted: the replay rebuilds its
	 * timeline from the definition snapshot, so the value must be re-evaluated from the effect's
	 * age instead of frozen at the recording-start value. Bounded by {@link #MAX_PARAMS} because
	 * the reader refuses a play action with more params than that, so an oversized effect is
	 * truncated rather than written undecodable.
	 *
	 * @param id       effect id, for the truncation warning
	 * @param timeline the running timeline to snapshot
	 */
	private static Map<String, Float> snapshotParams(final Identifier id, final VFXTimeline timeline) {
		VFXDefinition definition = VFXDefinitionManager.get().get(id);
		Map<String, Float> params = new LinkedHashMap<>();
		Map<String, Float> deferred = new LinkedHashMap<>();
		timeline.getValues().keySet().forEach(name -> deferred.put(name, timeline.getValue(name, Float.NaN)));
		timeline.getBindings().keySet().forEach(name -> deferred.put(name, timeline.getValue(name, Float.NaN)));
		timeline.getMultipliers().keySet().forEach(name -> deferred.put(name, timeline.getValue(name, Float.NaN)));
		timeline.getExpressions().keySet().forEach(name -> deferred.put(name, timeline.getValue(name, Float.NaN)));
		timeline.getOverrideNames().forEach(name -> deferred.put(name, timeline.getValue(name, Float.NaN)));
		for (Map.Entry<String, Float> entry : deferred.entrySet()) {
			if (Float.isNaN(entry.getValue())) {
				continue;
			}
			if (isDefinitionAnimated(definition, entry.getKey())) {
				// The definition re-evaluates this param from the effect's replay age.
				continue;
			}
			if (params.size() >= MAX_PARAMS) {
				LOGGER.warn("Snapshot of VFX effect '{}' has more than {} parameters; the rest are dropped", id, MAX_PARAMS);
				break;
			}
			params.put(entry.getKey(), entry.getValue());
		}
		return params;
	}

	/**
	 * True when the definition drives the parameter over time (keyframes, start/end, {@code expr},
	 * a world binding/multiplier or a graph input), so a snapshot must let the definition evaluate
	 * it instead of freezing its current value.
	 */
	private static boolean isDefinitionAnimated(final @Nullable VFXDefinition definition, final String name) {
		if (definition == null) {
			return false;
		}
		if (definition.getGraphInputs().containsKey(name)) {
			return true;
		}
		VFXDefinition.ParamSpec spec = definition.getParams().get(name);
		return spec != null
			&& (spec.animated() || !spec.keyframes().isEmpty() || spec.exprSource() != null || spec.bound() != null || spec.multiply() != null);
	}

	/**
	 * Records a client-local effect play into the active Flashback replay, if one is running.
	 * Persistent (negative duration) effects are recorded too: the replay controller keeps such a
	 * play alive until a recorded stop (or the whole replay when it was never stopped), so an
	 * infinite effect reproduces exactly like a finite one. The payload is written on the render
	 * thread, mirroring the {@code effectId, durationTicks, easing, params} order of the network
	 * trigger.
	 */
	public static void recordPlay(final Identifier effectId, final int durationTicks, final Map<String, Float> params, final EasingType easing) {
		recordPlay(effectId, durationTicks, params, easing, null, List.of());
	}

	/**
	 * Same as {@link #recordPlay(Identifier, int, Map, EasingType)} but anchored: a non-null
	 * {@code position} is written into the replay action, so a replay re-creates the effect at the
	 * same world point. The anchor block is optional and trailing, so replays recorded by an older
	 * build (which never wrote it) still decode.
	 */
	public static void recordPlay(final Identifier effectId, final int durationTicks, final Map<String, Float> params, final @Nullable EasingType easing, final @Nullable Vec3 position) {
		recordPlay(effectId, durationTicks, params, easing, position, List.of());
	}

	/**
	 * Same as {@link #recordPlay(Identifier, int, Map, EasingType, Vec3)} but with entity targets:
	 * an entity effect (tint/outline/displace) is attached to the entities the server resolved at
	 * trigger time, so the replay must carry those UUIDs or the effect has nothing to render on.
	 * The entity list is optional and trailing (after the anchor), so older recordings still
	 * decode.
	 */
	public static void recordPlay(final Identifier effectId, final int durationTicks, final Map<String, Float> params, final @Nullable EasingType easing, final @Nullable Vec3 position, final List<UUID> entityUuids) {
		if (!enabled) {
			return;
		}
		// A negative duration is the persistent sentinel (VFXAPI sends -1 for a persistent
		// definition). It is recorded, not skipped, so the play action exists at its tick; the
		// replay controller's replayEffectActive/phaseAt keep it active until a recorded stop.
		// Normalise to -1 so it can never collide with the -2..-5 edit sentinels.
		final int recordedDuration = durationTicks < 0 ? -1 : durationTicks;
		final List<UUID> recordedEntities = List.copyOf(entityUuids);
		try {
			Minecraft.getInstance().execute(() -> {
				try {
					Object recorder = recorderField.get(null);
					if (recorder == null) {
						return;
					}
					if (!((Boolean) readyToWriteMethod.invoke(recorder))) {
						return;
					}
					submitCustomTaskMethod.invoke(recorder, (Consumer<Object>) writer -> {
						try {
							writeAction(writer, effectId, recordedDuration, params, easing, position, recordedEntities);
						} catch (Throwable t) {
							LOGGER.warn("Failed to write VFX effect '{}' into Flashback replay", effectId, t);
						}
					});
				} catch (Throwable t) {
					LOGGER.warn("Failed to record VFX effect '{}' into Flashback replay", effectId, t);
				}
			});
		} catch (Throwable t) {
			LOGGER.warn("Failed to queue VFX effect '{}' for Flashback replay recording", effectId, t);
		}
	}

	/**
	 * Records a server-triggered effect play into the active Flashback replay (Flashback does not
	 * replay unknown custom payload packets on its own). A persistent definition arrives as a
	 * negative duration (VFXAPI sends -1) and is recorded like any other play; the replay
	 * controller keeps it alive until a recorded stop. The position and entity UUIDs the server
	 * shipped are recorded too, so a spatial or entity effect replays on the same target.
	 */
	public static void recordServerPlay(final Identifier effectId, final int durationTicks, final Map<String, Float> params, final String easing, final @Nullable Vec3 position, final List<UUID> entityUuids) {
		recordPlay(effectId, durationTicks, params, EasingType.fromString(easing), position, entityUuids);
	}

	/**
	 * Sentinel values written into the trigger action's {@code durationTicks} field: a real
	 * duration is a play, negative values are the live edits (a stop is {@code -2} for
	 * compatibility with recordings written before the other sentinels existed).
	 */
	private static final int ACTION_STOP = -2;
	private static final int ACTION_SET_PARAM = -3;
	private static final int ACTION_KEYFRAME = -4;
	private static final int ACTION_SET_EXPR = -5;

	/** Records a live parameter override ({@code setParam}) into the active replay. */
	public static void recordSetParam(final Identifier effectId, final String name, final float value) {
		recordEdit(effectId, ACTION_SET_PARAM, buf -> {
			buf.writeUtf(name);
			buf.writeFloat(value);
		});
	}

	/**
	 * Records a live keyframe ({@code setKeyframe}) into the active replay. A negative {@code time}
	 * ("from here") is written verbatim and reproduces the same relative segment on playback.
	 */
	public static void recordKeyframe(final Identifier effectId, final String name, final int time, final float value, final @Nullable String easing) {
		recordEdit(effectId, ACTION_KEYFRAME, buf -> {
			buf.writeUtf(name);
			buf.writeVarInt(time);
			buf.writeFloat(value);
			buf.writeUtf(easing == null ? "" : easing);
		});
	}

	/** Records a live expression swap ({@code setParamExpr}) into the active replay. */
	public static void recordSetExpr(final Identifier effectId, final String name, final @Nullable String exprSource) {
		recordEdit(effectId, ACTION_SET_EXPR, buf -> {
			buf.writeUtf(name);
			buf.writeUtf(exprSource == null ? "" : exprSource);
		});
	}

	/**
	 * Queues one live-edit action into the active replay, if one is running.
	 */
	private static void recordEdit(final Identifier effectId, final int sentinel, final Consumer<RegistryFriendlyByteBuf> payload) {
		if (!enabled) {
			return;
		}
		try {
			Minecraft.getInstance().execute(() -> {
				try {
					Object recorder = recorderField.get(null);
					if (recorder == null || !((Boolean) readyToWriteMethod.invoke(recorder))) {
						return;
					}
					submitCustomTaskMethod.invoke(recorder, (Consumer<Object>) writer -> {
						try {
							writeEdit(writer, effectId, sentinel, payload);
						} catch (Throwable t) {
							LOGGER.warn("Failed to write VFX edit '{}' into Flashback replay", effectId, t);
						}
					});
				} catch (Throwable t) {
					LOGGER.warn("Failed to record VFX edit '{}' into Flashback replay", effectId, t);
				}
			});
		} catch (Throwable t) {
			LOGGER.warn("Failed to queue VFX edit '{}' for Flashback replay recording", effectId, t);
		}
	}

	/**
	 * Writes one live-edit action: the effect id, the sentinel and whatever the payload writes.
	 */
	private static void writeEdit(final Object writer, final Identifier effectId, final int sentinel, final Consumer<RegistryFriendlyByteBuf> payload) throws Exception {
		boolean started = false;
		try {
			startActionMethod.invoke(writer, action);
			started = true;
			RegistryFriendlyByteBuf buf = (RegistryFriendlyByteBuf) friendlyByteBufMethod.invoke(writer);
			buf.writeIdentifier(effectId);
			buf.writeVarInt(sentinel);
			payload.accept(buf);
		} finally {
			if (started) {
				finishActionMethod.invoke(writer, action);
			}
		}
	}

	/**
	 * Records a server-triggered effect stop into the active Flashback replay, so a stop issued
	 * mid-event also replays. Encoded as the trigger action with the {@code -2} duration sentinel.
	 */
	public static void recordStop(final Identifier effectId) {
		if (!enabled) {
			return;
		}
		try {
			Minecraft.getInstance().execute(() -> {
				try {
					Object recorder = recorderField.get(null);
					if (recorder == null || !((Boolean) readyToWriteMethod.invoke(recorder))) {
						return;
					}
					submitCustomTaskMethod.invoke(recorder, (Consumer<Object>) writer -> {
						try {
							boolean started = false;
							try {
								startActionMethod.invoke(writer, action);
								started = true;
								RegistryFriendlyByteBuf buf = (RegistryFriendlyByteBuf) friendlyByteBufMethod.invoke(writer);
								buf.writeIdentifier(effectId);
								buf.writeVarInt(ACTION_STOP);
							} finally {
								if (started) {
									finishActionMethod.invoke(writer, action);
								}
							}
						} catch (Throwable t) {
							LOGGER.warn("Failed to write VFX stop '{}' into Flashback replay", effectId, t);
						}
					});
				} catch (Throwable t) {
					LOGGER.warn("Failed to record VFX stop '{}' into Flashback replay", effectId, t);
				}
			});
		} catch (Throwable t) {
			LOGGER.warn("Failed to queue VFX stop '{}' for Flashback replay recording", effectId, t);
		}
	}

	/**
	 * Writes one replay action via the {@code ReplayWriter} handed to us by Flashback's recorder.
	 */
	private static void writeAction(final Object writer, final Identifier effectId, final int durationTicks, final Map<String, Float> params, final @Nullable EasingType easing, final @Nullable Vec3 position, final List<UUID> entityUuids) throws Exception {
		boolean started = false;
		try {
			startActionMethod.invoke(writer, action);
			started = true;
			RegistryFriendlyByteBuf buf = (RegistryFriendlyByteBuf) friendlyByteBufMethod.invoke(writer);
			buf.writeIdentifier(effectId);
			buf.writeVarInt(durationTicks);
			// A blank easing name means "no override" (the playback uses the definition default).
			buf.writeUtf(easing == null ? "" : easing.name());
			buf.writeVarInt(params.size());
			for (Map.Entry<String, Float> entry : params.entrySet()) {
				buf.writeUtf(entry.getKey());
				buf.writeFloat(entry.getValue());
			}
			// Optional trailing anchor: absent in recordings written by an older build, which the
			// reader tolerates (it only reads this when bytes remain).
			buf.writeBoolean(position != null);
			if (position != null) {
				buf.writeDouble(position.x());
				buf.writeDouble(position.y());
				buf.writeDouble(position.z());
			}
			// Optional trailing entity targets, capped like the network payload so the action stays
			// bounded. Also absent in older recordings.
			int entityCount = Math.min(entityUuids.size(), VFXTriggerPayload.MAX_ENTITY_UUIDS);
			buf.writeVarInt(entityCount);
			for (int i = 0; i < entityCount; i++) {
				buf.writeUUID(entityUuids.get(i));
			}
		} finally {
			if (started) {
				finishActionMethod.invoke(writer, action);
			}
		}
	}

	/**
	 * Decodes a recorded play action and re-triggers the effect on the render thread. Called by
	 * Flashback on the replay server thread, hence the {@code execute} hop. A duration of
	 * {@code -2} is the stop sentinel: all instances of the effect are stopped instead.
	 */
	private static void handlePlayback(final RegistryFriendlyByteBuf buf) {
		Identifier effectId = buf.readIdentifier();
		if (effectId.equals(ACTION_DEFS_NAME)) {
			// The definitions snapshot travels through the same action, marked by a reserved id.
			handleDefinitions(buf);
			return;
		}
		int durationTicks = buf.readVarInt();
		// The tick this action was recorded at: read now, while still on the replay server thread.
		int triggerTick = currentActionTick();
		if (durationTicks == ACTION_STOP) {
			Minecraft.getInstance().execute(() -> VFXReplayController.get().onStop(effectId, triggerTick));
			return;
		}
		if (durationTicks == ACTION_SET_PARAM) {
			String name = buf.readUtf();
			float value = buf.readFloat();
			Minecraft.getInstance().execute(() -> VFXReplayController.get().onSetParam(effectId, name, value, triggerTick));
			return;
		}
		if (durationTicks == ACTION_KEYFRAME) {
			String name = buf.readUtf();
			int time = buf.readVarInt();
			float value = buf.readFloat();
			String easingName = buf.readUtf();
			EasingFunction keyframeEasing = EasingFunction.fromString(easingName);
			Minecraft.getInstance().execute(() -> VFXReplayController.get().onKeyframe(effectId, name, time, value, keyframeEasing, triggerTick));
			return;
		}
		if (durationTicks == ACTION_SET_EXPR) {
			String name = buf.readUtf();
			String exprSource = buf.readUtf();
			Minecraft.getInstance().execute(() -> VFXReplayController.get().onSetExpr(effectId, name, exprSource.isBlank() ? null : exprSource, triggerTick));
			return;
		}
		String easingName = buf.readUtf();
		int paramCount = buf.readVarInt();
		if (paramCount < 0 || paramCount > MAX_PARAMS) {
			// Corrupt or foreign payload: refuse to allocate an unbounded map.
			throw new IllegalStateException("Invalid VFX action param count: " + paramCount);
		}
		Map<String, Float> params = new HashMap<>(paramCount);
		for (int i = 0; i < paramCount; i++) {
			params.put(buf.readUtf(), buf.readFloat());
		}
		// A blank easing name means "no override": pass null so the effect manager applies the
		// definition's default easing instead of falling back to LINEAR.
		EasingType easing = easingName.isBlank() ? null : EasingType.fromString(easingName);
		// Optional trailing anchor. The flag is read only when bytes remain, so recordings written
		// before the anchor existed still decode; this relies on Flashback handing us a buffer that
		// holds exactly this action's payload (no trailing framing).
		Vec3 anchor = null;
		if (buf.isReadable() && buf.readBoolean()) {
			anchor = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
		}
		// Optional trailing entity targets, after the anchor. Absent in older recordings, where no
		// bytes remain once the anchor block has been read.
		List<UUID> entityUuids = List.of();
		if (buf.isReadable()) {
			int entityCount = buf.readVarInt();
			if (entityCount < 0 || entityCount > VFXTriggerPayload.MAX_ENTITY_UUIDS) {
				throw new IllegalStateException("Invalid VFX action entity count: " + entityCount);
			}
			if (entityCount > 0) {
				List<UUID> targets = new ArrayList<>(entityCount);
				for (int i = 0; i < entityCount; i++) {
					targets.add(buf.readUUID());
				}
				entityUuids = List.copyOf(targets);
			}
		}
		final Vec3 anchorPos = anchor;
		final List<UUID> targetUuids = entityUuids;
		final EasingFunction easingFunction = easing == null ? null : EasingFunction.builtIn(easing);
		final int recordedTick = triggerTick;
		Minecraft.getInstance().execute(() ->
			VFXReplayController.get().onPlay(effectId, durationTicks, params, easingFunction, anchorPos, targetUuids, recordedTick)
		);
	}

	/**
	 * Decodes a recorded definitions action and applies the datapack definitions/curves on the
	 * render thread. Written at recording start, so datapack-defined effect ids resolve during
	 * playback even when the server-side mod has been removed.
	 */
	private static void handleDefinitions(final RegistryFriendlyByteBuf buf) {
		int defCount = buf.readVarInt();
		if (defCount < 0 || defCount > 4096) {
			throw new IllegalStateException("Invalid VFX definitions count: " + defCount);
		}
		Map<Identifier, String> definitions = new HashMap<>(defCount);
		for (int i = 0; i < defCount; i++) {
			definitions.put(buf.readIdentifier(), buf.readUtf());
		}
		int curveCount = buf.readVarInt();
		if (curveCount < 0 || curveCount > 4096) {
			throw new IllegalStateException("Invalid VFX curves count: " + curveCount);
		}
		Map<Identifier, String> curves = new HashMap<>(curveCount);
		for (int i = 0; i < curveCount; i++) {
			curves.put(buf.readIdentifier(), buf.readUtf());
		}
		Minecraft.getInstance().execute(() -> {
			VFXDefinitionManager.get().applySynced(definitions);
			VFXCurveManager.get().applySynced(curves);
			LOGGER.debug("Replay applied {} VFX definitions and {} curves", definitions.size(), curves.size());
		});
	}

	/**
	 * {@link InvocationHandler} for the {@code com.moulberry.flashback.action.Action} proxy:
	 * dispatches {@code name()} and {@code handle(ReplayServer, RegistryFriendlyByteBuf)}. One
	 * action carries every payload - plays, stops, live edits and the definitions snapshot.
	 */
	private static final class ActionHandler implements InvocationHandler {
		@Override
		public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
			String name = method.getName();
			if (method.getDeclaringClass() == Object.class) {
				return switch (name) {
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					case "toString" -> "vfxweaver Flashback action " + ACTION_NAME;
					default -> throw new UnsupportedOperationException("Unsupported Object method: " + method);
				};
			}
			if ("name".equals(name)) {
				return ACTION_NAME;
			}
			if ("handle".equals(name)) {
				handlePlayback((RegistryFriendlyByteBuf) args[1]);
				return null;
			}
			throw new UnsupportedOperationException("Unsupported Action method: " + method);
		}
	}
}
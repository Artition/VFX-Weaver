package dev.vfxweaver.client.flashback;

import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.effect.EasingFunction;
import dev.vfxweaver.effect.EasingType;
import dev.vfxweaver.effect.VFXActiveEffect;
import dev.vfxweaver.effect.VFXCurveManager;
import dev.vfxweaver.effect.VFXEffectType;
import dev.vfxweaver.effect.VFXTimeline;
import dev.vfxweaver.resource.VFXDefinitionManager;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
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
 * registered actions' {@code handle} decodes the payloads and re-triggers everything through
 * {@link VFXEffectManager} on the render thread (the handler runs on the replay server thread).
 *
 * <p>Both client-local plays and server-triggered ones are recorded - Flashback does not replay
 * unknown custom payload packets on its own, so without this the server-triggered effects would be
 * missing from replays entirely (especially after the server-side mod has been removed).
 */
public final class FlashbackCompat {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/flashback");
	private static final Identifier ACTION_NAME = Identifier.fromNamespaceAndPath("vfxweaver", "effect_trigger");
	private static final Identifier ACTION_DEFS_NAME = Identifier.fromNamespaceAndPath("vfxweaver", "definitions");
	/** Safety cap on the number of params decoded from a replay file. */
	private static final int MAX_PARAMS = 32;
	/** Safety cap on the total characters of synced definition/curve JSON written into a replay. */
	private static final int MAX_DEFS_CHARS = 2_000_000;

	private static boolean enabled;
	private static @Nullable Class<?> actionClass;
	private static @Nullable Class<?> registryClass;
	private static @Nullable Class<?> recorderClass;
	private static @Nullable Class<?> replayWriterClass;
	private static @Nullable Class<?> flashbackClass;
	private static @Nullable Object action;
	private static @Nullable Object defsAction;
	// Reflective handles resolved once during init to avoid per-call getMethod/getField lookups.
	private static @Nullable Field recorderField;
	private static @Nullable Method readyToWriteMethod;
	private static @Nullable Method submitCustomTaskMethod;
	private static @Nullable Method startActionMethod;
	private static @Nullable Method finishActionMethod;
	private static @Nullable Method friendlyByteBufMethod;
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
		if (enabled || !FabricLoader.getInstance().isModLoaded("flashback")) {
			return;
		}
		try {
			actionClass = Class.forName("com.moulberry.flashback.action.Action");
			registryClass = Class.forName("com.moulberry.flashback.action.ActionRegistry");
			recorderClass = Class.forName("com.moulberry.flashback.record.Recorder");
			replayWriterClass = Class.forName("com.moulberry.flashback.io.ReplayWriter");
			flashbackClass = Class.forName("com.moulberry.flashback.Flashback");
			action = Proxy.newProxyInstance(actionClass.getClassLoader(), new Class<?>[]{actionClass}, new ActionHandler(false));
			defsAction = Proxy.newProxyInstance(actionClass.getClassLoader(), new Class<?>[]{actionClass}, new ActionHandler(true));
			Method register = registryClass.getMethod("register", actionClass);
			register.invoke(null, action);
			register.invoke(null, defsAction);
			recorderField = flashbackClass.getField("RECORDER");
			readyToWriteMethod = recorderClass.getMethod("readyToWrite");
			submitCustomTaskMethod = recorderClass.getMethod("submitCustomTask", Consumer.class);
			startActionMethod = replayWriterClass.getMethod("startAction", actionClass);
			finishActionMethod = replayWriterClass.getMethod("finishAction", actionClass);
			friendlyByteBufMethod = replayWriterClass.getMethod("friendlyByteBuf");
			ClientTickEvents.END_CLIENT_TICK.register(tick -> detectRecordingStart());
			enabled = true;
			LOGGER.info("Flashback compatibility enabled: VFX effects are recorded into replays");
		} catch (Throwable t) {
			enabled = false;
			LOGGER.warn("Failed to initialize Flashback compatibility; effects won't be recorded into replays", t);
		}
	}

	/**
	 * Watches {@code Flashback.RECORDER} each tick. When a recording just started and became ready
	 * (its initial world snapshot has been written), snapshots every effect that is already running
	 * so it appears in the replay from the first tick instead of being lost.
	 */
	private static void detectRecordingStart() {
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
						startActionMethod.invoke(writer, defsAction);
						started = true;
						RegistryFriendlyByteBuf buf = (RegistryFriendlyByteBuf) friendlyByteBufMethod.invoke(writer);
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
							finishActionMethod.invoke(writer, defsAction);
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
	 * effect's current parameter values so it replays in the same state. Persistent and looping
	 * effects are skipped — with no recorded stop event they would loop forever during playback.
	 */
	private static void writeActiveEffectsSnapshot(final Object recorder) {
		try {
			for (VFXActiveEffect effect : VFXEffectManager.get().getActive()) {
				Identifier id = effect.getId();
				VFXTimeline timeline = effect.getTimeline();
				// Without a recorded stop event a looping/persistent effect would loop forever
				// during playback, so only finite-duration effects are snapshotted.
				if (effect.getType() == VFXEffectType.COLLECTION || effect.getType() == VFXEffectType.CAMERA_SHAKE
					|| effect.isLooping() || timeline.getDuration() >= Integer.MAX_VALUE - 1) {
					continue;
				}
				int duration = Math.max(1, (int) Math.ceil(timeline.getDuration() - timeline.getElapsed()));
				Map<String, Float> params = snapshotParams(timeline);
				submitCustomTaskMethod.invoke(recorder, (Consumer<Object>) writer -> {
					try {
						writeAction(writer, id, duration, params, EasingType.LINEAR, null);
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
	 * expressions and live overrides) into a constant map, preserving the effect's on-screen state.
	 */
	private static Map<String, Float> snapshotParams(final VFXTimeline timeline) {
		Map<String, Float> params = new LinkedHashMap<>();
		Map<String, Float> deferred = new LinkedHashMap<>();
		timeline.getValues().keySet().forEach(name -> deferred.put(name, timeline.getValue(name, Float.NaN)));
		timeline.getBindings().keySet().forEach(name -> deferred.put(name, timeline.getValue(name, Float.NaN)));
		timeline.getMultipliers().keySet().forEach(name -> deferred.put(name, timeline.getValue(name, Float.NaN)));
		timeline.getExpressions().keySet().forEach(name -> deferred.put(name, timeline.getValue(name, Float.NaN)));
		timeline.getOverrideNames().forEach(name -> deferred.put(name, timeline.getValue(name, Float.NaN)));
		for (Map.Entry<String, Float> entry : deferred.entrySet()) {
			if (!Float.isNaN(entry.getValue())) {
				params.put(entry.getKey(), entry.getValue());
			}
		}
		return params;
	}

	/**
	 * Records a client-local effect play into the active Flashback replay, if one is running.
	 * Persistent (negative duration) effects are skipped: without a recorded stop event they would
	 * loop forever during playback. The payload is written on the render thread, mirroring the
	 * {@code effectId, durationTicks, easing, params} order of the network trigger.
	 */
	public static void recordPlay(final Identifier effectId, final int durationTicks, final Map<String, Float> params, final EasingType easing) {
		recordPlay(effectId, durationTicks, params, easing, null);
	}

	/**
	 * Same as {@link #recordPlay(Identifier, int, Map, EasingType)} but anchored: a non-null
	 * {@code position} is written into the replay action, so a replay re-creates the effect at the
	 * same world point. The anchor block is optional and trailing, so replays recorded by an older
	 * build (which never wrote it) still decode.
	 */
	public static void recordPlay(final Identifier effectId, final int durationTicks, final Map<String, Float> params, final @Nullable EasingType easing, final @Nullable Vec3 position) {
		if (!enabled || durationTicks < 0) {
			return;
		}
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
							writeAction(writer, effectId, durationTicks, params, easing, position);
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
	 * replay unknown custom payload packets on its own). Persistent (negative duration) effects
	 * are skipped: without a recorded stop event they would loop forever during playback.
	 */
	public static void recordServerPlay(final Identifier effectId, final int durationTicks, final Map<String, Float> params, final String easing) {
		recordPlay(effectId, durationTicks, params, EasingType.fromString(easing));
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
	private static void writeAction(final Object writer, final Identifier effectId, final int durationTicks, final Map<String, Float> params, final @Nullable EasingType easing, final @Nullable Vec3 position) throws Exception {
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
		int durationTicks = buf.readVarInt();
		if (durationTicks == ACTION_STOP) {
			Minecraft.getInstance().execute(() -> VFXEffectManager.get().stop(effectId));
			return;
		}
		if (durationTicks == ACTION_SET_PARAM) {
			String name = buf.readUtf();
			float value = buf.readFloat();
			Minecraft.getInstance().execute(() -> VFXEffectManager.get().setParam(effectId, name, value));
			return;
		}
		if (durationTicks == ACTION_KEYFRAME) {
			String name = buf.readUtf();
			int time = buf.readVarInt();
			float value = buf.readFloat();
			String easingName = buf.readUtf();
			EasingFunction keyframeEasing = EasingFunction.fromString(easingName);
			Minecraft.getInstance().execute(() -> VFXEffectManager.get().setKeyframe(effectId, name, time, value, keyframeEasing));
			return;
		}
		if (durationTicks == ACTION_SET_EXPR) {
			String name = buf.readUtf();
			String exprSource = buf.readUtf();
			Minecraft.getInstance().execute(() -> VFXEffectManager.get().setExpression(effectId, name, exprSource.isBlank() ? null : exprSource));
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
		final Vec3 anchorPos = anchor;
		final EasingFunction easingFunction = easing == null ? null : EasingFunction.builtIn(easing);
		Minecraft.getInstance().execute(() ->
			VFXEffectManager.get().play(effectId, durationTicks, 0L, anchorPos, params, easingFunction)
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
	 * dispatches {@code name()} and {@code handle(ReplayServer, RegistryFriendlyByteBuf)}.
	 * The {@code definitions} flavour carries the synced datapack content instead of a play.
	 */
	private static final class ActionHandler implements InvocationHandler {
		private final boolean definitions;

		ActionHandler(final boolean definitions) {
			this.definitions = definitions;
		}

		@Override
		public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
			String name = method.getName();
			if (method.getDeclaringClass() == Object.class) {
				return switch (name) {
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					case "toString" -> "vfxweaver Flashback action " + (this.definitions ? ACTION_DEFS_NAME : ACTION_NAME);
					default -> throw new UnsupportedOperationException("Unsupported Object method: " + method);
				};
			}
			if ("name".equals(name)) {
				return this.definitions ? ACTION_DEFS_NAME : ACTION_NAME;
			}
			if ("handle".equals(name)) {
				RegistryFriendlyByteBuf buf = (RegistryFriendlyByteBuf) args[1];
				if (this.definitions) {
					handleDefinitions(buf);
				} else {
					handlePlayback(buf);
				}
				return null;
			}
			throw new UnsupportedOperationException("Unsupported Action method: " + method);
		}
	}
}
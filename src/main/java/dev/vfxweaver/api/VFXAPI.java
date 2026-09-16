package dev.vfxweaver.api;

import dev.vfxweaver.effect.EasingFunction;
import dev.vfxweaver.effect.EasingType;
import dev.vfxweaver.effect.MathExpression;
import dev.vfxweaver.effect.VFXDefinition;
import dev.vfxweaver.effect.VFXScoreboardSync;
import dev.vfxweaver.effect.VFXServerEffects;
import dev.vfxweaver.network.VFXTriggerPayload;
import dev.vfxweaver.resource.VFXDefinitionManager;
import dev.vfxweaver.util.VFXLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Public API of the mod. Server-side code (other mods, datapack functions, commands) triggers
 * effects for players with {@link #sendEffect(ServerPlayer, Identifier, Map, EasingType)};
 * client-side code plays effects directly with {@link #playEffect(Identifier, int, Map, EasingType)}.
 */
public final class VFXAPI {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/api");

	private static @Nullable VFXLocalDispatcher localDispatcher;

	private VFXAPI() {
	}

	/**
	 * Called by the client entrypoint to register local playback.
	 */
	public static void setLocalDispatcher(final VFXLocalDispatcher dispatcher) {
		localDispatcher = dispatcher;
	}

	/**
	 * Plays an effect locally (client-side only). Returns {@code false} when running without a
	 * client (e.g. on a dedicated server), where the networked variant must be used instead.
	 *
	 * @param effectId      effect id (built-in or datapack-defined)
	 * @param durationTicks duration in ticks
	 * @param params        parameter overrides (empty for defaults)
	 * @param easing        easing curve (may be null for the definition default)
	 */
	public static boolean playEffect(final Identifier effectId, final int durationTicks, final Map<String, Float> params, final @Nullable EasingType easing) {
		if (localDispatcher == null) {
			VFXLog.warnOnce(LOGGER, "api:no-client", "playEffect({}) called without a client; use sendEffect() instead", effectId);
			return false;
		}
		localDispatcher.playEffect(effectId, durationTicks, params, easing);
		return true;
	}

	/**
	 * Plays an effect locally with linear easing.
	 */
	public static boolean playEffect(final Identifier effectId, final int durationTicks, final Map<String, Float> params) {
		return playEffect(effectId, durationTicks, params, EasingType.LINEAR);
	}

	/**
	 * Plays an effect locally and returns the id of the created instance, so a specific one of
	 * several concurrent instances can later be stopped with {@link #stopEffect(long)}.
	 * Returns {@code 0} when running without a client or when the effect was ignored.
	 *
	 * @param effectId      effect id (built-in or datapack-defined)
	 * @param durationTicks duration in ticks
	 * @param params        parameter overrides (empty for defaults)
	 * @param easing        easing curve (may be null for the definition default)
	 * @return the instance id, or {@code 0} on failure
	 */
	public static long playEffectId(final Identifier effectId, final int durationTicks, final Map<String, Float> params, final @Nullable EasingType easing) {
		if (localDispatcher == null) {
			VFXLog.warnOnce(LOGGER, "api:no-client", "playEffectId({}) called without a client; use sendEffect() instead", effectId);
			return 0L;
		}
		return localDispatcher.playEffect(effectId, durationTicks, params, easing);
	}

	/**
	 * Plays an effect locally on this client, anchored to a world position.
	 *
	 * <p>The position replaces the definition's position slots and re-anchors its spatial world
	 * bindings ({@code screen_x}, {@code screen_y}, {@code proximity}, ...) to that point, so a
	 * screen-space effect such as {@code dent}, {@code shockwave} or {@code vortex} lands where the
	 * event happened. This is the client-side equivalent of {@code /vfx playat} - no packet is
	 * sent, so it works while playing on a server that does not have the mod.</p>
	 *
	 * @param effectId      effect id (built-in or datapack-defined)
	 * @param durationTicks duration in ticks
	 * @param position      world position to anchor the effect to (null leaves the definition's own positions)
	 * @param params        parameter overrides (empty for defaults)
	 * @param easing        easing curve (may be null for the definition default)
	 * @return {@code true} when the effect was started
	 */
	public static boolean playEffect(final Identifier effectId, final int durationTicks, final Vec3 position, final Map<String, Float> params, final @Nullable EasingType easing) {
		return playEffectId(effectId, durationTicks, position, params, easing) != 0L;
	}

	/**
	 * Plays an anchored effect locally with linear easing.
	 */
	public static boolean playEffect(final Identifier effectId, final int durationTicks, final Vec3 position, final Map<String, Float> params) {
		return playEffect(effectId, durationTicks, position, params, EasingType.LINEAR);
	}

	/**
	 * Plays an anchored effect locally and returns its instance id, so it can later be re-anchored
	 * with {@link #moveEffect(Identifier, long, Vec3)} or stopped with {@link #stopEffect(long)}.
	 *
	 * @return the instance id, or {@code 0} on failure
	 */
	public static long playEffectId(final Identifier effectId, final int durationTicks, final Vec3 position, final Map<String, Float> params, final @Nullable EasingType easing) {
		if (localDispatcher == null) {
			VFXLog.warnOnce(LOGGER, "api:no-client", "playEffectId({}) called without a client; use sendEffect() instead", effectId);
			return 0L;
		}
		return localDispatcher.playEffect(effectId, durationTicks, position, params, easing);
	}

	/**
	 * Plays an effect locally, anchored to a world position and to entities.
	 *
	 * <p>When {@code position} is null and the definition declares entity-anchored positions, the
	 * supplied UUIDs are zipped with them in declaration order, so the effect follows those
	 * entities (the same mechanism the server uses after resolving an {@code entity_selector}).
	 * The caller resolves the entities itself - datapack selectors are server-side only. A non-null
	 * {@code position} wins over the definition's anchors.</p>
	 *
	 * @param effectId      effect id (built-in or datapack-defined)
	 * @param durationTicks duration in ticks
	 * @param position      world position to anchor the effect to (may be null)
	 * @param entityUuids   UUIDs for the definition's entity anchors, in declaration order
	 * @param params        parameter overrides (empty for defaults)
	 * @param easing        easing curve (may be null for the definition default)
	 * @return {@code true} when the effect was started
	 */
	public static boolean playEffect(final Identifier effectId, final int durationTicks, final @Nullable Vec3 position, final List<UUID> entityUuids, final Map<String, Float> params, final @Nullable EasingType easing) {
		return playEffectId(effectId, durationTicks, position, entityUuids, params, easing) != 0L;
	}

	/**
	 * Plays an entity-anchored effect locally and returns its instance id.
	 *
	 * @return the instance id, or {@code 0} on failure
	 */
	public static long playEffectId(final Identifier effectId, final int durationTicks, final @Nullable Vec3 position, final List<UUID> entityUuids, final Map<String, Float> params, final @Nullable EasingType easing) {
		if (localDispatcher == null) {
			VFXLog.warnOnce(LOGGER, "api:no-client", "playEffectId({}) called without a client; use sendEffect() instead", effectId);
			return 0L;
		}
		return localDispatcher.playEffect(effectId, durationTicks, position, entityUuids, params, easing);
	}

	/**
	 * Re-anchors a running effect instance to a new world position locally (no packet). Calling it
	 * every tick makes an anchored effect follow a moving point or entity.
	 *
	 * @param effectId   the effect id the instance belongs to
	 * @param instanceId the instance id returned by {@link #playEffectId(Identifier, int, Vec3, Map, EasingType)}
	 * @param worldPos   the new world position
	 * @return {@code true} when the request was applied (called on the render thread) or queued for
	 *         it; a queued request that turns out to reference an unknown instance fails silently
	 */
	public static boolean moveEffect(final Identifier effectId, final long instanceId, final Vec3 worldPos) {
		return localDispatcher != null && localDispatcher.moveEffect(effectId, instanceId, worldPos);
	}

	/**
	 * Stops all running instances of an effect locally (client-side only).
	 */
	public static boolean stopEffect(final Identifier effectId) {
		if (localDispatcher == null) {
			return false;
		}
		localDispatcher.stopEffect(effectId);
		return true;
	}

	/**
	 * Stops one specific instance of an effect locally (client-side only).
	 *
	 * @param instanceId the instance id returned from {@link #playEffectId(Identifier, int, Map, EasingType)}
	 * @return {@code false} when running without a client or when no such instance exists
	 */
	public static boolean stopEffect(final long instanceId) {
		if (localDispatcher == null) {
			return false;
		}
		localDispatcher.stopEffect(instanceId);
		return true;
	}

	/**
	 * Stops all running effects locally (client-side only).
	 */
	public static boolean stopAllEffects() {
		if (localDispatcher == null) {
			return false;
		}
		localDispatcher.stopAllEffects();
		return true;
	}

	/**
	 * Triggers an effect for a player over the network. Resolves the definition to merge its
	 * default constant parameters with the given overrides and to pick the default duration and
	 * easing when those are not supplied.
	 *
	 * @param player    the receiving player
	 * @param effectId  effect id (must be registered)
	 * @param overrides parameter overrides (may be empty)
	 * @param easing    easing curve (may be null to use the definition default)
	 * @return {@code true} when the effect was known and sent
	 */
	public static boolean sendEffect(final ServerPlayer player, final Identifier effectId, final Map<String, Float> overrides, final @Nullable EasingType easing) {
		return sendEffect(player, effectId, 0L, null, List.of(), overrides, easing);
	}

	/**
	 * Triggers an effect for a player over the network with an explicit world position. The
	 * client re-anchors spatial world bindings ({@code screen_x/y}, {@code proximity}) to that
	 * point and uses it for the effect's world positions — no {@code pos_x/y/z} override hacks.
	 *
	 * @param player    the receiving player
	 * @param effectId  effect id (must be registered)
	 * @param worldPos  world position to anchor the effect to
	 * @param overrides parameter overrides (may be empty)
	 * @param easing    easing curve (may be null to use the definition default)
	 * @return {@code true} when the effect was known and sent
	 */
	public static boolean sendEffect(final ServerPlayer player, final Identifier effectId, final Vec3 worldPos, final Map<String, Float> overrides, final @Nullable EasingType easing) {
		return sendEffect(player, effectId, 0L, worldPos, List.of(), overrides, easing);
	}

	/**
	 * Triggers an effect for a player over the network with an explicit instance id, world
	 * position and entity UUID targets (for entity tint/outline effects). The instance id lets a
	 * later {@link #sendStop(ServerPlayer, Identifier, long)} target this exact instance instead
	 * of every instance of the effect. When {@code instanceId} is {@code 0}, the client allocates
	 * one on play.
	 *
	 * @param player     the receiving player
	 * @param effectId   effect id (must be registered)
	 * @param instanceId instance id to assign (0 = client allocates)
	 * @param worldPos   world position to anchor the effect to (may be null)
	 * @param entityUuids entity UUIDs this effect applies to (for entity tint/outline)
	 * @param overrides  parameter overrides (may be empty)
	 * @param easing     easing curve (may be null to use the definition default)
	 * @return {@code true} when the effect was known and sent
	 */
	public static boolean sendEffect(
		final ServerPlayer player,
		final Identifier effectId,
		final long instanceId,
		final @Nullable Vec3 worldPos,
		final List<UUID> entityUuids,
		final Map<String, Float> overrides,
		final @Nullable EasingType easing
	) {
		VFXDefinition definition = VFXDefinitionManager.get().get(effectId);
		if (definition == null) {
			VFXLog.warnOnce(LOGGER, "api:unknown-effect:" + effectId, "sendEffect({}) failed: unknown effect", effectId);
			return false;
		}
		Map<String, Float> params = new HashMap<>();
		for (Map.Entry<String, VFXDefinition.ParamSpec> entry : definition.getParams().entrySet()) {
			VFXDefinition.ParamSpec spec = entry.getValue();
			if (!spec.animated() && spec.keyframes().isEmpty() && spec.bound() == null && spec.multiply() == null && spec.exprSource() == null) {
				params.put(entry.getKey(), spec.constant());
			}
		}
		params.putAll(overrides);
		int duration = definition.isPersistent() ? -1 : definition.getDefaultDuration();
		EasingFunction effectiveEasing = easing != null ? EasingFunction.builtIn(easing) : definition.getDefaultEasing();
		// An inline curve cannot be reconstructed from its name over the network; send a blank name
		// so the client falls back to its own definition default (which carries the same curve).
		String wireEasing = effectiveEasing.isInline() ? "" : effectiveEasing.name();
		ServerPlayNetworking.send(player, VFXTriggerPayload.play(effectId, duration, instanceId, worldPos, entityUuids, params, wireEasing));
		VFXServerEffects.get().record(player, effectId, duration, instanceId, worldPos, entityUuids, params, wireEasing);
		VFXScoreboardSync.onEffectPlayed(player, definition, duration);
		return true;
	}

	/**
	 * Triggers an effect for a player with the given explicit duration and easing, without
	 * consulting the definition registry.
	 */
	public static void sendEffect(
		final ServerPlayer player,
		final Identifier effectId,
		final int durationTicks,
		final Map<String, Float> params,
		final EasingType easing
	) {
		ServerPlayNetworking.send(player, VFXTriggerPayload.play(effectId, durationTicks, params, easing));
		VFXServerEffects.get().record(player, effectId, durationTicks, 0L, null, List.of(), params, easing.name());
	}

	/**
	 * Tells a player's client to stop all running instances of an effect.
	 */
	public static void sendStop(final ServerPlayer player, final Identifier effectId) {
		ServerPlayNetworking.send(player, VFXTriggerPayload.stop(effectId));
		VFXServerEffects.get().stop(player, effectId);
		VFXDefinition definition = VFXDefinitionManager.get().get(effectId);
		if (definition != null) {
			VFXScoreboardSync.onEffectStopped(player, definition);
		}
	}

	/**
	 * Tells a player's client to stop one specific instance of an effect. Requires the instance
	 * id that was sent with {@link #sendEffect(ServerPlayer, Identifier, long, Vec3, Map, EasingType)}
	 * when the effect was triggered.
	 *
	 * @param player     the receiving player
	 * @param effectId   effect id
	 * @param instanceId the instance id to stop
	 */
	public static void sendStop(final ServerPlayer player, final Identifier effectId, final long instanceId) {
		ServerPlayNetworking.send(player, VFXTriggerPayload.stop(effectId, instanceId));
		VFXServerEffects.get().stop(player, effectId, instanceId);
	}

	/**
	 * Live-overrides a parameter of a running effect on the player's client, without
	 * restarting its timeline. Ignored (with a client-side log warning) when the effect is
	 * not currently running.
	 *
	 * @param player  the receiving player
	 * @param effectId effect id
	 * @param param   parameter name
	 * @param value   the new constant value
	 */
	public static void sendSetParam(final ServerPlayer player, final Identifier effectId, final String param, final float value) {
		ServerPlayNetworking.send(player, VFXTriggerPayload.setParam(effectId, param, value));
	}

	/**
	 * Live-replaces a parameter of a running effect on the player's client with a compiled math
	 * expression, without restarting its timeline. Invalid expressions fall back to a constant
	 * {@code 0}; ignored (with a client-side log warning) when the effect is not currently
	 * running.
	 *
	 * @param player     the receiving player
	 * @param effectId   effect id
	 * @param param      parameter name
	 * @param exprSource expression source (variables {@code t}/{@code x}/{@code y}/{@code z},
	 *                   functions like {@code sin}/{@code noise}; see {@link MathExpression})
	 */
	public static void sendSetParamExpr(final ServerPlayer player, final Identifier effectId, final String param, final String exprSource) {
		ServerPlayNetworking.send(player, VFXTriggerPayload.setExpr(effectId, param, exprSource));
	}

	/**
	 * Moves a running effect instance on the player's client to a new world position,
	 * re-anchoring its spatial world bindings. Ignored (with a client-side log warning) when no
	 * such instance is running.
	 *
	 * @param player     the receiving player
	 * @param effectId   effect id
	 * @param instanceId the instance id to move
	 * @param worldPos   the new world position
	 */
	public static void sendMove(final ServerPlayer player, final Identifier effectId, final long instanceId, final Vec3 worldPos) {
		ServerPlayNetworking.send(player, VFXTriggerPayload.move(effectId, instanceId, worldPos));
	}

	/**
	 * Adds or replaces a keyframe of a parameter on a running effect on the player's client.
	 * Ignored (with a client-side log warning) when the effect is not currently running.
	 *
	 * @param player  the receiving player
	 * @param effectId effect id
	 * @param param   parameter name
	 * @param time    keyframe time in ticks from the effect start
	 * @param value   keyframe value
	 * @param easing  easing curve towards the next keyframe
	 */
	public static void sendKeyframe(final ServerPlayer player, final Identifier effectId, final String param, final int time, final float value, final EasingType easing) {
		ServerPlayNetworking.send(player, VFXTriggerPayload.keyframe(effectId, param, time, value, easing));
		VFXServerEffects.get().recordKeyframe(player, effectId, param, time, value, easing.name());
	}

	/**
	 * Adds or replaces a keyframe of a parameter on a running effect on the player's client, with
	 * a custom easing curve (named datapack curve or inline name).
	 *
	 * @param player  the receiving player
	 * @param effectId effect id
	 * @param param   parameter name
	 * @param time    keyframe time in ticks from the effect start
	 * @param value   keyframe value
	 * @param easing  easing curve name (built-in or custom datapack curve)
	 */
	public static void sendKeyframe(final ServerPlayer player, final Identifier effectId, final String param, final int time, final float value, final String easing) {
		ServerPlayNetworking.send(player, VFXTriggerPayload.keyframe(effectId, param, time, value, easing));
		VFXServerEffects.get().recordKeyframe(player, effectId, param, time, value, easing);
	}

	/**
	 * Fluent request for playing or sending an effect without growing positional overloads.
	 * Example:
	 * <pre>{@code
	 * VFXAPI.sendEffect(player, id, EffectRequest.of()
	 *     .duration(100)
	 *     .param("alpha", 0.8F)
	 *     .target(entityUuid)
	 *     .easing(EasingType.EASE_OUT));
	 * }</pre>
	 */
	public static final class EffectRequest {
		private int durationTicks;
		private final Map<String, Float> params = new HashMap<>();
		private final List<UUID> entityUuids = new ArrayList<>();
		private @Nullable EasingType easing;

		private EffectRequest() {
		}

		public static EffectRequest of() {
			return new EffectRequest();
		}

		/** Duration in ticks; {@code 0} uses the definition default (the default state). */
		public EffectRequest duration(final int ticks) {
			this.durationTicks = ticks;
			return this;
		}

		public EffectRequest param(final String name, final float value) {
			this.params.put(name, value);
			return this;
		}

		public EffectRequest params(final Map<String, Float> values) {
			this.params.putAll(values);
			return this;
		}

		public EffectRequest easing(final @Nullable EasingType easing) {
			this.easing = easing;
			return this;
		}

		/** Adds an entity target (for entity tint/outline effects). */
		public EffectRequest target(final UUID uuid) {
			this.entityUuids.add(uuid);
			return this;
		}
	}

	/**
	 * Plays an effect locally with a fluent request. Duration, params, easing and entity targets
	 * apply; use {@code playEffectId(Identifier, int, Vec3, List, Map, EasingType)} to anchor a
	 * local play to a world position as well.
	 */
	public static boolean playEffect(final Identifier effectId, final EffectRequest request) {
		if (request.entityUuids.isEmpty()) {
			return playEffect(effectId, request.durationTicks, request.params, request.easing);
		}
		return playEffect(effectId, request.durationTicks, null, request.entityUuids, request.params, request.easing);
	}

	/**
	 * Triggers an effect for a player with a fluent request (entity targets included).
	 */
	public static boolean sendEffect(final ServerPlayer player, final Identifier effectId, final EffectRequest request) {
		return sendEffect(player, effectId, 0L, null, request.entityUuids, request.params, request.easing);
	}
}

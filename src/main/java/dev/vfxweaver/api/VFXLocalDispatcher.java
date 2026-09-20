package dev.vfxweaver.api;

import dev.vfxweaver.effect.EasingType;
import dev.vfxweaver.effect.VFXBlockParticleSpec;
import dev.vfxweaver.effect.VFXSparkSpec;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Bridge implemented by the client to play effects locally (no server round-trip).
 * The client registers an instance through {@link VFXAPI#setLocalDispatcher(VFXLocalDispatcher)}.
 */
public interface VFXLocalDispatcher {
	/**
	 * Plays an effect immediately on this client and returns the id of the created instance
	 * (0 when the effect was ignored, e.g. because it is unknown).
	 *
	 * @param effectId      effect id (built-in or datapack-defined)
	 * @param durationTicks duration in ticks
	 * @param params        parameter overrides (empty for defaults)
	 * @param easing        easing curve (may be null for the definition default)
	 * @return the instance id, or {@code 0} when the effect was not started
	 */
	long playEffect(Identifier effectId, int durationTicks, Map<String, Float> params, EasingType easing);

	/**
	 * Plays an effect locally, anchored to a world position.
	 *
	 * <p>The position replaces the definition's own position slots and re-anchors its spatial
	 * world bindings ({@code screen_x}, {@code screen_y}, {@code proximity}, ...) to that point -
	 * the same behaviour as {@code /vfx playat} or a network play that carries a position.</p>
	 *
	 * @param effectId      effect id (built-in or datapack-defined)
	 * @param durationTicks duration in ticks
	 * @param position      world position to anchor the effect to (may be null)
	 * @param params        parameter overrides (empty for defaults)
	 * @param easing        easing curve (may be null for the definition default)
	 * @return the instance id, or {@code 0} when the effect was not started
	 */
	long playEffect(Identifier effectId, int durationTicks, @Nullable Vec3 position, Map<String, Float> params, @Nullable EasingType easing);

	/**
	 * Plays an effect locally, anchored to a world position and to entities.
	 *
	 * <p>When {@code position} is null and the definition declares entity-anchored positions, the
	 * supplied UUIDs are zipped with them in declaration order and the effect follows those
	 * entities. A non-null {@code position} wins over the definitions anchors (same rule as the
	 * manager's network path).</p>
	 *
	 * @param effectId      effect id (built-in or datapack-defined)
	 * @param durationTicks duration in ticks
	 * @param position      world position to anchor the effect to (may be null)
	 * @param entityUuids   UUIDs for the definition's entity anchors, in declaration order
	 * @param params        parameter overrides (empty for defaults)
	 * @param easing        easing curve (may be null for the definition default)
	 * @return the instance id, or {@code 0} when the effect was not started
	 */
	long playEffect(Identifier effectId, int durationTicks, @Nullable Vec3 position, List<UUID> entityUuids, Map<String, Float> params, @Nullable EasingType easing);

	/**
	 * Re-anchors a running effect instance to a new world position locally (no packet) - the local
	 * equivalent of the network {@code MOVE} action. Calling it every tick lets a caller make an
	 * anchored effect follow a moving entity or point.
	 *
	 * @param effectId   the effect id the instance belongs to
	 * @param instanceId the instance id returned when the effect was played
	 * @param worldPos   the new world position
	 * @return {@code true} when the request was applied (called on the render thread) or queued for
	 *         it; a queued request that turns out to reference an unknown instance fails silently
	 *         on the render thread
	 */
	boolean moveEffect(Identifier effectId, long instanceId, Vec3 worldPos);

	/**
	 * Live-overrides a parameter of every running instance of the effect locally, without
	 * restarting its timeline. When no instance is running, a persistent instance is started with
	 * the override baked in (same behaviour as {@code VFXAPI.sendSetParam}).
	 *
	 * @param effectId effect id
	 * @param name     parameter name
	 * @param value    the new constant value
	 * @return {@code true} when the request was applied or queued (see {@link #moveEffect})
	 */
	boolean setParam(Identifier effectId, String name, float value);

	/**
	 * Live-replaces a parameter of every running instance of the effect with a math expression
	 * (same syntax as the JSON {@code expr} field; see {@code MathExpression} for the available
	 * variables and functions).
	 *
	 * @param effectId   effect id
	 * @param name       parameter name
	 * @param exprSource expression source; {@code null} or an invalid source falls back to 0
	 * @return {@code true} when at least one running instance was found (or the request was queued)
	 */
	boolean setParamExpr(Identifier effectId, String name, String exprSource);

	/**
	 * Adds or replaces a keyframe of a parameter on every running instance of the effect. A
	 * <b>negative {@code time}</b> means "from here": the value the parameter has right now is
	 * pinned at the current time and the animation runs to {@code value} over {@code |time|} ticks
	 * (see {@code VFXTimeline#setKeyframe}).
	 *
	 * @param effectId effect id
	 * @param name     parameter name
	 * @param time     keyframe time in ticks from the effect start, or negative for "from now"
	 * @param value    keyframe value
	 * @param easing   easing curve towards the next keyframe ({@code null} = linear)
	 * @return {@code true} when at least one running instance was found (or the request was queued)
	 */
	boolean setKeyframe(Identifier effectId, String name, int time, float value, @Nullable EasingType easing);

	/**
	 * Adds or replaces a keyframe of a parameter, with a named easing curve (built-in name such as
	 * {@code ease_out_cubic}, or a datapack curve id).
	 *
	 * @param easing easing curve name
	 * @return {@code true} when at least one running instance was found (or the request was queued)
	 */
	boolean setKeyframe(Identifier effectId, String name, int time, float value, String easing);

	/**
	 * Stops all running instances of the given effect.
	 */
	void stopEffect(Identifier effectId);

	/**
	 * Stops one specific instance of an effect.
	 *
	 * @param instanceId the instance id returned from {@link #playEffect(Identifier, int, Map, EasingType)}
	 */
	void stopEffect(long instanceId);

	/**
	 * Spawns a one-shot block-model particle into the client engine - the local counterpart of
	 * {@link VFXAPI#spawnBlockParticle(VFXBlockParticleSpec, Vec3, Vec3)}.
	 *
	 * <p>The default is a no-op, so a dispatcher compiled before block particles existed keeps
	 * working; the mod's own client dispatcher overrides it.</p>
	 *
	 * @param spec     the particle's block and physics
	 * @param position world position of the particle centre
	 * @param velocity initial velocity in blocks per tick
	 */
	default void spawnBlockParticle(final VFXBlockParticleSpec spec, final Vec3 position, final Vec3 velocity) {
	}

	/**
	 * Spawns a one-shot spark on the client. A {@code default} no-op so a dispatcher compiled
	 * before sparks still links; the mod's client dispatcher overrides it.
	 *
	 * @param spec     the spark spec
	 * @param position world position of the spark
	 * @param velocity initial velocity in blocks per tick
	 */
	default void spawnSpark(final VFXSparkSpec spec, final Vec3 position, final Vec3 velocity) {
	}

	/**
	 * Stops all running effects.
	 */
	void stopAllEffects();
}
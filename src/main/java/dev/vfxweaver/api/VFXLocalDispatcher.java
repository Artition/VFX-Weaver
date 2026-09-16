package dev.vfxweaver.api;

import dev.vfxweaver.effect.EasingType;
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
	 * @return {@code true} when a matching instance was moved
	 */
	boolean moveEffect(Identifier effectId, long instanceId, Vec3 worldPos);

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
	 * Stops all running effects.
	 */
	void stopAllEffects();
}
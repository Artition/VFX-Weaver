package dev.vfxweaver.client;

import dev.vfxweaver.api.VFXLocalDispatcher;
import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.client.flashback.FlashbackCompat;
import dev.vfxweaver.client.render.VFXBlockParticleEngine;
import dev.vfxweaver.client.render.VFXSparkEngine;
import dev.vfxweaver.effect.EasingFunction;
import dev.vfxweaver.effect.EasingType;
import dev.vfxweaver.effect.VFXBlockParticleSpec;
import dev.vfxweaver.effect.VFXSparkSpec;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Client implementation of {@link VFXLocalDispatcher} that forwards playback requests onto the
 * render thread, where the effect manager is consumed each frame. The instance id is allocated
 * synchronously so it can be returned to the caller; the play itself is scheduled.
 */
public class VFXClientAPI implements VFXLocalDispatcher {
	@Override
	public long playEffect(final Identifier effectId, final int durationTicks, final Map<String, Float> params, final EasingType easing) {
		return this.schedulePlay(effectId, durationTicks, null, List.of(), params, easing);
	}

	@Override
	public long playEffect(final Identifier effectId, final int durationTicks, final @Nullable Vec3 position, final Map<String, Float> params, final @Nullable EasingType easing) {
		return this.schedulePlay(effectId, durationTicks, position, List.of(), params, easing);
	}

	@Override
	public long playEffect(final Identifier effectId, final int durationTicks, final @Nullable Vec3 position, final List<UUID> entityUuids, final Map<String, Float> params, final @Nullable EasingType easing) {
		return this.schedulePlay(effectId, durationTicks, position, entityUuids, params, easing);
	}

	/**
	 * Allocates the instance id synchronously (so it can be returned to the caller) and schedules
	 * the play on the render thread. A null easing is passed through as a null function: the
	 * effect manager then falls back to the definition's default easing.
	 */
	private long schedulePlay(
		final Identifier effectId,
		final int durationTicks,
		final @Nullable Vec3 position,
		final List<UUID> entityUuids,
		final Map<String, Float> params,
		final @Nullable EasingType easing
	) {
		long instanceId = VFXEffectManager.get().allocateInstanceId();
		EasingFunction easingFunction = easing == null ? null : EasingFunction.builtIn(easing);
		Minecraft.getInstance().execute(() -> {
			if (entityUuids.isEmpty()) {
				VFXEffectManager.get().play(effectId, durationTicks, instanceId, position, params, easingFunction);
			} else {
				VFXEffectManager.get().play(effectId, durationTicks, instanceId, position, entityUuids, params, easingFunction);
			}
			FlashbackCompat.recordPlay(effectId, durationTicks, params, easing, position);
		});
		return instanceId;
	}

	@Override
	public boolean moveEffect(final Identifier effectId, final long instanceId, final Vec3 worldPos) {
		return applyLive(() -> VFXEffectManager.get().move(effectId, instanceId, worldPos));
	}

	@Override
	public boolean setParam(final Identifier effectId, final String name, final float value) {
		FlashbackCompat.recordSetParam(effectId, name, value);
		return applyLive(() -> VFXEffectManager.get().setParam(effectId, name, value));
	}

	@Override
	public boolean setParamExpr(final Identifier effectId, final String name, final String exprSource) {
		FlashbackCompat.recordSetExpr(effectId, name, exprSource);
		return applyLive(() -> VFXEffectManager.get().setExpression(effectId, name, exprSource));
	}

	@Override
	public boolean setKeyframe(final Identifier effectId, final String name, final int time, final float value, final @Nullable EasingType easing) {
		// A null easing means linear for a keyframe (there is no "definition default" per segment).
		EasingFunction easingFunction = easing == null ? EasingFunction.builtIn(EasingType.LINEAR) : EasingFunction.builtIn(easing);
		FlashbackCompat.recordKeyframe(effectId, name, time, value, easing == null ? null : easing.name());
		return applyLive(() -> VFXEffectManager.get().setKeyframe(effectId, name, time, value, easingFunction));
	}

	@Override
	public boolean setKeyframe(final Identifier effectId, final String name, final int time, final float value, final String easing) {
		// Named curves resolve through the same path the network action uses (blank = linear).
		EasingFunction easingFunction = EasingFunction.fromString(easing);
		FlashbackCompat.recordKeyframe(effectId, name, time, value, easing);
		return applyLive(() -> VFXEffectManager.get().setKeyframe(effectId, name, time, value, easingFunction));
	}

	/**
	 * Runs a live edit on the render thread. Called from the render thread it returns the effect
	 * manager's result; otherwise the edit is queued and {@code true} means "accepted" (a queued
	 * edit that turns out to reference an unknown effect fails silently on the render thread).
	 */
	private static boolean applyLive(final BooleanSupplier edit) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.isSameThread()) {
			return edit.getAsBoolean();
		}
		minecraft.execute(edit::getAsBoolean);
		return true;
	}

	@Override
	public void spawnBlockParticle(final VFXBlockParticleSpec spec, final Vec3 position, final Vec3 velocity) {
		Minecraft.getInstance().execute(() -> VFXBlockParticleEngine.spawn(spec, position, velocity));
	}

	@Override
	public void spawnSpark(final VFXSparkSpec spec, final Vec3 position, final Vec3 velocity) {
		Minecraft.getInstance().execute(() -> VFXSparkEngine.spawn(spec, position, velocity));
	}

	@Override
	public void stopEffect(final Identifier effectId) {
		Minecraft.getInstance().execute(() -> VFXEffectManager.get().stop(effectId));
	}

	@Override
	public void stopEffect(final long instanceId) {
		Minecraft.getInstance().execute(() -> VFXEffectManager.get().stop(instanceId));
	}

	@Override
	public void stopAllEffects() {
		Minecraft.getInstance().execute(() -> VFXEffectManager.get().stopAll());
	}
}
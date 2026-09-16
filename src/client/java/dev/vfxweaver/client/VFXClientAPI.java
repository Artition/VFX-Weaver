package dev.vfxweaver.client;

import dev.vfxweaver.api.VFXLocalDispatcher;
import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.client.flashback.FlashbackCompat;
import dev.vfxweaver.effect.EasingFunction;
import dev.vfxweaver.effect.EasingType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
		long instanceId = VFXEffectManager.get().allocateInstanceId();
		Minecraft.getInstance().execute(() -> {
			VFXEffectManager.get().play(effectId, durationTicks, instanceId, null, params, EasingFunction.builtIn(easing));
			FlashbackCompat.recordPlay(effectId, durationTicks, params, easing);
		});
		return instanceId;
	}

	@Override
	public long playEffect(final Identifier effectId, final int durationTicks, final @Nullable Vec3 position, final Map<String, Float> params, final @Nullable EasingType easing) {
		long instanceId = VFXEffectManager.get().allocateInstanceId();
		Minecraft.getInstance().execute(() -> {
			VFXEffectManager.get().play(effectId, durationTicks, instanceId, position, params, EasingFunction.builtIn(easing));
			FlashbackCompat.recordPlay(effectId, durationTicks, params, easing, position);
		});
		return instanceId;
	}

	@Override
	public long playEffect(final Identifier effectId, final int durationTicks, final @Nullable Vec3 position, final List<UUID> entityUuids, final Map<String, Float> params, final @Nullable EasingType easing) {
		long instanceId = VFXEffectManager.get().allocateInstanceId();
		Minecraft.getInstance().execute(() -> {
			VFXEffectManager.get().play(effectId, durationTicks, instanceId, position, entityUuids, params, EasingFunction.builtIn(easing));
			FlashbackCompat.recordPlay(effectId, durationTicks, params, easing, position);
		});
		return instanceId;
	}

	@Override
	public boolean moveEffect(final Identifier effectId, final long instanceId, final Vec3 worldPos) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.isSameThread()) {
			return VFXEffectManager.get().move(effectId, instanceId, worldPos);
		}
		minecraft.execute(() -> VFXEffectManager.get().move(effectId, instanceId, worldPos));
		return true;
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
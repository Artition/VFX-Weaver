package dev.vfxweaver.client.mixin;

import dev.vfxweaver.client.access.IVFXWeaverEntityState;
import java.util.UUID;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Adds the {@link IVFXWeaverEntityState} UUID slot to item frame render states, mirroring
 * {@link LivingEntityRenderStateMixin} for the non-living path.
 */
@Mixin(ItemFrameRenderState.class)
public abstract class ItemFrameRenderStateMixin implements IVFXWeaverEntityState {
	@Unique
	private UUID vfxweaver$uuid;

	@Override
	public @Nullable UUID vfxweaver$getUuid() {
		return this.vfxweaver$uuid;
	}

	@Override
	public void vfxweaver$setUuid(final @Nullable UUID uuid) {
		this.vfxweaver$uuid = uuid;
	}
}

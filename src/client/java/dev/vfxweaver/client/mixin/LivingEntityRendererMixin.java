package dev.vfxweaver.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.vfxweaver.client.access.IVFXWeaverEntityState;
import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.client.render.VFXBeamsRenderer;
import dev.vfxweaver.client.render.VFXEntityEffectRenderer;
import dev.vfxweaver.effect.VFXActiveEffect;
import dev.vfxweaver.effect.VFXEffectType;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks {@link LivingEntityRenderer} twice:
 *
 * <ul>
 *   <li>after {@code extractRenderState} — stores the entity's UUID on the render state (see
 *       {@link IVFXWeaverEntityState});</li>
 *   <li>in {@code submit} — when an {@code entity_displace} effect targets the entity, the vanilla
 *       {@code submitModel} call is swapped for a displaced version of the model itself (the vanilla
 *       body is not drawn, so there is no copy on top of it); other effects (tint/outline/beams)
 *       are re-submitted as an extra second pass on the displaced geometry.</li>
 * </ul>
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin<T extends LivingEntity, S extends LivingEntityRenderState, M extends EntityModel<? super S>> {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/entity-fx");

	@Shadow
	protected M model;

	@Shadow
	public abstract Identifier getTextureLocation(S state);

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V", at = @At("TAIL"))
	private void vfxweaver$storeEntityUuid(final LivingEntity entity, final LivingEntityRenderState state, final float partialTicks, final CallbackInfo ci) {
		((IVFXWeaverEntityState) state).vfxweaver$setUuid(entity.getUUID());
	}

	/**
	 * Replaces the vanilla model pass with the displaced textured model when an active
	 * {@code entity_displace} effect targets the entity, else forwards to the original.
	 */
	@Redirect(
		method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V"
		)
	)
	private void vfxweaver$displaceOrSubmit(
		final SubmitNodeCollector submitNodeCollector,
		final net.minecraft.client.model.Model<? super S> vanillaModel,
		final Object stateObject,
		final PoseStack poseStack,
		final net.minecraft.client.renderer.rendertype.RenderType renderType,
		final int lightCoords,
		final int overlayCoords,
		final int modelTint,
		final net.minecraft.client.renderer.texture.TextureAtlasSprite sprite,
		final int outlineColor,
		final net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay crumblingOverlay
	) {
		@SuppressWarnings("unchecked")
		final S state = (S) stateObject;
		UUID uuid = ((IVFXWeaverEntityState) state).vfxweaver$getUuid();
		VFXActiveEffect displace = null;
		if (uuid != null) {
			for (VFXActiveEffect effect : VFXEffectManager.get().getActiveEntityEffects(uuid)) {
				if (effect.getType() == VFXEffectType.ENTITY_DISPLACE) {
					displace = effect;
					break;
				}
			}
		}
		if (displace == null) {
			submitNodeCollector.submitModel(vanillaModel, state, poseStack, renderType, lightCoords, overlayCoords, modelTint, sprite, outlineColor, crumblingOverlay);
			return;
		}
		Identifier texture = this.getTextureLocation(state);
		VFXEntityEffectRenderer.renderDisplacedModel(displace, state, poseStack, submitNodeCollector, this.model, texture, lightCoords);
	}

	@Inject(
		method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;IIILnet/minecraft/client/renderer/texture/TextureAtlasSprite;ILnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
			shift = At.Shift.AFTER
		)
	)
	private void vfxweaver$applyEntityEffects(final S state, final PoseStack poseStack, final SubmitNodeCollector submitNodeCollector, final CameraRenderState camera, final CallbackInfo ci) {
		UUID uuid = ((IVFXWeaverEntityState) state).vfxweaver$getUuid();
		if (uuid == null) {
			return;
		}
		List<VFXActiveEffect> effects = VFXEffectManager.get().getActiveEntityEffects(uuid);
		if (effects.isEmpty()) {
			return;
		}
		Identifier texture = this.getTextureLocation(state);
		for (VFXActiveEffect effect : effects) {
			try {
				if (effect.getType() == VFXEffectType.ENTITY_TINT) {
					VFXEntityEffectRenderer.renderTint(effect, state, poseStack, submitNodeCollector, this.model, texture);
				} else if (effect.getType() == VFXEffectType.ENTITY_OUTLINE) {
					VFXEntityEffectRenderer.renderOutline(effect, state, poseStack, submitNodeCollector, this.model, texture);
				} else if (effect.getType() == VFXEffectType.GOD_RAYS) {
					VFXBeamsRenderer.render(effect, state, poseStack, submitNodeCollector);
				}
			} catch (Exception e) {
				LOGGER.warn("Failed to apply entity effect '{}'", effect.getId(), e);
			}
		}
	}
}
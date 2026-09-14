package dev.vfxweaver.client.mixin;

import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.client.shake.CameraShakeManager;
import dev.vfxweaver.client.shake.VFXCameraRoll;
import net.minecraft.client.Camera;
//? if >=26.1
import net.minecraft.client.DeltaTracker;
//? if <26.1 {
/*import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
*///?}
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the combined camera shake from active {@code camera_shake} effects once the base
 * camera pose has been computed. The offsets are added on top of the real rotation and position
 * so that {@code extractRenderState} copies the shaken pose into the render state.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	private Vec3 position;

	@Shadow
	@Final
	private Vector3f forwards;

	@Shadow
	@Final
	private Vector3f up;

	@Shadow
	@Final
	private Vector3f left;

	@Shadow
	@Final
	private Quaternionf rotation;

	@Shadow
	private float xRot;

	@Shadow
	private float yRot;

	//? if >=26.1 {
	@Inject(method = "calculateFov(F)F", at = @At("RETURN"), cancellable = true)
	private void vfxweaver$modifyFov(final float partialTick, final CallbackInfoReturnable<Float> cir) {
		float delta = VFXEffectManager.get().getActiveFovDelta();
		if (delta != 0.0F) {
			cir.setReturnValue(cir.getReturnValue() + delta);
		}
	}
	//?}

	//? if <26.1 {
/*	@Inject(method = "setup(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;ZZF)V", at = @At("TAIL"))
	private void vfxweaver$applyShake(final Level level, final Entity entity, final boolean detached, final boolean mirror, final float partialTick, final CallbackInfo ci) {
		vfxweaver$applyShake();
	}
*///?} else {
	@Inject(method = "update(Lnet/minecraft/client/DeltaTracker;)V", at = @At("TAIL"))
	private void vfxweaver$applyShake(final DeltaTracker deltaTracker, final CallbackInfo ci) {
		vfxweaver$applyShake();
	}
//?}

	private void vfxweaver$applyShake() {
		float cameraRoll = VFXCameraRoll.compute(VFXEffectManager.get());
		CameraShakeManager.Offset offset = CameraShakeManager.compute(VFXEffectManager.get());
		if (offset.dx() == 0.0 && offset.dy() == 0.0 && offset.dz() == 0.0
			&& offset.yaw() == 0.0F && offset.pitch() == 0.0F && offset.roll() == 0.0F
			&& cameraRoll == 0.0F) {
			return;
		}

		this.setRotation(this.yRot + offset.yaw(), this.xRot + offset.pitch());

		if (offset.roll() != 0.0F) {
			Quaternionf roll = new Quaternionf().rotationZ(offset.roll() * (float) Math.PI / 180.0F);
			this.rotation.mul(roll, this.rotation);
			roll.transform(this.forwards);
			roll.transform(this.up);
			roll.transform(this.left);
		}

		this.setPosition(this.position.add(offset.dx(), offset.dy(), offset.dz()));

		if (cameraRoll != 0.0F) {
			Quaternionf roll = new Quaternionf().rotationZ(cameraRoll * (float) Math.PI / 180.0F);
			this.rotation.mul(roll, this.rotation);
			roll.transform(this.forwards);
			roll.transform(this.up);
			roll.transform(this.left);
		}
	}
}

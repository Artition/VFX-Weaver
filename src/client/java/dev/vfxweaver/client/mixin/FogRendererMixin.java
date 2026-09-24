package dev.vfxweaver.client.mixin;

import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.util.VFXFogModifier;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Applies the {@code fog_modifier} effect at the <b>source</b> of the vanilla fog, the way
 * {@code fov_modifier} modifies the FOV: it rewrites the values {@code FogRenderer} serialises into
 * the fog UBO, so vanilla terrain/entities and our own {@code core/entity_fx} pipelines (which read
 * the same {@code FogEnvironmentalStart/End}, {@code FogRenderDistanceStart/End} and {@code FogColor}
 * uniforms) all see the modified fog. It is <b>not</b> a post pass — a datapack author's own fog is
 * untouched.
 *
 * <p>The one write point shared by every node is the private
 * {@code updateBuffer(ByteBuffer, int, Vector4f, float x6)} that fills the UBO. On 26.x the public
 * {@code updateBuffer(FogData)} reads the record and calls it; on 1.21.11 {@code setupFog} returns
 * the colour and calls it inline. The mixin {@link ModifyArgs}-modifies the arguments of that call
 * on each node (see the per-node {@code method} guard). The argument layout is identical on all
 * nodes: {@code (data, offset, color, environmentalStart, environmentalEnd, renderDistanceStart,
 * renderDistanceEnd, skyEnd, cloudEnd)}. Environment and render-distance start/end are scaled;
 * {@code skyEnd}/{@code cloudEnd} are left alone (they fade the sky/clouds, not the fog distance).
 *
 * <p><b>Iris:</b> a shaderpack computes its own fog and ignores the vanilla UBO values, so under
 * Iris this effect is a no-op. That is inherent to modifying the vanilla fog at its source.
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
	//? if <26.1 {
	/*@ModifyArgs(
		method = "setupFog(Lnet/minecraft/client/Camera;ILnet/minecraft/client/DeltaTracker;FLnet/minecraft/client/multiplayer/ClientLevel;)Lorg/joml/Vector4f;",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V")
	)
	private void vfxweaver$modifyFog(final Args args) {
		vfxweaver$applyFog(args);
	}
	*///?} else {
	@ModifyArgs(
		method = "updateBuffer(Lnet/minecraft/client/renderer/fog/FogData;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V")
	)
	private void vfxweaver$modifyFog(final Args args) {
		vfxweaver$applyFog(args);
	}
	//?}

	/**
	 * Combines the active {@code fog_modifier} contributions and rewrites the fog UBO arguments.
	 * With no contribution the arguments are left bit-for-bit untouched.
	 *
	 * @param args the fog UBO write arguments (see the class javadoc for the layout)
	 */
	private static void vfxweaver$applyFog(final Args args) {
		final Vector4f color = args.get(2);
		final VFXFogModifier.Result result = VFXFogModifier.combine(
			VFXEffectManager.get().getActiveFogContributions(), color.x, color.y, color.z);
		if (!result.active()) {
			return;
		}
		final float start = result.startScale();
		if (start != 1.0F) {
			args.set(3, (Float) args.get(3) * start);
			args.set(5, (Float) args.get(5) * start);
		}
		final float end = result.endScale();
		if (end != 1.0F) {
			args.set(4, (Float) args.get(4) * end);
			args.set(6, (Float) args.get(6) * end);
		}
		if (result.hasColor()) {
			args.set(2, new Vector4f(result.r(), result.g(), result.b(), color.w));
		}
	}
}

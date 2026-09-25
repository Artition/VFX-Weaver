package dev.vfxweaver.client.mixin;

import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.util.VFXFogModifier;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
//? if <26.1 {
/*import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
*///?} else {
import net.minecraft.client.renderer.fog.FogData;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
//?}

/**
 * Applies the {@code fog_modifier} effect at the <b>source</b> of the vanilla fog, the way
 * {@code fov_modifier} modifies the FOV: it rewrites the six fog distances and the colour
 * {@code FogRenderer} serialises into the fog UBO, so vanilla terrain/entities, the sky and cloud
 * shaders and our own {@code core/entity_fx} pipelines all see the modified fog. It is <b>not</b> a
 * post pass — a datapack author's own fog is untouched.
 *
 * <p>On 26.x {@code setupFog} assembles a {@code FogData} and the public
 * {@code updateBuffer(FogData)} hands it to the private UBO writer. The mixin modifies that entry
 * ({@link ModifyVariable}, arg 0): it returns a <b>new</b> {@code FogData} with every start
 * ({@code environmentalStart}, {@code renderDistanceStart}) scaled by {@code fog_start_scale} and
 * every end ({@code environmentalEnd}, {@code renderDistanceEnd}, {@code skyEnd}, {@code cloudEnd})
 * by {@code fog_end_scale}, so the horizon band and the clouds follow the fog. The incoming instance
 * is <b>not</b> mutated — a cached/shared {@code FogData} must not accumulate the scale per frame.
 *
 * <p>On 1.21.11 there is no public {@code FogData} entry — {@code setupFog} builds a local
 * {@code FogData}, calls the private {@code updateBuffer(ByteBuffer, int, Vector4f, float x6)}
 * inline and returns only the colour — so the mixin {@link ModifyArgs}-modifies the six float slots
 * of that call with the same semantics. The float order is identical on both nodes (verified with
 * {@code javap} against the real jars): {@code (color, environmentalStart, environmentalEnd,
 * renderDistanceStart, renderDistanceEnd, skyEnd, cloudEnd)}.
 *
 * <p><b>Scope:</b> the fog distance the player sees is the stronger of the environmental and
 * render-distance ranges, so scaling both moves the wall; scaling {@code skyEnd}/{@code cloudEnd}
 * widens/narrows the horizon band and brings the clouds in or out. The <b>zenith</b> keeps the
 * dimension's own sky colour — the sky shader fogs {@code ColorModulator} toward {@code FogColor}
 * and only the far horizon reaches it — so that contrast is vanilla behaviour, not a bug.
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
		vfxweaver$applyFogArgs(args);
	}

	// Combines the active fog_modifier contributions and rewrites the fog UBO arguments.
	// With no contribution the arguments are left bit-for-bit untouched. The slots are
	// (data, offset, color, environmentalStart, environmentalEnd, renderDistanceStart,
	// renderDistanceEnd, skyEnd, cloudEnd).
	private static void vfxweaver$applyFogArgs(final Args args) {
		final Vector4f color = args.get(2);
		final VFXFogModifier.Result result = VFXFogModifier.combine(
			VFXEffectManager.get().getActiveFogContributions(), color.x, color.y, color.z);
		if (!result.active()) {
			return;
		}
		final float start = result.startScale();
		final float end = result.endScale();
		// environmental band (3 start / 4 end) and render-distance band (5 start / 6 end).
		final float environmentalEnd = (Float) args.get(4) * end;
		args.set(3, VFXFogModifier.pullBelow((Float) args.get(3) * start, environmentalEnd));
		args.set(4, environmentalEnd);
		final float renderDistanceEnd = (Float) args.get(6) * end;
		args.set(5, VFXFogModifier.pullBelow((Float) args.get(5) * start, renderDistanceEnd));
		args.set(6, renderDistanceEnd);
		// sky (7) and cloud (8) bands: end only, they fade the dome and the clouds.
		args.set(7, (Float) args.get(7) * end);
		args.set(8, (Float) args.get(8) * end);
		if (result.hasColor()) {
			args.set(2, new Vector4f(result.r(), result.g(), result.b(), color.w));
		}
	}
	*///?} else {
	@ModifyVariable(
		method = "updateBuffer(Lnet/minecraft/client/renderer/fog/FogData;)V",
		at = @At("HEAD"),
		argsOnly = true
	)
	private FogData vfxweaver$modifyFog(final FogData data) {
		return vfxweaver$applyFogData(data);
	}

	/**
	 * Rebuilds the vanilla {@code FogData}: every start scaled by {@code fog_start_scale}, every end
	 * by {@code fog_end_scale} (sky and cloud bands included) and the colour replaced when authored.
	 * Returns the incoming instance untouched when nothing is active, and always a <b>new</b>
	 * instance otherwise, so a cached/shared {@code FogData} never accumulates the scale.
	 *
	 * @param data the vanilla fog data read at the UBO entry
	 * @return the modified fog data (a new instance when active)
	 */
	private static FogData vfxweaver$applyFogData(final FogData data) {
		final VFXFogModifier.Result result = VFXFogModifier.combine(
			VFXEffectManager.get().getActiveFogContributions(), data.color.x, data.color.y, data.color.z);
		if (!result.active()) {
			return data;
		}
		final float start = result.startScale();
		final float end = result.endScale();
		final FogData modified = new FogData();
		modified.environmentalEnd = data.environmentalEnd * end;
		modified.environmentalStart = VFXFogModifier.pullBelow(data.environmentalStart * start, modified.environmentalEnd);
		modified.renderDistanceEnd = data.renderDistanceEnd * end;
		modified.renderDistanceStart = VFXFogModifier.pullBelow(data.renderDistanceStart * start, modified.renderDistanceEnd);
		modified.skyEnd = data.skyEnd * end;
		modified.cloudEnd = data.cloudEnd * end;
		modified.color = result.hasColor()
			? new Vector4f(result.r(), result.g(), result.b(), data.color.w)
			: data.color;
		return modified;
	}
	//?}
}

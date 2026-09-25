package dev.vfxweaver.client.mixin;

import dev.vfxweaver.client.effect.VFXEffectManager;
import dev.vfxweaver.util.VFXFogModifier;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//? if <26.1 {
/*import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
*///?} else {
import net.minecraft.client.renderer.fog.FogData;
//?}

/**
 * Applies the {@code fog_modifier} effect at the <b>source</b> of the vanilla fog, the way
 * {@code fov_modifier} modifies the FOV: it rewrites the fog distances and colour {@code FogRenderer}
 * produces each frame, so vanilla terrain/entities, the sky and cloud shaders (whose
 * {@code skyEnd}/{@code cloudEnd} drive the visible horizon band) and our own {@code core/entity_fx}
 * pipelines all see the modified fog. It is <b>not</b> a post pass — a datapack author's own fog is
 * untouched.
 *
 * <p>The hook is the <b>return of {@code setupFog}</b>, and the returned instance is mutated
 * <b>in place</b>. On 26.x {@code setupFog} assembles a fresh {@code FogData} and returns it; the
 * caller stores that exact instance on the level render state and later hands it to the fog UBO
 * writer (and the sky/cloud consumers), so mutating the returned object reaches every consumer. The
 * previous hook on {@code updateBuffer(FogData)} got this wrong: it substituted a <b>new</b>
 * instance for the parameter only, while the caller kept reading the original one — the distance
 * change never reached the sky/cloud band and looked invisible.
 *
 * <p>Every start ({@code environmentalStart}, {@code renderDistanceStart}) is scaled by
 * {@code fog_start_scale} and every end ({@code environmentalEnd}, {@code renderDistanceEnd},
 * {@code skyEnd}, {@code cloudEnd}) by {@code fog_end_scale}, so the horizon band and the clouds
 * follow the fog. The colour is set in place when authored.
 *
 * <p>On 1.21.11 there is no {@code FogData} return — {@code setupFog} keeps the six distances in a
 * local {@code FogData}, calls the private {@code updateBuffer(ByteBuffer, int, Vector4f, float x6)}
 * inline and returns only the colour {@code Vector4f}. The mixin therefore modifies the six float
 * slots of that call with the same semantics (slot order {@code 3=environmentalStart,
 * 4=environmentalEnd, 5=renderDistanceStart, 6=renderDistanceEnd, 7=skyEnd, 8=cloudEnd}, verified
 * with {@code javap} against the real jar), and separately mutates the returned {@code Vector4f} at
 * {@code setupFog}'s return so the caller-visible colour (the level renderer's sky/horizon band)
 * changes too, not just the UBO.
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
@Mixin(value = FogRenderer.class, priority = 900) // priority 900 so this injects before Sodium's fog mixin
public abstract class FogRendererMixin {
	//? if <26.1 {
	/*@ModifyArgs(
		method = "setupFog(Lnet/minecraft/client/Camera;ILnet/minecraft/client/DeltaTracker;FLnet/minecraft/client/multiplayer/ClientLevel;)Lorg/joml/Vector4f;",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V")
	)
	private void vfxweaver$modifyFog(final Args args) {
		vfxweaver$applyFogArgs(args);
	}

	// 1.21.11 setupFog keeps the six distances in a local FogData and hands them to the private
	// updateBuffer inline; the colour it returns is the Vector4f the level renderer uses for the
	// sky/horizon band. This rewrites the six float slots (3=envStart, 4=envEnd, 5=rdStart,
	// 6=rdEnd, 7=skyEnd, 8=cloudEnd) and REPLACES the colour argument the UBO writer sees.
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

	// setupFog RETURNS the colour the level renderer uses for the sky/horizon band. The @ModifyArgs
	// above only swaps the argument the private UBO writer sees, so the caller-visible Vector4f must
	// be rewritten in place here too. The argument was REPLACED (never mutated), so the returned
	// Vector4f still holds the vanilla colour and re-combining yields the same result -- no double
	// application.
	@Inject(
		method = "setupFog(Lnet/minecraft/client/Camera;ILnet/minecraft/client/DeltaTracker;FLnet/minecraft/client/multiplayer/ClientLevel;)Lorg/joml/Vector4f;",
		at = @At("RETURN")
	)
	private void vfxweaver$modifyFogColor(final CallbackInfoReturnable<Vector4f> cir) {
		final Vector4f color = cir.getReturnValue();
		final VFXFogModifier.Result result = VFXFogModifier.combine(
			VFXEffectManager.get().getActiveFogContributions(), color.x, color.y, color.z);
		if (result.active() && result.hasColor()) {
			color.set(result.r(), result.g(), result.b(), color.w);
		}
	}
	*///?} else {
	@Inject(method = "setupFog", at = @At("RETURN"))
	private void vfxweaver$modifyFog(final CallbackInfoReturnable<FogData> cir) {
		final FogData data = cir.getReturnValue();
		final VFXFogModifier.Result result = VFXFogModifier.combine(
			VFXEffectManager.get().getActiveFogContributions(), data.color.x, data.color.y, data.color.z);
		if (!result.active()) {
			return;
		}
		final float start = result.startScale();
		final float end = result.endScale();
		// The caller stores this exact FogData on the level render state and later hands it to the
		// fog UBO writer, so the returned instance is mutated IN PLACE (never substituted): every
		// downstream consumer (UBO, sky/cloud shaders, terrain) sees the change. Sky and cloud are
		// end-only, so they follow fog_end_scale.
		data.environmentalEnd = data.environmentalEnd * end;
		data.environmentalStart = VFXFogModifier.pullBelow(data.environmentalStart * start, data.environmentalEnd);
		data.renderDistanceEnd = data.renderDistanceEnd * end;
		data.renderDistanceStart = VFXFogModifier.pullBelow(data.renderDistanceStart * start, data.renderDistanceEnd);
		data.skyEnd = data.skyEnd * end;
		data.cloudEnd = data.cloudEnd * end;
		if (result.hasColor()) {
			data.color.set(result.r(), result.g(), result.b(), data.color.w);
		}
	}
	//?}
}

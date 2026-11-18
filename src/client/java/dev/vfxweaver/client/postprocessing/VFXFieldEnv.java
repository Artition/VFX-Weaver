package dev.vfxweaver.client.postprocessing;

import com.mojang.blaze3d.pipeline.RenderTarget;
//? if >=26.1 {
import dev.vfxweaver.client.platform.VFXClientRenderHooks;
//?}
import dev.vfxweaver.util.VFXLog;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-frame camera state used by the field program: the inverse view-projection (the verified
 * world-position recipe from the depth findings), the camera position and the target size. All
 * scratch objects are preallocated so the render path allocates nothing.
 *
 * <p><b>Where the matrices come from.</b> The pass runs from the layer-0 mixin hook
 * ({@code GameRenderer.renderLevel}), which is <em>outside</em> any render-event callback - so the
 * event-scoped {@code VFXClientRenderHooks.camera()} is {@code null} there and cannot be the primary
 * source. Instead the frame's {@code CameraRenderState} is read straight from the game render state
 * ({@code gameRenderState().levelRenderState.cameraRenderState}), which is the snapshot the world was
 * rendered with: {@code projectionMatrix} (built with the frame's FOV, sprint/fly/jump modulation
 * included) and {@code viewRotationMatrix} (rotation only by construction - {@code Camera} builds it
 * as {@code rotation(conjugate(orientation))}). Both, and the position, therefore come from
 * {@code pos} of the <b>same</b> state; mixing one of them with a live rebuild is what detached the
 * mask from the blocks whenever the FOV moved.
 *
 * <p>Fallbacks, in order: the camera state published by the render event (when a pass does run inside
 * one), then the live camera. Only the {@code 1.21.11} nodes end on the live path (their state has no
 * matrices) and they use the FOV the frame was rendered with - {@link VFXFrameFov}, captured by the
 * {@code getFov} hook - never the unmodulated {@code options.fov()}. Reaching the live path on the
 * {@code 26.x} nodes means the direct read failed, which is a bug rather than a state, so it warns
 * once.
 */
public final class VFXFieldEnv {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/field-env");
	private static final Matrix4f VIEW_ROTATION_PROJECTION = new Matrix4f();
	private static final Matrix4f INVERSE = new Matrix4f();
	private static final Vector3f CAMERA = new Vector3f();
	private static float invWidth;
	private static float invHeight;
	private static boolean depthValid;

	private VFXFieldEnv() {
	}

	/**
	 * Captures the current camera state. Call once per frame before the field passes.
	 *
	 * @param mainTarget the main render target whose depth drives the field
	 * @param valid      true when the depth buffer is valid for this pass (screen layer 0, the only
	 *                   layer where the scene depth is intact — it is cleared before the hand)
	 */
	public static void capture(final RenderTarget mainTarget, final boolean valid) {
		depthValid = valid;
		invWidth = mainTarget.width <= 0 ? 0.0F : 1.0F / mainTarget.width;
		invHeight = mainTarget.height <= 0 ? 0.0F : 1.0F / mainTarget.height;
		//? if >=26.1 {
		if (adopt(frameCameraState()) || adopt(VFXClientRenderHooks.camera())) {
			return;
		}
		//?}
		// Fallback: the live camera. On 1.21.11 this is the only path (its camera render state carries
		// no matrices), so its projection must use the FOV the frame was rendered with.
		liveCameraView();
	}

	//? if >=26.1 {
	/**
	 * The frame's camera render state, read directly from the game render state. Not the event-scoped
	 * hook: the post pass runs from the mixin, outside any render-event callback.
	 *
	 * @return the state, or {@code null} before the first extraction of the frame
	 */
	private static net.minecraft.client.renderer.state.level.CameraRenderState frameCameraState() {
		//? if <26.2 {
		return Minecraft.getInstance().gameRenderer.getGameRenderState().levelRenderState.cameraRenderState;
		//?} else {
		/*return Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
		*///?}
	}

	/**
	 * Takes the inverse view-projection and the camera position from one camera render state, so a
	 * matrix can never be paired with another source's position.
	 *
	 * @param state the frame's state, or {@code null}
	 * @return true when the state provided everything
	 */
	private static boolean adopt(final net.minecraft.client.renderer.state.level.CameraRenderState state) {
		if (state == null || state.pos == null || state.projectionMatrix == null || state.viewRotationMatrix == null) {
			return false;
		}
		CAMERA.set(state.pos.x, state.pos.y, state.pos.z);
		// projection * viewRotation, both the frame's own snapshots, then the camera translation, then
		// invert: the exact inverse view-projection the depth buffer was rendered with.
		VIEW_ROTATION_PROJECTION.set(state.projectionMatrix)
			.mul(state.viewRotationMatrix)
			.translate(-CAMERA.x, -CAMERA.y, -CAMERA.z)
			.invert(INVERSE);
		return true;
	}
	//?}

	/**
	 * Rebuilds both the position and the projection from the live camera. getViewRotationProjectionMatrix
	 * returns projection * viewRotation with no translation; post-multiply translate(-cameraPos) then
	 * invert (depth findings).
	 */
	private static void liveCameraView() {
		final Camera camera = liveCamera();
		CAMERA.set(camera.position().x, camera.position().y, camera.position().z);
		if (Minecraft.getInstance().level != null) {
			// A masked pass in a loaded world should always have the frame's state; reaching here means
			// the direct read came back empty, which would otherwise be a silent drift.
			VFXLog.warnOnce(LOGGER, "fieldenv:camera-state",
				"vfxweaver: no frame camera render state; rebuilding the camera projection from the live camera (the mask may drift while the FOV changes)");
		}
		//? if <26.1 {
		/*// 1.21.11: the state has no matrices, so the projection comes from the FOV the frame was
		// rendered with - the getFov hook's captured value, modulation and fov_modifier included.
		final float capturedFov = VFXFrameFov.get();
		final float renderedFov = capturedFov > 0.0F ? capturedFov : Minecraft.getInstance().options.fov().get().floatValue();
		final Matrix4f viewRotProj = Minecraft.getInstance().gameRenderer.getProjectionMatrix(renderedFov);
		viewRotProj.mul(new Matrix4f().rotation(new Quaternionf(camera.rotation()).conjugate()));
		viewRotProj.translate(-CAMERA.x, -CAMERA.y, -CAMERA.z).invert(INVERSE);
		*///?} else {
		camera.getViewRotationProjectionMatrix(VIEW_ROTATION_PROJECTION)
			.translate(-CAMERA.x, -CAMERA.y, -CAMERA.z)
			.invert(INVERSE);
		//?}
	}

	/** Camera accessor: gameRenderer.mainCamera() on >=26.2, getMainCamera() on <26.2. */
	private static Camera liveCamera() {
		//? if <26.2 {
		return Minecraft.getInstance().gameRenderer.getMainCamera();
		//?} else {
		/*return Minecraft.getInstance().gameRenderer.mainCamera();
		*///?}
	}

	/** True when the bound depth is usable this frame. */
	public static boolean depthValid() {
		return depthValid;
	}

	public static Matrix4f invViewProj() {
		return INVERSE;
	}

	public static float cameraX() {
		return CAMERA.x;
	}

	public static float cameraY() {
		return CAMERA.y;
	}

	public static float cameraZ() {
		return CAMERA.z;
	}

	public static float invWidth() {
		return invWidth;
	}

	public static float invHeight() {
		return invHeight;
	}
}
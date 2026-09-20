package dev.vfxweaver.client.postprocessing;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Per-frame camera state used by the field program: the inverse view-projection (the verified
 * world-position recipe from the depth findings), the camera position and the target size. All
 * scratch objects are preallocated so the render path allocates nothing.
 */
public final class VFXFieldEnv {
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
		// Camera accessor: gameRenderer.mainCamera() on >=26.2, getMainCamera() on <26.2.
		//? if <26.2 {
		final Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
		//?} else {
		/*final Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
		*///?}
		CAMERA.set(camera.position().x, camera.position().y, camera.position().z);
		// getViewRotationProjectionMatrix returns projection * viewRotation with no translation;
		// post-multiply translate(-cameraPos) then invert (depth findings, verified recipe).
		//? if <26.1 {
		/*final float renderedFov = Minecraft.getInstance().options.fov().get().floatValue();
		final Matrix4f viewRotProj = Minecraft.getInstance().gameRenderer.getProjectionMatrix(renderedFov);
		viewRotProj.mul(new Matrix4f().rotation(new Quaternionf(camera.rotation()).conjugate()));
		viewRotProj.translate(-CAMERA.x, -CAMERA.y, -CAMERA.z).invert(INVERSE);
		*///?} else {
		camera.getViewRotationProjectionMatrix(VIEW_ROTATION_PROJECTION)
			.translate(-CAMERA.x, -CAMERA.y, -CAMERA.z)
			.invert(INVERSE);
		//?}
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

package dev.vfxweaver.client.postprocessing;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.vfxweaver.client.platform.VFXClientRenderHooks;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Per-frame camera state used by the field program: the inverse view-projection (the verified
 * world-position recipe from the depth findings), the camera position and the target size. All
 * scratch objects are preallocated so the render path allocates nothing.
 *
 * <p>The matrices and the position come from the <b>frame's</b> {@code CameraRenderState}, which the
 * render hooks publish before the layer-0 pass: {@code projectionMatrix} and
 * {@code viewRotationMatrix} are the snapshots the world itself was rendered with, and
 * {@code viewRotationMatrix} is rotation-only by construction ({@code Camera} builds it as
 * {@code rotation(conjugate(orientation))}). Rebuilding the projection from the live camera instead
 * was wrong whenever the FOV moved (sprint, flight, jumping): {@code Camera.getViewRotationProjectionMatrix}
 * serves a cached matrix keyed by its {@code Projection}'s version, and later passes (the hand, the
 * HUD) mutate that same {@code Projection}, so the live rebuild could differ from the projection the
 * depth buffer was drawn with - and a mask drawn from it detached from the blocks. With a static FOV
 * the two coincided, which is why it only showed while the FOV stirred.
 *
 * <p>Outside a published render event (and on 1.21.11, whose state carries no matrices) the live
 * camera is the fallback.
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
		// The frame's published camera state: the same matrices and position the world was drawn with.
		// `var` keeps the per-node class name (state.level on 26.x, state on 1.21.11) out of this file.
		final var state = VFXClientRenderHooks.camera();
		final Camera camera = liveCamera();
		if (state != null) {
			CAMERA.set(state.pos.x, state.pos.y, state.pos.z);
		} else {
			CAMERA.set(camera.position().x, camera.position().y, camera.position().z);
		}
		//? if >=26.1 {
		// projection * viewRotation, both the frame's own snapshots, then the camera translation, then
		// invert: this is the exact inverse view-projection the depth buffer was rendered with.
		if (state != null && state.projectionMatrix != null && state.viewRotationMatrix != null) {
			VIEW_ROTATION_PROJECTION.set(state.projectionMatrix)
				.mul(state.viewRotationMatrix)
				.translate(-CAMERA.x, -CAMERA.y, -CAMERA.z)
				.invert(INVERSE);
			return;
		}
		//?}
		// Fallback: rebuild from the live camera (1.21.11 always, and any node outside a published
		// render event). getViewRotationProjectionMatrix returns projection * viewRotation with no
		// translation; post-multiply translate(-cameraPos) then invert (depth findings).
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

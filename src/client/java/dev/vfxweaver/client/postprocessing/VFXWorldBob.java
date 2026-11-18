package dev.vfxweaver.client.postprocessing;

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * The frame's view-bob pose - vanilla's {@code bobHurt} plus, when the option is on, {@code bobView} -
 * published once per frame by {@code GameRendererMixin} and composed into the mask's view-projection.
 *
 * <p>Why it is needed: vanilla folds the bob into the <b>projection</b> it hands the level renderer -
 * {@code GameRenderer.renderLevel} builds {@code projection * bobPose} and passes the rotation as the
 * model-view, on {@code 26.x} ({@code CameraRenderState.projectionMatrix}) exactly as on
 * {@code 1.21.11} ({@code getProjectionMatrix(fov)}). The mask builds its own matrix from the camera,
 * which carries the rotation only, so without this pose walking made the mask oscillate sideways
 * against the blocks. Applying the same pose in the same place ({@code projection * bob * rotation})
 * is what makes the two agree.
 *
 * <p>Identity until the first publish, and identity in practice whenever View Bobbing is off and no
 * hurt tilt is active, so a frame that never got published is drawn exactly as before.
 *
 * <p>The pose is built in one reused {@link PoseStack} (pushed and popped per publish), so a frame
 * allocates nothing.
 */
public final class VFXWorldBob {
	private static final Matrix4f POSE = new Matrix4f();
	/** Reused across frames; {@link #beginScratch()} pushes, {@link #publishScratch()} pops. */
	private static final PoseStack SCRATCH = new PoseStack();

	private VFXWorldBob() {
	}

	/** Pushes the shared scratch pose (identity) and returns it; always pair with {@link #publishScratch()}. */
	public static PoseStack beginScratch() {
		SCRATCH.pushPose();
		return SCRATCH;
	}

	/** Copies the scratch pose's matrix out as this frame's bob and pops the scratch. */
	public static void publishScratch() {
		publish(SCRATCH.last().pose());
		SCRATCH.popPose();
	}

	/** Publishes this frame's bob pose (copied; the caller keeps ownership of its matrix). */
	public static void publish(final Matrix4fc pose) {
		POSE.set(pose);
	}

	/** The pose published for the current frame, identity before the first publish. */
	public static Matrix4fc pose() {
		return POSE;
	}
}

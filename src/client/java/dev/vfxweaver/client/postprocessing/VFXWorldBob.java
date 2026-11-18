package dev.vfxweaver.client.postprocessing;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * The frame's view-bob pose - vanilla's {@code bobHurt} plus, when the option is on, {@code bobView} -
 * published once per frame by {@code GameRendererMixin} and composed into the mask's view-projection.
 *
 * <p>Why it is needed: on {@code 26.x} the world is drawn through
 * {@code (CameraRenderState.projectionMatrix * bobPose) * viewRotationMatrix} - the bob is folded into
 * the <em>projection</em> the level renderer binds, see {@code GameRenderer.renderLevel}. The mask
 * builds its own matrix from the same camera state, which carries the rotation only, so without this
 * pose walking made the mask oscillate sideways against the blocks. Applying the same pose in the
 * same place ({@code projection * bob * viewRotation}) is what makes the two agree.
 *
 * <p>Identity until the first publish, and identity in practice whenever View Bobbing is off and no
 * hurt tilt is active, so a frame that never got published is drawn exactly as before.
 */
public final class VFXWorldBob {
	private static final Matrix4f POSE = new Matrix4f();

	private VFXWorldBob() {
	}

	/** Publishes this frame's bob pose (copied; the caller keeps its own {@code PoseStack}). */
	public static void publish(final Matrix4fc pose) {
		POSE.set(pose);
	}

	/** The pose published for the current frame, identity before the first publish. */
	public static Matrix4fc pose() {
		return POSE;
	}
}

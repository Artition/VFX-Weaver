package dev.vfxweaver.client.postprocessing;

/**
 * The gameplay FOV the last frame was actually rendered with, captured by the 1.21.11
 * {@code GameRenderer.getFov} hook. It carries the sprint/fly/jump modulation and the mod's own
 * {@code fov_modifier} delta, i.e. the number the world's projection was built from -
 * {@code options.fov()} is the unmodulated setting and drifts from it the moment the FOV moves.
 *
 * <p>Only the {@code 1.21.11} nodes read this: their {@code CameraRenderState} has no projection
 * matrix, so the projection has to be rebuilt from the FOV. On {@code 26.x} the frame's own
 * {@code projectionMatrix} is used instead and this holder stays at its {@code <= 0} "not captured"
 * value.
 *
 * <p><b>It can be one frame old.</b> {@code getFov} is not guaranteed to have run before the layer-0
 * pass that rebuilds the projection (vanilla calls it while setting up the world projection, and the
 * order is not part of the contract), so for one frame after the FOV starts or stops moving the
 * rebuilt projection is the previous frame's. That is a whole-frame lag at worst, not the steady
 * offset the unmodulated {@code options.fov()} produced, and preferring the captured value is what
 * the old {@code vfxweaver$lastFov} field already did for the world bindings.
 */
public final class VFXFrameFov {
	/** {@code <= 0} until the 1.21.11 {@code getFov} hook has seen a gameplay frame. */
	private static float renderedFov = -1.0F;

	private VFXFrameFov() {
	}

	/** Records the FOV the frame was rendered with (the gameplay call only, never the culling one). */
	public static void set(final float fov) {
		renderedFov = fov;
	}

	/** The captured FOV, or a value {@code <= 0} when it has not been captured yet. */
	public static float get() {
		return renderedFov;
	}
}

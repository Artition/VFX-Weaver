package dev.vfxweaver.graph;

import org.jspecify.annotations.Nullable;

/**
 * One node input: either a literal number or a reference to the output of another node.
 *
 * @param reference true for a node reference, false for a literal
 * @param literal   the literal value (0 when {@code reference} is true)
 * @param node      the source node id, or {@code null} for a literal
 */
public record VFXGraphInput(boolean reference, float literal, @Nullable String node) {
	/**
	 * A constant input value.
	 */
	public static VFXGraphInput literal(final float value) {
		return new VFXGraphInput(false, value, null);
	}

	/**
	 * An input fed by another node's output.
	 */
	public static VFXGraphInput reference(final String nodeId) {
		return new VFXGraphInput(true, 0.0F, nodeId);
	}
}

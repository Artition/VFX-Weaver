package dev.vfxweaver.field;

import org.jspecify.annotations.Nullable;

/**
 * The value domain a field produces (spec §2). Types are fixed by the function, never declared
 * by the author.
 */
public enum VFXFieldType {
	FLOAT(1), VEC2(2), VEC3(3);

	private final int components;

	VFXFieldType(final int components) {
		this.components = components;
	}

	/** Number of scalar components (1, 2 or 3). */
	public int components() {
		return this.components;
	}

	/**
	 * The type of {@code a op b}, or {@code null} when the pair cannot be coerced.
	 * A {@code float} broadcasts against any vector; equal vector types combine componentwise;
	 * {@code vec2} against {@code vec3} is a parse error (spec §2).
	 */
	public static @Nullable VFXFieldType combine(final VFXFieldType a, final VFXFieldType b) {
		if (a == b) {
			return a;
		}
		if (a == FLOAT) {
			return b;
		}
		if (b == FLOAT) {
			return a;
		}
		return null;
	}
}

package dev.vfxweaver.field;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The composition operators of a field tree (spec §2). {@code mix} is the only ternary operator
 * and requires a {@code float} factor.
 */
public enum VFXFieldOp {
	MULTIPLY("multiply", 2), ADD("add", 2), SUBTRACT("subtract", 2),
	MIX("mix", 3), MIN("min", 2), MAX("max", 2);

	private final String id;
	private final int arity;

	VFXFieldOp(final String id, final int arity) {
		this.id = id;
		this.arity = arity;
	}

	public String id() {
		return this.id;
	}

	public int arity() {
		return this.arity;
	}

	/**
	 * Resolves an operator from its datapack spelling.
	 *
	 * @return the matching operator, or {@code null} when unknown
	 */
	public static @Nullable VFXFieldOp fromString(final String name) {
		if (name == null) {
			return null;
		}
		final String normalized = name.trim().toLowerCase(Locale.ROOT);
		for (final VFXFieldOp op : values()) {
			if (op.id.equals(normalized)) {
				return op;
			}
		}
		return null;
	}
}

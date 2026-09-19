package dev.vfxweaver.graph;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The uniform-graph node kinds supported in format version 1 (spec §9 step 2a).
 * The logic set ({@code compare}/{@code boolean}/{@code if}/{@code switch}) and the subgraph
 * kind are deliberately absent: they land in step 2b, additively.
 */
public enum VFXNodeKind {
	CONSTANT("constant", List.of(), Set.of("value")),
	TIME("time", List.of(), Set.of("speed", "offset")),
	RANDOM("random", List.of(), Set.of("min", "max", "index")),
	NOISE("noise", List.of(), Set.of("x", "y", "z", "scale", "octaves", "gain", "lacunarity")),
	CURVE("curve", List.of("points"), Set.of("time")),
	MATH("math", List.of("a", "b"), Set.of("a", "b")),
	MIX("mix", List.of("a", "b"), Set.of("a", "b", "factor")),
	CLAMP("clamp", List.of("value"), Set.of("value", "min", "max")),
	REMAP("remap", List.of("value"), Set.of("value", "in_min", "in_max", "out_min", "out_max")),
	BIND("bind", List.of("bind"), Set.of("fallback")),
	EXPR("expr", List.of("expr"), Set.of());

	private final String id;
	private final List<String> requiredInputs;
	private final Set<String> numericInputs;

	VFXNodeKind(final String id, final List<String> requiredInputs, final Set<String> numericInputs) {
		this.id = id;
		this.requiredInputs = requiredInputs;
		this.numericInputs = numericInputs;
	}

	/** The datapack spelling of this kind. */
	public String id() {
		return this.id;
	}

	/**
	 * Input names that must be present (as a literal or a reference) or the file is refused.
	 * Structural fields ({@code points}, {@code expr}, {@code bind}) are listed here but are not
	 * edge targets — see {@link #acceptsNodeInput(String)}.
	 */
	public List<String> requiredInputs() {
		return this.requiredInputs;
	}

	/**
	 * True when {@code name} may be fed by a node edge or a {@code { "from": ... }} object.
	 */
	public boolean acceptsNodeInput(final String name) {
		return this.numericInputs.contains(name);
	}

	/**
	 * Resolves a kind from its datapack spelling.
	 *
	 * @param name raw string, e.g. {@code "noise"}
	 * @return the matching kind, or {@code null} when unknown
	 */
	public static VFXNodeKind fromString(final String name) {
		if (name == null) {
			return null;
		}
		final String normalized = name.trim().toLowerCase(Locale.ROOT);
		for (final VFXNodeKind kind : values()) {
			if (kind.id.equals(normalized)) {
				return kind;
			}
		}
		return null;
	}
}

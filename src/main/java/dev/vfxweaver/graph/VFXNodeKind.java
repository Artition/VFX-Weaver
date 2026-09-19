package dev.vfxweaver.graph;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The uniform-graph node kinds supported in format version 1 (spec §9 steps 2a and 2b).
 * {@code subgraph} is deliberately absent: it is a load-time macro handled by
 * {@link VFXSubgraphExpander} and never reaches the parser.
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
	EXPR("expr", List.of("expr"), Set.of()),
	COMPARE("compare", List.of("a", "b"), Set.of("a", "b")),
	BOOLEAN("boolean", List.of("a"), Set.of("a", "b")),
	IF("if", List.of("condition"), Set.of("condition", "then", "else")),
	SWITCH("switch", List.of("index"), Set.of("index", "default"));

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
	 * A {@code switch} additionally accepts indexed case inputs named {@code case_0},
	 * {@code case_1}, … .
	 */
	public boolean acceptsNodeInput(final String name) {
		if (this == SWITCH && name != null && name.startsWith("case_")) {
			final String digits = name.substring(5);
			return !digits.isEmpty() && digits.chars().allMatch(Character::isDigit);
		}
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

package dev.vfxweaver.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Parses the client-side subset of the vanilla entity-selector grammar used by effect bindings and
 * screen-rectangle masks. The full vanilla grammar is server-side
 * ({@code EntitySelector.findEntities} needs a {@code CommandSourceStack} backed by a
 * {@code ServerLevel}), so a client-side binding cannot use it; this parser accepts the forms a
 * datapack author naturally writes and the client reader applies the result to the loaded entities.
 *
 * <p>Supported:
 * <ul>
 *   <li>base: {@code @s}, {@code @p}, {@code @a}, {@code @r}, {@code @e}, or a bare entity name</li>
 *   <li>arguments: {@code type=}, {@code tag=} and {@code name=} (each optionally negated with a
 *       leading {@code !}, values optionally quoted), {@code distance=} ({@code N}, {@code N..M},
 *       {@code ..M}, {@code N..}), {@code limit=N} and
 *       {@code sort=nearest|furthest|random|arbitrary}</li>
 * </ul>
 *
 * <p>Anything else — {@code nbt=}, {@code scores=}, {@code advancements=}, {@code team=},
 * {@code gamemode=}, {@code level=}, {@code x}/{@code y}/{@code z}/{@code dx}/{@code dy}/{@code dz},
 * {@code x_rotation}/{@code y_rotation}, {@code predicate}, an unknown base or a malformed value —
 * is rejected with an {@link IllegalArgumentException} naming the offending argument, so the caller
 * can fail a binding closed and warn once instead of silently matching the wrong entity.
 *
 * <p>MC-free by design (no Minecraft types), so it can be exercised by a compile-time check.
 */
public final class VFXEntitySelector {
	private VFXEntitySelector() {
	}

	/** The selector base, before its arguments. */
	public enum Base {
		/** {@code @s} — the local player. */
		SELF,
		/** {@code @p} — the nearest player. */
		NEAREST_PLAYER,
		/** {@code @a} — every player. */
		ALL_PLAYERS,
		/** {@code @r} — a random entity. */
		RANDOM_ENTITY,
		/** {@code @e} — every entity. */
		ALL_ENTITIES,
		/** A bare token matched against the entity name. */
		NAME
	}

	/** The order a multi-match selector is reduced to the single entity a binding needs. */
	public enum Sort {
		/** Closest to the reference point. */
		NEAREST,
		/** Farthest from the reference point. */
		FURTHEST,
		/** A uniformly random match. */
		RANDOM,
		/** Any match; the reader treats it as nearest so a binding resolves deterministically. */
		ARBITRARY
	}

	/**
	 * A parsed selector.
	 *
	 * @param base         the base selector
	 * @param type         entity type id (normalised to a namespaced id), or {@code null}
	 * @param typeInverted whether the type filter is negated ({@code type=!...})
	 * @param tag          entity tag, or {@code null}
	 * @param tagInverted  whether the tag filter is negated ({@code tag=!...})
	 * @param name         entity name (also the bare-name base), or {@code null}
	 * @param nameInverted whether the name filter is negated ({@code name=!...})
	 * @param hasDistance  whether a {@code distance=} range was given
	 * @param minDistance  inclusive lower distance bound in blocks
	 * @param maxDistance  inclusive upper distance bound in blocks
	 * @param limit        {@code limit=N} ({@code 0} = unlimited); a single-entity reader still returns one
	 * @param sort         match order
	 */
	public record Selector(Base base, @Nullable String type, boolean typeInverted, @Nullable String tag, boolean tagInverted, @Nullable String name, boolean nameInverted, boolean hasDistance, double minDistance, double maxDistance, int limit, Sort sort) {
	}

	/**
	 * Parses a selector string.
	 *
	 * @param selector the raw selector, e.g. {@code "@e[tag=vfx_showcase,limit=1]"}
	 * @return the parsed selector
	 * @throws IllegalArgumentException when the selector uses syntax this client-side subset does not
	 *                                  support (the message names the offending argument)
	 */
	public static Selector parse(final @Nullable String selector) {
		if (selector == null) {
			throw new IllegalArgumentException("selector is null");
		}
		final String trimmed = selector.trim();
		if (trimmed.isEmpty()) {
			throw new IllegalArgumentException("selector is blank");
		}
		final Base base;
		final int argsStart;
		String bareName = null;
		if (trimmed.charAt(0) == '@') {
			if (trimmed.length() < 2) {
				throw new IllegalArgumentException("selector '@' has no base: " + selector);
			}
			base = switch (trimmed.charAt(1)) {
				case 's' -> Base.SELF;
				case 'p' -> Base.NEAREST_PLAYER;
				case 'a' -> Base.ALL_PLAYERS;
				case 'r' -> Base.RANDOM_ENTITY;
				case 'e' -> Base.ALL_ENTITIES;
				default -> throw new IllegalArgumentException("unknown selector base '@" + trimmed.charAt(1) + "': " + selector);
			};
			argsStart = 2;
		} else {
			base = Base.NAME;
			bareName = trimmed;
			argsStart = trimmed.length();
		}
		String type = null;
		boolean typeInverted = false;
		String tag = null;
		boolean tagInverted = false;
		String name = bareName;
		boolean nameInverted = false;
		boolean hasDistance = false;
		double minDistance = 0.0;
		double maxDistance = Double.POSITIVE_INFINITY;
		int limit = 0;
		Sort sort = base == Base.NEAREST_PLAYER ? Sort.NEAREST : base == Base.RANDOM_ENTITY ? Sort.RANDOM : Sort.ARBITRARY;
		if (argsStart < trimmed.length()) {
			if (trimmed.charAt(argsStart) != '[') {
				throw new IllegalArgumentException("unexpected text after the selector base: " + selector);
			}
			if (trimmed.charAt(trimmed.length() - 1) != ']') {
				throw new IllegalArgumentException("selector arguments are not closed with ']': " + selector);
			}
			for (final String argument : splitArguments(trimmed.substring(argsStart + 1, trimmed.length() - 1))) {
				final int equals = argument.indexOf('=');
				if (equals < 0) {
					throw new IllegalArgumentException("unsupported selector argument '" + argument + "': " + selector);
				}
				final String key = argument.substring(0, equals).trim().toLowerCase(Locale.ROOT);
				final String rawValue = argument.substring(equals + 1).trim();
				switch (key) {
					case "type" -> {
						final boolean inverted = rawValue.startsWith("!");
						type = normalizeType(inverted ? rawValue.substring(1) : rawValue, selector);
						typeInverted = inverted;
					}
					case "tag" -> {
						final boolean inverted = rawValue.startsWith("!");
						tag = stripQuotes(inverted ? rawValue.substring(1) : rawValue);
						tagInverted = inverted;
					}
					case "name" -> {
						final boolean inverted = rawValue.startsWith("!");
						name = stripQuotes(inverted ? rawValue.substring(1) : rawValue);
						nameInverted = inverted;
					}
					case "distance" -> {
						hasDistance = true;
						final double[] range = parseDistance(rawValue, selector);
						minDistance = range[0];
						maxDistance = range[1];
					}
					case "limit" -> limit = parseLimit(rawValue, selector);
					case "sort" -> sort = parseSort(rawValue, selector);
					default -> throw new IllegalArgumentException("unsupported selector argument '" + key + "': " + selector);
				}
			}
		}
		return new Selector(base, type, typeInverted, tag, tagInverted, name, nameInverted, hasDistance, minDistance, maxDistance, limit, sort);
	}

	/**
	 * Splits the inside of {@code [ ... ]} on top-level commas, leaving quoted values and nested
	 * {@code { ... }} / {@code [ ... ]} blocks intact (an unsupported {@code nbt={...}} then fails as
	 * one argument rather than being torn apart).
	 */
	private static String[] splitArguments(final String inner) {
		final List<String> parts = new ArrayList<>();
		final StringBuilder current = new StringBuilder();
		char quote = 0;
		int depth = 0;
		for (int i = 0; i < inner.length(); i++) {
			final char character = inner.charAt(i);
			if (quote != 0) {
				current.append(character);
				if (character == quote) {
					quote = 0;
				}
				continue;
			}
			if (character == '\'' || character == '"') {
				quote = character;
				current.append(character);
				continue;
			}
			if (character == '{' || character == '[') {
				depth++;
			} else if (character == '}' || character == ']') {
				depth--;
			}
			if (character == ',' && depth == 0) {
				parts.add(current.toString().trim());
				current.setLength(0);
				continue;
			}
			current.append(character);
		}
		if (current.length() > 0) {
			parts.add(current.toString().trim());
		}
		return parts.toArray(new String[0]);
	}

	private static String stripQuotes(final String value) {
		final String trimmed = value.trim();
		if (trimmed.length() >= 2) {
			final char first = trimmed.charAt(0);
			final char last = trimmed.charAt(trimmed.length() - 1);
			if ((first == '\'' || first == '"') && first == last) {
				return trimmed.substring(1, trimmed.length() - 1);
			}
		}
		return trimmed;
	}

	private static String normalizeType(final String value, final String selector) {
		final String trimmed = stripQuotes(value);
		if (trimmed.isEmpty()) {
			throw new IllegalArgumentException("selector 'type=' has no value: " + selector);
		}
		return trimmed.indexOf(':') < 0 ? "minecraft:" + trimmed : trimmed;
	}

	private static double[] parseDistance(final String value, final String selector) {
		try {
			final int dots = value.indexOf("..");
			if (dots < 0) {
				final double exact = Double.parseDouble(value);
				return new double[]{exact, exact};
			}
			final String lower = value.substring(0, dots).trim();
			final String upper = value.substring(dots + 2).trim();
			final double min = lower.isEmpty() ? 0.0 : Double.parseDouble(lower);
			final double max = upper.isEmpty() ? Double.POSITIVE_INFINITY : Double.parseDouble(upper);
			return new double[]{min, max};
		} catch (final NumberFormatException e) {
			throw new IllegalArgumentException("selector 'distance=" + value + "' is not a number or range: " + selector);
		}
	}

	private static int parseLimit(final String value, final String selector) {
		try {
			return Math.max(Integer.parseInt(value), 0);
		} catch (final NumberFormatException e) {
			throw new IllegalArgumentException("selector 'limit=" + value + "' is not an integer: " + selector);
		}
	}

	private static Sort parseSort(final String value, final String selector) {
		return switch (value.toLowerCase(Locale.ROOT)) {
			case "nearest" -> Sort.NEAREST;
			case "furthest" -> Sort.FURTHEST;
			case "random" -> Sort.RANDOM;
			case "arbitrary" -> Sort.ARBITRARY;
			default -> throw new IllegalArgumentException("selector 'sort=" + value + "' is not nearest/furthest/random/arbitrary: " + selector);
		};
	}
}

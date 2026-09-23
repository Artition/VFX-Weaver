package dev.vfxweaver.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Brigadier argument for a brace-wrapped parameter map: {@code {[name:value],[name:value]}}
 * (e.g. {@code {[radius:8],[strength:0.5]}}).
 *
 * <p>The parser is <b>lenient</b>: an input that is still being typed (any prefix like
 * {@code {[radius:} or {@code {[radius:8],}) parses successfully with only the fully
 * completed pairs collected, so the attached suggestion provider keeps firing while the
 * user types. Malformed characters still stop the parse with a normal syntax error. An
 * incomplete map that somehow reaches execution simply applies zero pairs.</p>
 */
public final class ParamMapArgument implements ArgumentType<Map<String, Float>> {
	/** Mirrors {@code VFXTimeline.MAX_OVERRIDES} (command input, see AGENTS.md). */
	public static final int MAX_PARAMS = 256;

	@Override
	public Map<String, Float> parse(final StringReader reader) throws CommandSyntaxException {
		final Map<String, Float> params = new LinkedHashMap<>();
		reader.expect('{');
		while (true) {
			final String name;
			final float value;
			final int pairStart = reader.getCursor();
			try {
				reader.expect('[');
				name = reader.readStringUntil(':');
				value = reader.readFloat();
				reader.expect(']');
			} catch (CommandSyntaxException e) {
				if (!reader.canRead()) {
					// Ran out of input mid-pair — an in-progress prefix, not an error.
					// Drop the partial pair and consume the typed text so the parse ends
					// cleanly at EOF (keeps live suggestions working).
					reader.setCursor(pairStart);
					while (reader.canRead()) {
						reader.skip();
					}
					break;
				}
				throw e;
			}
			if (params.size() < MAX_PARAMS) {
				params.put(name.trim(), value);
			}
			if (!reader.canRead()) {
				break;
			}
			reader.skipWhitespace();
			if (!reader.canRead()) {
				break;
			}
			final char next = reader.peek();
			if (next == ',') {
				reader.skip();
				continue;
			}
			if (next == '}') {
				reader.skip();
				break;
			}
			break;
		}
		return params;
	}

	@Override
	public String toString() {
		return "vfx_param_map()";
	}
}

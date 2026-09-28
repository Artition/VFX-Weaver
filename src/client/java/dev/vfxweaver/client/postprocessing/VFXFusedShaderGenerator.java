package dev.vfxweaver.client.postprocessing;

import dev.vfxweaver.client.postprocessing.VFXFusionPlanner.StageRef;
import dev.vfxweaver.client.postprocessing.VFXFusedPrograms.Binding;
import dev.vfxweaver.client.postprocessing.VFXFusedPrograms.Kind;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Generates one fused fragment source for a run of post stages (spec §6).
 *
 * <p>The generator is <b>strictly fail-closed</b>: a stage whose source is anything other than a
 * version header, imports, declarations and a single {@code main}, or that declares a construct the
 * renamer does not fully understand, makes the whole run {@code null} ({@code UNUSABLE}) so the
 * planner falls back to {@code Single}s. It never guesses.
 *
 * <p>The rename rules are the spec's: per-stage samplers {@code s<k>_<Name>}, field functions
 * {@code fn_s<k>}, merged {@code Config} members {@code e<k>_<member>} in {@code [params][field
 * members]} stage order, {@code DepthSampler} left unrenamed, and {@code vfxQ8} applied between the
 * stages. Each stage becomes {@code vec4 fx<k>(vec2 texCoord)} reading {@code fx<k-1>q} (stage 0
 * reads {@code vfxIn}), and {@code main} returns the last stage's quantised value.
 */
public final class VFXFusedShaderGenerator {
	private static final String VERSION = "#version 330";
	private static final String SAMPLER_INFO_BLOCK = "layout(std140) uniform SamplerInfo {\n\tvec2 OutSize;\n\tvec2 InSize;\n};";

	private static final Pattern VERSION_LINE = Pattern.compile("^\\s*#version\\b.*$");
	private static final Pattern IMPORT_LINE = Pattern.compile("^\\s*#moj_import\\s*<([^>]+)>.*$");
	private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*\\)\\s*\\{");
	private static final Pattern BLOCK = Pattern.compile("layout\\s*\\(\\s*std140\\s*\\)\\s*uniform\\s+(\\w+)\\s*\\{(.*?)\\}\\s*;", Pattern.DOTALL);
	private static final Pattern SAMPLER = Pattern.compile("uniform\\s+sampler2D\\s+(\\w+)\\s*;");
	private static final Pattern TEXTURE_IN = Pattern.compile("texture\\s*\\(\\s*InSampler\\s*,");
	private static final Pattern TEXTURE_HISTORY = Pattern.compile("texture\\s*\\(\\s*HistSampler\\s*,");
	private static final Pattern FIELD_FUNCTION = Pattern.compile("(?m)^\\s*[\\w<>]+\\s+(\\w+)\\s*\\(");
	private static final String FIELD_INCLUDE = "vfxweaver:field.glsl";
	private static final String FIELD_BODY_INCLUDE = "vfxweaver:field_body.glsl";

	/** A stage to generate for: its fragment asset key and whether it is a mask consumer. */
	record StageAsset(Identifier location, boolean mask) {
	}

	/**
	 * The generated source plus the metadata the cache and manager need.
	 *
	 * @param source           the merged fragment source
	 * @param bindings         the run-level and per-stage sampler bindings
	 * @param configParamCount the merged {@code Config} member count ({@code params + field members})
	 * @param configUboSize    the merged {@code Config} std140 byte size
	 */
	public record Generated(String source, List<Binding> bindings, int configParamCount, int configUboSize) {
	}

	/**
	 * Generates the fused source for the planner's stages.
	 *
	 * @param stages the run's stages in chain order
	 * @param source resolves a fragment asset ({@code post/...} or {@code include/...}) to its raw text
	 * @return the generated program, or {@code null} when any stage is {@code UNUSABLE}
	 */
	public static @Nullable Generated generate(final List<StageRef> stages, final Function<Identifier, @Nullable String> source) {
		final List<StageAsset> assets = new ArrayList<>(stages.size());
		for (final StageRef stage : stages) {
			assets.add(new StageAsset(stage.info().pipeline().getLocation(), stage.mask()));
		}
		return generateAssets(assets, source);
	}

	static @Nullable Generated generateAssets(final List<StageAsset> stages, final Function<Identifier, @Nullable String> source) {
		if (stages.isEmpty()) {
			return null;
		}
		final List<Stage> parsed = new ArrayList<>(stages.size());
		boolean usesField = false;
		for (final StageAsset asset : stages) {
			final String text = source.apply(asset.location());
			if (text == null) {
				return null;
			}
			final Stage stage = parseStage(text, asset.mask());
			if (stage == null) {
				return null;
			}
			usesField |= stage.imports().contains(FIELD_INCLUDE);
			parsed.add(stage);
		}
		Field field = null;
		if (usesField) {
			field = loadField(source);
			if (field == null) {
				return null;
			}
		}

		final StringBuilder out = new StringBuilder();
		out.append(VERSION).append('\n');
		out.append("uniform sampler2D InSampler;\n");
		final Set<String> samplerDeclarations = new LinkedHashSet<>();
		if (field != null) {
			samplerDeclarations.add("uniform sampler2D DepthSampler;");
		}
		for (int k = 0; k < parsed.size(); k++) {
			for (final String sampler : parsed.get(k).samplers()) {
				if (sampler.equals("HistSampler") && k != 1) {
					continue;
				}
				samplerDeclarations.add("uniform sampler2D s" + k + "_" + sampler + ";");
			}
			if (parsed.get(k).imports().contains(FIELD_INCLUDE)) {
				samplerDeclarations.add("uniform sampler2D s" + k + "_fld_tex0;");
			}
		}
		for (final String declaration : samplerDeclarations) {
			out.append(declaration).append('\n');
		}
		out.append(SAMPLER_INFO_BLOCK).append('\n');
		out.append("layout(std140) uniform Config {\n");
		for (int k = 0; k < parsed.size(); k++) {
			for (final Member member : parsed.get(k).configMembers()) {
				out.append("\tfloat e").append(k).append('_').append(member.name()).append(";\n");
			}
			if (parsed.get(k).imports().contains(FIELD_INCLUDE)) {
				for (final Member member : field.members()) {
					out.append('\t').append(member.type()).append(" e").append(k).append('_').append(member.name()).append(";\n");
				}
			}
		}
		out.append("};\n");
		out.append("in vec2 texCoord;\nout vec4 fragColor;\n");
		out.append("vec4 vfxQ8(vec4 c) { return floor(clamp(c, 0.0, 1.0) * 255.0 + 0.5) / 255.0; }\n");
		out.append("vec4 vfxIn(vec2 uv) { return texture(InSampler, uv); }\n");
		for (final String shared : field == null ? List.<String>of() : field.shared()) {
			out.append(shared).append('\n');
		}
		if (field != null) {
			for (int k = 0; k < parsed.size(); k++) {
				if (parsed.get(k).imports().contains(FIELD_INCLUDE)) {
					out.append(renameField(field.body(), k, field)).append('\n');
				}
			}
		}
		for (int k = 0; k < parsed.size(); k++) {
			final Stage stage = parsed.get(k);
			String body = replaceWord(stage.body(), "fragColor", "vfxColor");
			body = TEXTURE_IN.matcher(body).replaceAll(Matcher.quoteReplacement(k == 0 ? "vfxIn(" : "fx" + (k - 1) + "q("));
			if (stage.mask()) {
				body = replaceWord(body, "CoverageSampler", "s" + k + "_CoverageSampler");
				if (k == 1) {
					body = replaceWord(body, "HistSampler", "s1_HistSampler");
				} else {
					body = TEXTURE_HISTORY.matcher(body).replaceAll(Matcher.quoteReplacement("fx" + (k - 2) + "q("));
				}
			}
			for (final Member member : stage.configMembers()) {
				body = replaceWord(body, member.name(), "e" + k + "_" + member.name());
			}
			if (stage.imports().contains(FIELD_INCLUDE)) {
				body = renameField(body, k, field);
			}
			body = body.replaceAll("\\breturn\\s*;", "return vfxColor;");
			out.append("vec4 fx").append(k).append("(vec2 texCoord) {\n");
			out.append("\tvec4 vfxColor = vec4(0.0);\n");
			out.append(body);
			out.append("\treturn vfxColor;\n}\n");
			out.append("vec4 fx").append(k).append("q(vec2 texCoord) { return vfxQ8(fx").append(k).append("(texCoord)); }\n");
		}
		out.append("void main() { fragColor = fx").append(parsed.size() - 1).append("q(texCoord); }\n");

		return new Generated(out.toString(), bindings(parsed), configCount(parsed, field), configSize(parsed, field));
	}

	private static String renameField(final String text, final int k, final Field field) {
		String result = text;
		result = replaceWord(result, "vfx_field_intensity", "vfx_field_intensity_s" + k);
		result = replaceWord(result, "fld_tex0", "s" + k + "_fld_tex0");
		for (final String function : field.functions()) {
			result = replaceWord(result, function, function + "_s" + k);
		}
		for (final Member member : field.members()) {
			result = replaceWord(result, member.name(), "e" + k + "_" + member.name());
		}
		return result;
	}

	private static List<Binding> bindings(final List<Stage> stages) {
		final List<Binding> bindings = new ArrayList<>();
		bindings.add(new Binding(Kind.INPUT, "InSampler", 0));
		boolean depth = false;
		for (int k = 0; k < stages.size(); k++) {
			if (stages.get(k).imports().contains(FIELD_INCLUDE)) {
				bindings.add(new Binding(Kind.FIELD, "s" + k + "_fld_tex0", k));
				if (!depth) {
					bindings.add(new Binding(Kind.DEPTH, "DepthSampler", -1));
					depth = true;
				}
			}
			if (stages.get(k).mask()) {
				bindings.add(new Binding(Kind.COVERAGE, "s" + k + "_CoverageSampler", k));
				if (k == 1) {
					bindings.add(new Binding(Kind.HISTORY, "s1_HistSampler", k));
				}
			}
		}
		return List.copyOf(bindings);
	}

	private static int configCount(final List<Stage> stages, final @Nullable Field field) {
		int count = 0;
		for (final Stage stage : stages) {
			count += stage.configMembers().size();
			if (field != null && stage.imports().contains(FIELD_INCLUDE)) {
				count += field.members().size();
			}
		}
		return count;
	}

	private static int configSize(final List<Stage> stages, final @Nullable Field field) {
		int size = 0;
		for (final Stage stage : stages) {
			for (final Member member : stage.configMembers()) {
				size = align(size, 4) + 4;
			}
			if (field != null && stage.imports().contains(FIELD_INCLUDE)) {
				for (final Member member : field.members()) {
					final int alignment = std140Alignment(member.type());
					size = align(size, alignment) + std140Width(member.type());
				}
			}
		}
		return align(size, 16);
	}

	private static int std140Alignment(final String type) {
		return switch (type) {
			case "float" -> 4;
			case "vec2" -> 8;
			default -> 16;
		};
	}

	private static int std140Width(final String type) {
		return switch (type) {
			case "float" -> 4;
			case "vec2" -> 8;
			case "vec3" -> 12;
			case "mat4" -> 64;
			default -> 16;
		};
	}

	private static int align(final int offset, final int alignment) {
		return (offset + alignment - 1) / alignment * alignment;
	}

	private record Member(String type, String name) {
	}

	private record Stage(List<String> imports, List<Member> configMembers, List<String> samplers, String body, boolean mask) {
	}

	private record Field(List<Member> members, List<String> functions, String body, List<String> shared) {
	}

	private record Header(List<String> imports, List<Member> configMembers, List<String> samplers) {
	}

	private static @Nullable Stage parseStage(final String source, final boolean mask) {
		final String text = stripComments(source);
		final Matcher main = MAIN.matcher(text);
		if (!main.find()) {
			return null;
		}
		final int open = text.indexOf('{', main.start());
		final int close = matchingBrace(text, open);
		if (open < 0 || close < 0 || !text.substring(close + 1).trim().isEmpty()) {
			return null;
		}
		final Header header = parseHeader(text.substring(0, main.start()));
		if (header == null) {
			return null;
		}
		final boolean usesField = header.imports().contains(FIELD_INCLUDE);
		if (!usesField && (text.contains("DepthSampler") || text.contains("fld_tex0"))) {
			return null;
		}
		if (header.samplers().contains("HistSampler") && !mask) {
			return null;
		}
		for (final String imported : header.imports()) {
			if (!imported.equals(FIELD_INCLUDE)) {
				return null;
			}
		}
		return new Stage(header.imports(), header.configMembers(), header.samplers(), text.substring(open + 1, close), mask);
	}

	private static @Nullable Header parseHeader(final String raw) {
		final List<String> imports = new ArrayList<>();
		final StringBuilder declarations = new StringBuilder();
		for (final String line : raw.split("\n", -1)) {
			final String trimmed = line.trim();
			if (trimmed.isEmpty() || VERSION_LINE.matcher(trimmed).matches()) {
				continue;
			}
			final Matcher imported = IMPORT_LINE.matcher(trimmed);
			if (imported.matches()) {
				imports.add(imported.group(1));
				continue;
			}
			declarations.append(trimmed).append('\n');
		}
		final List<Member> configMembers = new ArrayList<>();
		final List<String> samplers = new ArrayList<>();
		final StringBuilder body = new StringBuilder();
		final Matcher blocks = BLOCK.matcher(declarations);
		int cursor = 0;
		while (blocks.find()) {
			body.append(declarations, cursor, blocks.start());
			final String name = blocks.group(1);
			final List<Member> members = parseMembers(blocks.group(2));
			if (members == null || (!name.equals("Config") && !name.equals("SamplerInfo"))) {
				return null;
			}
			if (name.equals("Config")) {
				configMembers.addAll(members);
			}
			cursor = blocks.end();
		}
		body.append(declarations, cursor, declarations.length());
		final StringBuilder afterSamplers = new StringBuilder();
		final Matcher samplerMatcher = SAMPLER.matcher(body);
		cursor = 0;
		while (samplerMatcher.find()) {
			afterSamplers.append(body, cursor, samplerMatcher.start());
			final String name = samplerMatcher.group(1);
			if (!name.equals("InSampler") && !name.equals("DepthSampler")) {
				samplers.add(name);
			}
			cursor = samplerMatcher.end();
		}
		afterSamplers.append(body, cursor, body.length());
		for (final String statement : afterSamplers.toString().split(";")) {
			final String trimmed = statement.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			if (!trimmed.equals("in vec2 texCoord") && !trimmed.equals("out vec4 fragColor")) {
				return null;
			}
		}
		return new Header(imports, configMembers, samplers);
	}

	private static @Nullable Field loadField(final Function<Identifier, @Nullable String> source) {
		final String text = source.apply(Identifier.fromNamespaceAndPath("vfxweaver", "include/field.glsl"));
		if (text == null) {
			return null;
		}
		final String stripped = stripComments(text);
		final List<Member> members = new ArrayList<>();
		final List<String> shared = new ArrayList<>();
		String body = null;
		for (final String line : stripped.split("\n", -1)) {
			final Matcher imported = IMPORT_LINE.matcher(line.trim());
			if (!imported.matches()) {
				continue;
			}
			final String include = imported.group(1);
			final String includeText = source.apply(includeIdentifier(include));
			if (includeText == null) {
				return null;
			}
			if (include.equals(FIELD_BODY_INCLUDE)) {
				body = stripImports(stripComments(includeText));
			} else {
				shared.add(stripImports(stripComments(includeText)));
			}
		}
		if (body == null) {
			return null;
		}
		final Matcher blocks = BLOCK.matcher(stripped);
		while (blocks.find()) {
			if (blocks.group(1).equals("FieldConfig")) {
				final List<Member> parsed = parseMembers(blocks.group(2));
				if (parsed == null) {
					return null;
				}
				members.addAll(parsed);
			}
		}
		if (members.isEmpty()) {
			return null;
		}
		final List<String> functions = new ArrayList<>();
		final Matcher functionMatcher = FIELD_FUNCTION.matcher(body);
		while (functionMatcher.find()) {
			final String name = functionMatcher.group(1);
			if (!name.equals("if") && !name.equals("for") && !name.equals("while")) {
				functions.add(name);
			}
		}
		return new Field(members, functions, body, shared);
	}

	private static Identifier includeIdentifier(final String include) {
		final int colon = include.indexOf(':');
		final String namespace = colon < 0 ? "vfxweaver" : include.substring(0, colon);
		final String path = colon < 0 ? include : include.substring(colon + 1);
		return Identifier.fromNamespaceAndPath(namespace, "include/" + path);
	}

	private static String stripImports(final String text) {
		final StringBuilder out = new StringBuilder();
		for (final String line : text.split("\n", -1)) {
			final String trimmed = line.trim();
			if (trimmed.isEmpty() || VERSION_LINE.matcher(trimmed).matches() || IMPORT_LINE.matcher(trimmed).matches()) {
				continue;
			}
			out.append(line).append('\n');
		}
		return out.toString();
	}

	private static @Nullable List<Member> parseMembers(final String inner) {
		final List<Member> members = new ArrayList<>();
		for (final String statement : inner.split(";")) {
			final String trimmed = statement.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			final String[] tokens = trimmed.split("\\s+");
			if (tokens.length < 2) {
				return null;
			}
			String name = tokens[tokens.length - 1];
			final int bracket = name.indexOf('[');
			if (bracket >= 0) {
				name = name.substring(0, bracket);
			}
			members.add(new Member(tokens[0], name));
		}
		return members;
	}

	private static String stripComments(final String source) {
		return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
	}

	private static int matchingBrace(final String text, final int open) {
		int depth = 0;
		for (int i = open; i < text.length(); i++) {
			final char c = text.charAt(i);
			if (c == '{') {
				depth++;
			} else if (c == '}') {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return -1;
	}

	private static String replaceWord(final String text, final String word, final String replacement) {
		return text.replaceAll("\\b" + Pattern.quote(word) + "\\b", Matcher.quoteReplacement(replacement));
	}

	private VFXFusedShaderGenerator() {
	}
}

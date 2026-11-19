package dev.vfxweaver.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Moves large {@code block_list} position arrays out of a mask JSON string and back. */
final class MaskWire {
	static final String MARKER = "@wire:";

	record Split(String json, List<float[]> blobs) {
	}

	static Split split(final String maskJson) {
		final JsonElement root;
		try {
			root = JsonParser.parseString(maskJson);
		} catch (JsonParseException e) {
			return new Split(maskJson, List.of()); // let the client's parser reject it
		}
		final List<float[]> blobs = new ArrayList<>();
		strip(root, blobs);
		return new Split(blobs.isEmpty() ? maskJson : root.toString(), blobs);
	}

	private static void strip(final JsonElement node, final List<float[]> blobs) {
		if (node.isJsonArray()) {
			for (final JsonElement entry : node.getAsJsonArray()) {
				strip(entry, blobs);
			}
			return;
		}
		if (!node.isJsonObject()) {
			return;
		}
		final JsonObject object = node.getAsJsonObject();
		if (isBlockList(object) && object.get("positions") instanceof JsonArray array) {
			final float[] flat = flatten(array);
			if (flat != null) {
				object.addProperty("positions", MARKER + blobs.size());
				blobs.add(flat);
				return;
			}
		}
		for (final var entry : object.entrySet()) {
			strip(entry.getValue(), blobs);
		}
	}

	private static float @Nullable [] flatten(final JsonArray array) {
		if (array.isEmpty()) {
			return null;
		}
		final float[] out = new float[array.size() * 3];
		int i = 0;
		for (final JsonElement entry : array) {
			if (!(entry instanceof JsonArray triple) || triple.size() != 3) {
				return null;
			}
			for (final JsonElement axis : triple) {
				if (!axis.isJsonPrimitive() || !axis.getAsJsonPrimitive().isNumber()) {
					return null;
				}
				out[i++] = axis.getAsFloat();
			}
		}
		return out;
	}

	static String join(final String json, final List<float[]> blobs) {
		if (blobs.isEmpty()) {
			return json;
		}
		try {
			final JsonElement root = JsonParser.parseString(json);
			fill(root, blobs);
			return root.toString();
		} catch (JsonParseException e) {
			return json;
		}
	}

	private static void fill(final JsonElement node, final List<float[]> blobs) {
		if (node.isJsonArray()) {
			for (final JsonElement entry : node.getAsJsonArray()) {
				fill(entry, blobs);
			}
			return;
		}
		if (!node.isJsonObject()) {
			return;
		}
		final JsonObject object = node.getAsJsonObject();
		final JsonElement positions = object.get("positions");
		if (isBlockList(object) && positions instanceof JsonPrimitive primitive
				&& primitive.isString() && primitive.getAsString().startsWith(MARKER)) {
			try {
				final float[] values = blobs.get(Integer.parseInt(primitive.getAsString().substring(MARKER.length())));
				final JsonArray array = new JsonArray();
				for (int i = 0; i < values.length; i += 3) {
					final JsonArray triple = new JsonArray();
					triple.add(values[i]);
					triple.add(values[i + 1]);
					triple.add(values[i + 2]);
					array.add(triple);
				}
				object.add("positions", array);
			} catch (RuntimeException ignored) {
				// the marker stays, VFXMaskParser rejects it and the old mask is kept
			}
			return;
		}
		for (final var entry : object.entrySet()) {
			fill(entry.getValue(), blobs);
		}
	}

	private static boolean isBlockList(final JsonObject object) {
		final JsonElement shape = object.get("shape");
		return shape != null && shape.isJsonPrimitive() && shape.getAsJsonPrimitive().isString()
				&& "block_list".equals(shape.getAsString());
	}
}

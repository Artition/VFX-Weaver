package dev.vfxweaver.effect;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;

/**
 * The structural half of a {@code screen_image} effect: the resource id of the picture drawn onto
 * the game's own framebuffer. The animatable numbers (position, size, opacity, layer and frame)
 * stay in ordinary {@code params}. The string stays in the definition and never travels on the
 * wire, so the block is additive and invisible to an older mod.
 */
public final class VFXScreenImageSpec {
	private final Identifier texture;

	private VFXScreenImageSpec(final Identifier texture) {
		this.texture = texture;
	}

	/**
	 * Parses and validates the top-level {@code texture} of a {@code screen_image} definition.
	 *
	 * @param json the effect object
	 * @return the parsed spec, never {@code null}
	 * @throws IllegalArgumentException when {@code texture} is missing, not a string or blank
	 */
	public static VFXScreenImageSpec parse(final JsonObject json) {
		if (json == null) {
			throw new IllegalArgumentException("screen_image: expected an object");
		}
		final JsonElement element = json.get("texture");
		if (element == null || element.isJsonNull() || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException("screen_image: 'texture' is required and must be a resource id string");
		}
		final String raw = element.getAsString();
		if (raw.isBlank()) {
			throw new IllegalArgumentException("screen_image: 'texture' must be a non-blank resource id");
		}
		return new VFXScreenImageSpec(Identifier.parse(raw));
	}

	/** The picture resource id. */
	public Identifier texture() {
		return this.texture;
	}
}

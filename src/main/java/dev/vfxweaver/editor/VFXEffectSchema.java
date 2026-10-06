package dev.vfxweaver.editor;

import com.google.gson.Gson;
import dev.vfxweaver.effect.VFXEffectType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The registry of editable effect schemas, keyed by {@link VFXEffectType}. Descriptors are
 * registered once at client init (one per effect type) and read by the visual node editor;
 * {@link #toJson()} exports the whole registry for the editor UI.
 */
public final class VFXEffectSchema {
	private static final Map<VFXEffectType, VFXEffectDescriptor> DESCRIPTORS = new LinkedHashMap<>();
	private static final Gson GSON = new Gson();

	private VFXEffectSchema() {
	}

	/**
	 * Returns the registered schema for {@code type}.
	 *
	 * @param type the effect type
	 * @return the descriptor, or {@code null} when none is registered
	 */
	@Nullable
	public static VFXEffectDescriptor get(final VFXEffectType type) {
		return DESCRIPTORS.get(type);
	}

	/**
	 * Registers the schema of an effect type.
	 *
	 * @param descriptor the descriptor to register
	 * @throws IllegalStateException when {@code descriptor.type()} is already registered
	 */
	public static void register(final VFXEffectDescriptor descriptor) {
		Objects.requireNonNull(descriptor, "descriptor");
		if (DESCRIPTORS.putIfAbsent(descriptor.type(), descriptor) != null) {
			throw new IllegalStateException("Duplicate effect schema for " + descriptor.type());
		}
	}

	/**
	 * Serialises the registry to JSON, shaped as
	 * {@code { "<type name>": { "params": [ ... ] } }} with the shader-style lowercase type
	 * name ({@link VFXEffectType#getName()}) as the key.
	 *
	 * @return the JSON export
	 */
	public static String toJson() {
		final Map<String, Object> root = new LinkedHashMap<>();
		for (final VFXEffectDescriptor descriptor : DESCRIPTORS.values()) {
			root.put(descriptor.type().getName(), Map.of("params", descriptor.params()));
		}
		return GSON.toJson(root);
	}
}

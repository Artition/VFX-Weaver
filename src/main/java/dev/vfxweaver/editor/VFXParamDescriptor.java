package dev.vfxweaver.editor;

import java.util.List;
import java.util.Objects;

/**
 * One editable parameter of an effect type: its value kind, UI grouping and the
 * capabilities the visual node editor exposes for it.
 *
 * @param name         the parameter name as used by the effect's Config block
 * @param kind         the value kind
 * @param defaultValue the initial scalar value
 * @param min          the soft minimum (inclusive); meaningless when unbounded
 * @param max          the soft maximum (inclusive); meaningless when unbounded
 * @param step         the UI drag/step increment
 * @param group        the editor tab/group this parameter belongs to
 * @param animatable   whether the value can be keyframed over time
 * @param bindable     whether the value can be bound to another parameter
 * @param fieldCapable whether the value accepts a per-pixel field form
 * @param enumValues   the allowed values for {@link VFXParamKind#ENUM}, otherwise empty
 * @param neutral      the value that leaves the scene unchanged, or {@code null} when it must not fade
 */
public record VFXParamDescriptor(
	String name,
	VFXParamKind kind,
	float defaultValue,
	float min,
	float max,
	float step,
	String group,
	boolean animatable,
	boolean bindable,
	boolean fieldCapable,
	List<String> enumValues,
	Float neutral
) {
	public VFXParamDescriptor {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(kind, "kind");
		group = group == null ? "" : group;
		enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
	}
}

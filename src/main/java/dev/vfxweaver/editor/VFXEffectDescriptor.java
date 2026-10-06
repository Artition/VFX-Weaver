package dev.vfxweaver.editor;

import dev.vfxweaver.effect.VFXEffectType;
import java.util.List;
import java.util.Objects;

/**
 * The editable schema of one {@link VFXEffectType}: the ordered list of parameters the
 * visual node editor shows for it.
 *
 * @param type   the effect type this descriptor describes
 * @param params the editable parameters, in display order
 */
public record VFXEffectDescriptor(VFXEffectType type, List<VFXParamDescriptor> params) {
	public VFXEffectDescriptor {
		Objects.requireNonNull(type, "type");
		params = params == null ? List.of() : List.copyOf(params);
	}
}

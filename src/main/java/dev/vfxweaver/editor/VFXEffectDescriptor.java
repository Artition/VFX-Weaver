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
 * @param post   whether the effect renders as a screen post-processing layer
 *               ({@link VFXEffectType#isPostProcessing()}); the editor reads it to classify a
 *               node instead of keeping a static type list
 */
public record VFXEffectDescriptor(VFXEffectType type, List<VFXParamDescriptor> params, boolean post) {
	public VFXEffectDescriptor {
		Objects.requireNonNull(type, "type");
		params = params == null ? List.of() : List.copyOf(params);
	}
}

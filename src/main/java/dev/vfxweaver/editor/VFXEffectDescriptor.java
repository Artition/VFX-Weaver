package dev.vfxweaver.editor;

import dev.vfxweaver.effect.VFXEffectType;
import java.util.List;
import java.util.Objects;

/**
 * The editable schema of one {@link VFXEffectType}: the ordered list of parameters the
 * visual node editor shows for it.
 *
 * @param type       the effect type this descriptor describes
 * @param params     the editable parameters, in display order
 * @param post       whether the effect renders as a screen post-processing layer
 *                   ({@link VFXEffectType#isPostProcessing()}); the editor reads it to classify a
 *                   node instead of keeping a static type list
 * @param structural the non-parameter (structural) affordances, e.g. the mask (design §13.1), the
 *                   world positions/region, the entity selector and the sound (design §13.4); the
 *                   editor reads it to decide which sub-editor to offer. {@code null} derives it
 *                   from {@code type}, so an existing 3-argument construction is unchanged
 */
public record VFXEffectDescriptor(VFXEffectType type, List<VFXParamDescriptor> params, boolean post,
		VFXStructuralDescriptor structural) {
	public VFXEffectDescriptor {
		Objects.requireNonNull(type, "type");
		params = params == null ? List.of() : List.copyOf(params);
		structural = structural == null ? VFXStructuralDescriptor.of(type) : structural;
	}

	/**
	 * Backwards-compatible constructor (the pre-{@code structural} signature): derives
	 * {@link #structural()} from {@code type}.
	 *
	 * @param type   the effect type
	 * @param params the editable parameters
	 * @param post   whether the effect is a screen post-processing layer
	 */
	public VFXEffectDescriptor(final VFXEffectType type, final List<VFXParamDescriptor> params, final boolean post) {
		this(type, params, post, null);
	}
}

package dev.vfxweaver.editor;

import dev.vfxweaver.mask.VFXMaskShapeKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The non-parameter (structural) affordances of one effect type: which optional sub-structures the
 * visual node editor may offer for it (design §13.1). Additive beside the parameter list — a
 * descriptor without it behaves exactly as before this section existed.
 *
 * @param supportsMask whether the effect accepts a top-level {@code mask} block. Only a
 *                     post-processing effect does ({@link dev.vfxweaver.effect.VFXEffectType#isPostProcessing()});
 *                     a world/camera effect ignores the mask, so the editor must not offer it.
 * @param maskShapes   the available mask leaf shape ids, in {@link VFXMaskShapeKind} declaration
 *                     order. The editor populates its shape combo from this list instead of
 *                     hardcoding the shapes, so a new kind appears without an editor change. The
 *                     {@code block} geometry and custom-shape families are not enum kinds and are
 *                     therefore not listed here.
 */
public record VFXStructuralDescriptor(boolean supportsMask, List<String> maskShapes) {
	/** The mask shape ids, derived once from the shared {@link VFXMaskShapeKind} enum. */
	private static final List<String> SHAPE_IDS = shapeIds();

	public VFXStructuralDescriptor {
		Objects.requireNonNull(maskShapes, "maskShapes");
		maskShapes = List.copyOf(maskShapes);
	}

	/**
	 * The structural affordances of an effect type.
	 *
	 * @param post whether the effect renders as a screen post-processing layer
	 * @return a descriptor whose {@link #supportsMask()} matches {@code post}
	 */
	public static VFXStructuralDescriptor of(final boolean post) {
		return new VFXStructuralDescriptor(post, SHAPE_IDS);
	}

	/**
	 * Derives the mask shape id list from {@link VFXMaskShapeKind}, so the editor's combo cannot
	 * drift from the parser's shape table.
	 *
	 * @return the shape ids in enum declaration order
	 */
	private static List<String> shapeIds() {
		final List<String> ids = new ArrayList<>(VFXMaskShapeKind.values().length);
		for (final VFXMaskShapeKind kind : VFXMaskShapeKind.values()) {
			ids.add(kind.id());
		}
		return List.copyOf(ids);
	}
}

package dev.vfxweaver.mask;

import dev.vfxweaver.effect.BoundParam;
import org.jspecify.annotations.Nullable;

/**
 * One leaf shape of a mask: the shared shape kind and space, the resolved centre, rotation, the
 * per-kind numeric parameters, fill/stroke, the falloff and its optional edge field. Immutable
 * after parse. The shape's distance math is not here — it lives in the shared shape/field library,
 * and this record only carries what the coverage prepass uploads.
 *
 * @param shape              the shared shape kind, or {@code null} for a block/custom leaf
 * @param space              the space this leaf is classified in
 * @param centerSlots        reserved slot names for x (and y/z), length 2 or 3 by space
 * @param centerDefaults     default centre, parallel to {@code centerSlots}
 * @param rotationSlot       reserved slot name for the rotation
 * @param rotationDefault    rotation in the shared unit (degrees)
 * @param parameterSlots     per-kind numeric slot names, parallel to the shape's parameter names
 * @param parameterDefaults  per-kind default values
 * @param fill               solid or stroke
 * @param strokeSlot         reserved slot name for the stroke width (stroke only)
 * @param strokeDefault      stroke width when the slot is absent
 * @param softnessSlot       reserved slot name for the falloff width
 * @param softnessDefault    falloff width when the slot is absent
 * @param volumeMode         how a world {@code sphere}/{@code box} volume is evaluated ({@code surface}
 *                           or {@code aura}); {@link VFXMaskVolumeMode#SURFACE} for every other leaf
 * @param field              edge-perturbation kind
 * @param fieldAmountSlot    reserved slot name for the field amount (distance units)
 * @param fieldAmountDefault field amount when the slot is absent
 * @param fieldScaleSlot     reserved slot name for the field scale
 * @param fieldScaleDefault  field scale when the slot is absent
 * @param fieldSeed          field seed, fixed at parse time
 * @param blockSelection     the block-geometry selection for a block leaf, or {@code null}
 * @param occlude            for a block leaf, whether its rasterised model geometry is occluded by
 *                           the scene depth (a wall in front hides the mask). Ignored for every
 *                           other family; defaults to {@code true}, so a block mask is depth-tested
 *                           unless it opts into the see-through ("x-ray") look with
 *                           {@code "occlude": false}
 * @param customShape        the registered custom-shape id for a composed/plugin leaf, or {@code null}
 * @param centerBinding      a world-point binding that overrides the centre (a {@code POINT} binding), or a
 *                           derived {@code SCREEN_RECT} binding on a screen {@code rect} that also sets the
 *                           half-extents; {@code null} for a literal/graph centre
 * @param sizeBinding        a size-only world binding, or {@code null} (reserved; v1 packs none)
 */
public record VFXMaskPrimitive(
	@Nullable VFXMaskShapeKind shape,
	VFXMaskSpace space,
	String[] centerSlots,
	float[] centerDefaults,
	String rotationSlot,
	float rotationDefault,
	String[] parameterSlots,
	float[] parameterDefaults,
	VFXMaskFill fill,
	String strokeSlot,
	float strokeDefault,
	String softnessSlot,
	float softnessDefault,
	VFXMaskVolumeMode volumeMode,
	VFXMaskField field,
	String fieldAmountSlot,
	float fieldAmountDefault,
	String fieldScaleSlot,
	float fieldScaleDefault,
	float fieldSeed,
	@Nullable VFXMaskBlockSelection blockSelection,
	boolean occlude,
	@Nullable String customShape,
	@Nullable BoundParam centerBinding,
	@Nullable BoundParam sizeBinding
) {
	public VFXMaskPrimitive {
		centerSlots = centerSlots.clone();
		centerDefaults = centerDefaults.clone();
		parameterSlots = parameterSlots.clone();
		parameterDefaults = parameterDefaults.clone();
	}

	/**
	 * The family discriminator: a built-in shape leaf has a non-null {@link #shape()}; a block leaf
	 * has a {@link #blockSelection()} and a custom leaf a {@link #customShape()}. Exactly one of the
	 * three is set.
	 */
	public enum Family {
		SHAPE, BLOCK, CUSTOM
	}

	/** The shader kind code: the built-in ordinal for a shape leaf, {@code 6} for block, {@code 7} for custom. */
	public int kindCode() {
		if (this.blockSelection != null) {
			return 6;
		}
		if (this.customShape != null) {
			return 7;
		}
		return this.shape() == VFXMaskShapeKind.SKY ? 8 : this.shape().ordinal();
	}

	/** Which of the three mask families this leaf belongs to. */
	public Family family() {
		if (this.blockSelection != null) {
			return Family.BLOCK;
		}
		return this.customShape != null ? Family.CUSTOM : Family.SHAPE;
	}
}

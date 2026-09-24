package dev.vfxweaver.mask;

import dev.vfxweaver.effect.BoundParam;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A parsed, validated optional mask (spec §4). A mask evaluates to a coverage in {@code [0,1]}:
 * every leaf shape yields a signed distance {@code d} (negative inside) from the shared shape
 * library in its own space, its edge is perturbed in distance space
 * ({@code d' = d + field * amount}), the falloff is applied once
 * ({@code coverage = clamp(0.5 - d' / max(softness, eps), 0, 1)}), the leaves are folded
 * left-associatively with {@link #ops()} (union = max, intersection = min,
 * difference = a * (1 - b)) and {@code invert} is applied once afterwards.
 *
 * <p>The three families are all {@link VFXMaskPrimitive} leaves: a built-in 2D/3D shape yields a
 * distance from the shared library; a **block** leaf yields its coverage from a rasterised
 * geometry scratch (the model geometry of the selected blocks, not the voxel cell); a **custom**
 * leaf resolves a registered composed SDF or GLSL plugin. The composition above is family-agnostic.
 *
 * <p>The numeric leaves are exposed as {@link MaskSlot}s; the definition registers them as
 * parameters and graph inputs so any mask number can be animated with
 * {@code { "from": "<node>" }}.
 */
public final class VFXMask {
	/** Maximum leaves in one mask (spec §8). */
	public static final int MAX_PRIMITIVES = VFXMaskSlots.MAX_PRIMITIVES;
	/** Maximum composition nesting depth (spec §8). */
	public static final int MAX_COMPOSITION_DEPTH = 8;

	/**
	 * One numeric mask leaf. {@code graphNode} is non-null when the authored value was
	 * {@code { "from": "<node>" }}; {@code binding} is non-null when it was a world-coordinate
	 * {@code { "bind": ... }} form. At most one of the two is set. A bound slot resolves through
	 * {@link dev.vfxweaver.effect.VFXWorldBindings} at render time and falls back to
	 * {@code defaultValue}.
	 */
	public record MaskSlot(String name, float defaultValue, @Nullable String graphNode, @Nullable BoundParam binding) {
	}

	private final boolean invert;
	private final List<VFXMaskPrimitive> primitives;
	private final List<VFXMaskOp> ops;
	private final Map<String, MaskSlot> slots;

	VFXMask(final boolean invert, final List<VFXMaskPrimitive> primitives, final List<VFXMaskOp> ops, final Map<String, MaskSlot> slots) {
		this.invert = invert;
		this.primitives = List.copyOf(primitives);
		this.ops = List.copyOf(ops);
		this.slots = Map.copyOf(slots);
	}

	/**
	 * Parses and validates a {@code mask} block.
	 *
	 * @param owner the effect id, used only in error messages
	 * @param json  the value of the top-level {@code mask} field
	 * @return the parsed mask, never {@code null}
	 * @throws IllegalArgumentException on any validation fault
	 */
	public static VFXMask parse(final String owner, final com.google.gson.JsonObject json) {
		return VFXMaskParser.parse(owner, json);
	}

	public boolean invert() {
		return this.invert;
	}

	/** The leaves in left-to-right composition order. */
	public List<VFXMaskPrimitive> primitives() {
		return this.primitives;
	}

	/** The operators joining leaf {@code i} with leaf {@code i+1}; length is {@code primitives().size()-1}. */
	public List<VFXMaskOp> ops() {
		return this.ops;
	}

	/** Every numeric leaf by reserved name. */
	public Map<String, MaskSlot> slots() {
		return this.slots;
	}

	/** True when at least one leaf needs trustworthy scene depth in the coverage prepass. */
	public boolean needsDepth() {
		for (final VFXMaskPrimitive primitive : this.primitives) {
			if (primitive.space() == VFXMaskSpace.WORLD || primitive.space() == VFXMaskSpace.DOME) {
				return true;
			}
		}
		return false;
	}

	/**
	 * True when at least one leaf is a block-geometry leaf (the prepass then runs the geometry
	 * contribution). At most one block leaf is allowed (the parser enforces it): every block leaf
	 * shares the single geometry scratch.
	 */
	public boolean hasBlockLeaf() {
		for (final VFXMaskPrimitive primitive : this.primitives) {
			if (primitive.family() == VFXMaskPrimitive.Family.BLOCK) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The distinct custom-shape ids this mask references (composed or plugin), in first-use order,
	 * empty when the mask uses no custom shape. The client compiles one shader variant per distinct
	 * set; the cap is enforced when the variants are built.
	 */
	public List<String> customShapeIds() {
		final List<String> ids = new java.util.ArrayList<>();
		for (final VFXMaskPrimitive primitive : this.primitives) {
			if (primitive.customShape() != null && !ids.contains(primitive.customShape())) {
				ids.add(primitive.customShape());
			}
		}
		return List.copyOf(ids);
	}
}

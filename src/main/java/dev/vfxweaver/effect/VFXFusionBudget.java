package dev.vfxweaver.effect;

/**
 * The fusion budget: how much per-pixel work one generated fused pass may carry before the planner
 * has to cut the chain and start a new run.
 *
 * <p>The model is deliberately a sum, not a product of everything. What a fused stage costs is the
 * number of times the prefix is evaluated for it: a pointwise stage is one more evaluation, and a
 * stage that already samples the chain (a mask consumer) is one whose input is the prefix, so it
 * adds one evaluation and one sampler. Only the re-evaluating passes (a {@code UV_REMAP}: a blur-like
 * effect whose shifted taps each re-evaluate the prefix) multiply, and they are the one thing that
 * blows up quadratically - hence {@link #MAX_REMAP_PRODUCT} separately from {@link #MAX_STAGE_EVALS}.
 *
 * <p>Every bound is checked before the next stage is added, so a run can never exceed one; the
 * planner's job is to cut before that, not the budget's job to clamp after.
 */
public final class VFXFusionBudget {
	/** The linear bound: stage evaluations plus one per in-run consumer. */
	public static final int MAX_STAGE_EVALS = 32;

	/** The multiplicative bound: the product of the re-evaluating stages' prefix evaluations. */
	public static final int MAX_REMAP_PRODUCT = 3;

	/** The per-run sampler bound (a stage and each consumer of it can add one). */
	public static final int MAX_SAMPLERS = 12;

	/** The per-run merged-{@code Config} float bound (std140, so the UBO stays one block). */
	public static final int MAX_PARAMS = 384;

	/**
	 * One stage's contribution, computed by the caller from the extractor's parts.
	 *
	 * <p>{@code remaps} is the number of prefix evaluations this stage performs: {@code 1} for
	 * everything that reads the chain only at its own pixel, and a {@code UV_REMAP}'s
	 * {@code prefixEvals} for a stage that re-evaluates shifted taps.
	 */
	public static final class Cost {
		/** Linear evaluations this stage adds ({@code POINT} 1, an in-run consumer 2, a run head 1). */
		public int linear;

		/** Prefix evaluations this stage multiplies into the run ({@code 1} unless it is a remap). */
		public int remaps = 1;

		/** Samplers this stage (or its consumer) binds. */
		public int samplers;

		/** Merged-{@code Config} floats this stage and its consumer contribute. */
		public int params;
	}

	/**
	 * Whether {@code next} still fits in a run that already carries the given totals.
	 *
	 * @param linearTotal the run's linear evaluations so far
	 * @param remapProduct the run's remap product so far ({@code 1} when the run has no remap)
	 * @param samplersTotal the run's bound samplers so far
	 * @param paramsTotal the run's merged-{@code Config} floats so far
	 * @param next the stage being considered
	 * @return true when the stage may join without exceeding any bound
	 */
	public static boolean fits(final int linearTotal, final int remapProduct, final int samplersTotal,
			final int paramsTotal, final Cost next) {
		return remapProduct * next.remaps <= MAX_REMAP_PRODUCT
			&& linearTotal + next.linear <= MAX_STAGE_EVALS
			&& samplersTotal + next.samplers <= MAX_SAMPLERS
			&& paramsTotal + next.params <= MAX_PARAMS;
	}

	private VFXFusionBudget() {
	}
}

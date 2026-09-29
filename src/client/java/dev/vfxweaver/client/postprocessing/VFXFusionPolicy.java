package dev.vfxweaver.client.postprocessing;

import dev.vfxweaver.effect.VFXFusionBudget;
import dev.vfxweaver.util.VFXSettings;

/**
 * The post-chain fusion kill switches, budget bounds and canonical sampler names.
 *
 * <p>Stateless. {@link #enabled()} is on by default and is turned off with
 * {@code -Dvfxweaver.fusion=false} (or the in-game setting); {@link #remapEnabled()} is also on by
 * default now that the filter-exact in-game A/B passed (a frozen frame with {@code color_grade} then
 * {@code distortion}, off versus on, showed no visible difference), so a {@code UV_REMAP} stage is
 * fused unless {@code -Dvfxweaver.fusion.remap=false} (or the in-game setting) turns it off. Both
 * read {@link VFXSettings} on every call, so a change applies without a restart.
 */
public final class VFXFusionPolicy {
	/**
	 * The master switch ({@code -Dvfxweaver.fusion}, default {@code true}).
	 *
	 * <p>It is also inert while no program is annotated: a chain of {@link VFXFusionClass#BARRIER}
	 * stages cannot fuse into anything, so running the planner over it every frame would only
	 * allocate grouping objects for a run that is guaranteed to fall back to single passes. Gating
	 * here means an unannotated build takes exactly the pre-fusion path.
	 *
	 * @return whether fusion may run this frame
	 */
	public static boolean enabled() {
		return VFXSettings.get().fusion() && VFXShaderPrograms.hasAnnotatedProgram();
	}

	/**
	 * Enables {@code UV_REMAP} stages ({@code -Dvfxweaver.fusion.remap=false} disables; default on).
	 *
	 * @return whether the remaps are enabled
	 */
	public static boolean remapEnabled() {
		return VFXSettings.get().remap();
	}

	/** The linear-evaluation bound, from {@link VFXFusionBudget#MAX_STAGE_EVALS}. */
	public static final int MAX_STAGE_EVALS = VFXFusionBudget.MAX_STAGE_EVALS;

	/** The remap-product bound, from {@link VFXFusionBudget#MAX_REMAP_PRODUCT}. */
	public static final int MAX_REMAP_PRODUCT = VFXFusionBudget.MAX_REMAP_PRODUCT;

	/** The per-run sampler bound, from {@link VFXFusionBudget#MAX_SAMPLERS}. */
	public static final int MAX_SAMPLERS = VFXFusionBudget.MAX_SAMPLERS;

	/** The per-run merged-{@code Config} float bound, from {@link VFXFusionBudget#MAX_PARAMS}. */
	public static final int MAX_PARAMS = VFXFusionBudget.MAX_PARAMS;

	/** The compiled-program LRU cap. */
	public static final int MAX_FUSED = 12;

	/** The fused run's input sampler (the chain value entering the run). */
	public static final String INPUT_SAMPLER = "InSampler";

	/** The mask consumer's "before" sampler, declared only by a run-head consumer. */
	public static final String HIST_SAMPLER = "HistSampler";

	/** The mask consumer's coverage sampler. */
	public static final String COVERAGE_SAMPLER = "CoverageSampler";

	private VFXFusionPolicy() {
	}
}

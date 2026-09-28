package dev.vfxweaver.client.postprocessing;

import dev.vfxweaver.effect.VFXFusionBudget;

/**
 * The post-chain fusion kill switches, budget bounds and canonical sampler names.
 *
 * <p>Stateless. {@link #ENABLED} is on by default and is turned off with
 * {@code -Dvfxweaver.fusion=false}; {@link #REMAP_ENABLED} is off unless
 * {@code -Dvfxweaver.fusion.remap} is set, so a {@code UV_REMAP} stage stays a barrier until a
 * filter-exact in-game A/B proves the linear-fetch emulation.
 */
public final class VFXFusionPolicy {
	/** The master switch ({@code -Dvfxweaver.fusion}, default {@code true}). */
	public static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("vfxweaver.fusion", "true"));

	/** Enables {@code UV_REMAP} stages ({@code -Dvfxweaver.fusion.remap}, default off). */
	public static final boolean REMAP_ENABLED = Boolean.parseBoolean(System.getProperty("vfxweaver.fusion.remap", "false"));

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

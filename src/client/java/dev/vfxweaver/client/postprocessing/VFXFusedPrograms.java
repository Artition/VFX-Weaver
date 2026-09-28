package dev.vfxweaver.client.postprocessing;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The fused-program seam: compiles and caches the generated fused pass for a stage topology.
 *
 * <p>Task 5 replaces this stub with the generator, the SHA-256 source key and the bounded LRU
 * ({@link VFXFusionPolicy#MAX_FUSED}) plus poison set. Until then {@link #acquire} always returns
 * {@code null}, so the planner emits every stage as a {@code Single} and nothing fuses yet.
 */
public final class VFXFusedPrograms {
	/** The role of one fused-run binding beyond each stage's own declared samplers. */
	public enum Kind {
		/** The chain value entering the run ({@link VFXFusionPolicy#INPUT_SAMPLER}). */
		INPUT,
		/** The captured pre-run "before" a run-head consumer reads ({@link VFXFusionPolicy#HIST_SAMPLER}). */
		HISTORY,
		/** A mask's coverage texture ({@link VFXFusionPolicy#COVERAGE_SAMPLER}). */
		COVERAGE,
		/** The shared scene depth ({@code DepthSampler}). */
		DEPTH,
		/** A stage's field texture ({@code fld_tex0}). */
		FIELD
	}

	/**
	 * One fused-run binding: the canonical sampler name, its {@link Kind} and the stage it belongs
	 * to ({@code -1} for a run-level binding).
	 */
	public record Binding(Kind kind, String sampler, int stageIndex) {
	}

	/**
	 * A compiled fused pass: the pipeline, its program name, the sampler bindings its merged
	 * {@code Config} expects and the merged uniform block's size.
	 */
	public record FusedProgram(RenderPipeline pipeline, String name, List<Binding> bindings, int configParamCount, int configUboSize) {
	}

	/**
	 * The fused program for this exact stage topology, or {@code null} while fusion is unavailable.
	 *
	 * @param stages the run's stages in chain order
	 * @return the compiled program, or {@code null} (Task 5 implements the generator and cache)
	 */
	public static @Nullable FusedProgram acquire(final List<VFXFusionPlanner.StageRef> stages) {
		return null;
	}

	private VFXFusedPrograms() {
	}
}

package dev.vfxweaver.client.postprocessing;

import dev.vfxweaver.effect.VFXActiveEffect;
import dev.vfxweaver.effect.VFXFusionBudget;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Turns the ordered post-chain stages into {@code Single} passes and {@code Fused} runs under the
 * fusion budget (spec §8).
 *
 * <p>Consecutive fusible stages share a run while {@link VFXFusionBudget#fits} holds; a masked
 * effect and its coverage consumer are grouped atomically, and a run flushes only when it would
 * replace at least two passes and {@link VFXFusedPrograms#acquire} returns a compiled program.
 * Every cost is the one the spec pins: a {@code POINT} stage {@code +1} linear, an in-run consumer
 * {@code +2}, a run-head consumer {@code +1}; only a {@code UV_REMAP} multiplies the run's remap
 * product. The one invariant the planner enforces structurally is at most one run-head consumer per
 * run.
 */
public final class VFXFusionPlanner {
	/** The {@code FieldConfig} float slots a field-using stage adds to the merged {@code Config}. */
	private static final int FIELD_CONFIG_FLOATS = VFXShaderPrograms.FIELD_CONFIG_SIZE / 4;

	/**
	 * One chain entry.
	 *
	 * @param info          the program's static description
	 * @param effect        the running effect instance ({@code null} only in the planner fixtures)
	 * @param mask          true for the shared coverage-read consumer
	 * @param captureBefore true when this stage opens a masked effect's atomic group
	 */
	public record StageRef(VFXShaderPrograms.ProgramInfo info, @Nullable VFXActiveEffect effect, boolean mask, boolean captureBefore) {
	}

	/** One planned step: a single pass, a fused run, or a run's conversion boundary. */
	public sealed interface Step permits Step.Single, Step.Fused, Step.Down, Step.Up {
		/**
		 * A stage rendered as its own pass, exactly as before fusion existed.
		 *
		 * @param stage    the stage to render
		 * @param resScale the target scale relative to full resolution; {@code 1.0F} outside a
		 *                 half-resolution run, {@code 2.0F} inside one
		 */
		record Single(StageRef stage, float resScale) implements Step {
			/** The full-resolution pass every non-scalable stage still renders as. */
			Single(final StageRef stage) {
				this(stage, 1.0F);
			}
		}

		/** A run of stages rendered as one fused program. */
		record Fused(List<StageRef> stages, VFXFusedPrograms.FusedProgram program) implements Step {
		}

		/** The downsample opening a half-resolution scalable run. */
		record Down() implements Step {
		}

		/** The upsample closing a half-resolution scalable run. */
		record Up() implements Step {
		}
	}

	/**
	 * A budget-grouped run before the program cache is consulted.
	 *
	 * <p>{@code linear}/{@code remapProduct}/{@code samplers}/{@code params} are the run's totals
	 * including the run-level {@code InSampler} and any run-head {@code HistSampler}. A non-fusible
	 * run carries zero totals and {@code fusible == false}.
	 */
	record Run(List<StageRef> stages, int linear, int remapProduct, int samplers, int params, boolean headConsumer, boolean fusible) {
	}

	/**
	 * Plans the chain using the real program cache.
	 *
	 * @param chain the ordered stages
	 * @return one step per pass or fused run, in chain order
	 */
	public static List<Step> plan(final List<StageRef> chain) {
		if (!VFXFusionPolicy.ENABLED) {
			final List<Step> steps = new ArrayList<>(chain.size());
			for (final StageRef stage : chain) {
				steps.add(new Step.Single(stage));
			}
			return steps;
		}
		return plan(chain, VFXFusedPrograms::acquire);
	}

	/**
	 * Plans the chain against a caller-supplied acquisition, so the fixtures can exercise the exact
	 * budget path without a generator.
	 */
	static List<Step> plan(final List<StageRef> chain, final Function<List<StageRef>, VFXFusedPrograms.FusedProgram> acquire) {
		final List<Step> steps = new ArrayList<>(chain.size());
		for (final Run run : runs(chain)) {
			final VFXFusedPrograms.FusedProgram program = run.fusible() && run.stages().size() >= 2
				? acquire.apply(run.stages())
				: null;
			if (program != null) {
				steps.add(new Step.Fused(run.stages(), program));
			} else {
				for (final StageRef stage : run.stages()) {
					steps.add(new Step.Single(stage));
				}
			}
		}
		return wrapScalableRuns(steps);
	}

	/**
	 * Wraps every maximal run of consecutive scalable single passes in a {@code Down}/{@code Up}
	 * conversion pair when the chain-resolution switch is on and the run's tap sum reaches
	 * {@link VFXChainResolution#MIN_RUN_TAPS} (spec &sect;4).
	 *
	 * <p>Fusion runs first and is never changed: a scalable stage is a barrier, so it already maps to
	 * its own {@code Single} step, and any fused step, consumer, feedback or stop-motion pass between
	 * two scalable passes breaks the consecutive sequence here exactly as it breaks the run in the
	 * chain. The threshold is a minimum, not a budget, so a run of any length is wrapped once its
	 * sum clears it; a shorter sequence stays at full resolution.
	 */
	private static List<Step> wrapScalableRuns(final List<Step> steps) {
		if (!VFXChainResolution.HALF) {
			return steps;
		}
		final List<Step> wrapped = new ArrayList<>(steps.size());
		int i = 0;
		while (i < steps.size()) {
			if (!(steps.get(i) instanceof Step.Single first) || !scalable(first.stage())) {
				wrapped.add(steps.get(i));
				i++;
				continue;
			}
			int end = i;
			int taps = 0;
			while (end < steps.size() && steps.get(end) instanceof Step.Single single && scalable(single.stage())) {
				taps += single.stage().info().taps();
				end++;
			}
			if (taps >= VFXChainResolution.MIN_RUN_TAPS) {
				wrapped.add(new Step.Down());
				for (int k = i; k < end; k++) {
					wrapped.add(new Step.Single(((Step.Single) steps.get(k)).stage(), 2.0F));
				}
				wrapped.add(new Step.Up());
				i = end;
			} else {
				wrapped.add(steps.get(i));
				i++;
			}
		}
		return wrapped;
	}

	/** A stage that may render inside a half-resolution run: a scalable {@code NORMAL} barrier. */
	private static boolean scalable(final StageRef stage) {
		final VFXShaderPrograms.ProgramInfo info = stage.info();
		return VFXChainResolution.HALF
			&& info.scalable()
			&& info.role() == VFXShaderPrograms.PassRole.NORMAL
			&& !stage.mask()
			&& info.fusionClass() == VFXFusionClass.BARRIER;
	}

	/**
	 * Splits the chain into budget-grouped runs in order, without touching the program cache.
	 */
	static List<Run> runs(final List<StageRef> chain) {
		final List<Run> result = new ArrayList<>();
		final RunAccumulator accumulator = new RunAccumulator();
		for (final List<StageRef> group : groups(chain)) {
			if (!fusible(group)) {
				accumulator.flush(result);
				result.add(new Run(List.copyOf(group), 0, 1, 0, 0, false, false));
				continue;
			}
			final boolean head = group.get(0).mask();
			if (accumulator.open && head) {
				accumulator.flush(result);
			}
			final boolean addHistory = containsConsumer(group) && !accumulator.hasConsumer();
			final VFXFusionBudget.Cost cost = cost(group, addHistory, head);
			if (!accumulator.open) {
				cost.samplers += 1;
				if (!VFXFusionBudget.fits(0, 1, 0, 0, cost)) {
					result.add(new Run(List.copyOf(group), 0, 1, 0, 0, false, false));
					continue;
				}
				accumulator.start(group, cost);
			} else if (VFXFusionBudget.fits(accumulator.linear, accumulator.remaps, accumulator.samplers, accumulator.params, cost)) {
				accumulator.extend(group, cost);
			} else {
				accumulator.flush(result);
				final VFXFusionBudget.Cost fresh = cost(group, containsConsumer(group), head);
				fresh.samplers += 1;
				if (!VFXFusionBudget.fits(0, 1, 0, 0, fresh)) {
					result.add(new Run(List.copyOf(group), 0, 1, 0, 0, false, false));
					continue;
				}
				accumulator.start(group, fresh);
			}
		}
		accumulator.flush(result);
		return result;
	}

	/** Mutable accumulator for one open run; flushed when a group no longer fits. */
	private static final class RunAccumulator {
		private List<StageRef> stages = new ArrayList<>();
		private int linear;
		private int remaps = 1;
		private int samplers;
		private int params;
		private boolean open;

		private boolean hasConsumer() {
			for (final StageRef stage : this.stages) {
				if (stage.mask()) {
					return true;
				}
			}
			return false;
		}

		private void start(final List<StageRef> group, final VFXFusionBudget.Cost cost) {
			this.stages = new ArrayList<>(group);
			this.linear = cost.linear;
			this.remaps = cost.remaps;
			this.samplers = cost.samplers;
			this.params = cost.params;
			this.open = true;
		}

		private void extend(final List<StageRef> group, final VFXFusionBudget.Cost cost) {
			this.linear += cost.linear;
			this.remaps *= cost.remaps;
			this.samplers += cost.samplers;
			this.params += cost.params;
			this.stages.addAll(group);
		}

		private void flush(final List<Run> out) {
			if (!this.open) {
				return;
			}
			out.add(new Run(List.copyOf(this.stages), this.linear, this.remaps, this.samplers, this.params, this.stages.get(0).mask(), true));
			this.open = false;
			this.stages = new ArrayList<>();
		}
	}

	/**
	 * Groups the chain so that a masked effect's {@code captureBefore} stage and its coverage
	 * consumer stay atomic; every other stage is its own group.
	 */
	private static List<List<StageRef>> groups(final List<StageRef> chain) {
		final List<List<StageRef>> groups = new ArrayList<>();
		int i = 0;
		while (i < chain.size()) {
			final StageRef stage = chain.get(i);
			if (stage.captureBefore() && !stage.mask()) {
				int j = i + 1;
				while (j < chain.size() && !chain.get(j).mask()) {
					j++;
				}
				if (j < chain.size()) {
					groups.add(new ArrayList<>(chain.subList(i, j + 1)));
					i = j + 1;
					continue;
				}
			}
			groups.add(List.of(stage));
			i++;
		}
		return groups;
	}

	/** A group is fusible only when every stage is a {@code NORMAL} role and no stage is a barrier. */
	private static boolean fusible(final List<StageRef> group) {
		for (final StageRef stage : group) {
			if (stage.info().role() != VFXShaderPrograms.PassRole.NORMAL) {
				return false;
			}
			if (stage.captureBefore() && !containsConsumer(group)) {
				return false;
			}
			if (stage.mask()) {
				continue;
			}
			final VFXFusionClass fusionClass = stage.info().fusionClass();
			if (fusionClass == VFXFusionClass.BARRIER) {
				return false;
			}
			if (fusionClass == VFXFusionClass.UV_REMAP && !VFXFusionPolicy.REMAP_ENABLED) {
				return false;
			}
		}
		return true;
	}

	private static boolean containsConsumer(final List<StageRef> group) {
		for (final StageRef stage : group) {
			if (stage.mask()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The group's cost: a {@code POINT} stage {@code +1} linear and its own samplers/params; a
	 * consumer {@code +2} linear ({@code +1} when it heads the run) and one coverage sampler; the
	 * run-head {@code HistSampler} is added once via {@code addHistory}.
	 */
	private static VFXFusionBudget.Cost cost(final List<StageRef> group, final boolean addHistory, final boolean runHeadConsumer) {
		final VFXFusionBudget.Cost cost = new VFXFusionBudget.Cost();
		for (int i = 0; i < group.size(); i++) {
			final StageRef stage = group.get(i);
			cost.remaps *= stageRemaps(stage);
			if (stage.mask()) {
				cost.linear += runHeadConsumer && i == 0 ? 1 : 2;
				cost.samplers += 1;
			} else {
				cost.linear += 1;
				cost.samplers += stageSamplers(stage);
				cost.params += stageParams(stage);
			}
		}
		if (addHistory) {
			cost.samplers += 1;
		}
		return cost;
	}

	private static int stageRemaps(final StageRef stage) {
		if (!stage.mask() && stage.info().fusionClass() == VFXFusionClass.UV_REMAP) {
			return Math.max(1, stage.info().prefixEvals());
		}
		return 1;
	}

	private static int stageSamplers(final StageRef stage) {
		final VFXShaderPrograms.ProgramInfo info = stage.info();
		int samplers = info.usesDepth() ? 1 : 0;
		if (info.usesField()) {
			samplers++;
		}
		return samplers;
	}

	private static int stageParams(final StageRef stage) {
		return stage.info().configParams().length + (stage.info().usesField() ? FIELD_CONFIG_FLOATS : 0);
	}

	private VFXFusionPlanner() {
	}
}

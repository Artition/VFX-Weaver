package dev.vfxweaver.client.postprocessing;

/**
 * How a post shader may join a fused run (post-chain fusion).
 *
 * <p>The class is a <b>cost policy, not a different mechanism</b>: the generator rewrites every
 * fused stage the same way ({@code texture(InSampler, X)} becomes the previous stage's call), and
 * the planner uses the class only to decide where a run may be cut and how many prefix
 * evaluations a stage costs. An unannotated program is {@link #BARRIER}, so every built-in effect
 * keeps rendering as its own pass until it is explicitly annotated.
 */
public enum VFXFusionClass {
	/**
	 * Never fused: the stage gets its own pass, exactly as before fusion existed. This is the
	 * unannotated default, so anything the planner does not fully understand fails closed.
	 */
	BARRIER,

	/**
	 * Reads the chain only at its own pixel (a pointwise effect). Fusable: its input is the
	 * previous stage's value at the same uv.
	 */
	POINT,

	/**
	 * Re-evaluates the prefix at shifted uvs (a remap/filter effect). The mechanism exists, but it
	 * stays disabled by default until a filter-exact in-game A/B proves the linear-fetch emulation
	 * matches the standalone chain.
	 */
	UV_REMAP
}

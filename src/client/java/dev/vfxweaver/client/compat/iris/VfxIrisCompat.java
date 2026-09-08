package dev.vfxweaver.client.compat.iris;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.vfxweaver.client.render.VFXWorldOverlayRenderer;
import java.lang.reflect.Method;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Soft-dependency bridge to Iris (https://github.com/IrisShaders/Iris): maps every custom
 * {@code core/position_color} pipeline of {@link VFXWorldOverlayRenderer} to Iris's
 * {@code gbuffers_basic} program via {@code IrisApi.assignPipeline(RenderPipeline, IrisProgram)}.
 *
 * <p>Under an active shaderpack Iris redirects {@code GlDevice.getOrCompilePipeline} through its
 * own override list ({@code IrisPipelines.coreShaderMap}); a custom pipeline missing from that
 * map gets a null override and silently stops rendering ("Missing program ... in override list").
 * The public API call is one-shot per pipeline (the map is static and never rebuilt), and the
 * mod still works fully without Iris — nothing here runs when it is absent and all Iris classes
 * are reached through reflection, so there is no compile-time dependency (only
 * {@code suggests: iris} in {@code fabric.mod.json}).
 */
public final class VfxIrisCompat {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/iris");
	private static final String API_CLASS = "net.irisshaders.iris.api.v0.IrisApi";
	private static final String PROGRAM_CLASS = "net.irisshaders.iris.api.v0.IrisProgram";

	private VfxIrisCompat() {
	}

	/**
	 * Assigns every world-overlay pipeline to Iris's {@code BASIC} program. Safe to call once at
	 * client init; a no-op when Iris is absent or too old. Re-calling later just re-assigns the
	 * same mapping, so it also tolerates shaderpack switches in-game.
	 */
	public static void init() {
		try {
			Class<?> apiClass = Class.forName(API_CLASS);
			Class<?> programClass = Class.forName(PROGRAM_CLASS);
			Method getInstance = apiClass.getMethod("getInstance");
			Object api = getInstance.invoke(null);
			// IrisProgram.BASIC -> ProgramId.Basic; findBestMatch picks the exact BASIC_COLOR key
			// for our DefaultVertexFormat.POSITION_COLOR pipelines.
			Object basic = programClass.getMethod("valueOf", String.class).invoke(null, "BASIC");
			Method assign = apiClass.getMethod("assignPipeline", RenderPipeline.class, programClass);
			List<RenderPipeline> pipelines = VFXWorldOverlayRenderer.pipelinesForIris();
			for (RenderPipeline pipeline : pipelines) {
				assign.invoke(api, pipeline, basic);
			}
			LOGGER.info("Iris compatibility enabled: assigned {} custom pipelines to gbuffers_basic", pipelines.size());
		} catch (ClassNotFoundException notFound) {
			// Iris not installed — normal, nothing to bridge.
		} catch (NoSuchMethodException e) {
			LOGGER.warn("Iris is too old for pipeline assignment (missing IrisApi.assignPipeline); world overlays may not render under shaderpacks");
		} catch (Throwable t) {
			LOGGER.warn("Failed to initialize Iris compatibility; world overlays may not render under shaderpacks", t);
		}
	}
}
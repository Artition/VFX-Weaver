package dev.vfxweaver.client.window;

import dev.vfxweaver.util.VFXLog;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.resources.metadata.animation.AnimationFrame;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A decoded window/screen picture's metadata: how to address its frames. A plain still, a horizontal
 * datapack strip and a vanilla {@code .mcmeta} sheet all become this, so the draw paths only ever ask
 * for "the frame at this many ticks". No pixels are held - the window uploads from its own decoded
 * array and the screen image samples the GPU texture, so both keep only the geometry. No GL.
 *
 * @param imageWidth  the sheet width in pixels
 * @param imageHeight the sheet height in pixels
 * @param frameWidth  one frame's width in pixels; divides {@code imageWidth}
 * @param frameHeight one frame's height in pixels; divides {@code imageHeight}
 * @param frames      the playback order; never empty
 * @param animated    {@code false} for a still or a strip driven by {@code frame_time}
 */
public record VFXWindowFrames(
	int imageWidth, int imageHeight,
	int frameWidth, int frameHeight, List<Frame> frames, boolean animated) {

	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/window");
	/** Safety cap on the frame table decoded from a pack {@code .mcmeta} (external input). */
	public static final int MAX_FRAMES = 4096;

	/**
	 * One frame's slot in the sheet and how long it shows.
	 *
	 * @param index     the sheet slot, row-major
	 * @param timeTicks the ticks this frame shows; at least 1
	 */
	public record Frame(int index, int timeTicks) {
	}

	/** One still covering the whole image. */
	public static VFXWindowFrames still(final int imageWidth, final int imageHeight) {
		return new VFXWindowFrames(imageWidth, imageHeight, imageWidth, imageHeight,
			List.of(new Frame(0, 1)), false);
	}

	/**
	 * A horizontal strip of {@code frames} equal columns, the no-{@code .mcmeta} datapack layout.
	 *
	 * @param frames the column count; clamped to at least 1 and to the image width
	 */
	public static VFXWindowFrames strip(final int imageWidth, final int imageHeight, final int frames) {
		final int columns = Math.max(1, Math.min(frames, Math.max(1, imageWidth)));
		final int frameWidth = Math.max(1, imageWidth / columns);
		final List<Frame> table = java.util.stream.IntStream.range(0, columns)
			.mapToObj(i -> new Frame(i, 1)).toList();
		return new VFXWindowFrames(imageWidth, imageHeight, frameWidth, imageHeight, table, false);
	}

	/**
	 * A vanilla {@code .mcmeta} sheet: the given frame table, per-frame times.
	 *
	 * @param frames the playback order from the metadata; must be non-empty
	 */
	public static VFXWindowFrames animated(final int imageWidth, final int imageHeight,
		final int frameWidth, final int frameHeight, final List<Frame> frames) {
		return new VFXWindowFrames(imageWidth, imageHeight,
			Math.max(1, frameWidth), Math.max(1, frameHeight), List.copyOf(frames), true);
	}

	/**
	 * Builds the frames for a texture that has a sibling {@code .mcmeta}: the vanilla sheet size
	 * (width-only -> {@code (w, imgH)}, height-only -> {@code (imgW, h)}, neither -> {@code min(w,h)}
	 * square), the playback order and per-frame times (vanilla milliseconds to ticks, at least 1).
	 *
	 * @param m               the parsed animation section, never {@code null}
	 * @param imageWidth      the image width in pixels
	 * @param imageHeight     the image height in pixels
	 * @param requestedFrames the caller's strip frame count; unused here, the metadata wins
	 * @param warnKey         the logging key/id of the texture, for the frame-cap warning
	 * @return the built frames, never {@code null}
	 */
	public static VFXWindowFrames fromMetadata(final AnimationMetadataSection m, final int imageWidth,
		final int imageHeight, final int requestedFrames, final String warnKey) {
		final FrameSize size = m.calculateFrameSize(imageWidth, imageHeight);
		final int frameWidth = Math.max(1, size.width());
		final int frameHeight = Math.max(1, size.height());
		final int defaultMs = Math.max(1, m.defaultFrameTime());
		final List<Frame> table = new ArrayList<>();
		if (m.frames().isPresent()) {
			for (final AnimationFrame f : m.frames().get()) {
				if (table.size() >= MAX_FRAMES) {
					VFXLog.warnOnce(LOGGER, "window:frames:" + warnKey,
						"texture '{}' .mcmeta has more than {} frames; the rest are dropped", warnKey, MAX_FRAMES);
					break;
				}
				table.add(new Frame(f.index(), Math.max(1, f.timeOr(defaultMs) / 50)));
			}
		} else {
			final int cols = Math.max(1, imageWidth / frameWidth);
			final int rows = Math.max(1, imageHeight / frameHeight);
			if (cols * rows > MAX_FRAMES) {
				VFXLog.warnOnce(LOGGER, "window:frames:" + warnKey,
					"texture '{}' has more than {} frames; the rest are dropped", warnKey, MAX_FRAMES);
			}
			final int total = Math.min(cols * rows, MAX_FRAMES);
			for (int i = 0; i < total; i++) {
				table.add(new Frame(i, Math.max(1, defaultMs / 50)));
			}
		}
		if (table.isEmpty()) {
			return still(imageWidth, imageHeight);
		}
		return animated(imageWidth, imageHeight, frameWidth, frameHeight, table);
	}

	public int columns() {
		return Math.max(1, this.imageWidth / Math.max(1, this.frameWidth));
	}

	public int rows() {
		return Math.max(1, this.imageHeight / Math.max(1, this.frameHeight));
	}

	public int frameCount() {
		return this.frames.size();
	}

	public Frame frame(final int i) {
		return this.frames.get(i);
	}

	/**
	 * The frame to draw after {@code timeTicks} ticks. A still returns slot 0; an animated sheet
	 * walks the table (each frame for its own {@code timeTicks}, at least 1) and wraps - the vanilla
	 * behaviour without interpolation.
	 *
	 * @param timeTicks elapsed ticks since the window opened; negative is treated as 0
	 */
	public int frameAt(final long timeTicks) {
		if (!this.animated || this.frames.size() == 1) {
			return this.frames.get(0).index();
		}
		long total = 0;
		for (final Frame f : this.frames) {
			total += Math.max(1, f.timeTicks());
		}
		long t = Math.floorMod(timeTicks < 0 ? 0 : timeTicks, total);
		for (final Frame f : this.frames) {
			final long span = Math.max(1, f.timeTicks());
			if (t < span) {
				return f.index();
			}
			t -= span;
		}
		return this.frames.get(this.frames.size() - 1).index();
	}
}
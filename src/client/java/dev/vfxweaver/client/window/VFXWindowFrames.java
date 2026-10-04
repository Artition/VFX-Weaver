package dev.vfxweaver.client.window;

import java.util.List;

/**
 * A decoded window picture: the whole sheet in ARGB and how to address its frames. A plain still, a
 * horizontal datapack strip and a vanilla {@code .mcmeta} sheet all become this, so the draw path
 * only ever asks for "the frame at this many ticks". No Minecraft, no GL - see
 * {@link #strip} and {@link #animated}.
 *
 * @param pixels      the whole image, ARGB {@code 0xAARRGGBB}, row-major, top row first
 * @param imageWidth  the sheet width in pixels
 * @param imageHeight the sheet height in pixels
 * @param frameWidth  one frame's width in pixels; divides {@code imageWidth}
 * @param frameHeight one frame's height in pixels; divides {@code imageHeight}
 * @param frames      the playback order; never empty
 * @param animated    {@code false} for a still or a strip driven by {@code frame_time}
 */
public record VFXWindowFrames(
	int[] pixels, int imageWidth, int imageHeight,
	int frameWidth, int frameHeight, List<Frame> frames, boolean animated) {

	/**
	 * One frame's slot in the sheet and how long it shows.
	 *
	 * @param index     the sheet slot, row-major
	 * @param timeTicks the ticks this frame shows; at least 1
	 */
	public record Frame(int index, int timeTicks) {
	}

	/** One still covering the whole image. */
	public static VFXWindowFrames still(final int[] pixels, final int imageWidth, final int imageHeight) {
		return new VFXWindowFrames(pixels, imageWidth, imageHeight, imageWidth, imageHeight,
			List.of(new Frame(0, 1)), false);
	}

	/**
	 * A horizontal strip of {@code frames} equal columns, the no-{@code .mcmeta} datapack layout.
	 *
	 * @param frames the column count; clamped to at least 1 and to the image width
	 */
	public static VFXWindowFrames strip(final int[] pixels, final int imageWidth, final int imageHeight, final int frames) {
		final int columns = Math.max(1, Math.min(frames, Math.max(1, imageWidth)));
		final int frameWidth = Math.max(1, imageWidth / columns);
		final List<Frame> table = java.util.stream.IntStream.range(0, columns)
			.mapToObj(i -> new Frame(i, 1)).toList();
		return new VFXWindowFrames(pixels, imageWidth, imageHeight, frameWidth, imageHeight, table, false);
	}

	/**
	 * A vanilla {@code .mcmeta} sheet: the given frame table, per-frame times.
	 *
	 * @param frames the playback order from the metadata; must be non-empty
	 */
	public static VFXWindowFrames animated(final int[] pixels, final int imageWidth, final int imageHeight,
		final int frameWidth, final int frameHeight, final List<Frame> frames) {
		return new VFXWindowFrames(pixels, imageWidth, imageHeight,
			Math.max(1, frameWidth), Math.max(1, frameHeight), List.copyOf(frames), true);
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
	 * The same frames with the pixel array dropped. A caller that has already uploaded the pixels to
	 * the GPU keeps this instead of the whole decoded image, so a large pack texture is not retained
	 * for the window's lifetime.
	 *
	 * @return this when it already holds no pixels, otherwise a copy without them
	 */
	public VFXWindowFrames withoutPixels() {
		if (this.pixels.length == 0) {
			return this;
		}
		return new VFXWindowFrames(new int[0], this.imageWidth, this.imageHeight,
			this.frameWidth, this.frameHeight, this.frames, this.animated);
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

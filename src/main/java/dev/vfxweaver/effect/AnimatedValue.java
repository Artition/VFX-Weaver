package dev.vfxweaver.effect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A scalar value that animates over time between keyframes using an {@link EasingFunction}.
 * The time domain is expressed in game ticks; call {@link #update(float)} every frame with
 * the current elapsed time (in ticks) and read the result with {@link #get()}.
 */
public class AnimatedValue {
	private final List<Keyframe> keyframes;
	private final float startTime;
	private final float endTime;
	private float current;

	private AnimatedValue(final List<Keyframe> keyframes) {
		if (keyframes.size() < 2) {
			throw new IllegalArgumentException("AnimatedValue requires at least two keyframes");
		}
		List<Keyframe> sorted = new ArrayList<>(keyframes);
		sorted.sort((a, b) -> Float.compare(a.time(), b.time()));
		this.keyframes = Collections.unmodifiableList(sorted);
		this.startTime = sorted.get(0).time();
		this.endTime = sorted.get(sorted.size() - 1).time();
		this.current = sorted.get(0).value();
	}

	/**
	 * Creates a value that stays constant forever.
	 *
	 * @param value constant value
	 */
	public static AnimatedValue constant(final float value) {
		return new AnimatedValue(List.of(new Keyframe(0.0F, value), new Keyframe(Float.MAX_VALUE, value)));
	}

	/**
	 * Creates a linear interpolation between two values.
	 *
	 * @param startTime   start time in ticks
	 * @param endTime     end time in ticks
	 * @param startValue  value at start
	 * @param endValue    value at end
	 * @param easing      easing curve between the values
	 */
	public static AnimatedValue between(final float startTime, final float endTime, final float startValue, final float endValue, final EasingFunction easing) {
		return new AnimatedValue(List.of(new Keyframe(startTime, startValue, easing), new Keyframe(endTime, endValue, EasingFunction.builtIn(EasingType.LINEAR))));
	}

	/**
	 * Creates a multi-segment animation from the given keyframes.
	 *
	 * @param keyframes ordered (or unordered) keyframes; at least two required
	 */
	public static AnimatedValue fromKeyframes(final Keyframe... keyframes) {
		return new AnimatedValue(List.of(keyframes));
	}

	/**
	 * Advances this value to the given time and returns the current interpolated value.
	 *
	 * @param now current time in ticks
	 */
	public float update(final float now) {
		if (now <= this.startTime) {
			this.current = this.keyframes.get(0).value();
		} else if (now >= this.endTime) {
			if (this.keyframes.size() >= 2 && this.keyframes.get(this.keyframes.size() - 2).easing() != null) {
				// Pass the end through the segment's easing as well: for a curve whose value at
				// t=1 is not 1 (e.g. a triangle/wave), snapping to the raw end value would jump.
				Keyframe from = this.keyframes.get(this.keyframes.size() - 2);
				Keyframe to = this.keyframes.get(this.keyframes.size() - 1);
				this.current = from.value() + (to.value() - from.value()) * from.easing().apply(1.0F);
			} else {
				this.current = this.keyframes.get(this.keyframes.size() - 1).value();
			}
		} else {
			for (int i = 0; i < this.keyframes.size() - 1; i++) {
				Keyframe from = this.keyframes.get(i);
				Keyframe to = this.keyframes.get(i + 1);
				if (now >= from.time() && now <= to.time()) {
					float span = to.time() - from.time();
					float t = span <= 0.0F ? 1.0F : (now - from.time()) / span;
					float eased = from.easing().apply(t);
					this.current = from.value() + (to.value() - from.value()) * eased;
					break;
				}
			}
		}
		return this.current;
	}

	/**
	 * Returns the most recently computed value (updated via {@link #update(float)}).
	 */
	public float get() {
		return this.current;
	}

	/**
	 * Convenience accessor: updates to the given time and returns the value.
	 *
	 * @param now current time in ticks
	 */
	public float get(final float now) {
		return this.update(now);
	}

	public float getStartTime() {
		return this.startTime;
	}

	public float getEndTime() {
		return this.endTime;
	}

	public List<Keyframe> getKeyframes() {
		return this.keyframes;
	}
}
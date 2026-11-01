package dev.vfxweaver.network;

import dev.vfxweaver.effect.EasingFunction;
import dev.vfxweaver.effect.EasingType;
import dev.vfxweaver.util.VFXLog;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-to-client packet that triggers, stops, moves or live-edits a VFX effect. Carries a
 * protocol version, the effect id, the action, the duration in ticks, an optional resume offset
 * in ticks (how far into the timeline the effect already is вЂ” used when re-applying effects
 * after a reconnect), an optional explicit world position, an optional instance id (to target
 * one of several concurrent instances of the same effect), the (already resolved) parameter
 * map, the easing curve name (built-in or custom datapack curve), the list of entity UUIDs this
 * effect applies to (for entity tint/outline effects), for the {@link VFXAction#SET_EXPR} action
 * only the parameter name and expression source to install at runtime, and the optional text
 * override that replaces a {@code text_overlay} definition's {@code text.content} for this call.
 */
public record VFXTriggerPayload(
	byte protocolVersion,
	Identifier effectId,
	VFXAction action,
	int durationTicks,
	int elapsedTicks,
	long instanceId,
	@Nullable Vec3 position,
	List<UUID> entityUuids,
	Map<String, Float> params,
	String easing,
	@Nullable String exprParam,
	@Nullable String exprSource,
	@Nullable String textOverride
) implements CustomPacketPayload {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/payload");
	/** Bumped 6 -> 7 for {@code textOverride}: an old reader would misparse the extra optional string. */
	public static final byte PROTOCOL_VERSION = 7;
	/**
	 * Safety cap on the number of parameters a play packet may carry (server input, see AGENTS.md).
	 * Raised from 32 to 256 for per-leaf mask dynamic data: a mask may hold eight leaves and two
	 * custom leaves each register {@code MAX_LEAF_DATA = 32} {@code mask.p<N>.d<J>} params, so a
	 * large mask's resolved constant map no longer fits in 32. The wire format is unchanged (a
	 * varint count then entries); only this read bound moved, so this cap alone never bumps the
	 * protocol version.
	 */
	public static final int MAX_PARAMS = 256;
	/** Safety cap on the number of entity UUIDs in one packet. */
	public static final int MAX_ENTITY_UUIDS = 16;
	/**
	 * Wire cap on a call-time text override, in characters (see AGENTS.md: this sizes what the
	 * client lays out per frame, so it is bounded on both ends). A longer value is cut on decode
	 * and logged once; the renderer's own 256-glyph cap still applies afterwards.
	 */
	public static final int MAX_TEXT_OVERRIDE = 512;
	public static final Type<VFXTriggerPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("vfxweaver", "vfx_trigger"));

	public static final StreamCodec<ByteBuf, VFXAction> ACTION_CODEC = ByteBufCodecs.BYTE.map(VFXAction::fromId, VFXAction::getId);
	public static final StreamCodec<ByteBuf, Vec3> OPTIONAL_VEC3 = new StreamCodec<>() {
		public Vec3 decode(final ByteBuf input) {
			return input.readBoolean() ? Vec3.STREAM_CODEC.decode(input) : null;
		}

		public void encode(final ByteBuf output, final Vec3 value) {
			output.writeBoolean(value != null);
			if (value != null) {
				Vec3.STREAM_CODEC.encode(output, value);
			}
		}
	};
	public static final StreamCodec<ByteBuf, String> OPTIONAL_STRING = new StreamCodec<>() {
		public String decode(final ByteBuf input) {
			return input.readBoolean() ? ByteBufCodecs.STRING_UTF8.decode(input) : null;
		}

		public void encode(final ByteBuf output, final String value) {
			output.writeBoolean(value != null);
			if (value != null) {
				ByteBufCodecs.STRING_UTF8.encode(output, value);
			}
		}
	};
	public static final StreamCodec<ByteBuf, UUID> UUID_CODEC = new StreamCodec<>() {
		public UUID decode(final ByteBuf input) {
			return new UUID(input.readLong(), input.readLong());
		}

		public void encode(final ByteBuf output, final UUID value) {
			output.writeLong(value.getMostSignificantBits());
			output.writeLong(value.getLeastSignificantBits());
		}
	};
	/**
	 * {@link #OPTIONAL_STRING} for the text override, cut to {@link #MAX_TEXT_OVERRIDE} on decode so
	 * a long string from the server can never blow the client's per-frame layout budget.
	 */
	public static final StreamCodec<ByteBuf, String> OPTIONAL_TEXT_OVERRIDE = new StreamCodec<>() {
		public String decode(final ByteBuf input) {
			return clipTextOverride(OPTIONAL_STRING.decode(input));
		}

		public void encode(final ByteBuf output, final String value) {
			OPTIONAL_STRING.encode(output, clipTextOverride(value));
		}
	};

	/**
	 * Cuts an over-long override to {@link #MAX_TEXT_OVERRIDE}, with one warning. Shared by both
	 * directions: a value cut on the way out cannot make {@code STRING_UTF8} throw at send time, and
	 * one cut on the way in cannot reach the renderer. A value already inside the cap is returned
	 * unchanged (and {@code null} stays {@code null}).
	 */
	private static @Nullable String clipTextOverride(final @Nullable String value) {
		if (value == null || value.length() <= MAX_TEXT_OVERRIDE) {
			return value;
		}
		VFXLog.warnOnce(LOGGER, "net:text-override-clip", "Text override is {} characters long; cut to MAX_TEXT_OVERRIDE ({})", value.length(), MAX_TEXT_OVERRIDE);
		return value.substring(0, MAX_TEXT_OVERRIDE);
	}

	/**
	 * The three optional trailing strings, composed on their own: {@link StreamCodec#composite} has
	 * no overload past twelve codec pairs, and the payload has thirteen fields.
	 */
	private record TrailingStrings(@Nullable String exprParam, @Nullable String exprSource, @Nullable String textOverride) {
	}

	private static final StreamCodec<ByteBuf, TrailingStrings> TRAILING_STRINGS_CODEC = StreamCodec.composite(
		OPTIONAL_STRING,
		TrailingStrings::exprParam,
		OPTIONAL_STRING,
		TrailingStrings::exprSource,
		OPTIONAL_TEXT_OVERRIDE,
		TrailingStrings::textOverride,
		TrailingStrings::new
	);

	public static final StreamCodec<RegistryFriendlyByteBuf, VFXTriggerPayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.BYTE,
		VFXTriggerPayload::protocolVersion,
		Identifier.STREAM_CODEC,
		VFXTriggerPayload::effectId,
		ACTION_CODEC,
		VFXTriggerPayload::action,
		ByteBufCodecs.VAR_INT,
		VFXTriggerPayload::durationTicks,
		ByteBufCodecs.VAR_INT,
		VFXTriggerPayload::elapsedTicks,
		ByteBufCodecs.VAR_LONG,
		VFXTriggerPayload::instanceId,
		OPTIONAL_VEC3,
		VFXTriggerPayload::position,
		ByteBufCodecs.collection(ArrayList::new, UUID_CODEC, MAX_ENTITY_UUIDS),
		VFXTriggerPayload::entityUuids,
		ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.FLOAT, MAX_PARAMS),
		VFXTriggerPayload::params,
		ByteBufCodecs.STRING_UTF8,
		VFXTriggerPayload::easing,
		TRAILING_STRINGS_CODEC,
		VFXTriggerPayload::trailingStrings,
		(protocolVersion, effectId, action, durationTicks, elapsedTicks, instanceId, position, entityUuids, params, easing, trailingStrings) -> new VFXTriggerPayload(
			protocolVersion, effectId, action, durationTicks, elapsedTicks, instanceId, position, entityUuids, params, easing,
			trailingStrings.exprParam(), trailingStrings.exprSource(), trailingStrings.textOverride()
		)
	);

	/** Groups the optional trailing strings for {@link #TRAILING_STRINGS_CODEC}. */
	private TrailingStrings trailingStrings() {
		return new TrailingStrings(this.exprParam, this.exprSource, this.textOverride);
	}

	/**
	 * Creates a play payload with the current protocol version.
	 */
	public static VFXTriggerPayload play(final Identifier effectId, final int durationTicks, final Map<String, Float> params, final EasingType easing) {
		return play(effectId, durationTicks, params, easing, null);
	}

	/**
	 * Creates a play payload with the current protocol version and a call-time text override.
	 *
	 * @param textOverride replaces the definition's {@code text.content} (may be null for "no override")
	 */
	public static VFXTriggerPayload play(final Identifier effectId, final int durationTicks, final Map<String, Float> params, final EasingType easing, final @Nullable String textOverride) {
		return play(effectId, durationTicks, 0L, null, List.of(), params, easing.name(), textOverride);
	}

	/**
	 * Creates a play payload with an explicit instance id and an optional world position.
	 *
	 * @param effectId      effect id
	 * @param durationTicks duration in ticks
	 * @param instanceId    instance id (0 = let the client allocate one)
	 * @param position      world position to re-anchor spatial bindings to (may be null)
	 * @param params        parameter overrides
	 * @param easing        easing curve name (built-in or custom datapack curve)
	 */
	public static VFXTriggerPayload play(final Identifier effectId, final int durationTicks, final long instanceId, final @Nullable Vec3 position, final Map<String, Float> params, final String easing) {
		return play(effectId, durationTicks, instanceId, position, List.of(), params, easing);
	}

	/**
	 * Creates a play payload with an instance id, world position and entity UUID targets.
	 *
	 * @param effectId      effect id
	 * @param durationTicks duration in ticks
	 * @param instanceId    instance id (0 = let the client allocate one)
	 * @param position      world position to re-anchor spatial bindings to (may be null)
	 * @param entityUuids   entity UUIDs this effect applies to (for entity tint/outline)
	 * @param params        parameter overrides
	 * @param easing        easing curve name (built-in or custom datapack curve)
	 */
	public static VFXTriggerPayload play(final Identifier effectId, final int durationTicks, final long instanceId, final @Nullable Vec3 position, final List<UUID> entityUuids, final Map<String, Float> params, final String easing) {
		return play(effectId, durationTicks, 0, instanceId, position, entityUuids, params, easing, null);
	}

	/**
	 * Same as {@link #play(Identifier, int, long, Vec3, List, Map, String)} with a call-time text
	 * override.
	 *
	 * @param textOverride replaces the definition's {@code text.content} (may be null for "no override")
	 */
	public static VFXTriggerPayload play(final Identifier effectId, final int durationTicks, final long instanceId, final @Nullable Vec3 position, final List<UUID> entityUuids, final Map<String, Float> params, final String easing, final @Nullable String textOverride) {
		return play(effectId, durationTicks, 0, instanceId, position, entityUuids, params, easing, textOverride);
	}

	/**
	 * Creates a play payload that resumes an already-running timeline from the given offset.
	 *
	 * @param elapsedTicks how far into the timeline the effect already is, in ticks
	 */
	public static VFXTriggerPayload play(final Identifier effectId, final int durationTicks, final int elapsedTicks, final long instanceId, final @Nullable Vec3 position, final List<UUID> entityUuids, final Map<String, Float> params, final String easing) {
		return play(effectId, durationTicks, elapsedTicks, instanceId, position, entityUuids, params, easing, null);
	}

	/**
	 * Same as {@link #play(Identifier, int, int, long, Vec3, List, Map, String)} with a call-time
	 * text override.
	 *
	 * @param textOverride replaces the definition's {@code text.content} (may be null for "no override")
	 */
	public static VFXTriggerPayload play(final Identifier effectId, final int durationTicks, final int elapsedTicks, final long instanceId, final @Nullable Vec3 position, final List<UUID> entityUuids, final Map<String, Float> params, final String easing, final @Nullable String textOverride) {
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.PLAY, durationTicks, Math.max(0, elapsedTicks), instanceId, position, entityUuids, params, easing, null, null, textOverride);
	}

	/**
	 * Creates a stop payload with the current protocol version.
	 */
	public static VFXTriggerPayload stop(final Identifier effectId) {
		return stop(effectId, 0L);
	}

	/**
	 * Creates a stop payload targeting one specific instance of the effect.
	 *
	 * @param effectId   effect id
	 * @param instanceId instance id (0 = stop every instance of the effect)
	 */
	public static VFXTriggerPayload stop(final Identifier effectId, final long instanceId) {
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.STOP, 0, 0, instanceId, null, List.of(), Map.of(), EasingType.LINEAR.name(), null, null, null);
	}

	/**
	 * Creates a live parameter-override payload for a running effect ({@code params} carries
	 * a single {@code name -> value} entry).
	 */
	public static VFXTriggerPayload setParam(final Identifier effectId, final String param, final float value) {
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.SET_PARAM, 0, 0, 0L, null, List.of(), Map.of(param, value), EasingType.LINEAR.name(), null, null, null);
	}

	/**
	 * Creates a live keyframe payload for a running effect: {@code time} is carried in
	 * {@code durationTicks}, the value in {@code params} and the outgoing easing in {@code easing}.
	 */
	public static VFXTriggerPayload keyframe(final Identifier effectId, final String param, final int time, final float value, final EasingType easing) {
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.KEYFRAME, time, 0, 0L, null, List.of(), Map.of(param, value), easing.name(), null, null, null);
	}

	/**
	 * Creates a live keyframe payload with a custom curve name.
	 */
	public static VFXTriggerPayload keyframe(final Identifier effectId, final String param, final int time, final float value, final String easing) {
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.KEYFRAME, time, 0, 0L, null, List.of(), Map.of(param, value), easing, null, null, null);
	}

	/**
	 * Creates a live expression payload for a running effect: {@code exprSource} replaces the
	 * parameter's whole value source (keyframes, binding or previous expression) at runtime.
	 */
	public static VFXTriggerPayload setExpr(final Identifier effectId, final String param, final String exprSource) {
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.SET_EXPR, 0, 0, 0L, null, List.of(), Map.of(), EasingType.LINEAR.name(), param, exprSource, null);
	}

	/**
	 * Creates a move payload that re-anchors a running instance to a new world position.
	 *
	 * @param effectId   effect id
	 * @param instanceId instance id to move (must match an instance of the effect on the client)
	 * @param worldPos   the new world position
	 */
	public static VFXTriggerPayload move(final Identifier effectId, final long instanceId, final Vec3 worldPos) {
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.MOVE, 0, 0, instanceId, worldPos, List.of(), Map.of(), EasingType.LINEAR.name(), null, null, null);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
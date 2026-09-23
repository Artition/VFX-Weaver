package dev.vfxweaver.network;

import dev.vfxweaver.effect.EasingFunction;
import dev.vfxweaver.effect.EasingType;
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

/**
 * Server-to-client packet that triggers, stops, moves or live-edits a VFX effect. Carries a
 * protocol version, the effect id, the action, the duration in ticks, an optional resume offset
 * in ticks (how far into the timeline the effect already is вЂ” used when re-applying effects
 * after a reconnect), an optional explicit world position, an optional instance id (to target
 * one of several concurrent instances of the same effect), the (already resolved) parameter
 * map, the easing curve name (built-in or custom datapack curve), the list of entity UUIDs this
 * effect applies to (for entity tint/outline effects) and — for the {@link VFXAction#SET_EXPR}
 * action only — the parameter name and expression source to install at runtime.
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
	@Nullable String exprSource
) implements CustomPacketPayload {
	public static final byte PROTOCOL_VERSION = 6;
	/**
	 * Safety cap on the number of parameters a play packet may carry (server input, see AGENTS.md).
	 * Raised from 32 to 256 for per-leaf mask dynamic data: a mask may hold eight leaves and two
	 * custom leaves each register {@code MAX_LEAF_DATA = 32} {@code mask.p<N>.d<J>} params, so a
	 * large mask's resolved constant map no longer fits in 32. The wire format is unchanged (a
	 * varint count then entries); only this read bound moved, so {@code PROTOCOL_VERSION} stays 6.
	 */
	public static final int MAX_PARAMS = 256;
	/** Safety cap on the number of entity UUIDs in one packet. */
	public static final int MAX_ENTITY_UUIDS = 16;
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
		OPTIONAL_STRING,
		VFXTriggerPayload::exprParam,
		OPTIONAL_STRING,
		VFXTriggerPayload::exprSource,
		VFXTriggerPayload::new
	);

	/**
	 * Creates a play payload with the current protocol version.
	 */
	public static VFXTriggerPayload play(final Identifier effectId, final int durationTicks, final Map<String, Float> params, final EasingType easing) {
		return play(effectId, durationTicks, 0L, null, List.of(), params, easing.name());
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
		return play(effectId, durationTicks, 0, instanceId, position, entityUuids, params, easing);
	}

	/**
	 * Creates a play payload that resumes an already-running timeline from the given offset.
	 *
	 * @param elapsedTicks how far into the timeline the effect already is, in ticks
	 */
	public static VFXTriggerPayload play(final Identifier effectId, final int durationTicks, final int elapsedTicks, final long instanceId, final @Nullable Vec3 position, final List<UUID> entityUuids, final Map<String, Float> params, final String easing) {
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.PLAY, durationTicks, Math.max(0, elapsedTicks), instanceId, position, entityUuids, params, easing, null, null);
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
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.STOP, 0, 0, instanceId, null, List.of(), Map.of(), EasingType.LINEAR.name(), null, null);
	}

	/**
	 * Creates a live parameter-override payload for a running effect ({@code params} carries
	 * a single {@code name -> value} entry).
	 */
	public static VFXTriggerPayload setParam(final Identifier effectId, final String param, final float value) {
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.SET_PARAM, 0, 0, 0L, null, List.of(), Map.of(param, value), EasingType.LINEAR.name(), null, null);
	}

	/**
	 * Creates a live keyframe payload for a running effect: {@code time} is carried in
	 * {@code durationTicks}, the value in {@code params} and the outgoing easing in {@code easing}.
	 */
	public static VFXTriggerPayload keyframe(final Identifier effectId, final String param, final int time, final float value, final EasingType easing) {
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.KEYFRAME, time, 0, 0L, null, List.of(), Map.of(param, value), easing.name(), null, null);
	}

	/**
	 * Creates a live keyframe payload with a custom curve name.
	 */
	public static VFXTriggerPayload keyframe(final Identifier effectId, final String param, final int time, final float value, final String easing) {
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.KEYFRAME, time, 0, 0L, null, List.of(), Map.of(param, value), easing, null, null);
	}

	/**
	 * Creates a live expression payload for a running effect: {@code exprSource} replaces the
	 * parameter's whole value source (keyframes, binding or previous expression) at runtime.
	 */
	public static VFXTriggerPayload setExpr(final Identifier effectId, final String param, final String exprSource) {
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.SET_EXPR, 0, 0, 0L, null, List.of(), Map.of(), EasingType.LINEAR.name(), param, exprSource);
	}

	/**
	 * Creates a move payload that re-anchors a running instance to a new world position.
	 *
	 * @param effectId   effect id
	 * @param instanceId instance id to move (must match an instance of the effect on the client)
	 * @param worldPos   the new world position
	 */
	public static VFXTriggerPayload move(final Identifier effectId, final long instanceId, final Vec3 worldPos) {
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.MOVE, 0, 0, instanceId, worldPos, List.of(), Map.of(), EasingType.LINEAR.name(), null, null);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
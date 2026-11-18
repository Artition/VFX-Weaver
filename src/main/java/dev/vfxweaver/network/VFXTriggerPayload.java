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
 * effect applies to (for entity tint/outline effects), an optional list of world positions one
 * instance renders at (empty for the single-point behaviour), for the {@link VFXAction#SET_EXPR}
 * action only the parameter name and expression source to install at runtime, and the optional
 * text override that replaces a {@code text_overlay} definition's {@code text.content} for this
 * call.
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
	List<Vec3> positions,
	Map<String, Float> params,
	String easing,
	@Nullable String exprParam,
	@Nullable String exprSource,
	@Nullable String textOverride
) implements CustomPacketPayload {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/payload");
	/**
	 * Bumped 7 -> 8 for {@code positions}: an old reader would misparse the extra list. An old
	 * client ignores this version on receipt (see {@code VFXClient}), so it keeps its former
	 * single-point behaviour for effects it does understand.
	 */
	public static final byte PROTOCOL_VERSION = 8;
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
	/**
	 * Cap on the number of world positions one play packet may carry, mirroring
	 * {@code VFXDefinition.MAX_POSITIONS}. This is a <b>packet-size bound</b> (8192 positions would
	 * need ~200 KiB), not a cap on how many instances may run: one instance renders every position
	 * in this list, so a single packet can tint hundreds of blocks well under
	 * {@code MAX_ACTIVE_EFFECTS}. A longer list is cut on decode with one warning.
	 */
	public static final int MAX_POSITIONS = 4096;
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
	 * World-position list with the same wire shape as
	 * {@code ByteBufCodecs.collection(ArrayList::new, Vec3.STREAM_CODEC, MAX_POSITIONS)} (a varint
	 * count then element codecs), except that an over-long list on decode is cut to
	 * {@link #MAX_POSITIONS} with one warning instead of failing the packet — a hostile or buggy
	 * server must not disconnect the client over a too-large position list. The whole count is still
	 * read off the buffer so the stream stays aligned; entries past the cap are dropped.
	 */
	public static final StreamCodec<ByteBuf, List<Vec3>> POSITIONS_CODEC = new StreamCodec<>() {
		public List<Vec3> decode(final ByteBuf input) {
			int declared = ByteBufCodecs.VAR_INT.decode(input);
			if (declared <= 0) {
				return List.of();
			}
			int kept = Math.min(declared, MAX_POSITIONS);
			List<Vec3> out = new ArrayList<>(kept);
			for (int i = 0; i < declared; i++) {
				Vec3 value = Vec3.STREAM_CODEC.decode(input);
				if (i < kept) {
					out.add(value);
				}
			}
			if (declared > MAX_POSITIONS) {
				VFXLog.warnOnce(LOGGER, "net:positions-clip", "Play packet carries {} positions; cut to MAX_POSITIONS ({})", declared, MAX_POSITIONS);
			}
			return out;
		}

		public void encode(final ByteBuf output, final List<Vec3> value) {
			List<Vec3> list = value == null ? List.of() : value;
			int size = Math.min(list.size(), MAX_POSITIONS);
			ByteBufCodecs.VAR_INT.encode(output, size);
			for (int i = 0; i < size; i++) {
				Vec3.STREAM_CODEC.encode(output, list.get(i));
			}
		}
	};

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
		POSITIONS_CODEC,
		VFXTriggerPayload::positions,
		ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.FLOAT, MAX_PARAMS),
		VFXTriggerPayload::params,
		ByteBufCodecs.STRING_UTF8,
		VFXTriggerPayload::easing,
		TRAILING_STRINGS_CODEC,
		VFXTriggerPayload::trailingStrings,
		(protocolVersion, effectId, action, durationTicks, elapsedTicks, instanceId, position, entityUuids, positions, params, easing, trailingStrings) -> new VFXTriggerPayload(
			protocolVersion, effectId, action, durationTicks, elapsedTicks, instanceId, position, entityUuids, positions, params, easing,
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
		return play(effectId, durationTicks, 0, 0L, null, List.of(), List.of(), params, easing.name(), textOverride);
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
		return play(effectId, durationTicks, 0, instanceId, position, List.of(), List.of(), params, easing, null);
	}

	/**
	 * Creates a play payload that renders one instance at every world position in {@code positions}
	 * (empty = the single-point behaviour). Used by the network API to tint many blocks with one
	 * packet instead of one instance per block.
	 *
	 * @param effectId      effect id
	 * @param durationTicks duration in ticks
	 * @param instanceId    instance id (0 = let the client allocate one)
	 * @param positions     world positions the instance renders at (empty = single point)
	 * @param params        parameter overrides
	 * @param easing        easing curve name (built-in or custom datapack curve)
	 */
	public static VFXTriggerPayload play(final Identifier effectId, final int durationTicks, final long instanceId, final List<Vec3> positions, final Map<String, Float> params, final String easing) {
		return play(effectId, durationTicks, 0, instanceId, null, List.of(), positions, params, easing, null);
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
		return play(effectId, durationTicks, 0, instanceId, position, entityUuids, List.of(), params, easing, null);
	}

	/**
	 * Same as {@link #play(Identifier, int, long, Vec3, List, Map, String)} with a call-time text
	 * override.
	 *
	 * @param textOverride replaces the definition's {@code text.content} (may be null for "no override")
	 */
	public static VFXTriggerPayload play(final Identifier effectId, final int durationTicks, final long instanceId, final @Nullable Vec3 position, final List<UUID> entityUuids, final Map<String, Float> params, final String easing, final @Nullable String textOverride) {
		return play(effectId, durationTicks, 0, instanceId, position, entityUuids, List.of(), params, easing, textOverride);
	}

	/**
	 * Creates a play payload that resumes an already-running timeline from the given offset.
	 *
	 * @param elapsedTicks how far into the timeline the effect already is, in ticks
	 */
	public static VFXTriggerPayload play(final Identifier effectId, final int durationTicks, final int elapsedTicks, final long instanceId, final @Nullable Vec3 position, final List<UUID> entityUuids, final Map<String, Float> params, final String easing) {
		return play(effectId, durationTicks, elapsedTicks, instanceId, position, entityUuids, List.of(), params, easing, null);
	}

	/**
	 * Same as {@link #play(Identifier, int, int, long, Vec3, List, Map, String)} with a call-time
	 * text override.
	 *
	 * @param textOverride replaces the definition's {@code text.content} (may be null for "no override")
	 */
	public static VFXTriggerPayload play(final Identifier effectId, final int durationTicks, final int elapsedTicks, final long instanceId, final @Nullable Vec3 position, final List<UUID> entityUuids, final Map<String, Float> params, final String easing, final @Nullable String textOverride) {
		return play(effectId, durationTicks, elapsedTicks, instanceId, position, entityUuids, List.of(), params, easing, textOverride);
	}

	/**
	 * Full play factory: every optional anchor ({@code position}, {@code entityUuids},
	 * {@code positions}) plus the resume offset and text override. The position list is carried as
	 * {@link #POSITIONS_CODEC} and cut to {@link #MAX_POSITIONS} on decode.
	 *
	 * @param positions world positions one instance renders at (empty = single-point behaviour)
	 */
	public static VFXTriggerPayload play(final Identifier effectId, final int durationTicks, final int elapsedTicks, final long instanceId, final @Nullable Vec3 position, final List<UUID> entityUuids, final List<Vec3> positions, final Map<String, Float> params, final String easing, final @Nullable String textOverride) {
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.PLAY, durationTicks, Math.max(0, elapsedTicks), instanceId, position, entityUuids, positions, params, easing, null, null, textOverride);
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
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.STOP, 0, 0, instanceId, null, List.of(), List.of(), Map.of(), EasingType.LINEAR.name(), null, null, null);
	}

	/**
	 * Creates a live parameter-override payload for a running effect ({@code params} carries
	 * a single {@code name -> value} entry).
	 */
	public static VFXTriggerPayload setParam(final Identifier effectId, final String param, final float value) {
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.SET_PARAM, 0, 0, 0L, null, List.of(), List.of(), Map.of(param, value), EasingType.LINEAR.name(), null, null, null);
	}

	/**
	 * Creates a live keyframe payload for a running effect: {@code time} is carried in
	 * {@code durationTicks}, the value in {@code params} and the outgoing easing in {@code easing}.
	 */
	public static VFXTriggerPayload keyframe(final Identifier effectId, final String param, final int time, final float value, final EasingType easing) {
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.KEYFRAME, time, 0, 0L, null, List.of(), List.of(), Map.of(param, value), easing.name(), null, null, null);
	}

	/**
	 * Creates a live keyframe payload with a custom curve name.
	 */
	public static VFXTriggerPayload keyframe(final Identifier effectId, final String param, final int time, final float value, final String easing) {
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.KEYFRAME, time, 0, 0L, null, List.of(), List.of(), Map.of(param, value), easing, null, null, null);
	}

	/**
	 * Creates a live expression payload for a running effect: {@code exprSource} replaces the
	 * parameter's whole value source (keyframes, binding or previous expression) at runtime.
	 */
	public static VFXTriggerPayload setExpr(final Identifier effectId, final String param, final String exprSource) {
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.SET_EXPR, 0, 0, 0L, null, List.of(), List.of(), Map.of(), EasingType.LINEAR.name(), param, exprSource, null);
	}

	/**
	 * Creates a move payload that re-anchors a running instance to a new world position.
	 *
	 * @param effectId   effect id
	 * @param instanceId instance id to move (must match an instance of the effect on the client)
	 * @param worldPos   the new world position
	 */
	public static VFXTriggerPayload move(final Identifier effectId, final long instanceId, final Vec3 worldPos) {
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.MOVE, 0, 0, instanceId, worldPos, List.of(), List.of(), Map.of(), EasingType.LINEAR.name(), null, null, null);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
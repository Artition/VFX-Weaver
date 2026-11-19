package dev.vfxweaver.network;

import dev.vfxweaver.effect.EasingFunction;
import dev.vfxweaver.effect.EasingType;
import dev.vfxweaver.util.VFXLog;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
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
 * call, and the optional mask JSON that replaces an effect's authored mask at runtime.
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
	@Nullable String textOverride,
	@Nullable String maskJson
) implements CustomPacketPayload {
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/payload");
	/**
	 * Bumped 9 -> 10 for the mask wire format: the trailing mask JSON now carries its
	 * {@code block_list} positions as a compact binary blob after the string, so an old reader would
	 * not consume those bytes and would fail to decode. An old client drops the whole packet on a
	 * version mismatch (see {@code VFXClient}), which is exactly the desired behaviour for a mask
	 * override it cannot apply. The payload codec peeks this version first and skips the rest of the
	 * buffer on a mismatch instead of throwing a decode error.
	 */
	public static final byte PROTOCOL_VERSION = 10;
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
	 * Character cap tied to {@code ByteBufCodecs.STRING_UTF8}/{@code Utf8String.write}: a JSON string
	 * longer than this cannot be written to the wire at all. Large {@code block_list} position arrays
	 * are lifted out of the JSON by {@link MaskWire} before this bound is checked.
	 */
	public static final int MAX_WIRE_JSON = 32767;
	/**
	 * Byte budget for the lifted position blobs: the 1 MiB custom-payload cap minus headroom for the
	 * other fields in the packet. This is a <b>packet-size bound</b> (see AGENTS.md), not a cap on the
	 * number of blocks a mask may hold. Large values may raise overdraw but are never hard-clamped.
	 */
	public static final int MAX_WIRE_POSITION_BYTES = (1 << 20) - 192 * 1024;

	/** null = the mask fits; otherwise the reason to refuse it before sending. */
	public static @Nullable String wireProblem(final String maskJson) {
		if (maskJson.contains(MaskWire.MARKER)) {
			return "mask JSON contains reserved '" + MaskWire.MARKER + "'";
		}
		final MaskWire.Split split = MaskWire.split(maskJson);
		long bytes = 0;
		for (final float[] blob : split.blobs()) {
			bytes += 4L * blob.length;
		}
		if (split.json().length() > MAX_WIRE_JSON) {
			return "mask JSON without positions is " + split.json().length() + " chars (max " + MAX_WIRE_JSON + ")";
		}
		if (bytes > MAX_WIRE_POSITION_BYTES) {
			return "positions take " + bytes + " bytes (max " + MAX_WIRE_POSITION_BYTES + ")";
		}
		return null;
	}
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
	 * The reference collection codec the position list is modelled on
	 * ({@code ByteBufCodecs.collection(ArrayList::new, Vec3.STREAM_CODEC, MAX_POSITIONS)}): used to
	 * encode (after clipping to {@link #MAX_POSITIONS}), while decode reads the same varint-count
	 * format by hand so an over-long list is cut, not rejected.
	 */
	private static final StreamCodec<ByteBuf, List<Vec3>> POSITIONS_COLLECTION = ByteBufCodecs.collection(ArrayList::new, Vec3.STREAM_CODEC, MAX_POSITIONS);

	/**
	 * World-position list with the same wire shape as {@link #POSITIONS_COLLECTION} (a varint count
	 * then element codecs), except that an over-long list on decode is cut to
	 * {@link #MAX_POSITIONS} with one warning instead of failing the packet — a hostile or buggy
	 * server must not disconnect the client over a too-large position list. The whole count is still
	 * read off the buffer so the stream stays aligned; entries past the cap are dropped. An empty
	 * list round-trips to {@link List#of()}, which the play path reads as the ordinary single point.
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
			POSITIONS_COLLECTION.encode(output, list.size() > MAX_POSITIONS ? list.subList(0, MAX_POSITIONS) : list);
		}
	};

	/**
	 * The four optional trailing strings, composed on their own: {@link StreamCodec#composite} has
	 * no overload past twelve codec pairs, and the payload has fourteen fields.
	 */
	private record TrailingStrings(@Nullable String exprParam, @Nullable String exprSource, @Nullable String textOverride, @Nullable String maskJson) {
	}

	/**
	 * The mask JSON as an optional string plus a compact binary tail of {@code block_list} positions.
	 * The JSON keeps a fail-closed {@code @wire:N} marker where the arrays were; the client restores
	 * the float triples before parsing. A {@code null} mask ("remove override") is encoded as just
	 * the absent-string marker with no blob count following it, which {@link #decode} mirrors.
	 */
	public static final StreamCodec<ByteBuf, String> OPTIONAL_MASK_JSON = new StreamCodec<>() {
		public String decode(final ByteBuf input) {
			final String json = OPTIONAL_STRING.decode(input);
			if (json == null) {
				return null;
			}
			final int count = VarInt.read(input);
			if (count < 0 || count > input.readableBytes()) {
				throw new DecoderException("mask wire: bad array count " + count);
			}
			final List<float[]> blobs = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				final int positions = VarInt.read(input);
				if (positions < 0 || 12L * positions > input.readableBytes()) {
					throw new DecoderException("mask wire: bad position count " + positions);
				}
				final float[] flat = new float[positions * 3];
				for (int j = 0; j < flat.length; j++) {
					flat[j] = input.readFloat();
				}
				blobs.add(flat);
			}
			return MaskWire.join(json, blobs);
		}

		public void encode(final ByteBuf output, final String value) {
			if (value == null) {
				OPTIONAL_STRING.encode(output, null); // null = remove override
				return;
			}
			final MaskWire.Split split = MaskWire.split(value);
			final boolean fits = wireProblem(value) == null; // last line of defence
			OPTIONAL_STRING.encode(output, fits ? split.json()
					: split.json().substring(0, Math.min(split.json().length(), MAX_WIRE_JSON)));
			final List<float[]> blobs = fits ? split.blobs() : List.of();
			VarInt.write(output, blobs.size());
			for (final float[] blob : blobs) {
				VarInt.write(output, blob.length / 3);
				for (final float coordinate : blob) {
					output.writeFloat(coordinate);
				}
			}
		}
	};

	private static final StreamCodec<ByteBuf, TrailingStrings> TRAILING_STRINGS_CODEC = StreamCodec.composite(
		OPTIONAL_STRING,
		TrailingStrings::exprParam,
		OPTIONAL_STRING,
		TrailingStrings::exprSource,
		OPTIONAL_TEXT_OVERRIDE,
		TrailingStrings::textOverride,
		OPTIONAL_MASK_JSON,
		TrailingStrings::maskJson,
		TrailingStrings::new
	);

	private static final StreamCodec<RegistryFriendlyByteBuf, VFXTriggerPayload> COMPOSITE_CODEC = StreamCodec.composite(
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
			trailingStrings.exprParam(), trailingStrings.exprSource(), trailingStrings.textOverride(), trailingStrings.maskJson()
		)
	);

	private static final Identifier VERSION_MISMATCH_ID =
			Identifier.fromNamespaceAndPath("vfxweaver", "__protocol_mismatch");

	/**
	 * Peeks the protocol version before decoding the fourteen fields. A payload from an older client
	 * carries a shape this codec cannot read, so on a mismatch the rest of the buffer is skipped and a
	 * sentinel returned: {@code VFXClient.handleTrigger} already drops any payload whose version
	 * differs, so the skip is silent instead of a decode error that would disconnect the client.
	 */
	public static final StreamCodec<RegistryFriendlyByteBuf, VFXTriggerPayload> STREAM_CODEC =
			new StreamCodec<>() {
				@Override
				public VFXTriggerPayload decode(final RegistryFriendlyByteBuf input) {
					input.markReaderIndex();
					final byte version = input.readByte();
					if (version != PROTOCOL_VERSION) {
						input.skipBytes(input.readableBytes());
						return new VFXTriggerPayload(version, VERSION_MISMATCH_ID, VFXAction.STOP,
								0, 0, 0L, null, List.of(), List.of(), Map.of(), "linear", null, null, null, null);
					}
					input.resetReaderIndex();
					return COMPOSITE_CODEC.decode(input);
				}

				@Override
				public void encode(final RegistryFriendlyByteBuf output, final VFXTriggerPayload value) {
					COMPOSITE_CODEC.encode(output, value);
				}
			};

	/** Groups the optional trailing strings for {@link #TRAILING_STRINGS_CODEC}. */
	private TrailingStrings trailingStrings() {
		return new TrailingStrings(this.exprParam, this.exprSource, this.textOverride, this.maskJson);
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
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.PLAY, durationTicks, Math.max(0, elapsedTicks), instanceId, position, entityUuids, positions, params, easing, null, null, textOverride, null);
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
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.STOP, 0, 0, instanceId, null, List.of(), List.of(), Map.of(), EasingType.LINEAR.name(), null, null, null, null);
	}

	/**
	 * Creates a live parameter-override payload for a running effect ({@code params} carries
	 * a single {@code name -> value} entry).
	 */
	public static VFXTriggerPayload setParam(final Identifier effectId, final String param, final float value) {
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.SET_PARAM, 0, 0, 0L, null, List.of(), List.of(), Map.of(param, value), EasingType.LINEAR.name(), null, null, null, null);
	}

	/**
	 * Creates a live keyframe payload for a running effect: {@code time} is carried in
	 * {@code durationTicks}, the value in {@code params} and the outgoing easing in {@code easing}.
	 */
	public static VFXTriggerPayload keyframe(final Identifier effectId, final String param, final int time, final float value, final EasingType easing) {
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.KEYFRAME, time, 0, 0L, null, List.of(), List.of(), Map.of(param, value), easing.name(), null, null, null, null);
	}

	/**
	 * Creates a live keyframe payload with a custom curve name.
	 */
	public static VFXTriggerPayload keyframe(final Identifier effectId, final String param, final int time, final float value, final String easing) {
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.KEYFRAME, time, 0, 0L, null, List.of(), List.of(), Map.of(param, value), easing, null, null, null, null);
	}

	/**
	 * Creates a live expression payload for a running effect: {@code exprSource} replaces the
	 * parameter's whole value source (keyframes, binding or previous expression) at runtime.
	 */
	public static VFXTriggerPayload setExpr(final Identifier effectId, final String param, final String exprSource) {
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.SET_EXPR, 0, 0, 0L, null, List.of(), List.of(), Map.of(), EasingType.LINEAR.name(), param, exprSource, null, null);
	}

	/**
	 * Creates a move payload that re-anchors a running instance to a new world position.
	 *
	 * @param effectId   effect id
	 * @param instanceId instance id to move (must match an instance of the effect on the client)
	 * @param worldPos   the new world position
	 */
	public static VFXTriggerPayload move(final Identifier effectId, final long instanceId, final Vec3 worldPos) {
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.MOVE, 0, 0, instanceId, worldPos, List.of(), List.of(), Map.of(), EasingType.LINEAR.name(), null, null, null, null);
	}

	/**
	 * Creates a mask payload that replaces the effect's authored mask for every running and future
	 * instance of that effect id, or removes the override and restores the authored mask.
	 *
	 * <p>A {@code null} or blank {@code maskJson} is the "remove the override" form, so an absent
	 * value must never be encoded for an over-long one: {@code VFXAPI.sendMask} refuses a value that
	 * fails {@link #wireProblem(String)} and the codec never sends {@code null} in its place.
	 *
	 * @param effectId the effect whose mask is overridden (all its instances, per player)
	 * @param maskJson the mask JSON object (the same shape as a datapack {@code mask} block); null
	 *                 or blank removes the override
	 */
	public static VFXTriggerPayload setMask(final Identifier effectId, final @Nullable String maskJson) {
		return new VFXTriggerPayload(PROTOCOL_VERSION, effectId, VFXAction.SET_MASK, 0, 0, 0L, null, List.of(), List.of(), Map.of(), EasingType.LINEAR.name(), null, null, null, maskJson);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
package dev.vfxweaver.network;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client packet that synchronizes the datapack-defined VFX effect definitions and
 * easing curves to a connecting (or just-reloaded) client. The client keeps its built-in
 * {@code vfxweaver:*} effects and merges the received datapack definitions on top, so custom
 * effects declared in {@code data/<namespace>/vfx/*.json} work on dedicated servers just like
 * in single player.
 *
 * <p>Carries the raw JSON source of each definition/curve, which the client re-parses through
 * the normal {@code VFXDefinitionManager}/{@code VFXCurveManager} loading path (so a malformed
 * entry from the server is logged and skipped without breaking the rest).
 *
 * <p>A definition's top-level {@code positions} array is lifted into {@link #positionBlobs} as raw
 * float triples (12 bytes each) so a large {@code block_tint}/{@code block_outline} list does not
 * overflow {@code ByteBufCodecs.STRING_UTF8}. The client restores it before parsing. The map sizes
 * are capped ({@link #MAX_DEFINITIONS} / {@link #MAX_CURVES}) and each blob is bounded by the same
 * packet budget as the trigger payload.
 */
public record VFXSyncPayload(
	byte protocolVersion,
	Map<Identifier, String> definitions,
	Map<Identifier, String> curves,
	Map<Identifier, List<float[]>> positionBlobs
) implements CustomPacketPayload {
	public static final byte PROTOCOL_VERSION = 3;
	public static final int MAX_DEFINITIONS = 1024;
	public static final int MAX_CURVES = 256;
	public static final Type<VFXSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("vfxweaver", "vfx_sync"));

	/** Convenience overload for callers that do not lift positions: the blob map defaults to empty. */
	public VFXSyncPayload(final byte protocolVersion, final Map<Identifier, String> definitions,
			final Map<Identifier, String> curves) {
		this(protocolVersion, definitions, curves, Map.of());
	}

	private static final StreamCodec<ByteBuf, Map<Identifier, String>> DEFINITIONS_CODEC =
		ByteBufCodecs.map(HashMap::new, Identifier.STREAM_CODEC, ByteBufCodecs.STRING_UTF8, MAX_DEFINITIONS);
	private static final StreamCodec<ByteBuf, Map<Identifier, String>> CURVES_CODEC =
		ByteBufCodecs.map(HashMap::new, Identifier.STREAM_CODEC, ByteBufCodecs.STRING_UTF8, MAX_CURVES);

	/** Raw float triples lifted out of a definition's top-level positions array. */
	private static final StreamCodec<ByteBuf, List<float[]>> POSITION_BLOBS_CODEC = new StreamCodec<>() {
		@Override
		public List<float[]> decode(final ByteBuf input) {
			final int count = VarInt.read(input);
			if (count < 0 || count > MAX_DEFINITIONS) {
				throw new DecoderException("sync wire: bad blob count " + count);
			}
			final List<float[]> blobs = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				final int positions = VarInt.read(input);
				if (positions < 0 || 12L * positions > VFXTriggerPayload.MAX_WIRE_POSITION_BYTES
						|| 12L * positions > input.readableBytes()) {
					throw new DecoderException("sync wire: bad position count " + positions);
				}
				final float[] flat = new float[positions * 3];
				for (int j = 0; j < flat.length; j++) {
					flat[j] = input.readFloat();
				}
				blobs.add(flat);
			}
			return blobs;
		}

		@Override
		public void encode(final ByteBuf output, final List<float[]> value) {
			VarInt.write(output, value.size());
			for (final float[] blob : value) {
				VarInt.write(output, blob.length / 3);
				for (final float axis : blob) {
					output.writeFloat(axis);
				}
			}
		}
	};

	private static final StreamCodec<ByteBuf, Map<Identifier, List<float[]>>> POSITION_BLOBS_MAP_CODEC =
		ByteBufCodecs.map(HashMap::new, Identifier.STREAM_CODEC, POSITION_BLOBS_CODEC, MAX_DEFINITIONS);

	private static final StreamCodec<ByteBuf, VFXSyncPayload> COMPOSITE_CODEC = StreamCodec.composite(
		ByteBufCodecs.BYTE, VFXSyncPayload::protocolVersion,
		DEFINITIONS_CODEC, VFXSyncPayload::definitions,
		CURVES_CODEC, VFXSyncPayload::curves,
		POSITION_BLOBS_MAP_CODEC, VFXSyncPayload::positionBlobs,
		VFXSyncPayload::new
	);

	/**
	 * Peeks the protocol version before decoding the rest. A v2 sync packet carries no blob map, so
	 * on a mismatch the rest of the buffer is skipped and a sentinel returned: VFXClient.handleSync
	 * already drops any payload whose version differs, so the skip is silent instead of a decode
	 * error that would disconnect the client.
	 */
	public static final StreamCodec<ByteBuf, VFXSyncPayload> STREAM_CODEC = new StreamCodec<>() {
		@Override
		public VFXSyncPayload decode(final ByteBuf input) {
			input.markReaderIndex();
			final byte version = input.readByte();
			if (version != PROTOCOL_VERSION) {
				input.skipBytes(input.readableBytes());
				return new VFXSyncPayload(version, Map.of(), Map.of());
			}
			input.resetReaderIndex();
			return COMPOSITE_CODEC.decode(input);
		}

		@Override
		public void encode(final ByteBuf output, final VFXSyncPayload value) {
			COMPOSITE_CODEC.encode(output, value);
		}
	};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

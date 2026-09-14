package dev.vfxweaver.network;

import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.Map;
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
 * <p>The map sizes are capped ({@link #MAX_DEFINITIONS} / {@link #MAX_CURVES}) and each string is
 * capped by {@code ByteBufCodecs.STRING_UTF8}, protecting the client from oversized packets from
 * a hostile or buggy server.
 */
public record VFXSyncPayload(byte protocolVersion, Map<Identifier, String> definitions, Map<Identifier, String> curves)
	implements CustomPacketPayload {
	public static final byte PROTOCOL_VERSION = 2;
	public static final int MAX_DEFINITIONS = 1024;
	public static final int MAX_CURVES = 256;
	public static final Type<VFXSyncPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("vfxweaver", "vfx_sync"));

	private static final StreamCodec<ByteBuf, Map<Identifier, String>> DEFINITIONS_CODEC =
		ByteBufCodecs.map(HashMap::new, Identifier.STREAM_CODEC, ByteBufCodecs.STRING_UTF8, MAX_DEFINITIONS);
	private static final StreamCodec<ByteBuf, Map<Identifier, String>> CURVES_CODEC =
		ByteBufCodecs.map(HashMap::new, Identifier.STREAM_CODEC, ByteBufCodecs.STRING_UTF8, MAX_CURVES);

	public static final StreamCodec<ByteBuf, VFXSyncPayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.BYTE, VFXSyncPayload::protocolVersion,
		DEFINITIONS_CODEC, VFXSyncPayload::definitions,
		CURVES_CODEC, VFXSyncPayload::curves,
		VFXSyncPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
package dev.vfxweaver.network;

import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Client-to-server packet that asks the server to play a VFX effect. With {@code broadcast}
 * set the effect is sent to every connected player (permission-gated server-side, see
 * {@code VFXPayloads}); otherwise it plays only for the requesting player. The protocol
 * version must equal {@link VFXTriggerPayload#PROTOCOL_VERSION} — the handler drops the
 * packet otherwise.
 *
 * <p>Carries the effect id, whether to broadcast, an optional instance id ({@code 0} lets the
 * client allocate one), an optional world position to anchor the effect to, the parameter
 * overrides and the easing curve name (built-in or custom datapack curve).
 */
public record VFXRequestPayload(
	byte protocolVersion,
	Identifier effectId,
	boolean broadcast,
	long instanceId,
	@Nullable Vec3 worldPos,
	Map<String, Float> params,
	String easing
) implements CustomPacketPayload {
	public static final Type<VFXRequestPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("vfxweaver", "vfx_request"));

	public static final StreamCodec<ByteBuf, VFXRequestPayload> STREAM_CODEC = StreamCodec.composite(
		ByteBufCodecs.BYTE,
		VFXRequestPayload::protocolVersion,
		Identifier.STREAM_CODEC,
		VFXRequestPayload::effectId,
		ByteBufCodecs.BOOL,
		VFXRequestPayload::broadcast,
		ByteBufCodecs.VAR_LONG,
		VFXRequestPayload::instanceId,
		VFXTriggerPayload.OPTIONAL_VEC3,
		VFXRequestPayload::worldPos,
		ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.FLOAT, VFXTriggerPayload.MAX_PARAMS),
		VFXRequestPayload::params,
		ByteBufCodecs.STRING_UTF8,
		VFXRequestPayload::easing,
		VFXRequestPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

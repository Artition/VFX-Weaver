package dev.vfxweaver.network;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server-to-client packet carrying the scoreboard values referenced by active scoreboard bindings.
 *
 * <p>The server derives the tracked {@code (objective, holder)} pairs from the effect definitions
 * it sends (see {@code VFXScoreboardSync}), so the client never names an objective itself. An
 * absent value means "no score / untracked" and removes the entry from the client cache, making
 * the binding fall back to its default (0).
 */
public record VFXScoreboardPayload(List<ScoreUpdate> updates) implements CustomPacketPayload {
	/** Hard cap per packet (external input, see AGENTS.md). */
	public static final int MAX_UPDATES = 256;
	public static final Type<VFXScoreboardPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("vfxweaver", "scoreboard_sync"));

	/**
	 * One tracked score. {@code holder} is already resolved to a concrete scoreholder name by the
	 * server (a binding's {@code null} holder becomes the receiving player's name).
	 */
	public record ScoreUpdate(String objective, String holder, OptionalInt value) {
		/** Explicit presence flag + VAR_INT, so "no score" is distinct from a value of 0. */
		private static final StreamCodec<ByteBuf, OptionalInt> OPTIONAL_INT = new StreamCodec<>() {
			@Override
			public OptionalInt decode(final ByteBuf buf) {
				return buf.readBoolean() ? OptionalInt.of(VarInt.read(buf)) : OptionalInt.empty();
			}

			@Override
			public void encode(final ByteBuf buf, final OptionalInt value) {
				buf.writeBoolean(value.isPresent());
				if (value.isPresent()) {
					VarInt.write(buf, value.getAsInt());
				}
			}
		};

		public static final StreamCodec<ByteBuf, ScoreUpdate> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, ScoreUpdate::objective,
			ByteBufCodecs.STRING_UTF8, ScoreUpdate::holder,
			OPTIONAL_INT, ScoreUpdate::value,
			ScoreUpdate::new
		);
	}

	public static final StreamCodec<ByteBuf, VFXScoreboardPayload> STREAM_CODEC = new StreamCodec<>() {
		@Override
		public VFXScoreboardPayload decode(final ByteBuf buf) {
			int size = Math.min(VarInt.read(buf), MAX_UPDATES);
			List<ScoreUpdate> list = new ArrayList<>(size);
			for (int i = 0; i < size; i++) {
				list.add(ScoreUpdate.STREAM_CODEC.decode(buf));
			}
			return new VFXScoreboardPayload(list);
		}

		@Override
		public void encode(final ByteBuf buf, final VFXScoreboardPayload payload) {
			List<ScoreUpdate> list = payload.updates();
			VarInt.write(buf, list.size());
			for (ScoreUpdate update : list) {
				ScoreUpdate.STREAM_CODEC.encode(buf, update);
			}
		}
	};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}

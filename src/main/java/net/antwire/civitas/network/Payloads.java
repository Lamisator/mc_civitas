package net.antwire.civitas.network;

import net.antwire.civitas.Civitas;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Two generic payloads carrying JSON: state from server to client, actions from client to server (see {@link Dto}). */
public final class Payloads {
	private Payloads() {
	}

	public record State(String kind, String json) implements CustomPacketPayload {
		public static final Type<State> TYPE = new Type<>(Civitas.id("state"));
		public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, State::kind, ByteBufCodecs.stringUtf8(1 << 20), State::json, State::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record Action(String kind, String json) implements CustomPacketPayload {
		public static final Type<Action> TYPE = new Type<>(Civitas.id("action"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = StreamCodec.composite(
			ByteBufCodecs.STRING_UTF8, Action::kind, ByteBufCodecs.stringUtf8(1 << 16), Action::json, Action::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}
}

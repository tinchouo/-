package ar.tod5.menu;

import java.nio.charset.StandardCharsets;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Los mensajes entre el mod y el plugin del server. Son texto plano (UTF-8), asi el plugin de Paper los lee
 * como "plugin messages" sin nada especial:
 *   server -> mod  (tod5:sala):  "espera;<cantidad>"  |  "abrir"
 *   mod -> server  (tod5:hola):  "<version del mod>;<mod1>,<mod2>,..."
 */
public final class Red {
	private Red() {}

	public record Texto(Type<Texto> tipo, String texto) implements CustomPacketPayload {
		@Override public Type<? extends CustomPacketPayload> type() { return tipo; }
	}

	public static final CustomPacketPayload.Type<Texto> SALA = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tod5", "sala"));
	public static final CustomPacketPayload.Type<Texto> HOLA = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tod5", "hola"));

	public static StreamCodec<RegistryFriendlyByteBuf, Texto> codec(CustomPacketPayload.Type<Texto> tipo) {
		return StreamCodec.of(
			(buf, p) -> buf.writeBytes(p.texto().getBytes(StandardCharsets.UTF_8)),
			buf -> {
				byte[] b = new byte[buf.readableBytes()];
				buf.readBytes(b);
				return new Texto(tipo, new String(b, StandardCharsets.UTF_8));
			});
	}
}

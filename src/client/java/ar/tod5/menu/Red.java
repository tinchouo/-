package ar.tod5.menu;

import java.nio.charset.StandardCharsets;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Los mensajes entre el mod y el plugin del server. Son texto plano (UTF-8), asi el plugin de Paper los lee
 * como "plugin messages" sin nada especial:
 *   mod -> server  (tod5:hola):    "<version del mod>;<mod1>,<mod2>,..."
 *   server -> mod  (tod5:tablet):  JSON con lo que muestra la tablet (solo lo que ese jugador puede ver)
 *   server -> mod  (tod5:ruleta):  el numero del color en el que frena la ruleta (0..7)
 *   mod -> server  (tod5:accion):  JSON con lo que hace un editor en la tablet (activar crafteos, etc.)
 */
public final class Red {
	private Red() {}

	public record Texto(Type<Texto> tipo, String texto) implements CustomPacketPayload {
		@Override public Type<? extends CustomPacketPayload> type() { return tipo; }
	}

	public static final CustomPacketPayload.Type<Texto> HOLA = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tod5", "hola"));
	public static final CustomPacketPayload.Type<Texto> TABLET = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tod5", "tablet"));
	public static final CustomPacketPayload.Type<Texto> RULETA = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tod5", "ruleta"));
	public static final CustomPacketPayload.Type<Texto> ACCION = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tod5", "accion"));
	/** server -> mod: el contador de un desafio de monedas: "juntadas;total", "fin;total" o "nada". */
	public static final CustomPacketPayload.Type<Texto> DESAFIO = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tod5", "desafio"));

	/** server -> mod: la gravedad de un jugador: "uuid;direccion" (abajo, arriba, este, oeste, sur, norte). */
	public static final CustomPacketPayload.Type<Texto> GRAVEDAD = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tod5", "gravedad"));
	/** server -> mod: los orbes y pads del mundo (JSON). */
	public static final CustomPacketPayload.Type<Texto> SALTOS = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tod5", "saltos"));
	/** server -> mod: las plataformas que se mueven (JSON: id, raiz, tamaño). */
	public static final CustomPacketPayload.Type<Texto> PLATAFORMAS = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tod5", "plataformas"));
	/** server -> mod: la mochila que tenes puesta y el color de la de cada jugador (JSON). */
	public static final CustomPacketPayload.Type<Texto> EQUIPOS = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tod5", "equipos"));
	public static final CustomPacketPayload.Type<Texto> SISTEMA = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tod5", "sistema"));
	public static final CustomPacketPayload.Type<Texto> MOCHILAS = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tod5", "mochilas"));
	/** mod -> server: use este orbe/pad ("id"). */
	public static final CustomPacketPayload.Type<Texto> SALTO = new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tod5", "salto"));

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

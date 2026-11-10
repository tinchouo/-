package ar.tod5.menu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Le pregunta al server como esta, desde el menu y sin entrar (igual que la lista de servers de Minecraft).
 * Cada 3 segundos, en un hilo aparte, para no trabar el menu.
 *
 * El plugin del server contesta en la descripcion: "tod5;<abierto|preparacion>;<jugadores en la fila>".
 * Para que el server sepa quien esta en la fila, el mod le pasa un codigo propio en la direccion del saludo.
 */
public final class EstadoServer {
	public enum Estado { BUSCANDO, CERRADO, PREPARACION, ABIERTO }

	private static final Logger LOG = LoggerFactory.getLogger("TOD5");
	private static final String CODIGO = UUID.randomUUID().toString().substring(0, 8);

	public static volatile Estado estado = Estado.BUSCANDO;
	public static volatile int enFilaServer = 0, conectados = 0, maximo = 0;
	/** Apreto JUGAR con el server sin abrir: esta en la fila y entra solo cuando abra. */
	public static volatile boolean enFila = false;

	private static Thread hilo;
	private static volatile boolean activo;
	private static Estado anterior = null;

	private EstadoServer() {}

	public static synchronized void empezar() {
		activo = true;
		if (hilo != null && hilo.isAlive()) return;
		hilo = new Thread(EstadoServer::bucle, "TOD5 estado del server");
		hilo.setDaemon(true);
		hilo.start();
	}

	public static void parar() { activo = false; }

	private static void bucle() {
		while (activo) {
			consultar();
			try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
		}
	}

	private static void consultar() {
		Estado nuevo;
		try {
			ServerAddress dir = ServerAddress.parseString(TOD5Client.IP_SERVER);
			ResolvedServerAddress r = ServerNameResolver.DEFAULT.resolveAddress(dir).orElseThrow(() -> new IOException("no se encontro el server"));
			String json = pedirEstado(r.getHostIp(), r.getPort(), dir.getHost() + "\0tod5:" + CODIGO + (enFila ? ":fila" : ""));
			JsonObject o = JsonParser.parseString(json).getAsJsonObject();
			String desc = textoPlano(o.get("description"));
			if (o.has("players")) {
				conectados = o.getAsJsonObject("players").get("online").getAsInt();
				maximo = o.getAsJsonObject("players").get("max").getAsInt();
			}
			if (desc.startsWith("tod5;")) {
				String[] p = desc.split(";");
				nuevo = "abierto".equals(p[1]) ? Estado.ABIERTO : Estado.PREPARACION;
				enFilaServer = p.length > 2 ? Integer.parseInt(p[2].trim()) : 0;
			} else {
				nuevo = Estado.ABIERTO;   // server sin el plugin: lo tomo como abierto
			}
		} catch (Exception e) {
			nuevo = Estado.CERRADO;
		}
		estado = nuevo;
		if (nuevo != anterior) { LOG.info("TOD5 estado del server: {} (en la fila: {})", nuevo, enFilaServer); anterior = nuevo; }
	}

	/** Protocolo de "estado" de Minecraft: saludo + pedido de estado, y lee el JSON de respuesta. */
	private static String pedirEstado(String host, int puerto, String direccionSaludo) throws IOException {
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress(host, puerto), 3000);
			s.setSoTimeout(3000);
			DataOutputStream out = new DataOutputStream(s.getOutputStream());
			DataInputStream in = new DataInputStream(s.getInputStream());
			ByteArrayOutputStream b = new ByteArrayOutputStream();
			DataOutputStream h = new DataOutputStream(b);
			varInt(h, 0x00); varInt(h, -1); texto(h, direccionSaludo); h.writeShort(puerto); varInt(h, 1);
			varInt(out, b.size()); out.write(b.toByteArray());
			varInt(out, 1); varInt(out, 0x00);   // pedido de estado
			out.flush();
			leerVarInt(in);                       // largo del paquete
			if (leerVarInt(in) != 0x00) throw new IOException("respuesta rara");
			byte[] js = new byte[leerVarInt(in)];
			in.readFully(js);
			return new String(js, StandardCharsets.UTF_8);
		}
	}

	private static String textoPlano(JsonElement e) {
		if (e == null || e.isJsonNull()) return "";
		if (e.isJsonPrimitive()) return e.getAsString();
		if (e.isJsonArray()) { StringBuilder sb = new StringBuilder(); e.getAsJsonArray().forEach(x -> sb.append(textoPlano(x))); return sb.toString(); }
		JsonObject o = e.getAsJsonObject();
		StringBuilder sb = new StringBuilder(o.has("text") ? o.get("text").getAsString() : "");
		if (o.has("extra")) o.getAsJsonArray("extra").forEach(x -> sb.append(textoPlano(x)));
		return sb.toString();
	}

	private static void varInt(DataOutputStream o, int v) throws IOException {
		while ((v & ~0x7F) != 0) { o.writeByte((v & 0x7F) | 0x80); v >>>= 7; }
		o.writeByte(v);
	}
	private static int leerVarInt(DataInputStream in) throws IOException {
		int v = 0, n = 0; byte b;
		do { b = in.readByte(); v |= (b & 0x7F) << (7 * n++); if (n > 5) throw new IOException("varint largo"); } while ((b & 0x80) != 0);
		return v;
	}
	private static void texto(DataOutputStream o, String s) throws IOException {
		byte[] b = s.getBytes(StandardCharsets.UTF_8); varInt(o, b.length); o.write(b);
	}
}

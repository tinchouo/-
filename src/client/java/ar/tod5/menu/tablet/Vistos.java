package ar.tod5.menu.tablet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Lo que el jugador ya vio en la tablet (novedades y crafteos), para saber que es "nuevo".
 * Se guarda en config/tod5_vistos.txt (un renglon por cosa vista).
 */
final class Vistos {
	private static final Path ARCHIVO = FabricLoader.getInstance().getConfigDir().resolve("tod5_vistos.txt");
	private static Set<String> vistos;
	private static boolean cambio;

	private Vistos() {}

	private static Set<String> todos() {
		if (vistos == null) {
			vistos = new HashSet<>();
			try { if (Files.exists(ARCHIVO)) vistos.addAll(Files.readAllLines(ARCHIVO, StandardCharsets.UTF_8)); } catch (IOException ignorado) {}
		}
		return vistos;
	}
	static boolean visto(String id) { return todos().contains(id); }
	static void marcar(String id) { if (todos().add(id)) cambio = true; }
	static void guardar() {
		if (!cambio) return;
		cambio = false;
		try { Files.write(ARCHIVO, todos(), StandardCharsets.UTF_8); } catch (IOException ignorado) {}
	}
}

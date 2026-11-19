package ar.tod5.menu;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/** Ajustes del jugador (Opciones > TRUCOFDEATH). Se guardan en config/tod5.properties. */
public final class Ajustes {
	public enum PosRuleta {
		CENTRO("Centro"), ESQUINA("Abajo a la derecha");
		public final String texto;
		PosRuleta(String t) { texto = t; }
		public PosRuleta siguiente() { return values()[(ordinal() + 1) % values().length]; }
	}

	private static final Path ARCHIVO = FabricLoader.getInstance().getConfigDir().resolve("tod5.properties");
	private static PosRuleta ruleta = PosRuleta.CENTRO;
	private static boolean rendimiento;   // efectos de rareza: false = completos, true = rendimiento (sin animaciones ni particulas)
	private static boolean cargado;

	private Ajustes() {}

	public static PosRuleta ruleta() { cargar(); return ruleta; }
	public static void ruleta(PosRuleta p) { cargar(); ruleta = p; guardar(); }

	public static boolean rendimiento() { cargar(); return rendimiento; }
	public static void rendimiento(boolean r) { cargar(); rendimiento = r; guardar(); }

	private static void cargar() {
		if (cargado) return;
		cargado = true;
		if (!Files.exists(ARCHIVO)) return;
		Properties p = new Properties();
		try (Reader r = Files.newBufferedReader(ARCHIVO)) {
			p.load(r);
			rendimiento = Boolean.parseBoolean(p.getProperty("rendimiento", "false"));
			ruleta = PosRuleta.valueOf(p.getProperty("ruleta", "CENTRO"));   // si estaba "APAGADA" (version vieja) vuelve al centro
		} catch (IOException | IllegalArgumentException e) { ruleta = PosRuleta.CENTRO; }
	}

	private static void guardar() {
		Properties p = new Properties();
		p.setProperty("ruleta", ruleta.name());
		p.setProperty("rendimiento", Boolean.toString(rendimiento));
		try (Writer w = Files.newBufferedWriter(ARCHIVO)) { p.store(w, "TRUCOFDEATH: ajustes del jugador"); } catch (IOException ignored) {}
	}
}

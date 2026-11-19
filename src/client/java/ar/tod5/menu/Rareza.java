package ar.tod5.menu;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Rarezas de los items del evento (como Fortnite). El plugin las guarda en el item: tod5:rareza (texto) y tod5:unico.
 * Aca: que rareza tiene un item, sus colores y el turquesa "aurora" del exotico (con manchitas verdes y rosadas lentas).
 */
public enum Rareza {
	COMUN("comun", "Común", 0xB0B0B0, 0xD8D8D8, 1),
	POCO_COMUN("poco_comun", "Poco común", 0x55D93D, 0xA6F08F, 2),
	RARO("raro", "Raro", 0x2F95E0, 0x8CC8F5, 3),
	EPICO("epico", "Épico", 0xB45CF0, 0xD9A6FF, 4),
	LEGENDARIO("legendario", "Legendario", 0xF7A64A, 0xFFD29A, 5),
	MITICO("mitico", "Mítico", 0xFFD93B, 0xFFF4B0, 6),
	EXOTICO("exotico", "Exótico", 0x00E1FF, 0x8CF4FF, 7);

	public final String id, nombre;
	public final int color, claro, estrellas;
	Rareza(String id, String nombre, int color, int claro, int estrellas) { this.id = id; this.nombre = nombre; this.color = color; this.claro = claro; this.estrellas = estrellas; }

	public static Rareza porId(String id) { for (Rareza r : values()) if (r.id.equals(id)) return r; return null; }

	/** Lo que dice el item: rareza (o null) y si es unico. */
	public record Datos(Rareza rareza, boolean unico) {}
	private static final Datos NADA = new Datos(null, false);
	private static final Map<CustomData, Datos> CACHE = new IdentityHashMap<>();

	public static Datos de(ItemStack it) {
		if (it == null || it.isEmpty()) return NADA;
		CustomData cd = it.get(DataComponents.CUSTOM_DATA);
		if (cd == null) return NADA;
		synchronized (CACHE) {
			Datos d = CACHE.get(cd);
			if (d != null) return d;
			CompoundTag b = cd.copyTag().getCompoundOrEmpty("PublicBukkitValues");
			d = new Datos(porId(b.getStringOr("tod5:rareza", "")), b.getByteOr("tod5:unico", (byte) 0) != 0);
			if (CACHE.size() > 2048) CACHE.clear();
			CACHE.put(cd, d);
			return d;
		}
	}
	public static Rareza rareza(ItemStack it) { return de(it).rareza(); }

	// ================= colores animados =================
	public static float segundos() { return (Util.getMillis() % 3_600_000L) / 1000f; }
	public static int mezclar(int a, int b, float f) {
		f = Math.max(0, Math.min(1, f));
		int r = (int) (((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f), g = (int) (((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f), bl = (int) ((a & 255) * (1 - f) + (b & 255) * f);
		return (r << 16) | (g << 8) | bl;
	}
	private static final int VERDE = 0x78FFAA, ROSA = 0xFF5FCD;
	/**
	 * El turquesa del exotico en la posicion x (de 0 a ancho): dos manchitas (verde y rosa) que van y vienen despacio, sin orden fijo.
	 * Con "rendimiento" queda el turquesa quieto.
	 */
	public static int aurora(float x, float ancho) {
		if (Ajustes.rendimiento()) return EXOTICO.color;
		float t = segundos(), w = Math.max(ancho, 30);
		float cg = w * (0.5f + 0.55f * (float) Math.sin(t * 0.24 + 1.3) + 0.22f * (float) Math.sin(t * 0.53 + 0.4));
		float cp = w * (0.5f + 0.55f * (float) Math.sin(t * 0.19 + 4.1) + 0.22f * (float) Math.sin(t * 0.41 + 2.0));
		float s = Math.max(9, w * 0.17f);
		float ig = 0.72f * (float) Math.exp(-((x - cg) * (x - cg)) / (2 * s * s));
		float ip = 0.78f * (float) Math.exp(-((x - cp) * (x - cp)) / (2 * s * s));
		return mezclar(mezclar(EXOTICO.color, VERDE, ig), ROSA, ip);
	}
	/** Arcoiris que corre (para la etiqueta Unico). */
	public static int arcoiris(float x) {
		float h = ((x / 60f) - segundos() * (Ajustes.rendimiento() ? 0 : 0.5f)) % 1f;
		if (h < 0) h += 1;
		return net.minecraft.util.Mth.hsvToRgb(h, 0.65f, 1f) & 0xFFFFFF;
	}
	/** Un texto con cada letra de su color: el turquesa aurora (exotico) o el arcoiris. */
	public static MutableComponent pintado(String s, net.minecraft.client.gui.Font f, boolean arco) {
		MutableComponent c = Component.empty();
		float ancho = f.width(s), x = 0;
		for (int i = 0; i < s.length(); ) {
			int cp = s.codePointAt(i); String ch = new String(Character.toChars(cp));
			float w = f.width(ch);
			int col = arco ? arcoiris(x + w / 2) : aurora(x + w / 2, ancho);
			c.append(Component.literal(ch).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(col)).withItalic(false)));
			x += w; i += Character.charCount(cp);
		}
		return c;
	}
	/** El brillo sube y baja un poco (quieto en "rendimiento"). */
	public static float pulso() { return Ajustes.rendimiento() ? 0.85f : 0.62f + 0.38f * (0.5f + 0.5f * (float) Math.sin(segundos() * Math.PI)); }
}

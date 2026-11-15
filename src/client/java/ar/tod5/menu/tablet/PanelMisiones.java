package ar.tod5.menu.tablet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Modo admin > Misiones: a la izquierda todas las misiones, a la derecha el editor.
 *  - nombre, clase (diaria con su dia, u opcional), si es DE EQUIPO o de cada jugador, estado (oculta / activa)
 *  - objetivos: conseguir, fabricar, matar, picar, ir a un lugar, evento. Cada uno con su cantidad.
 *    El item (o el bloque) se toma del que tenes en la mano; el lugar, de donde estas parado.
 *  - recompensa: se carga en un cofre del server (hasta 27 items)
 * Un anuncio puede activar la mision al enviarse (se elige en el editor de anuncios).
 */
final class PanelMisiones extends Panel {
	private static final String[] TIPOS = { "tener", "fabricar", "matar", "picar", "ir", "evento" };
	private static final String[] NOMBRES = { "Conseguir", "Fabricar", "Matar", "Picar", "Ir a", "Evento" };
	private static final ItemStack[] ICONOS = { new ItemStack(Items.CHEST), new ItemStack(Items.CRAFTING_TABLE), new ItemStack(Items.IRON_SWORD),
		new ItemStack(Items.DIAMOND_PICKAXE), new ItemStack(Items.ENDER_EYE), new ItemStack(Items.NAME_TAG) };
	private static final String[] AYUDA = {
		"Tener el item en el inventario. No se consume. Se lo pueden pasar.",
		"Sacarlo él mismo de la mesa, un horno, la herrería o un crafteo del evento. Si se lo pasan, no cuenta. El jugador lo lee como \"Conseguí\".",
		"Matar ese mob. Escribí su nombre en inglés (zombie, creeper...) o custom:etiqueta para un mob con esa etiqueta (tag).",
		"Romper ese bloque. Los que puso un jugador no cuentan.",
		"Llegar a un lugar. Radio 0 = alcanza con entrar a esa dimensión.",
		"Algo que el juego no detecta. Lo completás vos: /tod mision completar <id> <jugador|todos>" };

	/** Un objetivo mientras se edita (el item y el lugar los guarda el server). */
	private static final class Borr { String uid, tipo = "tener", clave = "", texto = ""; int cantidad = 1; double radio = 6; }

	private final Tablet t;
	private String id;
	private String clase = "opcional"; private int dia = 1; private boolean equipo;
	private final List<Borr> objetivos = new ArrayList<>();
	private int sel = -1;
	private final EditorTexto nombre = new EditorTexto(false, 40), clave = new EditorTexto(false, 40), texto = new EditorTexto(false, 60);
	private boolean conClave, conTexto;   // si esas cajas se dibujaron en este cuadro
	private boolean sucio; private long graciaHasta;
	private String confirmar; private long confirmarHasta;
	private String aviso; private long avisoHasta;
	private int scroll, scrollObj; private int lx, ly, lw, lh, ox, oy, ow, oh;

	PanelMisiones(Tablet t) { this.t = t; }

	private static int indice(String s) { for (int i = 0; i < TIPOS.length; i++) if (TIPOS[i].equals(s)) return i; return 0; }
	private TabletDatos.Mision actual(TabletDatos d) { return this.id == null ? null : d.misionesAdmin.stream().filter(m -> m.id().equals(this.id)).findFirst().orElse(null); }
	private static TabletDatos.Objetivo delServer(TabletDatos.Mision m, String uid) { return m == null ? null : m.objetivos().stream().filter(o -> o.uid().equals(uid)).findFirst().orElse(null); }

	private void abrir(TabletDatos.Mision m) {
		guardarSiHaceFalta();
		this.id = m.id(); this.clase = m.clase(); this.dia = Math.max(1, m.dia()); this.equipo = m.equipo();
		this.nombre.ponerPlano(m.titulo());
		this.objetivos.clear();
		for (TabletDatos.Objetivo o : m.objetivos()) { Borr b = new Borr(); b.uid = o.uid(); b.tipo = o.tipo(); b.clave = o.clave(); b.texto = o.texto(); b.cantidad = o.cantidad(); b.radio = o.radio(); this.objetivos.add(b); }
		elegir(this.objetivos.isEmpty() ? -1 : 0);
		sinFoco(); this.sucio = false; this.confirmar = null; this.scrollObj = 0;
	}
	private void nueva(TabletDatos d) {
		guardarSiHaceFalta();
		this.id = "m" + Long.toString(System.currentTimeMillis(), 36);
		this.clase = "opcional"; this.dia = Math.max(1, d.hoy); this.equipo = false;
		this.nombre.ponerPlano("Misión nueva"); this.objetivos.clear(); elegir(-1);
		sinFoco(); this.nombre.foco(true);
		mandarGuardar();
	}
	private void elegir(int i) {
		this.sel = i;
		Borr b = i >= 0 && i < this.objetivos.size() ? this.objetivos.get(i) : null;
		this.clave.ponerPlano(b == null ? "" : b.clave); this.texto.ponerPlano(b == null ? "" : b.texto);
		this.clave.foco(false); this.texto.foco(false);
	}
	private Borr elegido() { return this.sel >= 0 && this.sel < this.objetivos.size() ? this.objetivos.get(this.sel) : null; }

	private void mandarGuardar() {
		if (this.id == null) return;
		JsonObject m = new JsonObject();
		m.addProperty("id", this.id); m.addProperty("nombre", this.nombre.plano().trim()); m.addProperty("clase", this.clase);
		m.addProperty("dia", this.dia); m.addProperty("equipo", this.equipo);
		JsonArray obs = new JsonArray();
		for (Borr b : this.objetivos) {
			JsonObject j = new JsonObject();
			j.addProperty("uid", b.uid); j.addProperty("tipo", b.tipo); j.addProperty("clave", b.clave.trim()); j.addProperty("texto", b.texto.trim());
			j.addProperty("cantidad", b.cantidad); j.addProperty("radio", b.radio);
			obs.add(j);
		}
		m.add("objetivos", obs);
		JsonObject o = new JsonObject(); o.addProperty("a", "mision_guardar"); o.add("mision", m);
		ar.tod5.menu.Conexion.accion(o.toString());
		this.sucio = false; this.graciaHasta = Util.getMillis() + 4000;
	}
	private void accion(String a, String extraK, String extraV) {
		JsonObject o = new JsonObject(); o.addProperty("a", a);
		JsonArray ids = new JsonArray(); ids.add(this.id); o.add("ids", ids);
		if (extraK != null) o.addProperty(extraK, extraV);
		ar.tod5.menu.Conexion.accion(o.toString());
	}
	@Override void guardarSiHaceFalta() { if (this.sucio && this.id != null) mandarGuardar(); }
	private void avisar(String s) { this.aviso = s; this.avisoHasta = Util.getMillis() + 2500; }

	@Override
	void dibujar(GuiGraphics g, TabletDatos d, int x, int y, int cw, int ch, int mx, int my) {
		Font f = Minecraft.getInstance().font;
		this.conClave = false; this.conTexto = false;
		if (this.confirmar != null && Util.getMillis() > this.confirmarHasta) this.confirmar = null;
		if (this.id != null && !this.sucio && actual(d) == null && Util.getMillis() > this.graciaHasta) this.id = null;
		t.titulo(g, "Misiones", x, y);
		if (!d.pluginAlMenos("0.23")) {
			g.drawString(f, "El plugin del server no tiene misiones.", x, y + 22, Tablet.AMARILLO_ADMIN, false);
			g.drawString(f, "Actualizalo: tod5-plugin-servidor 0.23 o más nuevo.", x, y + 34, Tablet.TEXTO, false);
			return;
		}

		// ===== lista: diarias (por dia) y opcionales =====
		this.lw = 102; this.lx = x; this.ly = y + 16; this.lh = ch - 16;
		t.boton(g, "+Nueva", x + lw - f.width("+Nueva") - 10, y - 1, false, mx, my, () -> nueva(d));
		List<TabletDatos.Mision> orden = new ArrayList<>(d.misionesAdmin);
		orden.sort(Comparator.comparing((TabletDatos.Mision m) -> !m.diaria()).thenComparingInt(m -> m.diaria() ? m.dia() : 0));
		g.enableScissor(lx, ly, lx + lw, ly + lh);
		int yy = ly - this.scroll; String grupoAnt = null;
		for (TabletDatos.Mision m : orden) {
			String grupo = m.diaria() ? "Diarias" : "Opcionales";
			if (!grupo.equals(grupoAnt)) { g.drawString(f, grupo, lx + 1, yy + 2, Tablet.GRIS_TEXTO, false); yy += 12; grupoAnt = grupo; }
			boolean selec = m.id().equals(this.id), activa = m.estado().equals("activa");
			int alto = 21;
			g.fill(lx, yy, lx + lw, yy + alto, selec ? t.acento() : Tablet.LINEA);
			g.fill(lx + 1, yy + 1, lx + lw - 1, yy + alto - 1, selec ? 0xFF2A2610 : 0xFF141B18);
			g.fill(lx + 3, yy + 3, lx + 5, yy + alto - 3, m.diaria() ? Tablet.VERDE : Tablet.CELESTE);
			g.drawString(f, f.plainSubstrByWidth(m.titulo(), lw - 12), lx + 8, yy + 3, Tablet.TEXTO, false);
			String sub = (m.diaria() ? "Día " + m.dia() + " · " : "") + (activa ? "Activa" : "Oculta") + (m.equipo() ? " · Eq." : "");
			g.drawString(f, f.plainSubstrByWidth(sub, lw - 12), lx + 8, yy + 12, activa ? 0xFF55FF55 : 0xFFE06060, false);
			if (yy + alto > ly && yy < ly + lh) t.zona(lx, Math.max(yy, ly), lw, Math.min(yy + alto, ly + lh) - Math.max(yy, ly), () -> abrir(m));
			yy += alto + 2;
		}
		g.disableScissor();
		this.scroll = Math.max(0, Math.min(this.scroll, yy + this.scroll - ly - lh));
		if (d.misionesAdmin.isEmpty()) { g.drawString(f, "Todavía no hay", lx + 1, ly + 4, Tablet.GRIS_TEXTO, false); g.drawString(f, "misiones.", lx + 1, ly + 14, Tablet.GRIS_TEXTO, false); }
		g.fill(x + lw + 6, y - 2, x + lw + 7, y + ch, Tablet.LINEA);

		// ===== editor =====
		int ex = x + lw + 14, ew = cw - lw - 14;
		TabletDatos.Mision srv = actual(d);
		if (this.id == null) {
			g.drawString(f, "Elegí una misión de la lista", ex, y + 20, Tablet.GRIS_TEXTO, false);
			g.drawString(f, "o creá una con \"+Nueva\".", ex, y + 31, Tablet.GRIS_TEXTO, false);
			return;
		}
		int ey = y;
		g.drawString(f, "Nombre", ex, ey + 3, Tablet.GRIS_TEXTO, false);
		caja(g, this.nombre, ex + 44, ey, ew - 44, 14);
		ey += 17;
		// clase, dia y de quien es el progreso
		int bx = ex;
		bx += t.boton(g, "Diaria", bx, ey, this.clase.equals("diaria"), mx, my, () -> { this.clase = "diaria"; this.sucio = true; }) + 2;
		bx += t.boton(g, "Opcional", bx, ey, this.clase.equals("opcional"), mx, my, () -> { this.clase = "opcional"; this.sucio = true; }) + 6;
		if (this.clase.equals("diaria")) {
			g.drawString(f, "Día", bx, ey + 3, Tablet.GRIS_TEXTO, false); bx += f.width("Día") + 3;
			bx += t.boton(g, "<", bx, ey, false, mx, my, () -> { this.dia = Math.max(1, this.dia - 1); this.sucio = true; }) + 1;
			String ds = String.valueOf(this.dia); g.drawString(f, ds, bx + (16 - f.width(ds)) / 2, ey + 3, Tablet.TEXTO, false); bx += 16;
			t.boton(g, ">", bx, ey, false, mx, my, () -> { this.dia = Math.min(999, this.dia + 1); this.sucio = true; });
		}
		String qt = this.equipo ? "De equipo" : "Por jugador";
		int qx = ex + ew - f.width(qt) - 10;
		t.boton(g, qt, qx, ey, this.equipo, mx, my, () -> { this.equipo = !this.equipo; this.sucio = true; });
		if (mx >= qx && my >= ey && mx < ex + ew && my < ey + 13) g.setTooltipForNextFrame(Component.literal(this.equipo
			? "De equipo: el progreso es uno solo para todo el equipo (los teams del juego). En la tablet dice \"DE EQUIPO\". Clic: cambiar"
			: "Por jugador: cada uno hace la suya. Clic: cambiar a de equipo"), mx, my);
		ey += 17;
		// estado
		g.drawString(f, "Estado", ex, ey + 3, Tablet.GRIS_TEXTO, false);
		int sx = ex + 44; boolean activa = srv != null && srv.estado().equals("activa");
		sx += t.boton(g, "Oculta", sx, ey, !activa, mx, my, () -> { guardarSiHaceFalta(); accion("mision_estado", "estado", "oculta"); avisar("Oculta: nadie la ve."); }) + 3;
		sx += t.boton(g, "Activa", sx, ey, activa, mx, my, () -> { guardarSiHaceFalta(); accion("mision_estado", "estado", "activa"); avisar("Activa: ya la ven todos."); }) + 6;
		g.drawString(f, f.plainSubstrByWidth("o activala con un anuncio", ex + ew - sx), sx, ey + 3, Tablet.GRIS_TEXTO, false);
		ey += 16;
		g.fill(ex, ey, ex + ew, ey + 1, Tablet.LINEA);
		ey += 4;

		// ===== objetivos =====
		g.drawString(f, "Objetivos", ex, ey + 3, Tablet.GRIS_TEXTO, false);
		t.boton(g, "+Objetivo", ex + ew - f.width("+Objetivo") - 10, ey, false, mx, my, () -> {
			Borr b = new Borr(); b.uid = "o" + Long.toString(System.nanoTime(), 36);
			this.objetivos.add(b); elegir(this.objetivos.size() - 1); this.sucio = true;
			this.scrollObj = Integer.MAX_VALUE;
		});
		ey += 15;
		int by = y + ch - 14;
		int filaAlto = 18, abajoFijo = 17 + 17 + 4 + 22 + 4;   // detalle (2 renglones), recompensa, botones
		this.ox = ex; this.oy = ey; this.ow = ew; this.oh = Math.max(filaAlto, Math.min(this.objetivos.size(), 4) * filaAlto);
		this.oh = Math.min(this.oh, Math.max(filaAlto, by - abajoFijo - ey));
		this.scrollObj = Math.max(0, Math.min(this.scrollObj, this.objetivos.size() * filaAlto - this.oh));
		g.enableScissor(ox, oy, ox + ow, oy + oh);
		int fy = oy - this.scrollObj;
		for (int i = 0; i < this.objetivos.size(); i++, fy += filaAlto) {
			if (fy + filaAlto <= oy || fy >= oy + oh) continue;
			final int idx = i; Borr b = this.objetivos.get(i); int ti = indice(b.tipo);
			TabletDatos.Objetivo so = delServer(srv, b.uid);
			ItemStack item = so == null ? ItemStack.EMPTY : so.item();
			boolean selec = i == this.sel;
			g.fill(ox, fy, ox + ow, fy + filaAlto - 1, selec ? t.acento() : Tablet.LINEA);
			g.fill(ox + 1, fy + 1, ox + ow - 1, fy + filaAlto - 2, selec ? 0xFF2A2610 : 0xFF141B18);
			boolean conItem = !item.isEmpty() && (ti == 0 || ti == 1 || ti == 3);
			g.renderItem(conItem ? item : ICONOS[ti], ox + 1, fy);
			TabletDatos.Objetivo vista = new TabletDatos.Objetivo(b.uid, b.tipo, b.clave.trim().isEmpty() ? "?" : b.clave.trim(), b.texto, b.cantidad, 0, b.radio, item, "");
			g.drawString(f, f.substrByWidth(vista.frase(), ow - 36).getString(), ox + 20, fy + 5, Tablet.TEXTO, false);
			boolean visible = fy >= oy && fy + filaAlto <= oy + oh + 1;
			if (visible) {
				int xx = ox + ow - 13;
				boolean sobreX = mx >= xx && my >= fy + 2 && mx < xx + 11 && my < fy + 15;
				g.drawString(f, "✖", xx + 2, fy + 5, sobreX ? 0xFFFF6060 : Tablet.GRIS_TEXTO, false);
				t.zona(ox, fy, ow - 14, filaAlto - 1, () -> { if (this.sel != idx) elegir(idx); });
				t.zona(xx, fy + 2, 11, 13, () -> { this.objetivos.remove(idx); elegir(Math.min(idx, this.objetivos.size() - 1)); this.sucio = true; });
			}
		}
		g.disableScissor();
		if (this.objetivos.isEmpty()) g.drawString(f, "Sin objetivos todavía: agregá uno con \"+Objetivo\".", ox + 2, oy + 5, 0xFFE0A040, false);
		ey = oy + oh + 3;

		// ===== el objetivo elegido =====
		Borr b = elegido();
		if (b != null) {
			int ti = indice(b.tipo);
			TabletDatos.Objetivo so = delServer(srv, b.uid);
			// renglon 1: el tipo (clic: cambia) y la cantidad
			int dx = ex;
			int tw = t.boton(g, NOMBRES[ti] + " ▸", dx, ey, true, mx, my, () -> { b.tipo = TIPOS[(indice(b.tipo) + 1) % TIPOS.length]; if (b.tipo.equals("ir") || b.tipo.equals("evento")) b.cantidad = 1; this.sucio = true; });
			if (mx >= dx && my >= ey && mx < dx + tw && my < ey + 13) g.setTooltipForNextFrame(Component.literal(AYUDA[ti] + " (Clic: cambiar el tipo)"), mx, my);
			dx += tw + 5;
			if (ti <= 3) {
				dx += t.boton(g, "-10", dx, ey, false, mx, my, () -> { b.cantidad = Math.max(1, b.cantidad - 10); this.sucio = true; }) + 1;
				dx += t.boton(g, "-", dx, ey, false, mx, my, () -> { b.cantidad = Math.max(1, b.cantidad - 1); this.sucio = true; }) + 1;
				String cs = String.valueOf(b.cantidad); int cwid = Math.max(22, f.width(cs) + 6);
				g.drawString(f, cs, dx + (cwid - f.width(cs)) / 2, ey + 3, Tablet.TEXTO, false); dx += cwid + 1;
				dx += t.boton(g, "+", dx, ey, false, mx, my, () -> { b.cantidad = Math.min(100000, b.cantidad + 1); this.sucio = true; }) + 1;
				dx += t.boton(g, "+10", dx, ey, false, mx, my, () -> { b.cantidad = Math.min(100000, b.cantidad + 10); this.sucio = true; }) + 5;
			}
			if (ti == 0 || ti == 1 || ti == 3) {
				boolean falta = so == null || so.item().isEmpty();
				String it = ti == 3 ? "Bloque de mi mano" : "Ítem de mi mano";
				if (dx + f.width(it) + 10 > ex + ew) it = "De mi mano";
				int iw = t.boton(g, it, dx, ey, falta, mx, my, () -> { mandarGuardar(); accion("mision_obj_mano", "uid", b.uid); avisar("Tomado de tu mano."); });
				if (mx >= dx && my >= ey && mx < dx + iw && my < ey + 13) g.setTooltipForNextFrame(Component.literal("Tené el item en la mano y hacé clic acá. Un item común cuenta por su tipo; uno especial (con nombre, encantado...) tiene que ser igual."), mx, my);
			} else if (ti == 2) {
				g.drawString(f, "Mob", dx, ey + 3, Tablet.GRIS_TEXTO, false); dx += f.width("Mob") + 4;
				this.conClave = true;
				caja(g, this.clave, dx, ey - 1, ex + ew - dx, 14);
				if (!this.clave.plano().equals(b.clave)) { b.clave = this.clave.plano(); this.sucio = true; }
			} else if (ti == 4) {
				int lw2 = t.boton(g, "Usar donde estoy", dx, ey, so == null || so.lugar().isEmpty(), mx, my, () -> { mandarGuardar(); accion("mision_obj_lugar", "uid", b.uid); avisar("Lugar guardado."); });
				dx += lw2 + 5;
				g.drawString(f, "Radio", dx, ey + 3, Tablet.GRIS_TEXTO, false); dx += f.width("Radio") + 3;
				dx += t.boton(g, "-", dx, ey, false, mx, my, () -> { b.radio = Math.max(0, b.radio - (b.radio > 10 ? 5 : 1)); this.sucio = true; }) + 1;
				String rs = b.radio <= 0 ? "∞" : String.valueOf((int) b.radio); g.drawString(f, rs, dx + (18 - f.width(rs)) / 2, ey + 3, Tablet.TEXTO, false); dx += 19;
				t.boton(g, "+", dx, ey, false, mx, my, () -> { b.radio = Math.min(500, b.radio + (b.radio >= 10 ? 5 : 1)); this.sucio = true; });
			}
			ey += 16;
			// renglon 2: como lo lee el jugador
			String et = ti == 4 ? "Lugar" : ti == 5 ? "Texto" : "Nombre";
			g.drawString(f, et, ex, ey + 3, Tablet.GRIS_TEXTO, false);
			int tx0 = ex + 44, tw0 = ew - 44;
			if (ti == 4) {   // al lado, donde quedo guardado
				String donde = so == null || so.lugar().isEmpty() ? "sin lugar" : so.lugar();
				int dw = Math.min(110, f.width(donde) + 4);
				g.drawString(f, f.plainSubstrByWidth(donde, dw), ex + ew - dw, ey + 3, so == null || so.lugar().isEmpty() ? 0xFFE0A040 : Tablet.GRIS_TEXTO, false);
				tw0 -= dw + 4;
			}
			this.conTexto = true;
			caja(g, this.texto, tx0, ey, tw0, 14);
			if (!this.texto.plano().equals(b.texto)) { b.texto = this.texto.plano(); this.sucio = true; }
			if (this.texto.plano().isEmpty() && !this.texto.foco())
				g.drawString(f, f.plainSubstrByWidth(ti == 4 ? "el templo del Aether" : ti == 5 ? "Ganá el torneo" : "(opcional) otro nombre", tw0 - 8), tx0 + 4, ey + 3, 0xFF4A5650, false);
			ey += 17;
		} else ey += 33;

		// ===== recompensa =====
		g.fill(ex, ey, ex + ew, ey + 1, Tablet.LINEA);
		ey += 4;
		g.drawString(f, "Premio", ex, ey + 5, Tablet.GRIS_TEXTO, false);
		String ct = "Cargar";
		int cx2 = ex + ew - f.width(ct) - 10;
		int px = ex + 44, k = 0;
		if (srv != null) for (ItemStack st : srv.recompensas()) {
			if (px + 17 > cx2 - 14) { g.drawString(f, "+" + (srv.recompensas().size() - k), px + 1, ey + 5, Tablet.GRIS_TEXTO, false); break; }
			g.renderItem(st, px, ey); if (st.getCount() > 1) g.renderItemDecorations(f, st, px, ey, String.valueOf(st.getCount()));
			if (mx >= px && my >= ey && mx < px + 16 && my < ey + 16) g.setTooltipForNextFrame(Component.literal(st.getCount() + " × ").append(st.getHoverName()), mx, my);
			px += 17; k++;
		}
		if (srv != null && srv.recompensas().isEmpty()) g.drawString(f, "sin recompensa", px, ey + 5, 0xFFE0A040, false);
		t.boton(g, ct, cx2, ey + 2, srv != null && srv.recompensas().isEmpty(), mx, my, () -> { mandarGuardar(); accion("mision_recompensa", null, null); t.onClose(); });

		// ===== botones =====
		int bbx = ex;
		bbx += t.boton(g, "Guardar", bbx, by, this.sucio, mx, my, () -> { mandarGuardar(); avisar("Guardado."); }) + 3;
		bbx += t.boton(g, "Duplicar", bbx, by, false, mx, my, () -> { guardarSiHaceFalta(); accion("mision_duplicar", null, null); avisar("Copia creada."); }) + 3;
		boolean confRei = "reiniciar".equals(this.confirmar);
		bbx += t.boton(g, confRei ? "¿Seguro?" : "Reiniciar", bbx, by, confRei, mx, my, () -> {
			if (confRei) { accion("mision_reiniciar", null, null); this.confirmar = null; avisar("Progreso borrado."); }
			else { this.confirmar = "reiniciar"; this.confirmarHasta = Util.getMillis() + 3000; }
		}) + 3;
		if (mx >= bbx - 60 && my >= by && mx < bbx && my < by + 13 && !confRei) g.setTooltipForNextFrame(Component.literal("Borra el progreso de todos en esta misión"), mx, my);
		boolean confBor = "borrar".equals(this.confirmar);
		t.boton(g, confBor ? "¿Borrar?" : "Borrar", bbx, by, confBor, mx, my, () -> {
			if (confBor) { accion("mision_borrar", null, null); this.id = null; this.sucio = false; this.confirmar = null; }
			else { this.confirmar = "borrar"; this.confirmarHasta = Util.getMillis() + 3000; }
		});
		if (this.aviso != null && Util.getMillis() < this.avisoHasta) g.drawString(f, this.aviso, ex + ew - f.width(this.aviso), by + 3, t.acento(), false);
	}

	private void caja(GuiGraphics g, EditorTexto e, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, e.foco() ? t.acento() : 0xFF3A4840);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF070B09);
		String antes = e.plano();
		e.dibujar(g, x + 4, y + 3, w - 8, h - 5, Tablet.TEXTO);
		if (e == this.nombre && !antes.equals(e.plano())) this.sucio = true;
	}

	// ---------- teclado y mouse ----------
	private EditorTexto[] cajas() {
		List<EditorTexto> l = new ArrayList<>(); l.add(this.nombre);
		if (this.conClave) l.add(this.clave);
		if (this.conTexto) l.add(this.texto);
		return l.toArray(new EditorTexto[0]);
	}
	@Override boolean escribiendo() { for (EditorTexto e : cajas()) if (e.foco()) return true; return false; }
	@Override boolean tecla(KeyEvent ev) {
		if (ev.isEscape() && escribiendo()) { sinFoco(); return true; }
		if (ev.isCycleFocus() && escribiendo()) {
			EditorTexto[] cs = cajas();
			for (int i = 0; i < cs.length; i++) if (cs[i].foco()) { cs[i].foco(false); cs[(i + 1) % cs.length].foco(true); return true; }
		}
		boolean r = false;
		for (EditorTexto e : cajas()) r |= e.tecla(ev);
		if (r) this.sucio = true;
		return r || escribiendo();
	}
	@Override boolean caracter(CharacterEvent ev) {
		boolean r = false;
		for (EditorTexto e : cajas()) r |= e.caracter(ev);
		if (r) this.sucio = true;
		return r;
	}
	@Override boolean clic(double mx, double my, boolean shift) {
		if (this.id == null) return false;
		for (EditorTexto e : cajas()) if (e.adentro(mx, my)) { for (EditorTexto o : cajas()) if (o != e) o.foco(false); e.clic(mx, my, shift); return true; }
		return false;
	}
	@Override void sinFoco() { this.nombre.foco(false); this.clave.foco(false); this.texto.foco(false); }
	@Override void arrastrar(double mx, double my) { for (EditorTexto e : cajas()) e.arrastrar(mx, my); }
	@Override void soltar() { for (EditorTexto e : cajas()) e.soltar(); }
	@Override boolean ruedita(double mx, double my, double dy) {
		if (mx >= lx && my >= ly && mx < lx + lw && my < ly + lh) { this.scroll = Math.max(0, this.scroll - (int) (dy * 12)); return true; }
		if (mx >= ox && my >= oy && mx < ox + ow && my < oy + oh) { this.scrollObj = Math.max(0, this.scrollObj - (int) (dy * 18)); return true; }
		return false;
	}
}

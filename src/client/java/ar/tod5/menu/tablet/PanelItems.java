package ar.tod5.menu.tablet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Modo admin > Items: los items propios del evento (para usarlos en recetas y recompensas).
 * Un item nuevo parte de uno de Minecraft (ej. diamond_sword) y le pones nombre con color, descripcion y si brilla.
 * Tambien podes guardar el que tenes en la mano (si lo armaste con un yunque o comandos).
 * Aparecen en la pestaña Trucofdeath del creativo (para los editores).
 */
final class PanelItems extends Panel {
	private static final int[] COLORES = { 0xFFFFFF, 0xAAAAAA, 0x55FF55, 0x55FFFF, 0x4678FF, 0xB45AFF, 0xFF78C8, 0xFF3C3C, 0xFF9600, 0xFFE63C };

	private final Tablet t;
	private String id;
	private int color = 0xFFFFFF; private boolean brillo;
	private final EditorTexto base = new EditorTexto(false, 40), nombre = new EditorTexto(false, 40), desc = new EditorTexto(true, 200);
	private boolean sucio; private long graciaHasta;
	private String confirmar; private long confirmarHasta;
	private String aviso; private long avisoHasta;
	private int lx, ly, lw, lh, scroll;

	PanelItems(Tablet t) { this.t = t; }

	private static ItemStack deBase(String s) {
		Identifier k = Identifier.tryParse(s.trim().toLowerCase().replace(' ', '_'));
		if (k == null) return ItemStack.EMPTY;
		return BuiltInRegistries.ITEM.getOptional(k).map(ItemStack::new).orElse(ItemStack.EMPTY);
	}
	private TabletDatos.ItemPropio actual(TabletDatos d) { return this.id == null ? null : d.items.stream().filter(i -> i.id().equals(this.id)).findFirst().orElse(null); }

	private void abrir(TabletDatos.ItemPropio ip) {
		guardarSiHaceFalta();
		this.id = ip.id();
		ItemStack it = ip.item();
		this.base.ponerPlano(BuiltInRegistries.ITEM.getKey(it.getItem()).getPath());
		Component n = it.getHoverName();
		this.nombre.ponerPlano(n.getString());
		TextColor tc = n.getStyle().getColor();
		this.color = tc == null ? 0xFFFFFF : tc.getValue();
		ItemLore lore = it.get(DataComponents.LORE);
		StringBuilder sb = new StringBuilder();
		if (lore != null) for (Component l : lore.lines()) { if (sb.length() > 0) sb.append('\n'); sb.append(l.getString()); }
		this.desc.ponerPlano(sb.toString());
		this.brillo = it.hasFoil();
		sinFoco(); this.sucio = false; this.confirmar = null;
	}
	private void nuevo() {
		guardarSiHaceFalta();
		this.id = "i" + Long.toString(System.currentTimeMillis(), 36);
		this.base.ponerPlano("paper"); this.nombre.ponerPlano("Item nuevo"); this.desc.ponerPlano(""); this.color = 0xFFFFFF; this.brillo = false;
		sinFoco(); this.nombre.foco(true);
		mandarGuardar();
	}
	private void mandarGuardar() {
		if (this.id == null) return;
		JsonObject i = new JsonObject();
		i.addProperty("id", this.id); i.addProperty("base", this.base.plano().trim()); i.addProperty("nombre", this.nombre.plano().trim());
		i.addProperty("color", this.color); i.addProperty("desc", this.desc.plano()); i.addProperty("brillo", this.brillo);
		JsonObject o = new JsonObject(); o.addProperty("a", "item_guardar"); o.add("item", i);
		ar.tod5.menu.Conexion.accion(o.toString());
		this.sucio = false; this.graciaHasta = Util.getMillis() + 4000;
	}
	@Override void guardarSiHaceFalta() { if (this.sucio && this.id != null) mandarGuardar(); }
	private void avisar(String s) { this.aviso = s; this.avisoHasta = Util.getMillis() + 2500; }

	@Override
	void dibujar(GuiGraphics g, TabletDatos d, int x, int y, int cw, int ch, int mx, int my) {
		Font f = Minecraft.getInstance().font;
		if (this.confirmar != null && Util.getMillis() > this.confirmarHasta) this.confirmar = null;
		if (this.id != null && !this.sucio && actual(d) == null && Util.getMillis() > this.graciaHasta) this.id = null;
		if (!d.pluginAlMenos("0.21")) {
			t.titulo(g, "Ítems", x, y);
			g.drawString(f, "El plugin del server es viejo: actualizalo (0.21 o más).", x, y + 22, Tablet.AMARILLO_ADMIN, false);
			return;
		}
		// ===== lista =====
		this.lw = 102; this.lx = x; this.ly = y + 16; this.lh = ch - 16;
		t.titulo(g, "Ítems", x, y);
		t.boton(g, "+Nuevo", x + lw - f.width("+Nuevo") - 10, y - 1, false, mx, my, this::nuevo);
		g.enableScissor(lx, ly, lx + lw, ly + lh);
		int yy = ly - this.scroll;
		for (TabletDatos.ItemPropio ip : d.items) {
			boolean sel = ip.id().equals(this.id);
			g.fill(lx, yy, lx + lw, yy + 20, sel ? t.acento() : Tablet.LINEA);
			g.fill(lx + 1, yy + 1, lx + lw - 1, yy + 19, sel ? 0xFF2A2610 : 0xFF141B18);
			g.renderItem(ip.item(), lx + 2, yy + 2);
			g.drawString(f, f.plainSubstrByWidth(ip.item().getHoverName().getString(), lw - 26), lx + 21, yy + 6, Tablet.TEXTO, false);
			if (yy + 20 > ly && yy < ly + lh) t.zona(lx, Math.max(yy, ly), lw, Math.min(yy + 20, ly + lh) - Math.max(yy, ly), () -> abrir(ip));
			yy += 22;
		}
		g.disableScissor();
		this.scroll = Math.max(0, Math.min(this.scroll, yy + this.scroll - ly - lh));
		if (d.items.isEmpty()) { g.drawString(f, "Todavía no hay", lx + 1, ly + 4, Tablet.GRIS_TEXTO, false); g.drawString(f, "ítems propios.", lx + 1, ly + 14, Tablet.GRIS_TEXTO, false); }
		g.fill(x + lw + 6, y - 2, x + lw + 7, y + ch, Tablet.LINEA);

		int ex = x + lw + 14, ew = cw - lw - 14, ey = y;
		int by = y + ch - 14;
		// guardar el de la mano (sirve siempre, aunque no haya ninguno elegido)
		String ml = "Guardar el de mi mano";
		t.boton(g, ml, ex + ew - f.width(ml) - 10, by, false, mx, my, () -> { JsonObject o = new JsonObject(); o.addProperty("a", "item_mano"); ar.tod5.menu.Conexion.accion(o.toString()); avisar("Listo."); });
		if (this.id == null) {
			g.drawString(f, "Elegí un ítem de la lista o creá uno", ex, y + 20, Tablet.GRIS_TEXTO, false);
			g.drawString(f, "con \"+Nuevo\". Aparecen en la pestaña", ex, y + 31, Tablet.GRIS_TEXTO, false);
			g.drawString(f, "Trucofdeath del creativo.", ex, y + 42, Tablet.GRIS_TEXTO, false);
			return;
		}
		// de que item parte (con el dibujo al lado: si no existe, sale en rojo)
		g.drawString(f, "Parte de", ex, ey + 3, Tablet.GRIS_TEXTO, false);
		ItemStack muestra = deBase(this.base.plano());
		texto(g, this.base, ex + 48, ey, ew - 48 - 22, 14, muestra.isEmpty() ? 0xFFFF6060 : Tablet.TEXTO);
		VistaReceta.casilla(g, ex + ew - 18, ey - 2, 18, 18);
		if (!muestra.isEmpty()) g.renderItem(muestra, ex + ew - 17, ey - 1);
		ey += 19;
		g.drawString(f, "Nombre", ex, ey + 3, Tablet.GRIS_TEXTO, false);
		texto(g, this.nombre, ex + 48, ey, ew - 48, 14, 0xFF000000 | this.color);
		ey += 18;
		g.drawString(f, "Color", ex, ey + 2, Tablet.GRIS_TEXTO, false);
		int cx = ex + 48;
		for (int c : COLORES) {
			boolean sel = c == this.color;
			g.fill(cx, ey, cx + 12, ey + 12, sel ? 0xFFFFFFFF : 0xFF000000);
			g.fill(cx + 1, ey + 1, cx + 11, ey + 11, 0xFF000000 | c);
			final int cc = c;
			t.zona(cx, ey, 12, 12, () -> { this.color = cc; this.sucio = true; });
			cx += 14;
		}
		t.boton(g, "Brillo: " + (this.brillo ? "sí" : "no"), cx + 6, ey - 1, this.brillo, mx, my, () -> { this.brillo = !this.brillo; this.sucio = true; });
		ey += 18;
		g.drawString(f, "Descripción", ex, ey, Tablet.GRIS_TEXTO, false);
		texto(g, this.desc, ex, ey + 10, ew, 32, 0xFFAAAAAA);
		ey += 48;
		// como queda
		g.drawString(f, "Así queda", ex, ey + 4, Tablet.GRIS_TEXTO, false);
		TabletDatos.ItemPropio ip = actual(d);
		if (ip != null) {
			VistaReceta.casilla(g, ex + 60, ey, 18, 18);
			g.renderItem(ip.item(), ex + 61, ey + 1);
			if (mx >= ex + 60 && my >= ey && mx < ex + 78 && my < ey + 18) g.setTooltipForNextFrame(f, ip.item(), mx, my);
			if (this.sucio) g.drawString(f, "(guardá para ver los cambios)", ex + 84, ey + 5, Tablet.GRIS_TEXTO, false);
		}
		// botones
		int bbx = ex;
		bbx += t.boton(g, "Guardar", bbx, by, this.sucio, mx, my, () -> { mandarGuardar(); avisar("Guardado."); }) + 3;
		boolean confBor = "borrar".equals(this.confirmar);
		t.boton(g, confBor ? "¿Borrar?" : "Borrar", bbx, by, confBor, mx, my, () -> {
			if (confBor) {
				JsonObject o = new JsonObject(); o.addProperty("a", "item_borrar"); JsonArray ids = new JsonArray(); ids.add(this.id); o.add("ids", ids);
				ar.tod5.menu.Conexion.accion(o.toString()); this.id = null; this.sucio = false; this.confirmar = null;
			} else { this.confirmar = "borrar"; this.confirmarHasta = Util.getMillis() + 3000; }
		});
		if (this.aviso != null && Util.getMillis() < this.avisoHasta) g.drawString(f, this.aviso, ex, by - 11, t.acento(), false);
	}

	private void texto(GuiGraphics g, EditorTexto e, int x, int y, int w, int h, int color) {
		g.fill(x, y, x + w, y + h, e.foco() ? t.acento() : 0xFF3A4840);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF070B09);
		String antes = e.plano();
		e.dibujar(g, x + 4, y + 3, w - 8, h - 5, color);
		if (!antes.equals(e.plano())) this.sucio = true;
	}

	private EditorTexto[] cajas() { return new EditorTexto[] { this.base, this.nombre, this.desc }; }
	@Override boolean escribiendo() { for (EditorTexto e : cajas()) if (e.foco()) return true; return false; }
	@Override boolean tecla(KeyEvent ev) {
		if (ev.isEscape() && escribiendo()) { sinFoco(); return true; }
		if (ev.isCycleFocus() && escribiendo()) { EditorTexto[] cs = cajas(); for (int i = 0; i < cs.length; i++) if (cs[i].foco()) { cs[i].foco(false); cs[(i + 1) % cs.length].foco(true); return true; } }
		boolean r = false; for (EditorTexto e : cajas()) r |= e.tecla(ev);
		if (r) this.sucio = true;
		return r || escribiendo();
	}
	@Override boolean caracter(CharacterEvent ev) { boolean r = false; for (EditorTexto e : cajas()) r |= e.caracter(ev); if (r) this.sucio = true; return r; }
	@Override boolean clic(double mx, double my, boolean shift) {
		if (this.id == null) return false;
		for (EditorTexto e : cajas()) if (e.adentro(mx, my)) { for (EditorTexto o : cajas()) if (o != e) o.foco(false); e.clic(mx, my, shift); return true; }
		return false;
	}
	@Override void sinFoco() { for (EditorTexto e : cajas()) e.foco(false); }
	@Override void arrastrar(double mx, double my) { for (EditorTexto e : cajas()) e.arrastrar(mx, my); }
	@Override void soltar() { for (EditorTexto e : cajas()) e.soltar(); }
	@Override boolean ruedita(double mx, double my, double dy) {
		if (mx >= lx && my >= ly && mx < lx + lw && my < ly + lh) { this.scroll = Math.max(0, this.scroll - (int) (dy * 12)); return true; }
		return false;
	}
}

package ar.tod5.menu;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

/**
 * La descripcion (al pasar el mouse) de un item con rareza:
 *   - el nombre del color de la rareza (el exotico, turquesa con manchitas)
 *   - abajo: "Épico ★★★★" con estrellas chicas, y la etiqueta Único (arcoiris, con recuadro) si la tiene
 *   - el borde del cuadro del color de la rareza (lo dibuja tod5$fondo, lo marca el "estilo" tod5:rareza_<id>)
 */
public final class RarezaTooltip {
	private RarezaTooltip() {}
	/** El item que esta por mostrar su descripcion (lo anotan los mixins justo antes). */
	public static ItemStack hover = ItemStack.EMPTY;

	/** Arma la lista de renglones. null = no tiene rareza (va lo de siempre). */
	public static List<ClientTooltipComponent> armar(Font f, ItemStack it, List<Component> textos, Optional<TooltipComponent> imagen) {
		Rareza.Datos d = Rareza.de(it);
		if (d.rareza() == null && !d.unico()) return null;
		List<ClientTooltipComponent> l = new ArrayList<>();
		for (int i = 0; i < textos.size(); i++) {
			Component c = textos.get(i);
			if (i == 0 && d.rareza() != null) {
				String n = c.getString();
				c = d.rareza() == Rareza.EXOTICO ? Rareza.pintado(n, f, false)
					: Component.literal(n).withStyle(Style.EMPTY.withColor(TextColor.fromRgb(d.rareza().color)).withItalic(false));
			}
			l.add(ClientTooltipComponent.create(c.getVisualOrderText()));
			if (i == 0) l.add(new Linea(d.rareza(), d.unico()));
		}
		if (textos.isEmpty()) l.add(new Linea(d.rareza(), d.unico()));
		imagen.ifPresent(im -> l.add(l.isEmpty() ? 0 : Math.min(2, l.size()), ClientTooltipComponent.create(im)));
		return l;
	}
	public static Identifier estilo(ItemStack it) {
		Rareza r = Rareza.rareza(it);
		return r == null ? null : Identifier.fromNamespaceAndPath("tod5", "rareza_" + r.id);
	}

	/** El renglon "Rareza ★★★ [Único]". */
	static final class Linea implements ClientTooltipComponent {
		private static final float CHICA = 0.7f;
		private final Rareza r; private final boolean unico;
		Linea(Rareza r, boolean unico) { this.r = r; this.unico = unico; }
		private int anchoRareza(Font f) { return r == null ? 0 : f.width(r.nombre + " ") + (int) Math.ceil(f.width("★".repeat(r.estrellas)) * CHICA); }
		private int anchoUnico(Font f) { return unico ? f.width("Único") + 6 : 0; }
		@Override public int getHeight(Font f) { return 11; }
		@Override public int getWidth(Font f) { return anchoRareza(f) + (unico ? (r == null ? 0 : 5) + anchoUnico(f) : 0); }
		@Override public void renderText(GuiGraphics g, Font f, int x, int y) {
			int ax = x;
			if (r != null) {
				String n = r.nombre + " ";
				if (r == Rareza.EXOTICO) g.drawString(f, Rareza.pintado(n, f, false), ax, y + 1, 0xFFFFFFFF, true);
				else g.drawString(f, n, ax, y + 1, 0xFF000000 | r.color, true);
				ax += f.width(n);
				String es = "★".repeat(r.estrellas);
				var pose = g.pose();
				pose.pushMatrix();
				pose.translate(ax, y + 2.5f);
				pose.scale(CHICA, CHICA);
				if (r == Rareza.EXOTICO) g.drawString(f, Rareza.pintado(es, f, false), 0, 0, 0xFFFFFFFF, true);
				else g.drawString(f, es, 0, 0, 0xFF000000 | r.color, true);
				pose.popMatrix();
				ax += (int) Math.ceil(f.width(es) * CHICA) + 5;
			}
			if (unico) {
				int w = anchoUnico(f);
				// recuadro arcoiris (de a 1 pixel, cada uno de su color)
				for (int i = 0; i < w; i++) {
					int c = 0xFF000000 | Rareza.arcoiris(ax + i);
					g.fill(ax + i, y - 1, ax + i + 1, y, c);
					g.fill(ax + i, y + 10, ax + i + 1, y + 11, c);
				}
				g.fill(ax, y, ax + 1, y + 10, 0xFF000000 | Rareza.arcoiris(ax));
				g.fill(ax + w - 1, y, ax + w, y + 10, 0xFF000000 | Rareza.arcoiris(ax + w));
				g.drawString(f, Rareza.pintado("Único", f, true), ax + 3, y + 1, 0xFFFFFFFF, true);
			}
		}
	}

	/** El cuadro de la descripcion: fondo oscuro como el de siempre y el borde del color de la rareza. */
	public static boolean fondo(GuiGraphics g, int x, int y, int w, int h, Identifier estilo) {
		if (estilo == null || !estilo.getNamespace().equals("tod5") || !estilo.getPath().startsWith("rareza_")) return false;
		Rareza r = Rareza.porId(estilo.getPath().substring(7));
		if (r == null) return false;
		int bg = 0xF0100010;
		g.fill(x - 3, y - 4, x + w + 3, y - 3, bg);
		g.fill(x - 3, y + h + 3, x + w + 3, y + h + 4, bg);
		g.fill(x - 3, y - 3, x + w + 3, y + h + 3, bg);
		g.fill(x - 4, y - 3, x - 3, y + h + 3, bg);
		g.fill(x + w + 3, y - 3, x + w + 4, y + h + 3, bg);
		int x0 = x - 3, y0 = y - 3, x1 = x + w + 3, y1 = y + h + 3;
		if (r == Rareza.EXOTICO) {
			float ancho = x1 - x0;
			for (int i = x0; i < x1; i += 2) {
				int c = 0xFF000000 | Rareza.aurora(i - x0, ancho);
				g.fill(i, y0, Math.min(i + 2, x1), y0 + 1, c);
				g.fill(i, y1 - 1, Math.min(i + 2, x1), y1, 0xFF000000 | Rareza.aurora(x1 - i, ancho));
			}
			for (int j = y0 + 1; j < y1 - 1; j += 2) {
				float f = (j - y0) / (float) (y1 - y0);
				g.fill(x0, j, x0 + 1, Math.min(j + 2, y1 - 1), 0xFF000000 | Rareza.aurora(0 + f * 8, ancho));
				g.fill(x1 - 1, j, x1, Math.min(j + 2, y1 - 1), 0xFF000000 | Rareza.aurora(ancho - f * 8, ancho));
			}
		} else {
			int a = 0xFF000000 | r.color, b = 0xFF000000 | Rareza.mezclar(r.color, 0x100010, 0.45f);
			g.fill(x0, y0, x1, y0 + 1, a);
			g.fill(x0, y1 - 1, x1, y1, b);
			g.fillGradient(x0, y0 + 1, x0 + 1, y1 - 1, a, b);
			g.fillGradient(x1 - 1, y0 + 1, x1, y1 - 1, a, b);
		}
		return true;
	}
}

package ar.tod5.menu.tablet;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/** Una seccion del modo admin (Anuncios, Crafteos, Items): la tablet le pasa el dibujo, el teclado y el mouse. */
abstract class Panel {
	abstract void dibujar(GuiGraphics g, TabletDatos d, int x, int y, int cw, int ch, int mx, int my);
	boolean tecla(KeyEvent e) { return false; }
	boolean caracter(CharacterEvent e) { return false; }
	boolean clic(double mx, double my, boolean shift) { return false; }
	void arrastrar(double mx, double my) {}
	void soltar() {}
	boolean ruedita(double mx, double my, double dy) { return false; }
	void sinFoco() {}
	boolean escribiendo() { return false; }
	void guardarSiHaceFalta() {}
}

package ar.tod5.menu;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lo que pasa al apretar JUGAR (fondo oscuro, como Opciones):
 *   "Conectando..."  ->  abierto: entra.
 *                    ->  en preparacion: intenta entrar (los OP entran); si el server lo rechaza, queda en la cola:
 *                        SERVIDOR EN PREPARACION (amarillo) + "Jugadores en cola: N".
 *                    ->  apagado: SERVIDOR CERRADO (rojo), y se queda esperando igual.
 *   Cuando abre: SERVIDOR ABIERTO (verde), suena el aviso y 3 segundos despues de que termina, entra solo.
 * VOLVER (o Cancelar) saca de la cola y vuelve al menu.
 */
public class PantallaEntrar extends Screen {
	private static final Logger LOG = LoggerFactory.getLogger("TOD5");
	private static final Identifier SONIDO_ABRIO = Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "abrio");
	private static final float DURA_SONIDO = 6.1f, ESPERA_DESPUES = 3f;

	public enum Paso { CONECTANDO, CERRADO, PREPARACION, ABRIO }

	private Paso paso;
	/** Ya intentamos entrar con el server en preparacion y nos rechazo (no somos OP): no reintentar hasta que abra. */
	private boolean rechazado;
	private long abrioEn;
	private Button volver;

	public PantallaEntrar() { this(Paso.CONECTANDO, false); }

	/** Volver a la cola despues de que el server nos rechazo por estar en preparacion. */
	public static PantallaEntrar enCola() { return new PantallaEntrar(Paso.PREPARACION, true); }

	private PantallaEntrar(Paso paso, boolean rechazado) {
		super(Component.literal("Entrando"));
		this.paso = paso;
		this.rechazado = rechazado;
	}

	@Override
	protected void init() {
		this.volver = this.addRenderableWidget(Button.builder(Component.literal(this.paso == Paso.CONECTANDO ? "Cancelar" : "Volver"), b -> volver())
			.bounds(this.width / 2 - 100, this.height - 40, 200, 20).build());
		EstadoServer.enFila = true;
		if (this.paso == Paso.CONECTANDO) EstadoServer.estado = EstadoServer.Estado.BUSCANDO;
		EstadoServer.empezar();
	}

	private void volver() {
		EstadoServer.enFila = false;
		EstadoServer.parar();
		this.minecraft.gui.setScreen(new MenuPrincipal());
	}

	private void conectar() {
		EstadoServer.parar();
		ServerAddress dir = ServerAddress.parseString(TOD5Client.IP_SERVER);
		ServerData datos = new ServerData(MenuPrincipal.NOMBRE, TOD5Client.IP_SERVER, ServerData.Type.OTHER);
		ConnectScreen.startConnecting(new MenuPrincipal(), this.minecraft, dir, datos, false, null);
	}

	@Override
	public void tick() {
		var e = EstadoServer.estado;
		switch (this.paso) {
			case CONECTANDO, CERRADO, PREPARACION -> {
				if (e == EstadoServer.Estado.ABIERTO) {
					if (this.paso == Paso.CONECTANDO) { LOG.info("TOD5 server abierto: entrando"); conectar(); return; }
					// estabas esperando y abrio: aviso, sonido y despues entra
					this.paso = Paso.ABRIO;
					this.abrioEn = Util.getMillis();
					LOG.info("TOD5 el server abrio: sonido y entrando en {} s", DURA_SONIDO + ESPERA_DESPUES);
					this.minecraft.getSoundManager().play(new SimpleSoundInstance(SONIDO_ABRIO, SoundSource.MASTER, 1f, 1f,
						RandomSource.create(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true));
				} else if (e == EstadoServer.Estado.PREPARACION) {
					if (!this.rechazado) {   // puede ser OP: intenta entrar. Si no lo es, el server lo rechaza y vuelve aca.
						this.rechazado = true;
						LOG.info("TOD5 server en preparacion: intento entrar (por si soy OP)");
						conectar();
						return;
					}
					this.paso = Paso.PREPARACION;
				} else if (e == EstadoServer.Estado.CERRADO) {
					this.paso = Paso.CERRADO;
					this.rechazado = false;   // si despues prende en preparacion, vuelve a probar (por si es OP)
				}
			}
			case ABRIO -> {
				if ((Util.getMillis() - this.abrioEn) / 1000f >= DURA_SONIDO + ESPERA_DESPUES) { this.paso = Paso.CONECTANDO; conectar(); }
			}
		}
		if (this.volver != null) this.volver.setMessage(Component.literal(this.paso == Paso.CONECTANDO ? "Cancelar" : "Volver"));
	}

	@Override public boolean shouldCloseOnEsc() { return false; }
	@Override public boolean isPauseScreen() { return false; }

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		Fondo.dibujarSinAgujero(g, this.width, this.height);   // el mismo fondo oscuro de Opciones
	}

	private void centrado(GuiGraphicsExtractor g, String t, int y, int color, float escala) {
		g.pose().pushMatrix();
		g.pose().translate(this.width / 2f, y);
		g.pose().scale(escala, escala);
		g.text(this.font, t, -this.font.width(t) / 2, 0, color);
		g.pose().popMatrix();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		int y = this.height / 2 - 30;
		switch (this.paso) {
			case CONECTANDO -> centrado(g, "Conectando" + ".".repeat((int) (Util.getMillis() / 400 % 4)), y + 10, 0xFFFFFFFF, 1f);
			case CERRADO -> {
				centrado(g, "SERVIDOR CERRADO", y, 0xFFFF5555, 2f);
				centrado(g, "Cuando abra, vas a entrar solo.", y + 28, 0xFFAAAAAA, 1f);
			}
			case PREPARACION -> {
				centrado(g, "SERVIDOR EN PREPARACIÓN", y, 0xFFFFDD55, 2f);
				centrado(g, "Jugadores en cola: " + Math.max(1, EstadoServer.enFilaServer), y + 28, 0xFFFFFFFF, 1f);
			}
			case ABRIO -> {
				centrado(g, "SERVIDOR ABIERTO", y, 0xFF55FF77, 2f);
				centrado(g, "Entrando...", y + 28, 0xFFAAAAAA, 1f);
			}
		}
	}
}

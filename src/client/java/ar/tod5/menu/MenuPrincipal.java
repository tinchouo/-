package ar.tod5.menu;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * El menu principal: la galaxia girando de fondo, el logo y tres botones (Jugar, Opciones, Salir).
 *
 * La primera vez que se abre (al iniciar el juego) hace la intro:
 *   negro -> arranca la musica -> aparece la galaxia -> el logo sale despacio de la oscuridad -> aparecen los botones.
 * Cualquier tecla o click la saltea. Las otras veces solo hace un fundido corto.
 */
public class MenuPrincipal extends Screen {
	private static final Logger LOG = LoggerFactory.getLogger("TOD5");

	public static final String NOMBRE = "TRUCOFDEATH: Revenants";
	private static final String CREDITO = "Creado por Trick";
	private static final String VERSION = FabricLoader.getInstance().getModContainer(TOD5Client.MOD_ID)
		.map(m -> m.getMetadata().getVersion().getFriendlyString().replaceAll("\\.0$", "")).orElse("?");

	// el logo va en 3 capas: la base, el brillo verde de TRUCOFDEATH (late y le pasa un reflejo) y el fuego de REVENANTS
	private static final Identifier LOGO = tex("logo_base"), BRILLO = tex("logo_brillo"), FUEGO = tex("logo_fuego");
	private static final int LOGO_W = 1200, LOGO_H = 273;                                   // tamano de la imagen
	private static final int FUEGO_X = 154, FUEGO_Y = 104, FUEGO_W = 872, FUEGO_H = 166, FUEGO_CUADROS = 24;
	private static final float FUEGO_FPS = 15f;
	private static Identifier tex(String n) { return Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "textures/gui/" + n + ".png"); }

	// tiempos de la intro, en segundos
	private static final float T_GALAXIA = 1.2f, T_GALAXIA_FIN = 3.6f;   // el negro se va y se ve la galaxia
	private static final float T_LOGO = 3.4f, LOGO_DURA = 2.6f;          // el logo sale despacio de la oscuridad
	private static final float T_BOTONES = 6.0f, BOTONES_DURA = 1.2f;    // aparecen los botones
	private static final float FIN_INTRO = T_BOTONES + BOTONES_DURA;

	private static boolean introVista = false;

	private final boolean conIntro;
	private long inicio;
	private int botonesY;

	public MenuPrincipal() {
		super(Component.literal(NOMBRE));
		this.conIntro = !introVista;
	}

	@Override
	protected void init() {
		int bw = 200, bh = 20, x = this.width / 2 - bw / 2;
		// los botones van en la mitad de la pantalla (solo el logo va arriba)
		int y = Math.min(this.height - 3 * 24 - 16, Math.max(logoY() + logoAlto() + 16, this.height / 2 + 30));
		this.botonesY = y;
		this.addRenderableWidget(new BotonTOD("jugar", "Jugar", x, y, bw, bh, b -> jugar()));
		this.addRenderableWidget(new BotonTOD("opciones", "Opciones", x, y + 24, bw, bh,
			b -> this.minecraft.gui.setScreen(new OptionsScreen(this, this.minecraft.options))));
		this.addRenderableWidget(new BotonTOD("salir", "Salir", x, y + 48, bw, bh, b -> this.minecraft.stop()));
	}

	@Override
	public void added() {
		super.added();
		this.minecraft.gameRenderer.panorama().startSpin();
	}

	/** La intro arranca recien cuando se fue la pantalla de carga de Mojang (si no, queda tapada). */
	private void arrancarSiHaceFalta() {
		if (this.inicio != 0 || this.minecraft.gui.overlay() != null) return;
		this.inicio = Util.getMillis();
		LOG.info("TOD5 menu abierto (intro: {}, version {})", this.conIntro, VERSION);
		if (this.conIntro) introVista = true;
		MusicaMenu.iniciar();   // con la intro arranca desde el principio; si ya sonaba, sigue
	}

	private void jugar() {
		ServerAddress dir = ServerAddress.parseString(TOD5Client.IP_SERVER);
		ServerData datos = new ServerData(NOMBRE, TOD5Client.IP_SERVER, ServerData.Type.OTHER);
		ConnectScreen.startConnecting(this, this.minecraft, dir, datos, false, null);
	}

	/** Segundos desde que se abrio (con intro) o un tiempo "ya terminado" (sin intro, con fundido corto). */
	private float t() {
		if (this.inicio == 0) return 0;
		float s = (Util.getMillis() - this.inicio) / 1000f;
		return this.conIntro ? s : FIN_INTRO + s;
	}

	private void saltearIntro() {
		if (this.inicio == 0) return;
		if (this.conIntro && t() < FIN_INTRO) this.inicio = Util.getMillis() - (long) (FIN_INTRO * 1000);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (this.conIntro && t() < T_BOTONES) { saltearIntro(); return true; }
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (this.conIntro && t() < T_BOTONES) { saltearIntro(); return true; }
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean shouldCloseOnEsc() { return false; }

	@Override
	public boolean isPauseScreen() { return false; }

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
	}

	private int logoAncho() { return Math.min(340, (int) (this.width * 0.7f)); }
	private int logoAlto() { return logoAncho() * LOGO_H / LOGO_W; }
	private int logoY() { return Math.max(10, Math.min(30, this.height / 10)); }

	private void dibujarLogo(GuiGraphicsExtractor g, float t, float alfa) {
		int lw = logoAncho(), lh = logoAlto(), lx = this.width / 2 - lw / 2, ly = logoY();
		float s = lw / (float) LOGO_W;
		g.blit(RenderPipelines.GUI_TEXTURED, LOGO, lx, ly, 0, 0, lw, lh, LOGO_W, LOGO_H, LOGO_W, LOGO_H, argb(alfa, 0xFFFFFF));

		// brillo verde de TRUCOFDEATH: late despacito, poquito
		float late = 0.22f + 0.16f * (0.5f + 0.5f * (float) Math.sin(t * 1.15));
		g.blit(RenderPipelines.GUI_TEXTURED, BRILLO, lx, ly, 0, 0, lw, lh, LOGO_W, LOGO_H, LOGO_W, LOGO_H, argb(alfa * late, 0xFFFFFF));
		// y cada 9 segundos le pasa un reflejo de izquierda a derecha
		float ciclo = (t % 9f) / 2.4f;
		if (ciclo < 1) {
			int cx = lx + (int) ((-0.15f + 1.3f * ciclo) * lw), banda = Math.max(8, lw / 12);
			g.enableScissor(cx - banda, ly, cx + banda, ly + lh);
			g.blit(RenderPipelines.GUI_TEXTURED, BRILLO, lx, ly, 0, 0, lw, lh, LOGO_W, LOGO_H, LOGO_W, LOGO_H, argb(alfa * 0.55f, 0xFFFFFF));
			g.disableScissor();
		}

		// fuego verde de REVENANTS (animado)
		int cuadro = (int) (t * FUEGO_FPS) % FUEGO_CUADROS;
		g.blit(RenderPipelines.GUI_TEXTURED, FUEGO, lx + Math.round(FUEGO_X * s), ly + Math.round(FUEGO_Y * s), 0, cuadro * FUEGO_H,
			Math.round(FUEGO_W * s), Math.round(FUEGO_H * s), FUEGO_W, FUEGO_H, FUEGO_W, FUEGO_H * FUEGO_CUADROS, argb(alfa * 0.9f, 0xFFFFFF));
	}

	private static float clamp01(float v) { return v < 0 ? 0 : Math.min(1, v); }
	private static float suave(float x) { return x * x * (3 - 2 * x); }
	private static int argb(float alpha, int rgb) { return ((int) (clamp01(alpha) * 255) << 24) | (rgb & 0xFFFFFF); }

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		arrancarSiHaceFalta();
		float t = t();
		boolean empezo = this.inicio != 0;

		float alfaBotones = !empezo ? 0 : this.conIntro ? clamp01((t - T_BOTONES) / BOTONES_DURA) : 1;
		this.fadeWidgets(alfaBotones);
		for (var w : this.children()) if (w instanceof Button b) b.active = alfaBotones > 0.6f;

		// 1) el fondo (plano y animado); el agujero negro va entre el logo y los botones
		int arriba = logoY() + logoAlto(), hueco = Math.max(20, this.botonesY - arriba);
		Fondo.dibujar(g, this.width, this.height, t, this.width / 2, arriba + hueco / 2, Math.min(hueco * 0.62f, this.width * 0.12f));

		// 2) el logo, quieto arriba, saliendo de la oscuridad
		float alfaLogo = !empezo ? 0 : this.conIntro ? suave(clamp01((t - T_LOGO) / LOGO_DURA)) : 1;
		if (alfaLogo > 0) dibujarLogo(g, t, alfaLogo);

		// 3) los botones y los textos de abajo
		super.extractRenderState(g, mouseX, mouseY, a);
		int gris = argb(alfaBotones * 0.6f, 0xFFFFFF);
		g.text(this.font, NOMBRE + " v" + VERSION, 2, this.height - 10, gris);
		g.text(this.font, CREDITO, this.width - this.font.width(CREDITO) - 2, this.height - 10, gris);

		// 4) el negro del principio que se va
		float negro = !empezo ? 1 : this.conIntro
			? 1 - clamp01((t - T_GALAXIA) / (T_GALAXIA_FIN - T_GALAXIA))
			: 1 - clamp01((t - FIN_INTRO) / 0.5f);
		if (negro > 0) g.fill(0, 0, this.width, this.height, argb(negro, 0x000000));
	}
}

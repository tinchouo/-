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
import net.minecraft.sounds.Musics;
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

	private static final Identifier LOGO = Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "textures/gui/logo.png");
	private static final int LOGO_W = 1200, LOGO_H = 286;   // tamano real de la imagen

	// tiempos de la intro, en segundos
	private static final float T_GALAXIA = 1.2f, T_GALAXIA_FIN = 3.6f;   // el negro se va y se ve la galaxia
	private static final float T_LOGO = 3.4f, LOGO_DURA = 2.6f;          // el logo sale despacio de la oscuridad
	private static final float T_BOTONES = 6.0f, BOTONES_DURA = 1.2f;    // aparecen los botones
	private static final float FIN_INTRO = T_BOTONES + BOTONES_DURA;

	private static boolean introVista = false;

	private final boolean conIntro;
	private long inicio;
	private boolean toastMostrado;

	public MenuPrincipal() {
		super(Component.literal(NOMBRE));
		this.conIntro = !introVista;
	}

	@Override
	protected void init() {
		int bw = 200, bh = 20, x = this.width / 2 - bw / 2;
		int y = Math.min(this.height - 3 * 24 - 16, this.height / 2 + 30);
		this.addRenderableWidget(Button.builder(Component.literal("Jugar"), b -> jugar()).bounds(x, y, bw, bh).build());
		this.addRenderableWidget(Button.builder(Component.literal("Opciones"),
			b -> this.minecraft.gui.setScreen(new OptionsScreen(this, this.minecraft.options))).bounds(x, y + 24, bw, bh).build());
		this.addRenderableWidget(Button.builder(Component.literal("Salir"), b -> this.minecraft.stop()).bounds(x, y + 48, bw, bh).build());
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
		if (this.conIntro) {
			introVista = true;
			// la musica arranca justo con la intro
			this.minecraft.getMusicManager().stopPlaying();
			this.minecraft.getMusicManager().startPlaying(Musics.MENU);
			// el cartelito de "Sonando ahora" sale cuando aparecen los botones, no arriba de la intro
			this.minecraft.gui.toastManager().hideNowPlayingToast();
		}
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
		if (this.conIntro && !this.toastMostrado && alfaBotones > 0) {
			this.toastMostrado = true;
			this.minecraft.gui.toastManager().showNowPlayingToast();
		}

		// 1) la galaxia
		this.extractPanorama(g, a);

		// 2) el logo, saliendo de la oscuridad y flotando despacito
		float alfaLogo = !empezo ? 0 : this.conIntro ? suave(clamp01((t - T_LOGO) / LOGO_DURA)) : 1;
		if (alfaLogo > 0) {
			int lw = Math.min(340, (int) (this.width * 0.7f));
			int lh = lw * LOGO_H / LOGO_W;
			int ly = Math.max(8, this.height / 2 + 30 - lh - 18) + (int) Math.round(Math.sin(t * 1.1) * 2);
			g.blit(RenderPipelines.GUI_TEXTURED, LOGO, this.width / 2 - lw / 2, ly, 0, 0, lw, lh, LOGO_W, LOGO_H, LOGO_W, LOGO_H, argb(alfaLogo, 0xFFFFFF));
		}

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

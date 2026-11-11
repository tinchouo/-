package ar.tod5.menu;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
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
	private static final int LOGO_W = 1756, LOGO_H = 399;                                   // tamano de la imagen
	private static final int FUEGO_X = 226, FUEGO_Y = 152, FUEGO_W = 1276, FUEGO_H = 243, FUEGO_CUADROS = 24;
	private static final float FUEGO_FPS = 15f;
	private static Identifier tex(String n) { return Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "textures/gui/" + n + ".png"); }

	// tiempos de la intro, en segundos
	private static final float T_GALAXIA = 1.2f, T_GALAXIA_FIN = 3.6f;   // el negro se va y se ve la galaxia
	private static final float T_LOGO = 3.4f, LOGO_DURA = 2.6f;          // el logo sale despacio de la oscuridad
	private static final float T_BOTONES = 6.0f, BOTONES_DURA = 1.2f;    // aparecen los botones
	private static final float FIN_INTRO = T_BOTONES + BOTONES_DURA;

	/** Estilo de los botones del menu: "boton_" = placa de hierro, "botontxt_" = solo el texto flotando (idea de Frack). */
	private static final String ESTILO = "botontxt_";
	private static boolean introVista = false;

	private final boolean conIntro;
	private long inicio;
	private int botonesY;

	// ---- secreto: clic en el agujero negro -> entras, se ve la pista, y volves ----
	private enum Secreto { NADA, ENTRANDO, PISTA, SALIENDO, VOLVIENDO }
	private static final Identifier PISTA = tex("pista");
	private static final int PISTA_W = 750, PISTA_H = 1200;
	private static final Identifier SONIDO_ENTRAR = Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "secreto_entrar");
	private static final Identifier SONIDO_VOLVER = Identifier.fromNamespaceAndPath(TOD5Client.MOD_ID, "secreto_volver");
	private static final float S_ENTRAR = 2.8f, S_PISTA_FADE = 1.8f, S_SALIR = 0.9f;
	private static final float S_AGUJERO = 2.2f, S_LOGO = 1.2f, S_BOTONES = 0.9f;   // la vuelta, como la intro
	private Secreto secreto = Secreto.NADA;
	private long secretoDesde;
	private float agX, agY, agD;          // donde esta el agujero ahora (para el clic)

	private float ts() { return (Util.getMillis() - this.secretoDesde) / 1000f; }
	private void pasarA(Secreto s) { this.secreto = s; this.secretoDesde = Util.getMillis(); }
	private void sonidoSecreto(Identifier id) {
		this.minecraft.getSoundManager().play(new net.minecraft.client.resources.sounds.SimpleSoundInstance(id, net.minecraft.sounds.SoundSource.MASTER,
			0.55f, 1f, net.minecraft.util.RandomSource.create(), false, 0, net.minecraft.client.resources.sounds.SoundInstance.Attenuation.NONE, 0, 0, 0, true));
	}

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
		this.addRenderableWidget(new BotonTOD(ESTILO, "jugar", "Jugar", x, y, bw, bh, b -> this.minecraft.setScreen(new PantallaEntrar())));
		this.addRenderableWidget(new BotonTOD(ESTILO, "opciones", "Opciones", x, y + 24, bw, bh,
			b -> this.minecraft.setScreen(new OptionsScreen(this, this.minecraft.options))));
		this.addRenderableWidget(new BotonTOD(ESTILO, "salir", "Salir", x, y + 48, bw, bh, b -> this.minecraft.stop()));
	}

	@Override
	public void added() {
		super.added();
	}

	/** La intro arranca recien cuando se fue la pantalla de carga de Mojang (si no, queda tapada). */
	private void arrancarSiHaceFalta() {
		if (this.inicio != 0 || this.minecraft.getOverlay() != null) return;
		this.inicio = Util.getMillis();
		LOG.info("TOD5 menu abierto (intro: {}, version {})", this.conIntro, VERSION);
		if (this.conIntro) introVista = true;
		MusicaMenu.iniciar();   // con la intro arranca desde el principio; si ya sonaba, sigue
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
		if (this.secreto != Secreto.NADA) { volverDelSecreto(); return true; }
		if (this.conIntro && t() < T_BOTONES) { saltearIntro(); return true; }
		return super.keyPressed(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (this.secreto != Secreto.NADA) { volverDelSecreto(); return true; }
		if (this.conIntro && t() < T_BOTONES) { saltearIntro(); return true; }
		if (super.mouseClicked(event, doubleClick)) return true;
		// el secreto: clic adentro del agujero (sin sonido de clic ni nada que lo delate)
		double dx = event.x() - this.agX, dy = event.y() - this.agY;
		if (this.inicio != 0 && dx * dx + dy * dy <= (this.agD * 0.55) * (this.agD * 0.55)) {
			pasarA(Secreto.ENTRANDO);
			MusicaMenu.fundir(0f, S_ENTRAR * 0.9f);
			sonidoSecreto(SONIDO_ENTRAR);
			return true;
		}
		return false;
	}

	/** Desde la imagen se puede volver con cualquier clic o tecla (cuando ya termino de entrar). */
	private void volverDelSecreto() {
		if (this.secreto == Secreto.PISTA && ts() > 0.6f) { pasarA(Secreto.SALIENDO); sonidoSecreto(SONIDO_VOLVER); }
	}

	@Override
	public boolean shouldCloseOnEsc() { return false; }

	@Override
	public boolean isPauseScreen() { return false; }

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float a) {
	}

	private int logoAncho() { return Math.min(340, (int) (this.width * 0.7f)); }
	private int logoAlto() { return logoAncho() * LOGO_H / LOGO_W; }
	private int logoY() { return Math.max(10, Math.min(30, this.height / 10)); }

	private void dibujarLogo(GuiGraphics g, float t, float alfa) {
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

	private static float lerp(float a, float b, float k) { return a + (b - a) * k; }

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float a) {
		arrancarSiHaceFalta();
		float t = t();
		boolean empezo = this.inicio != 0;

		// donde va el agujero normalmente: entre el logo y los botones
		int arriba = logoY() + logoAlto(), hueco = Math.max(20, this.botonesY - arriba);
		float baseX = this.width / 2f, baseY = arriba + hueco / 2f, baseD = Math.min(hueco * 0.62f, this.width * 0.12f);
		float grande = 3.2f * (float) Math.hypot(this.width, this.height);   // tan grande que el centro negro tapa todo

		float alfaBotones = !empezo ? 0 : this.conIntro ? clamp01((t - T_BOTONES) / BOTONES_DURA) : 1;
		float alfaLogo = !empezo ? 0 : this.conIntro ? suave(clamp01((t - T_LOGO) / LOGO_DURA)) : 1;
		float negroSecreto = 0, alfaPista = 0;
		boolean agujeroArriba = false;   // true: el agujero va encima del logo y los botones
		this.agX = baseX; this.agY = baseY; this.agD = baseD;

		switch (this.secreto) {
			case ENTRANDO -> {
				float p = clamp01(ts() / S_ENTRAR), k = p * p * p;
				this.agD = baseD * (float) Math.exp(Math.log(grande / baseD) * k);
				float mover = 1 - baseD / this.agD;   // se corre al centro solo a medida que crece (como una camara entrando)
				this.agX = lerp(baseX, this.width / 2f, mover);
				this.agY = lerp(baseY, this.height / 2f, mover);
				agujeroArriba = true;
				negroSecreto = clamp01((p - 0.78f) / 0.22f);
				if (p >= 1) pasarA(Secreto.PISTA);
			}
			case PISTA -> { negroSecreto = 1; alfaPista = suave(clamp01((ts() - 0.3f) / S_PISTA_FADE)); }
			case SALIENDO -> {
				negroSecreto = 1;
				alfaPista = 1 - clamp01(ts() / S_SALIR);
				if (ts() >= S_SALIR + 0.3f) pasarA(Secreto.VOLVIENDO);
			}
			case VOLVIENDO -> {
				float s = ts(), p = clamp01(s / S_AGUJERO), k = 1 - suave(p);
				this.agD = baseD * (float) Math.exp(Math.log(grande / baseD) * k * k * k);
				float mover = 1 - baseD / this.agD;
				this.agX = lerp(baseX, this.width / 2f, mover);
				this.agY = lerp(baseY, this.height / 2f, mover);
				negroSecreto = 1 - clamp01(s / 0.5f);
				alfaLogo = suave(clamp01((s - S_AGUJERO) / S_LOGO));
				alfaBotones = clamp01((s - S_AGUJERO - S_LOGO * 0.6f) / S_BOTONES);
				if (s >= S_AGUJERO && !this.musicaVuelve) { this.musicaVuelve = true; MusicaMenu.fundir(1f, 2.5f); }
				if (s >= S_AGUJERO + S_LOGO * 0.6f + S_BOTONES) { pasarA(Secreto.NADA); this.musicaVuelve = false; }
			}
			default -> {}
		}
		if (this.secreto == Secreto.ENTRANDO) {
			alfaBotones = Math.min(alfaBotones, 1 - clamp01(ts() / 0.6f));
			alfaLogo = Math.min(alfaLogo, 1 - clamp01(ts() / (S_ENTRAR * 0.55f)));
		}
		else if (this.secreto == Secreto.PISTA || this.secreto == Secreto.SALIENDO) { alfaLogo = 0; alfaBotones = 0; }

		this.fadeWidgets(alfaBotones);
		for (var w : this.children()) if (w instanceof Button b) b.active = alfaBotones > 0.6f && this.secreto == Secreto.NADA;

		// 1) el cielo y (normalmente) el agujero debajo de todo
		Fondo.cielo(g, this.width, this.height);
		if (!agujeroArriba) Fondo.agujero(g, Math.round(this.agX), Math.round(this.agY), this.agD);

		// 2) el logo, quieto arriba
		if (alfaLogo > 0) dibujarLogo(g, t, alfaLogo);

		// 3) los botones y los textos de abajo
		super.render(g, mouseX, mouseY, a);
		int gris = argb(alfaBotones * 0.6f, 0xFFFFFF);
		g.drawString(this.font, NOMBRE + " v" + VERSION, 2, this.height - 10, gris);
		g.drawString(this.font, CREDITO, this.width - this.font.width(CREDITO) - 2, this.height - 10, gris);

		// 4) entrando al agujero: va encima de todo y lo tapa
		if (agujeroArriba) Fondo.agujero(g, Math.round(this.agX), Math.round(this.agY), this.agD);

		// 5) adentro: negro y la imagen
		if (negroSecreto > 0) g.fill(0, 0, this.width, this.height, argb(negroSecreto, 0x000000));
		if (alfaPista > 0) {
			int ph = (int) (this.height * 0.9f), pw = ph * PISTA_W / PISTA_H;
			g.blit(RenderPipelines.GUI_TEXTURED, PISTA, (this.width - pw) / 2, (this.height - ph) / 2, 0, 0, pw, ph, PISTA_W, PISTA_H, PISTA_W, PISTA_H, argb(alfaPista, 0xFFFFFF));
		}

		// 6) el negro del principio que se va
		float negro = !empezo ? 1 : this.conIntro
			? 1 - clamp01((t - T_GALAXIA) / (T_GALAXIA_FIN - T_GALAXIA))
			: 1 - clamp01((t - FIN_INTRO) / 0.5f);
		if (negro > 0) g.fill(0, 0, this.width, this.height, argb(negro, 0x000000));

		// 7) cerrando el juego: todo a negro
		float salida = Salida.oscuro();
		if (salida > 0) g.fill(0, 0, this.width, this.height, argb(salida, 0x000000));
	}

	private boolean musicaVuelve = false;
}

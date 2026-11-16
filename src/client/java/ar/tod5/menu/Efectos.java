package ar.tod5.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Las particulas y animaciones de orbes y pads (hechas por el mod, en la compu de cada uno):
 *  - chispa: un puntito suave que va derecho y se desvanece de a poco (sin gravedad, sin dar vueltas)
 *  - al usar una orbe: un aro blanco que se agranda y se desvanece, y la orbe "late" (se agranda y vuelve)
 *  - al usar un pad: un chorro de chispas para donde apunta
 */
public final class Efectos {
	private Efectos() {}
	private static final RandomSource R = RandomSource.create();
	private static final int AMARILLO = 0xFFF27A, ROJO = 0xFF5A4A;

	/** Un cuadradito que mira a la camara: se mueve derecho, cambia de tamaño y se desvanece. Siempre a pleno brillo. */
	static final class Chispa extends SingleQuadParticle {
		private final float tam0, tam1, alfa0;
		Chispa(ClientLevel nivel, Vec3 pos, Vec3 vel, TextureAtlasSprite sprite, int color, float tam0, float tam1, float alfa0, int vida) {
			super(nivel, pos.x, pos.y, pos.z, sprite);
			this.xd = vel.x; this.yd = vel.y; this.zd = vel.z;
			this.gravity = 0; this.friction = 1.0F; this.hasPhysics = false;
			this.tam0 = tam0; this.tam1 = tam1; this.alfa0 = alfa0;
			this.quadSize = tam0; this.alpha = alfa0;
			this.lifetime = vida;
			this.setColor(((color >> 16) & 0xFF) / 255f, ((color >> 8) & 0xFF) / 255f, (color & 0xFF) / 255f);
		}
		@Override public void tick() {
			super.tick();
			float t = Mth.clamp(this.age / (float) this.lifetime, 0f, 1f);
			this.quadSize = Mth.lerp(t, this.tam0, this.tam1);
			this.alpha = this.alfa0 * (1 - t * t);   // se desvanece de a poco (mas rapido al final)
		}
		@Override protected SingleQuadParticle.Layer getLayer() { return SingleQuadParticle.Layer.ITEMS; }
		@Override protected int getLightColor(float f) { return 15728880; }
	}

	private static TextureAtlasSprite sprite(String nombre) {
		return Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.ITEMS).getSprite(Identifier.fromNamespaceAndPath("tod5", "item/" + nombre));
	}
	private static void poner(Vec3 pos, Vec3 vel, String sprite, int color, float tam0, float tam1, float alfa, int vida) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		mc.particleEngine.add(new Chispa(mc.level, pos, vel, sprite(sprite), color, tam0, tam1, alfa, vida));
	}
	/** Un punto al azar en el cuadrado de la cara del pad (un poco adentro). */
	private static Vec3 enCara(Vec3 c, Vec3 n, double lado) {
		double a = (R.nextDouble() - 0.5) * lado, b = (R.nextDouble() - 0.5) * lado;
		if (n.x != 0) return c.add(0, a, b);
		if (n.y != 0) return c.add(a, 0, b);
		return c.add(a, b, 0);
	}

	/** Las de siempre del pad: salen de la cara, derecho para donde apunta. */
	static void chispaPad(Vec3 c, Vec3 n, boolean roja) {
		if (R.nextFloat() > 0.6f) return;
		poner(enCara(c, n, 0.7).add(n.scale(0.1)), n.scale(0.045 + R.nextDouble() * 0.02), "chispa", roja ? ROJO : AMARILLO, 0.07f, 0.03f, 0.9f, 18 + R.nextInt(8));
	}
	/** Las de siempre de la orbe: aparecen alrededor y van derecho hacia el centro. */
	static void chispaOrbe(Vec3 c, boolean roja) {
		double a = R.nextDouble() * Math.PI * 2, b = Math.acos(2 * R.nextDouble() - 1);
		Vec3 dir = new Vec3(Math.sin(b) * Math.cos(a), Math.cos(b), Math.sin(b) * Math.sin(a));
		poner(c.add(dir.scale(0.75)), dir.scale(-0.035), "chispa", roja ? ROJO : AMARILLO, 0.06f, 0.02f, 0.9f, 16);
	}
	/** Al usar una orbe: aro que se agranda y se va, y la orbe late. */
	static void orbe(Vec3 c, boolean roja) {
		poner(c, Vec3.ZERO, "orbe_aro", 0xFFFFFF, 0.5f, 1.3f, 1.0f, 9);
		poner(c, Vec3.ZERO, roja ? "orbe_rojo" : "orbe_amarillo", 0xFFFFFF, 0.55f, 0.75f, 0.85f, 6);
		for (int i = 0; i < 10; i++) {
			double a = i * Math.PI / 5;
			poner(c, new Vec3(Math.cos(a), Math.sin(a) * 0.3 + 0.15, Math.sin(a)).scale(0.08), "chispa", roja ? ROJO : AMARILLO, 0.08f, 0.02f, 1f, 12);
		}
	}
	/** Al usar un pad: un chorro de chispas para donde apunta. */
	static void pad(Vec3 c, Vec3 n, boolean roja) {
		for (int i = 0; i < 12; i++)
			poner(enCara(c, n, 0.8).add(n.scale(0.1)), n.scale(0.12 + R.nextDouble() * 0.08), "chispa", roja ? ROJO : AMARILLO, 0.09f, 0.02f, 1f, 14);
	}
}

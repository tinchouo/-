package ar.tod5.menu;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * Items con rareza tirados en el piso: un brillo detras del item (desde su centro), el rayo para arriba y la raya blanca desde legendario,
 * y particulitas que suben. Todo del lado del mod (al server no le cuesta nada).
 */
public final class RarezaPiso {
	private RarezaPiso() {}
	private static final Identifier BRILLO = Identifier.fromNamespaceAndPath("tod5", "textures/efectos/rareza_brillo.png");
	private static final Identifier RAYO = Identifier.fromNamespaceAndPath("tod5", "textures/efectos/rareza_rayo.png");
	private static final Identifier RAYA = Identifier.fromNamespaceAndPath("tod5", "textures/efectos/rareza_raya.png");
	private static final int LUZ = 0xF000F0;
	private static final RandomSource AZAR = RandomSource.create();

	/** El color del brillo en el piso (el exotico: turquesa que cambia un poquito de tono, sin verde ni rosa). */
	private static int colorPiso(Rareza r, float edad) {
		if (r != Rareza.EXOTICO || Ajustes.rendimiento()) return r.color;
		float f = (float) Math.sin(edad * 0.06);
		return f > 0 ? Rareza.mezclar(r.color, r.claro, f * 0.45f) : Rareza.mezclar(r.color, 0x00A8D8, -f * 0.4f);
	}

	/** Se llama al dibujar el item (con la pose en los pies del item). centroY = altura del centro del item. */
	public static void dibujar(Rareza r, float edad, float centroY, PoseStack pose, SubmitNodeCollector col, CameraRenderState cam) {
		float pulso = Ajustes.rendimiento() ? 0.85f : 0.65f + 0.35f * (0.5f + 0.5f * (float) Math.sin(edad * 0.157));
		int c = colorPiso(r, edad);
		pose.pushPose();
		pose.translate(0, centroY, 0);
		// rayo para arriba (dos planos cruzados), desde legendario
		if (r.estrellas >= 5) {
			float alto = 0.7f + 0.45f * (r.estrellas - 5), ancho = 0.07f;
			int cr = argb(r == Rareza.EXOTICO ? Rareza.mezclar(c, 0xFFFFFF, 0.25f) : c, 0.85f * pulso);
			col.submitCustomGeometry(pose, RenderTypes.entityTranslucentEmissive(RAYO), (p, vc) -> {
				quad(p, vc, -ancho, 0, 0, ancho, alto, 0, cr, true);
				quad(p, vc, 0, 0, -ancho, 0, alto, ancho, cr, true);
			});
		}
		// mirando a la camara: brillo (un poco detras del item) y la raya blanca
		pose.mulPose(cam.orientation);
		pose.pushPose();
		pose.translate(0, 0, -0.18f);
		float t = 0.26f + 0.05f * r.estrellas;
		int cb = argb(c, (r.estrellas >= 5 ? 0.95f : 0.8f) * pulso);
		col.submitCustomGeometry(pose, RenderTypes.entityTranslucentEmissive(BRILLO), (p, vc) -> quad(p, vc, -t, -t, 0, t, t, 0, cb, false));
		if (r.estrellas >= 5) {
			float tc = t * 0.45f;
			int cc = argb(r.claro, 0.9f * pulso);
			col.submitCustomGeometry(pose, RenderTypes.entityTranslucentEmissive(BRILLO), (p, vc) -> quad(p, vc, -tc, -tc, 0.001f, tc, tc, 0.001f, cc, false));
			float rw = 0.45f + 0.2f * (r.estrellas - 5), rh = 0.035f;
			int cw = argb(0xFFFFFF, 0.85f * pulso);
			col.submitCustomGeometry(pose, RenderTypes.entityTranslucentEmissive(RAYA), (p, vc) -> quad(p, vc, -rw, -rh, 0.25f, rw, rh, 0.25f, cw, false));
		}
		pose.popPose();
		pose.popPose();
	}
	private static int argb(int rgb, float a) { return ((int) (Math.max(0, Math.min(1, a)) * 255) << 24) | (rgb & 0xFFFFFF); }
	/** Un cuadrado (de a, b) en el plano dado; "vertical": de x0,y0,z0 a x1,y1,z1 con la textura de abajo (0) a arriba. Por las dos caras. */
	private static void quad(PoseStack.Pose p, VertexConsumer vc, float x0, float y0, float z0, float x1, float y1, float z1, int color, boolean vertical) {
		float[][] v = vertical
			? new float[][] { { x0, y0, z0, 0, 1 }, { x1, y0, z1, 1, 1 }, { x1, y1, z1, 1, 0 }, { x0, y1, z0, 0, 0 } }
			: new float[][] { { x0, y0, z0, 0, 1 }, { x1, y0, z0, 1, 1 }, { x1, y1, z1, 1, 0 }, { x0, y1, z1, 0, 0 } };
		for (int i = 0; i < 4; i++) vert(p, vc, v[i], color);
		for (int i = 3; i >= 0; i--) vert(p, vc, v[i], color);
	}
	private static void vert(PoseStack.Pose p, VertexConsumer vc, float[] v, int color) {
		vc.addVertex(p, v[0], v[1], v[2]).setColor(color).setUv(v[3], v[4]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LUZ).setNormal(p, 0, 1, 0);
	}

	/** Cada tick: particulitas que suben de los items con rareza cercanos (mas cuanto mas rara). */
	public static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null || mc.isPaused() || Ajustes.rendimiento()) return;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof ItemEntity ie) || e.distanceToSqr(mc.player) > 32 * 32) continue;
			Rareza r = Rareza.rareza(ie.getItem());
			if (r == null || AZAR.nextFloat() > 0.04f + 0.04f * r.estrellas) continue;
			int c = r == Rareza.EXOTICO ? 0xE3FCFF : r.color;
			double x = e.getX() + (AZAR.nextDouble() - 0.5) * 0.4, y = e.getY() + 0.25 + AZAR.nextDouble() * 0.2, z = e.getZ() + (AZAR.nextDouble() - 0.5) * 0.4;
			mc.level.addParticle(ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0xFF000000 | c), x, y, z, 0, 0.02, 0);
		}
	}
}

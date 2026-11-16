package ar.tod5.menu;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * La gravedad de cada jugador (como en Mario Galaxy), del lado del mod.
 *
 * Arriba / abajo: el server cambia el atributo de gravedad (negativo = cae para arriba) y Minecraft ya mueve al jugador solo.
 * Aca se da vuelta la camara, el mouse, las teclas de costado, y el techo cuenta como piso.
 *
 * A los costados (este, oeste, sur, norte): Minecraft no tiene eso, asi que lo hace todo el mod.
 *  - "marco": un giro de 90 grados que lleva el "abajo" normal hacia la pared. El jugador mira, camina y salta dentro de ese marco
 *    (el yaw y el pitch del jugador son "del marco"; al server se le mandan pasados al mundo, asi los arcos, perlas, etc. salen bien).
 *  - la caja del jugador queda acostada (1,8 de largo hacia la pared, 0,6 de ancho). El server usa una caja chica de 0,6 que queda adentro
 *    de esta, asi lo que el mod deja moverse, el server tambien lo acepta.
 *  - el movimiento (caminar, caer, saltar, volar en creativo) se calcula aca, en el marco.
 *  - al cambiar, el jugador gira alrededor del centro de su cuerpo (no de los pies), y si quedaria metido en un bloque se corre.
 * El server avisa (canal tod5:gravedad: "uuid;direccion") quien tiene la gravedad de costado.
 */
public final class GravedadCliente {
	private GravedadCliente() {}

	public enum G {
		ABAJO(0, -1, 0), ARRIBA(0, 1, 0), ESTE(1, 0, 0), OESTE(-1, 0, 0), SUR(0, 0, 1), NORTE(0, 0, -1);
		public final int x, y, z;
		G(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
		public boolean costado() { return this.y == 0; }
		static G de(String s) { for (G g : values()) if (g.name().equalsIgnoreCase(s)) return g; return null; }
	}

	private static final Map<UUID, G> COSTADOS = new HashMap<>();
	private static G antes = G.ABAJO, ahora = G.ABAJO;   // del jugador propio: de donde viene y a donde va el giro de la camara
	private static float t = 1f, tAnt = 1f;               // 0 = recien cambio, 1 = termino de girar (medio segundo)
	private static final float PI = (float) Math.PI;

	// ================= quien tiene que gravedad =================
	private static boolean atributoNegativo(Entity e) {
		return e instanceof LivingEntity le && le.getAttributeValue(Attributes.GRAVITY) < 0;
	}
	/** Tiene la gravedad para arriba (se ve cabeza abajo). */
	public static boolean invertida(Entity e) { return atributoNegativo(e) && !costado(e); }
	/** Tiene la gravedad de costado. */
	public static boolean costado(Entity e) { return e instanceof Player && COSTADOS.containsKey(e.getUUID()); }
	public static G de(Entity e) {
		if (e instanceof Player) { G c = COSTADOS.get(e.getUUID()); if (c != null) return c; }
		return atributoNegativo(e) ? G.ARRIBA : G.ABAJO;
	}

	/** Llega del server: "uuid;direccion". */
	public static void recibir(String texto) {
		String[] p = texto.split(";");
		if (p.length < 2) return;
		G g = G.de(p[1]);
		if (g == null) return;
		UUID u;
		try { u = UUID.fromString(p[0]); } catch (IllegalArgumentException e) { return; }
		Minecraft mc = Minecraft.getInstance();
		Player quien = mc.level == null ? null : mc.level.getPlayerByUUID(u);
		G vieja = quien == null ? null : de(quien);
		Vec3 centro = vieja != null && quien == mc.player ? centro(quien.position(), vieja, quien.getBbWidth(), quien.getBbHeight()) : null;
		if (g.costado()) COSTADOS.put(u, g); else COSTADOS.remove(u);
		if (quien == null) return;
		quien.refreshDimensions();
		if (centro != null && (vieja.costado() || g.costado()) && vieja != g) reubicar((LocalPlayer) quien, centro, g);
	}
	public static void limpiar() { COSTADOS.clear(); antes = ahora = G.ABAJO; t = tAnt = 1f; }

	/** Gira alrededor del centro del cuerpo; si queda metido en algo, lo corre a lo largo de su nuevo "arriba". */
	private static void reubicar(LocalPlayer p, Vec3 centro, G g) {
		double w = p.getBbWidth(), h = p.getBbHeight();
		Vec3 base = desdeCentro(centro, g, w, h);
		Vec3 arriba = new Vec3(-g.x, -g.y, -g.z);
		Vec3 elegido = base;
		buscar:
		for (int i = 0; i <= 24; i++) {
			for (int signo = 1; signo >= -1; signo -= 2) {
				Vec3 q = base.add(arriba.scale(signo * i * 0.05));
				if (p.level().noCollision(p, caja(q, g, w, h).deflate(1.0E-7))) { elegido = q; break buscar; }
				if (i == 0) break;
			}
		}
		p.setPos(elegido);
		p.xo = p.xOld = elegido.x; p.yo = p.yOld = elegido.y; p.zo = p.zOld = elegido.z;
		p.fallDistance = 0;
	}

	// ================= la caja y los ojos =================
	/** La caja acostada (de costado): los pies contra la pared, 0,6 de ancho. Incluye la caja chica de 0,6 que usa el server. */
	public static AABB caja(Vec3 pos, G g, double w, double alto) {
		double h = w / 2, cx = pos.x, cy = pos.y + h, cz = pos.z;
		if (g.x != 0) {
			double pies = cx + g.x * h, cabeza = pies - g.x * alto;
			return new AABB(Math.min(pies, cabeza), cy - h, cz - h, Math.max(pies, cabeza), cy + h, cz + h);
		}
		double pies = cz + g.z * h, cabeza = pies - g.z * alto;
		return new AABB(cx - h, cy - h, Math.min(pies, cabeza), cx + h, cy + h, Math.max(pies, cabeza));
	}
	static Vec3 centro(Vec3 pos, G g, double w, double alto) {
		if (!g.costado()) return pos.add(0, alto / 2, 0);
		return pos.add(g.x * (w / 2 - alto / 2), w / 2, g.z * (w / 2 - alto / 2));
	}
	static Vec3 desdeCentro(Vec3 c, G g, double w, double alto) {
		if (!g.costado()) return c.add(0, -alto / 2, 0);
		return c.add(-g.x * (w / 2 - alto / 2), -w / 2, -g.z * (w / 2 - alto / 2));
	}
	/** Los ojos del jugador propio (para la camara y para lo que apunta la mira), con el giro a medio camino. */
	public static Vec3 ojos(LivingEntity e, float parcial) {
		Vec3 pos = new Vec3(Mth.lerp(parcial, e.xo, e.getX()), Mth.lerp(parcial, e.yo, e.getY()), Mth.lerp(parcial, e.zo, e.getZ()));
		double w = e.getBbWidth(), alto = e.getBbHeight(), ojo = e.getEyeHeight() - alto / 2;
		Vec3 c = centro(pos, de(e), w, alto);
		float s = suave(parcial);
		double ux = Mth.lerp(s, -antes.x, -ahora.x), uy = Mth.lerp(s, -antes.y, -ahora.y), uz = Mth.lerp(s, -antes.z, -ahora.z);
		return c.add(ux * ojo, uy * ojo, uz * ojo);
	}

	/** Los ojos de cualquier jugador (para las cajas de F3+B): el propio con el giro a medio camino; los otros, ya girados. */
	public static Vec3 ojosDe(Player e, float parcial) {
		if (e == Minecraft.getInstance().player) return ojos(e, parcial);
		G g = de(e);
		Vec3 pos = e.getPosition(parcial);
		double alto = e.getBbHeight(), ojo = e.getEyeHeight() - alto / 2;
		return centro(pos, g, e.getBbWidth(), alto).add(-g.x * ojo, -g.y * ojo, -g.z * ojo);
	}
	/** El "plano de los ojos" de F3+B (la rayita roja): cruzando la caja, a la altura de los ojos, de costado al piso de cada uno. */
	public static AABB planoOjos(AABB caja, G g, Vec3 ojo) {
		if (g.x != 0) return new AABB(ojo.x - 0.01, caja.minY, caja.minZ, ojo.x + 0.01, caja.maxY, caja.maxZ);
		if (g.z != 0) return new AABB(caja.minX, caja.minY, ojo.z - 0.01, caja.maxX, caja.maxY, ojo.z + 0.01);
		return new AABB(caja.minX, ojo.y - 0.01, caja.minZ, caja.maxX, ojo.y + 0.01, caja.maxZ);
	}

	// ================= agachado en el borde =================
	/**
	 * Agachado no te caes del borde, como en Minecraft, pero mirando el piso de cada uno (el techo con la gravedad para arriba, la pared de costado).
	 * null = gravedad normal (lo hace Minecraft).
	 */
	public static Vec3 borde(Player p, Vec3 mov) {
		G g = de(p);
		if (g == G.ABAJO) return null;
		if (p.getAbilities().flying || !p.isShiftKeyDown() || !p.onGround()) return mov;
		if (-(mov.x * g.x + mov.y * g.y + mov.z * g.z) > 0) return mov;   // se esta alejando del piso (salto)
		int ga = g.x != 0 ? 0 : g.y != 0 ? 1 : 2, ea = ga == 0 ? 1 : 0, eb = ga == 2 ? 1 : 2;
		double[] m = { mov.x, mov.y, mov.z };
		double d = m[ea], e = m[eb], h = Math.signum(d) * 0.05, i = Math.signum(e) * 0.05;
		while (d != 0.0 && puedeCaer(p, g, ga, ea, eb, d, 0.0)) { if (Math.abs(d) <= 0.05) { d = 0.0; break; } d -= h; }
		while (e != 0.0 && puedeCaer(p, g, ga, ea, eb, 0.0, e)) { if (Math.abs(e) <= 0.05) { e = 0.0; break; } e -= i; }
		while (d != 0.0 && e != 0.0 && puedeCaer(p, g, ga, ea, eb, d, e)) {
			if (Math.abs(d) <= 0.05) d = 0.0; else d -= h;
			if (Math.abs(e) <= 0.05) e = 0.0; else e -= i;
		}
		m[ea] = d; m[eb] = e;
		return new Vec3(m[0], m[1], m[2]);
	}
	/** Si se corre (d, e) sobre el piso, abajo de los pies (para su "abajo") no hay nada en 0,6 bloques. */
	private static boolean puedeCaer(Player p, G g, int ga, int ea, int eb, double d, double e) {
		AABB b = p.getBoundingBox();
		double[] mn = { b.minX, b.minY, b.minZ }, mx = { b.maxX, b.maxY, b.maxZ };
		mn[ea] += d + 1.0E-7; mx[ea] += d - 1.0E-7;
		mn[eb] += e + 1.0E-7; mx[eb] += e - 1.0E-7;
		if (g.x + g.y + g.z > 0) { mn[ga] = mx[ga]; mx[ga] += 0.6 + 1.0E-7; }
		else { mx[ga] = mn[ga]; mn[ga] -= 0.6 + 1.0E-7; }
		return p.level().noCollision(p, new AABB(mn[0], mn[1], mn[2], mx[0], mx[1], mx[2]));
	}

	// ================= la camara =================
	public static void tick(Minecraft mc) {
		G real = mc.player == null ? G.ABAJO : de(mc.player);
		if (real != ahora) { antes = ahora; ahora = real; t = tAnt = 0f; }
		else { tAnt = t; t = Math.min(1f, t + 0.1f); }
	}
	private static float suave(float parcial) {
		float x = Mth.lerp(parcial, tAnt, t);
		return x * x * (3 - 2 * x);
	}
	/** La camara no se toca (todo normal y quieto). */
	public static boolean normal() { return antes == G.ABAJO && ahora == G.ABAJO; }
	/** Cuanto esta dado vuelta (0 a 1): para el mouse y las teclas con la gravedad para arriba. */
	public static float giro(float parcial) {
		float s = suave(parcial);
		return Mth.lerp(s, antes == G.ARRIBA ? 1f : 0f, ahora == G.ARRIBA ? 1f : 0f);
	}
	/** El mouse y las teclas de costado van al reves (solo con la gravedad para arriba, a partir de la mitad del giro). */
	public static boolean controlesDadosVuelta() { return giro(1f) > 0.5f; }

	public static Quaternionf marcoDe(G g) { return marco(g); }
	static Quaternionf marco(G g) {
		return switch (g) {
			case ESTE -> new Quaternionf().rotationZ(PI / 2);
			case OESTE -> new Quaternionf().rotationZ(-PI / 2);
			case SUR -> new Quaternionf().rotationX(-PI / 2);
			case NORTE -> new Quaternionf().rotationX(PI / 2);
			default -> new Quaternionf();
		};
	}
	private static Quaternionf rot(G g, float yaw, float pitch) {
		float r = PI / 180f;
		Quaternionf q = new Quaternionf().rotationYXZ(PI - yaw * r, -pitch * r, g == G.ARRIBA ? PI : 0f);
		return g.costado() ? marco(g).mul(q) : q;
	}
	/** Hacia donde mira la camara, con el giro a medio camino. */
	public static Quaternionf camara(float yaw, float pitch, float parcial) {
		return rot(antes, yaw, pitch).slerp(rot(ahora, yaw, pitch), suave(parcial));
	}

	// ================= mirar (marco <-> mundo) =================
	private static Vec3 vista(float pitch, float yaw) {
		float h = pitch * PI / 180f, i = -yaw * PI / 180f;
		return new Vec3(Mth.sin(i) * Mth.cos(h), -Mth.sin(h), Mth.cos(i) * Mth.cos(h));
	}
	private static float[] angulos(Vector3f v) {
		float yaw = (float) Math.toDegrees(Math.atan2(-v.x, v.z));
		float pitch = (float) Math.toDegrees(Math.asin(Mth.clamp(-v.y, -1f, 1f)));
		return new float[] { yaw, pitch };
	}
	/** A donde mira de verdad (en el mundo) alguien de costado, con su yaw/pitch del marco. */
	public static Vec3 mirada(G g, float pitch, float yaw) {
		Vec3 v = vista(pitch, yaw);
		if (!g.costado()) return v;
		Vector3f w = marco(g).transform(new Vector3f((float) v.x, (float) v.y, (float) v.z));
		return new Vec3(w.x, w.y, w.z);
	}
	/** yaw/pitch del marco -> del mundo (lo que se le manda al server). */
	public static float[] aMundo(G g, float yaw, float pitch) {
		Vec3 v = mirada(g, pitch, yaw);
		return angulos(new Vector3f((float) v.x, (float) v.y, (float) v.z));
	}
	/** yaw/pitch del mundo -> del marco (para dibujar a los otros jugadores de costado). */
	public static float[] aMarco(G g, float yaw, float pitch) {
		Vec3 v = vista(pitch, yaw);
		Vector3f l = marco(g).conjugate().transform(new Vector3f((float) v.x, (float) v.y, (float) v.z));
		return angulos(l);
	}
	/** La pose de alguien de costado: parado (o agachado si aprieta shift); nunca nadando ni gateando. */
	public static Pose pose(Player p) {
		return p.isShiftKeyDown() && !p.getAbilities().flying ? Pose.CROUCHING : Pose.STANDING;
	}

	// ================= moverse de costado =================
	private static Vector3f v3(Vec3 v) { return new Vector3f((float) v.x, (float) v.y, (float) v.z); }
	private static Vec3 v3(Vector3f v) { return new Vec3(v.x, v.y, v.z); }

	/** El "travel" de Minecraft, pero dentro del marco. Devuelve false si no le toca (y se usa el normal). */
	public static boolean viajar(Player yo, Vec3 entrada) {
		if (!(yo instanceof LocalPlayer p) || p.isPassenger() || p.isSpectator()) return false;
		G g = de(p);
		if (!g.costado()) return false;
		Quaternionf m = marco(g), mi = new Quaternionf(m).conjugate();
		Vector3f v = mi.transform(v3(p.getDeltaMovement()));
		Abilities hab = p.getAbilities();
		boolean volando = hab.flying;
		if (volando) {
			// Minecraft ya empujo para arriba/abajo del mundo con espacio/shift: lo saco y lo empujo para arriba/abajo del marco
			Input k = p.input.keyPresses;
			int i = (k.jump() ? 1 : 0) - (k.shift() ? 1 : 0);
			if (i != 0) {
				float empuje = i * hab.getFlyingSpeed() * 3.0F;
				v.sub(mi.transform(new Vector3f(0, empuje, 0)));
				v.y += empuje;
			}
		}
		boolean agua = p.isInWater(), lava = p.isInLava();
		Vec3 pos = p.position();
		double w = p.getBbWidth();
		Vec3 pies = pos.add(g.x * w / 2, w / 2, g.z * w / 2);
		BlockPos debajo = BlockPos.containing(pies.add(g.x * 0.5, 0, g.z * 0.5));
		float friccion = p.onGround() ? p.level().getBlockState(debajo).getBlock().getFriction() : 1.0F;
		float vel;
		if (volando) vel = p.isSprinting() ? hab.getFlyingSpeed() * 2 : hab.getFlyingSpeed();
		else if (agua || lava) vel = 0.02F;
		else if (p.onGround()) vel = p.getSpeed() * (0.21600002F / (friccion * friccion * friccion));
		else vel = p.isSprinting() ? 0.025999999F : 0.02F;
		// lo que apretas (adelante/costados), girado por el yaw del marco
		double largo = entrada.lengthSqr();
		if (largo > 1.0E-7) {
			Vec3 in = (largo > 1.0 ? entrada.normalize() : entrada).scale(vel);
			float s = Mth.sin(p.getYRot() * PI / 180f), c = Mth.cos(p.getYRot() * PI / 180f);
			v.x += (float) (in.x * c - in.z * s);
			v.z += (float) (in.z * c + in.x * s);
			v.y += (float) in.y;
		}
		float vyAntes = v.y;
		Vec3 pedido = v3(m.transform(new Vector3f(v)));
		p.setDeltaMovement(pedido);
		p.move(MoverType.SELF, pedido);
		Vec3 hecho = p.position().subtract(pos);
		// choques: lo que no se pudo mover en cada eje del mundo, pasado al marco
		boolean choqueX = Math.abs(pedido.x - hecho.x) > 1.0E-5, choqueY = Math.abs(pedido.y - hecho.y) > 1.0E-5, choqueZ = Math.abs(pedido.z - hecho.z) > 1.0E-5;
		boolean choquePiso = g.x != 0 ? choqueX : choqueZ;
		p.horizontalCollision = choqueY || (g.x != 0 ? choqueZ : choqueX);
		p.minorHorizontalCollision = false;
		p.verticalCollision = choquePiso;
		boolean suelo = !p.level().noCollision(p, p.getBoundingBox().move(g.x * 0.01, 0, g.z * 0.01));
		p.setOnGround(suelo);
		if (suelo) p.fallDistance = 0;
		v = mi.transform(v3(p.getDeltaMovement()));
		if (volando) {
			v.x *= 0.91F; v.z *= 0.91F; v.y = vyAntes * 0.6F;
		} else if (agua) {
			v.mul(0.8F); v.y -= 0.005F;
		} else if (lava) {
			v.mul(0.5F); v.y -= 0.02F;
		} else {
			float caida = v.y <= 0 && p.hasEffect(MobEffects.SLOW_FALLING) ? 0.01F : 0.08F;
			v.y -= caida;
			float f = friccion * 0.91F;
			v.x *= f; v.z *= f; v.y *= 0.98F;
		}
		p.setDeltaMovement(v3(m.transform(v)));
		return true;
	}

	/** Saltar de costado: para "arriba" del marco. Devuelve false si no le toca. */
	public static boolean saltar(LivingEntity yo, float fuerza) {
		if (!(yo instanceof LocalPlayer p)) return false;
		G g = de(p);
		if (!g.costado()) return false;
		if (fuerza <= 1.0E-5F) return true;
		Quaternionf m = marco(g);
		Vector3f v = new Quaternionf(m).conjugate().transform(v3(p.getDeltaMovement()));
		v.y = Math.max(fuerza, v.y);
		if (p.isSprinting()) {
			float r = p.getYRot() * PI / 180f;
			v.x += -Mth.sin(r) * 0.2F;
			v.z += Mth.cos(r) * 0.2F;
		}
		p.setDeltaMovement(v3(m.transform(v)));
		return true;
	}
}

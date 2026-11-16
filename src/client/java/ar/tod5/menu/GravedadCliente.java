package ar.tod5.menu;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * La gravedad dada vuelta (como en Mario Galaxy), del lado del jugador.
 *
 * El server cambia el atributo de gravedad del jugador (negativo = cae para arriba) y Minecraft ya mueve al jugador solo.
 * Aca se hace lo que falta para que se juegue bien:
 *  - la camara gira 180 grados de a poco (medio segundo) y baja a donde quedan los ojos cuando estas en el techo
 *  - el mouse y las teclas de costado se dan vuelta (asi "izquierda" sigue siendo izquierda en la pantalla)
 *  - el techo cuenta como piso: se puede caminar normal y saltar (para abajo)
 *  - los jugadores con la gravedad dada vuelta se ven cabeza abajo (para todos)
 */
public final class GravedadCliente {
	private GravedadCliente() {}
	private static float giro, giroAnt;   // 0 = normal, 1 = dado vuelta

	/** Esta entidad tiene la gravedad para arriba. */
	public static boolean invertida(Entity e) {
		return e instanceof LivingEntity le && le.getAttributeValue(Attributes.GRAVITY) < 0;
	}

	public static void tick(Minecraft mc) {
		giroAnt = giro;
		float obj = mc.player != null && invertida(mc.player) ? 1f : 0f;
		giro += Mth.clamp(obj - giro, -0.1f, 0.1f);
	}

	/** Cuanto esta girada la camara del jugador (0 a 1, suavizado). */
	public static float giro(float parcial) {
		float t = Mth.lerp(parcial, giroAnt, giro);
		return t * t * (3 - 2 * t);
	}

	/** El mouse y las teclas de costado van al reves (a partir de la mitad del giro). */
	public static boolean controlesDadosVuelta() { return giro > 0.5f; }

	/** Cuanto se corren los ojos cuando estas en el techo: quedan cerca del borde de abajo de tu caja, no del de arriba. */
	public static double bajarOjos(LivingEntity e, float parcial) {
		return (e.getBbHeight() - 2 * e.getEyeHeight()) * giro(parcial);
	}
}

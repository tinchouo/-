package ar.tod4.cambios;

import java.util.ArrayList;
import java.util.List;

import ar.tod4.TOD4;
import ar.tod4.util.Ancho;

/**
 * Los mensajes de los cambios de dificultad (y los avisos buenos), cargados una sola vez y vinculados a su cambio.
 * Los que ya existen con ese nombre no se tocan. En el texto, lo que va entre *asteriscos* sale en el color fuerte.
 * Cada parrafo se parte en renglones del largo de la linea de arriba y de abajo del mensaje.
 */
final class AvisosCambios {
    private AvisosCambios() {}

    private static final String DIF = "⚠ CAMBIO DE DIFICULTAD ⚠";
    private static final String AVI = "✦ AVISO ✦";
    private static final String DUN = "✦ NUEVA DUNGEON ✦";
    private static final String PODER_1 = "Los encantamientos *Poder* e *Infinidad* ahora también se pueden usar en la *ballesta*.";
    private static final String PODER_2 = "*Poder* aumenta su daño e *Infinidad* hace que no gaste *flechas comunes*. Algunos mobs con ballesta pueden tener Poder.";
    /** El texto que tenia antes (sin Infinidad): si nadie lo edito, se cambia por el nuevo. */
    private static final String NOCHES_VIEJO = "Ya no se puede *saltar la noche* durmiendo.";
    private static final String NOCHES = "Las *noches* ahora duran *el triple*.";
    private static final String[] BALLESTEROS_VIEJO = {"Aparecieron los *esqueletos ballesteros*, con mucha más vida y una *ballesta*.",
            "Los *rojos* disparan flechas con *Poder V* y de cerca te golpean, los *amarillos* lanzan *fuegos artificiales* que explotan y los *negros* disparan *TNT* con su arco."};
    private static final String[] BALLESTEROS = {"Aparecieron los *esqueletos ballesteros*, con mucha más vida y siempre en movimiento.",
            "El *rojo* dispara flechas con *Poder V* y de cerca te golpea, el *esqueleto de hielo* lanza *fuegos artificiales* que explotan y el *esqueleto wither* dispara *TNT* con su arco."};
    private static final String[] XP_VIEJO = {"Los *mobs hostiles* ahora sueltan *el doble de experiencia* al morir a manos de un jugador.",
            "No cuenta para los mobs de *spawners*."};
    private static final String[] XP = {"Los *mobs hostiles* y los *minerales* ahora dan *el doble de experiencia*.",
            "Cuentan los mobs que mata un jugador (no los de *spawners*) y los minerales del Overworld (el *cuarzo* no)."};
    private static final String[] NOCHES_P_VIEJO = {"Las *noches* ahora son peligrosas: aparecen *el doble de mobs*.",
            "Además, de noche los mobs hostiles hacen *50% más de daño*."};
    private static final String[] NOCHES_P = {"Las *noches* ahora son peligrosas.",
            "En el Overworld, de noche, los mobs hostiles hacen *el doble de daño*."};
    private static final String TOTEM_VIEJO = "Cuando un *tótem de inmortalidad* te salva, quedas *ciego por 3 segundos*.";
    private static final String TOTEM = "Cuando un *tótem de inmortalidad* te salva, quedas *ciego por 2 segundos*.";
    private static final String[] PODER_VIEJO = {"El encantamiento *Poder* ahora también se puede usar en la *ballesta* y aumenta su daño.",
            "Algunos mobs con ballesta pueden tenerlo."};


    /** {id del mensaje, id del cambio (o ""), dia, "d" dificultad | "a" aviso | "n" nueva dungeon, parrafos...} */
    private static final String[][] M = {
        {"cambio_hornos", "d1.hornos", "1", "d",
            "Los *hornos comunes* empezaron a fallar. Ahora tienen una pequeña posibilidad de *explotar* al usarlos.",
            "Si explotan, se pierden *todos los ítems* que estaban dentro."},
        {"cambio_warden", "d1.warden", "1", "d",
            "Los *Wardens* aparecen de forma natural en las *Ancient City*.",
            "Ya no hace falta hacer ruido para despertarlos."},
        {"cambio_aldeanos", "d1.aldeanos", "1", "d",
            "Los *aldeanos* ya no se generan de forma natural.",
            "Las aldeas quedaron *totalmente despobladas*."},
        {"cambio_totems", "d1.totems", "1", "d",
            "Los *invocadores* no sueltan *tótem de inmortalidad* al morir."},
        {"cambio_nether", "d1.nether", "1", "d",
            "El *calor del Nether* subió repentinamente, de tal manera que ningún ser humano puede soportarlo."},
        {"cambio_reproduccion", "d2.reproduccion", "2", "d",
            "Los *aldeanos* se volvieron *estériles*. Ya no pueden reproducirse."},
        {"cambio_mending", "", "2", "d",
            "Los *aldeanos bibliotecarios* ya no venden *Reparación*. Se volvió demasiado valiosa."},
        {"cambio_hojas", "d2.saplings", "2", "d",
            "Las *hojas* ya no sueltan *brotes* ni *manzanas*. Los árboles se han secado."},
        {"cambio_silverfish", "d2.silverfish", "2", "d",
            "Al picar *por debajo de la capa 0* puede aparecer un *Silverfish*.",
            "Su mordedura provoca *fatiga minera*."},
        {"aviso_hornos_especiales", "d2.hornos", "3", "a",
            "El *alto horno* y el *ahumador* ahora pueden cocinar cosas que antes no.",
            "*Alto horno:* arena, piedra, basalto, arenisca y terracotas. *Ahumador:* cactus, pepino de mar, chorus, troncos y esponja."},
        {"cambio_leche", "d3.leche", "3", "d",
            "Las *vacas* ya no dan *leche*."},
        {"cambio_lava_blaze", "d3.lava", "3", "d",
            "La *resistencia al fuego* ya no protege de la *lava* ni de las *bolas de fuego* de los *Blazes*."},
        {"aviso_mesa", "d4.mesa", "1", "a",
            "La *mesa de encantamientos* desbloquea un nuevo nivel. Con *30 estanterías* aparece un *nivel 40* con encantamientos mucho mejores.",
            "Además, hay una pequeña posibilidad de obtener *Reparación* al encantar libros."},
        {"cambio_ahogo", "d4.agua", "4", "d",
            "Ahora te *ahogas 5 veces más rápido*."},
        {"cambio_puertas", "d4.puertas", "4", "d",
            "Interactuar con *botones* o *puertas* te hace *10.000 de daño*."},
        {"cambio_crianza", "d4.crianza", "4", "d",
            "Todos los *animales* se volvieron *estériles*."},
        {"cambio_perlas", "d4.perla", "4", "d",
            "Las *perlas de ender* ahora quitan *4 corazones* al utilizarse."},
        {"cambio_wither", "d5.wither", "5", "d",
            "Para invocar al *Wither* ahora hace falta colocar la *cabeza de un jugador muerto* en el pecho mientras se hace el ritual."},
        {"cambio_creepers", "", "5", "d",
            "Para festejar el *Día Mundial del Creeper* se descubrieron *6 nuevos tipos de creeper*:",
            "el *Random Creeper*, el *Mini Creeper*, el *Creeper Calamar*, el *Void Creeper*, el *Creeper Nuclear* y el *Flash Creeper*.",
            "Además, los creepers normales ahora son *eléctricos*."},
        {"cambio_poder", "d2.poder", "2", "d", PODER_1, PODER_2},
        {"aviso_equipos", "", "2", "a",
            "Las *tumbas* de los jugadores que tienen *equipo* ahora solo pueden abrirlas o romperlas *su dueño y su equipo*.",
            "Si el jugador no tiene equipo, cualquiera puede abrirla, como siempre. Además, las tumbas ya no se rompen con *explosiones*."},
        {"aviso_bunker", "", "2", "n",
            "Tras derrotar al *viejo amigo de Trick*, este nos dio una *llave*, la cual nos dejó acceder a su *búnker secreto*.",
            "Una *nueva dungeon* repleta de *cofres* espera en su interior."},
        {"cambio_caida", "d6.caida", "6", "d",
            "La *protección de la armadura* ya no reduce el daño por *caída*."},
        {"cambio_noches", "d6.noches", "6", "d", NOCHES},
        {"cambio_phantoms", "d6.phantoms", "6", "d",
            "Los *Phantoms* ya no se queman con la *luz del sol*."},
        {"cambio_aire", "d7.aire", "7", "d",
            "En el *Aether* el aire ahora se acaba: a los *5 minutos* empiezas a *asfixiarte*.",
            "Para respirar ahí necesitas el *Casco de Oxígeno*. Cada carga dura *30 minutos* y se recarga con un *bloque de amatista*."},
        {"cambio_creeper_muerte", "d7.creeper_muerte", "7", "d",
            "Al morir, hay un *10%* de probabilidad de que aparezca un *creeper eléctrico* en el lugar de tu muerte."},
        {"cambio_pacificos", "d8.pacificos", "9", "d",
            "Los *animales* se volvieron agresivos. Te persiguen, te atacan y, si se acercan lo suficiente, te dejan una *TNT*."},
        {"cambio_ballesteros", "d10.ballesteros", "10", "d", BALLESTEROS[0], BALLESTEROS[1]},
        {"cambio_zombies", "d9.zombies", "9", "d",
            "Los *zombies* ahora aparecen con *armadura* de hierro o diamante, muchas veces con *Protección IV*.",
            "Además, siempre llevan una *espada*, que puede tener hasta *Filo V*."},
        {"cambio_nuclear_muerte", "d11.nuclear_muerte", "11", "d",
            "Al morir, hay un *1%* de probabilidad de que aparezca un *Creeper Nuclear* en el lugar de tu muerte."},
        // ---- dias 7 a 14 (nuevos)
        {"cambio_plantas", "d7.plantas", "7", "d",
            "Todas las *plantas* y *cultivos* ahora crecen *3 veces más lento*.",
            "Toca planificar mejor las granjas."},
        {"aviso_xp", "d7.xp", "7", "a", XP[0], XP[1]},
        {"cambio_wither_x2", "d8.wither", "9", "d",
            "El *Wither* ahora es *el doble de fuerte*: tiene el doble de vida, pega el doble, dispara más seguido y se mueve más rápido.",
            "Su efecto de *Wither* es más fuerte y dura más."},
        {"cambio_equipo", "d8.equipo", "10", "d",
            "Por cada *compañero de equipo muerto* que no fue revivido, recibes un *5% más de daño*.",
            "Lo que tienes de más se ve al lado de tu equipo, en la barra de la derecha."},
        {"cambio_hueso", "d9.hueso", "9", "d",
            "El *polvo de hueso* dejó de funcionar.",
            "Ya no hace crecer ningún cultivo, planta ni árbol."},
        {"cambio_esqueletos", "d9.esqueletos", "9", "d",
            "Los *esqueletos* ahora aparecen con *armadura* y un *arco con Poder*.",
            "Sus flechas pueden ser de *daño instantáneo*, *levitación*, *ceguera* o *lentitud*."},
        {"cambio_solo_uhc", "d10.solo_uhc", "10", "d",
            "Si no tienes *equipo* (o estás solo en el tuyo), *ya no regeneras vida*.",
            "Solo te curan las *manzanas doradas* y las *pociones de curación* y *regeneración*. Con un compañero no te afecta."},
        {"cambio_solo_corazones", "d10.solo_corazones", "10", "d",
            "Si no tienes *equipo*, *no puedes usar Corazones Rojos* y los que ya tenías *no cuentan*: tu vida vuelve a *10 corazones*.",
            "Te los devolvemos apenas entres a un equipo con al menos un compañero."},
        {"cambio_minero", "d10.minero", "10", "d",
            "Apareció el *Zombie Minero*: te ve a través de los bloques y *cava* hasta llegar a ti.",
            "Cuando está cerca, pone una *TNT* el doble de fuerte. Cuanto más profundo estés, más aparecen."},
        {"cambio_herramientas", "d8.herramientas", "8", "d",
            "Por culpa de *Misovia_*, ahora romper cualquier bloque de *pico* o de *hacha* te saca *medio corazón*.",
            "Solo el *TOD Pickaxe* y el *TOD Axe* te salvan de eso."},
        {"cambio_backrooms", "d13.backrooms", "13", "d",
            "Dormir ya no es tan seguro: al acostarte hay un *5%* de probabilidad de despertar en las *backrooms*."},
        {"cambio_totem_falla", "d14.totem_falla", "14", "d",
            "Los *tótems* ya no son infalibles: hay un *1%* de probabilidad de que *no te salven*."},
        {"cambio_hambre", "d11.hambre", "11", "d",
            "El *hambre* ahora baja *el doble de rápido*.",
            "Lleva siempre comida encima."},
        {"cambio_noches_peligrosas", "d12.noches_peligrosas", "12", "d", NOCHES_P[0], NOCHES_P[1]},
        {"cambio_totem", "d14.totem", "14", "d",
            TOTEM},
        {"cambio_bedrock", "d6.bedrock", "6", "d",
            "Tocar *bedrock* te da *Wither III* y *desgasta la armadura*."},
    };

    private static List<String> armar(String[] parrafos, int desde, int max) {
        List<String> lineas = new ArrayList<>();
        for (int i = desde; i < parrafos.length; i++) {
            if (i > desde) lineas.add(".");
            lineas.addAll(envolver(parrafos[i], max));
        }
        return lineas;
    }

    static void sembrar(TOD4 pl, Cambios cb) {
        // el largo de la linea de arriba y de abajo del mensaje (40 guiones en negrita) menos la sangria de 3 espacios
        int max = Ancho.de("&l" + "-".repeat(40)) - Ancho.de("   ") - 4;
        boolean nuevo = false;
        for (String[] m : M) {
            List<String> lineas = armar(m, 4, max);
            // naranja (Cambio de dificultad), celeste (Avisos) o azul (Nueva dungeon)
            int paleta = switch (m[3]) { case "d" -> 2; case "n" -> 5; default -> 6; };
            String titulo = switch (m[3]) { case "d" -> DIF; case "n" -> DUN; default -> AVI; };
            int sonido = switch (m[3]) { case "d" -> 4; case "n" -> 6; default -> 7; };
            if (pl.avisos().sembrar(m[0], paleta, titulo, sonido, Integer.parseInt(m[2]), lineas)) {
                nuevo = true;
                if (!m[1].isEmpty()) cb.vincular(m[1], m[0]);
            }
        }
        if (nuevo) pl.getLogger().info("Mensajes de los cambios cargados (nombres cambio_* y aviso_*).");
        pl.avisos().cambiarDia("cambio_warden", 4, 1);   // el Warden vuelve al dia 1
        pl.avisos().cambiarDia("aviso_mesa", 4, 1);      // la mesa de encantamientos pasa al dia 1
        pl.avisos().cambiarTexto("cambio_noches", armar(new String[]{NOCHES_VIEJO}, 0, max), armar(new String[]{NOCHES}, 0, max));   // ahora duran el triple
        pl.avisos().cambiarTexto("cambio_poder", armar(PODER_VIEJO, 0, max), armar(new String[]{PODER_1, PODER_2}, 0, max));   // + Infinidad
        pl.avisos().cambiarTexto("cambio_ballesteros", armar(BALLESTEROS_VIEJO, 0, max), armar(BALLESTEROS, 0, max));   // hielo y wither
        pl.avisos().cambiarDia("cambio_nuclear_muerte", 12, 11);
        pl.avisos().cambiarDia("cambio_pacificos", 8, 9);     // el dia 8 queda solo para "Solo herramientas TOD"
        pl.avisos().cambiarDia("cambio_wither_x2", 8, 9);
        pl.avisos().cambiarDia("cambio_equipo", 8, 10);
        pl.avisos().cambiarTexto("aviso_xp", armar(XP_VIEJO, 0, max), armar(XP, 0, max));   // + minerales y MythicMobs
        pl.avisos().cambiarTexto("cambio_noches_peligrosas", armar(NOCHES_P_VIEJO, 0, max), armar(NOCHES_P, 0, max));   // doble dano, sin mas mobs
        pl.avisos().cambiarTexto("cambio_totem", armar(new String[]{TOTEM_VIEJO}, 0, max), armar(new String[]{TOTEM}, 0, max));   // 2 segundos   // paso al dia 11
        // una sola vez: se borran los mensajes de los dias 1 a 6 y el de los Pigman (los generales no se tocan; quedan guardados en borrados)
        pl.avisos().borrarUnaVez("limpieza_dia6", 1, 6, java.util.List.of("cambio_pigman"));
    }

    /** Parte un parrafo en renglones que no pasen de {@code max} pixeles; un *resaltado* que cruza el renglon se cierra y se reabre. */
    static List<String> envolver(String parrafo, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder linea = new StringBuilder();
        boolean dentro = false;
        for (String w : parrafo.split(" ")) {
            String cand = linea.length() == 0 ? w : linea + " " + w;
            if (linea.length() > 0 && Ancho.de(medida(cand)) > max) {
                out.add(linea + (dentro ? "*" : ""));
                linea = new StringBuilder(dentro ? "*" + w : w);
            } else {
                linea = new StringBuilder(cand);
            }
            for (char c : w.toCharArray()) if (c == '*') dentro = !dentro;
        }
        out.add(linea.toString());
        return out;
    }

    /** El texto como se va a ver: lo resaltado va en negrita. */
    private static String medida(String s) {
        StringBuilder sb = new StringBuilder();
        boolean dentro = false;
        for (char c : s.toCharArray()) {
            if (c == '*') { dentro = !dentro; sb.append(dentro ? "&l" : "&r"); } else sb.append(c);
        }
        return sb.toString();
    }
}

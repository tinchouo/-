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
        {"cambio_noches", "d6.noches", "6", "d",
            "Ya no se puede *saltar la noche* durmiendo."},
        {"cambio_phantoms", "d6.phantoms", "6", "d",
            "Los *Phantoms* ya no se queman con la *luz del sol*."},
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
        pl.avisos().cambiarTexto("cambio_poder", armar(PODER_VIEJO, 0, max), armar(new String[]{PODER_1, PODER_2}, 0, max));   // + Infinidad
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

package ar.tod4.aether;

import org.bukkit.configuration.file.YamlConfiguration;

/** Opciones de plugins/TOD4/aether.yml (si falta alguna, se usa el valor de aca). */
final class Conf {

    String mundo, mundoPrincipal;
    int entrarY, volverY, volverCieloY, sinCaidaSeg;
    int pocionNivel, pocionSeg;
    String musica, musicaIntro;
    double musicaVuelve;
    boolean animales;

    int baseY, maxDev, radioExpansion, colaMax, expansionesPorSeg;
    int giro, rectoMin, costadoMax, derivaMax, chanceGrande, casas, ruina, puenteRoto, medianasMax;
    int azulSeparacion, azulVariacion, azulRamasDesde;
    int ramaChance, ramaSeparacion, ramaVivasMax, ramaGrande, ramaGiro, cobreFinal;
    int especialChance, especialDistMin, malaSuerteMax, malaSuertePaso, especialSeparacion;
    int wVillage, wColiseum, wWindmill;
    int decorCerca, decorLejos, decorVacio;
    String menaId;
    int menaEsquina, menaOtras, ticksBaldosa;

    static Conf de(YamlConfiguration y) {
        Conf c = new Conf();
        c.mundo = y.getString("mundo", "");
        c.mundoPrincipal = y.getString("mundo_principal", "world");
        c.entrarY = y.getInt("entrar_y", 500);
        c.volverY = y.getInt("volver_y", 120);
        c.volverCieloY = y.getInt("volver_cielo_y", 300);
        c.sinCaidaSeg = y.getInt("sin_dano_caida_seg", 30);
        c.pocionNivel = Math.max(1, Math.min(255, y.getInt("pocion.nivel", 50)));
        c.pocionSeg = Math.max(1, y.getInt("pocion.segundos", 30));
        c.musica = y.getString("musica.sonido", "aether:aether.music");
        c.musicaIntro = y.getString("musica.intro", "aether:aether.music_intro");
        c.musicaVuelve = y.getDouble("musica.repetir_cada_seg", 83.92);
        c.animales = y.getBoolean("vida.animales", true);

        c.baseY = y.getInt("mapa.altura_base", 210);
        c.maxDev = y.getInt("mapa.desvio_altura_max", 10);
        c.radioExpansion = y.getInt("mapa.radio_expansion", 380);
        c.colaMax = y.getInt("mapa.cola_max", 10);
        c.expansionesPorSeg = Math.max(1, y.getInt("mapa.expansiones_por_segundo", 2));

        c.giro = y.getInt("caminos.chance_doblar", 20);
        c.rectoMin = y.getInt("caminos.recto_min", 3);
        c.costadoMax = y.getInt("caminos.costado_max", 3);
        c.derivaMax = y.getInt("caminos.deriva_max", 110);
        c.chanceGrande = y.getInt("caminos.chance_isla_grande", 40);
        c.casas = y.getInt("caminos.chance_casas", 20);
        c.ruina = y.getInt("caminos.chance_ruina", 1);
        c.puenteRoto = y.getInt("caminos.chance_puente_roto", 5);
        c.medianasMax = y.getInt("caminos.medianas_seguidas_max", 3);

        c.azulSeparacion = y.getInt("caminos_azules.separacion", 1500);
        c.azulVariacion = y.getInt("caminos_azules.variacion", 100);
        c.azulRamasDesde = y.getInt("caminos_azules.ramas_desde", 700);

        c.ramaChance = y.getInt("ramas.chance", 45);
        c.ramaSeparacion = y.getInt("ramas.separacion", 300);
        c.ramaVivasMax = y.getInt("ramas.vivas_max", 3);
        c.ramaGrande = y.getInt("ramas.chance_isla_grande", 25);
        c.ramaGiro = y.getInt("ramas.chance_doblar", 10);
        c.cobreFinal = y.getInt("ramas.cobre_al_final", 80);

        c.especialChance = y.getInt("estructuras.chance", 40);
        c.especialDistMin = y.getInt("estructuras.distancia_min_spawn", 150);
        c.malaSuerteMax = y.getInt("estructuras.mala_suerte_max", 7);
        c.malaSuertePaso = y.getInt("estructuras.mala_suerte_paso", 12);
        c.especialSeparacion = y.getInt("estructuras.separacion_min", 450);
        c.wVillage = y.getInt("estructuras.peso_village", 36);
        c.wColiseum = y.getInt("estructuras.peso_coliseum", 22);
        c.wWindmill = y.getInt("estructuras.peso_windmill", 20);

        c.decorCerca = y.getInt("decoracion.chance_cerca", 70);
        c.decorLejos = y.getInt("decoracion.chance_lejos", 45);
        c.decorVacio = y.getInt("decoracion.chance_vacio", 35);
        c.menaId = y.getString("mena_truco.oraxen_id", "mineral_truco_ore");
        c.menaEsquina = y.getInt("mena_truco.molino_por_mena", 8);
        c.menaOtras = y.getInt("mena_truco.otras_estructuras_max", 6);
        c.ticksBaldosa = Math.max(1, y.getInt("mapa.ticks_entre_pedazos", 2));
        return c;
    }
}

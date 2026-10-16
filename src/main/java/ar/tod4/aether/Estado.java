package ar.tod4.aether;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Todo lo que el Aether tiene que recordar (se guarda en plugins/TOD4/aether_estado.json.gz). */
final class Estado {

    String mundo;
    boolean listo;
    boolean borrando;
    String alTerminarBorrado;
    int nextId;
    int ntr = 4;
    String force;
    double[] spawn;

    /** Ocupacion del mapa en celdas de 8x8: id de isla (o decoracion), o -(id+1) para el puente que lleva a esa isla. */
    Map<Long, Integer> occ = new HashMap<>();
    /** Puertos abiertos (la "frontera" del mapa), en orden de creacion. */
    LinkedHashMap<Integer, Puerto> puertos = new LinkedHashMap<>();
    /** Camino -> id de su puerto de punta. */
    Map<Integer, Integer> tp = new HashMap<>();
    Map<Integer, Camino> caminos = new HashMap<>();
    /** Estructuras especiales generadas: nombre -> lista de {x, y, z}. */
    Map<String, List<int[]>> loc = new HashMap<>();
    /** Cajas de lo que se pego (para poder borrarlo): {x0, y0, z0, x1, y1, z1}. */
    List<int[]> hechos = new ArrayList<>();
    /** Puentes pegados (para /tod4:aether_test). */
    List<Puente> puentes = new ArrayList<>();
    /** Islitas de relleno que se deciden despues (cuando las ramas de la zona ya crecieron): {x, y, z, chica, skip, turno}. */
    List<int[]> decorPend = new ArrayList<>();
    int expansiones;
    /** Piezas por pegar. */
    List<Trabajo> cola = new ArrayList<>();

    static final class Puerto {
        int id, x, z, y, h, dx, dz;
        boolean rama;
        int t, left, owner, odx, odz, sw;
        boolean big;
        int idx, idz, mc;
        boolean st;
        int fw;
        boolean gate;
        int fails;
        int sgn = 1;
        Integer ccx, ccz;
        /** Lado al que el camino no dobla por unas islas (ahi acaba de salir una rama o un camino azul). */
        int evx, evz, evn;
    }

    static final class Camino {
        int t;
        /** 0 = camino principal (sale del spawn); si no, el principal del que salio (camino azul). */
        int padre;
        int ox, oz, odx, odz;
        int alive, since, lastside, retros, thBase, islas;
        boolean lb;
        int lbx, lbz;
        /** Historial de islas: {cx, cz, cy, hc, id, big, mc} (las ultimas 40). */
        List<int[]> th = new ArrayList<>();
        /** Solo caminos principales: a que distancia del spawn sale cada camino azul. */
        List<Integer> planA = new ArrayList<>();
        int sig;
        /** Caminos azules que ya salieron: {distancia, camino, lateral}. */
        List<int[]> azules = new ArrayList<>();
    }

    static final class Puente {
        String n;
        int x, y, z;
        Puente() { }
        Puente(String n, int x, int y, int z) { this.n = n; this.x = x; this.y = y; this.z = z; }
    }

    /** tipo: P = pieza, E = borrar una caja, R = fin del borrado. */
    static final class Trabajo {
        String tipo = "P";
        String n;
        int x0, y0, z0, x1, y1, z1;
        String rot;
    }
}

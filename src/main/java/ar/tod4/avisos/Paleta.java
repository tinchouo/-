package ar.tod4.avisos;

import org.bukkit.Material;

/**
 * Paleta de la ruleta TOD4.
 *  1 Rojo Momento Truco | 2 Naranja Cambio de dificultad | 3 Amarillo Momento Cuy | 4 Verde Mision diaria
 *  5 Azul Nueva dungeon | 6 Celeste Avisos | 7 Violeta Sanciones | 8/9/10 Blanco y grises (sorpresa: no se ven
 *  en la ruleta hasta que esta por caer ahi).
 */
public enum Paleta {
    ROJO(1, "&cRojo", "Momento Truco", Material.RED_DYE, "&4", "&c", "&c&l", "&c", true, "fca5a5", "7f1d1d", "entity.wither.spawn"),
    NARANJA(2, "&6Naranja", "Cambio de dificultad", Material.ORANGE_DYE, "&6", "&6", "&6&l", "&6", true, "fde68a", "78350f", "block.anvil.land"),
    AMARILLO(3, "&eAmarillo", "Momento Cuy", Material.YELLOW_DYE, "&6", "&e", "&e&l", "&e", true, "fef08a", "854d0e", "entity.player.levelup"),
    VERDE(4, "&aVerde", "Misión diaria", Material.LIME_DYE, "&2", "&a", "&a&l", "&a", true, "a7f3d0", "148836", "ui.toast.challenge_complete"),
    AZUL(5, "&9Azul", "Nueva dungeon", Material.BLUE_DYE, "&1", "&9", "&9&l", "&9", true, "93c5fd", "172554", "block.end_portal.spawn"),
    CELESTE(6, "&bCeleste", "Avisos", Material.LIGHT_BLUE_DYE, "&3", "&b", "&b&l", "&b", true, "a5f3fc", "155e75", "block.note_block.bell"),
    VIOLETA(7, "<#a855f7>Violeta", "Sanciones", Material.PURPLE_DYE, "<#5b21b6>", "<#a855f7>", "<#c084fc>&l", "&5", true, "e9d5ff", "581c87", "block.beacon.deactivate"),
    BLANCO(8, "&fBlanco", "Sorpresa", Material.WHITE_DYE, "&7", "&f", "&f&l", "&f", false, "ffffff", "64748b", "block.note_block.chime"),
    GRIS(9, "&7Gris claro", "Sorpresa", Material.LIGHT_GRAY_DYE, "&8", "&7", "&7&l", "&7", false, "e2e8f0", "475569", "block.note_block.chime"),
    GRIS_OSCURO(10, "&8Gris oscuro", "Sorpresa", Material.GRAY_DYE, "&0", "&8", "&8&l", "&8", false, "cbd5e1", "0f172a", "block.note_block.chime");

    public final int idx;
    public final String nombre, uso, oscuro, medio, brillante, ruleta, claroHex, oscuroHex, sonidoCae;
    public final Material item;
    public final boolean visible;

    Paleta(int idx, String nombre, String uso, Material item, String oscuro, String medio, String brillante, String ruleta,
           boolean visible, String claroHex, String oscuroHex, String sonidoCae) {
        this.idx = idx; this.nombre = nombre; this.uso = uso; this.item = item; this.oscuro = oscuro; this.medio = medio;
        this.brillante = brillante; this.ruleta = ruleta; this.visible = visible; this.claroHex = claroHex;
        this.oscuroHex = oscuroHex; this.sonidoCae = sonidoCae;
    }

    public static Paleta of(int idx) {
        for (Paleta p : values()) if (p.idx == idx) return p;
        return CELESTE;
    }

    public Paleta next() { return of(idx % values().length + 1); }

    /** Paleta del script viejo de mensajes -> nueva. (El verde viejo era de avisos buenos: pasa a celeste.) */
    public static int desdeVieja(int vieja) {
        switch (vieja) {
            case 1: return 1;
            case 2: return 2;
            case 3: return 3;
            case 4: return 6;
            case 5: return 5;
            case 6: return 8;
            case 7: return 9;
            default: return 10;
        }
    }

    /** "Bienvenido a TRUCOFDEATH 4" con degradado de esta paleta. */
    public String header() {
        String w = "TRUCOFDEATH";
        int[] a = rgb(claroHex), b = rgb(oscuroHex);
        StringBuilder sb = new StringBuilder("<#" + claroHex + ">&l✦ <#" + claroHex + ">&lBienvenido a ");
        for (int i = 0; i < w.length(); i++) {
            double t = w.length() == 1 ? 0 : (double) i / (w.length() - 1);
            sb.append(String.format("<#%02x%02x%02x>&l%c",
                    (int) Math.round(a[0] + (b[0] - a[0]) * t), (int) Math.round(a[1] + (b[1] - a[1]) * t),
                    (int) Math.round(a[2] + (b[2] - a[2]) * t), w.charAt(i)));
        }
        sb.append(" &f&l4 <#").append(claroHex).append(">&l✦");
        return sb.toString();
    }

    private static int[] rgb(String h) {
        return new int[]{Integer.parseInt(h.substring(0, 2), 16), Integer.parseInt(h.substring(2, 4), 16), Integer.parseInt(h.substring(4, 6), 16)};
    }
}

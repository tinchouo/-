package ar.tod4.avisos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import ar.tod4.TOD4;
import ar.tod4.data.Store;
import ar.tod4.util.ChatInput;
import ar.tod4.util.Items;
import ar.tod4.util.Menu;
import ar.tod4.util.Style;
import ar.tod4.util.Txt;
import net.kyori.adventure.title.Title;

/**
 * Gestor de mensajes TOD4 (portado de mensajes.sk) + ruleta compartida.
 * Nuevo: icono propio por mensaje, dia de cada mensaje, ruleta opcional por mensaje, paleta nueva,
 * ruleta sin subtitulo, blanco/grises sorpresa.
 */
public final class Avisos implements Listener {

    private final TOD4 pl;
    private final Store st;
    private boolean ruletaGlobal;
    private final Map<UUID, Boolean> ruletaLocal = new ConcurrentHashMap<>();

    /** El icono de cada mensaje es su dia: una terracota de color por dia (1 a 16). Sin dia = General. */
    private static final Material[] DIAS = {
            Material.RED_TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.YELLOW_TERRACOTTA, Material.LIME_TERRACOTTA,
            Material.GREEN_TERRACOTTA, Material.CYAN_TERRACOTTA, Material.LIGHT_BLUE_TERRACOTTA, Material.BLUE_TERRACOTTA,
            Material.PURPLE_TERRACOTTA, Material.MAGENTA_TERRACOTTA, Material.PINK_TERRACOTTA, Material.BROWN_TERRACOTTA,
            Material.WHITE_TERRACOTTA, Material.LIGHT_GRAY_TERRACOTTA, Material.GRAY_TERRACOTTA, Material.BLACK_TERRACOTTA};

    public static Material iconoDia(int dia) {
        return dia >= 1 && dia <= DIAS.length ? DIAS[dia - 1] : Material.TERRACOTTA;
    }

    private static String textoDia(int dia) { return dia > 0 ? "Día " + dia : "General"; }

    /** Ordena: primero los generales y despues por dia (1, 2, 3...). */
    private void ordenar(List<String> l) {
        l.sort((a, b) -> {
            int da = Math.max(0, msg(a).getInt("dia", 0)), db = Math.max(0, msg(b).getInt("dia", 0));
            return da != db ? Integer.compare(da, db) : a.compareToIgnoreCase(b);
        });
    }

    /**
     * Una sola vez (marca {@code clave}): borra los mensajes de los dias {@code desde} a {@code hasta} y los de la lista.
     * Los generales (sin dia) no se tocan. Lo borrado queda guardado en "borrados" del avisos.yml por si hace falta.
     */
    public void borrarUnaVez(String clave, int desde, int hasta, List<String> tambien) {
        if (st.y().getBoolean("limpiezas." + clave, false)) return;
        int n = 0;
        for (String id : ids("mensajes")) {
            int dia = msg(id).getInt("dia", 0);
            if (!(dia >= desde && dia <= hasta) && !tambien.contains(id)) continue;
            st.y().createSection("borrados." + id, msg(id).getValues(false));
            st.set("mensajes." + id, null);
            n++;
        }
        st.set("limpiezas." + clave, true);
        if (n > 0) pl.getLogger().info("Avisos: se borraron " + n + " mensajes de los dias " + desde + " a " + hasta + " (copia en avisos.yml, seccion borrados).");
    }

    /** Ruleta del mensaje: 0 no tiene, 1 normal, 2 el doble de rapida. */
    private static int ruletaModo(ConfigurationSection m) {
        if (!m.getBoolean("ruleta", true)) return 0;
        return m.getBoolean("ruleta_x2", false) ? 2 : 1;
    }

    private static final Material[] DISCOS = {null, Material.MUSIC_DISC_PIGSTEP, Material.MUSIC_DISC_MELLOHI, Material.MUSIC_DISC_13,
            Material.MUSIC_DISC_CHIRP, Material.MUSIC_DISC_FAR, Material.MUSIC_DISC_OTHERSIDE, Material.MUSIC_DISC_CAT,
            Material.MUSIC_DISC_WAIT, Material.BARRIER, Material.MUSIC_DISC_RELIC, Material.MUSIC_DISC_RELIC,
            Material.MUSIC_DISC_RELIC, Material.MUSIC_DISC_RELIC, Material.MUSIC_DISC_RELIC};

    private static final String[] SONIDOS_NOMBRE = {"", "&61. Desafío Completado (Épico)", "&52. Aparición del Wither (Grave)",
            "&43. Rugido del Dragón", "&c4. Impacto de Trueno Fuerte", "&e5. Tótem de Inmortalidad", "&36. Apertura del Portal del End",
            "&b7. Campana Cristalina", "&a8. Subida de Nivel (Brillante)", "&79. Silencioso (Sin sonido)",
            "&d10. [Custom 1]", "&d11. [Custom 2]", "&d12. [Custom 3]", "&d13. [Custom 4]", "&d14. [Custom 5]"};

    public Avisos(TOD4 pl) {
        this.pl = pl;
        this.st = new Store(pl, "avisos");
        Bukkit.getPluginManager().registerEvents(this, pl);
    }

    public Store store() { return st; }

    private ConfigurationSection msg(String id) {
        ConfigurationSection s = st.y().getConfigurationSection("mensajes." + id);
        return s != null ? s : st.y().createSection("mensajes." + id);
    }

    private ConfigurationSection bien(String id) {
        ConfigurationSection s = st.y().getConfigurationSection("bienvenidas." + id);
        return s != null ? s : st.y().createSection("bienvenidas." + id);
    }

    /** Crea un mensaje una sola vez (si no existe y nunca se sembro antes, asi un mensaje borrado no vuelve). */
    public boolean sembrar(String id, int paleta, String titulo, int sonido, int dia, List<String> lineas) {
        if (existe(id) || st.y().getBoolean("sembrados." + id, false)) return false;
        ConfigurationSection c = msg(id);
        c.set("paleta", paleta);
        c.set("titulo", titulo);
        c.set("sonido", sonido);
        c.set("ruleta", true);
        c.set("dia", dia);
        c.set("lineas", lineas);
        st.set("sembrados." + id, true);
        return true;
    }

    /** Cambia el texto de un mensaje solo si todavia tiene el de antes (si lo editaste a mano, no se toca). */
    public void cambiarTexto(String id, List<String> antes, List<String> ahora) {
        if (existe(id) && antes.equals(msg(id).getStringList("lineas"))) { msg(id).set("lineas", ahora); st.touch(); }
    }

    /** Cambia el dia de un mensaje solo si todavia tiene el de antes (si lo cambiaste a mano, no se toca). */
    public void cambiarDia(String id, int de, int a) {
        if (existe(id) && msg(id).getInt("dia", 0) == de) { msg(id).set("dia", a); st.touch(); }
    }

    public boolean existe(String id) { return st.y().isConfigurationSection("mensajes." + id); }

    /** Los datos de un mensaje (titulo, paleta, lineas...). */
    public ConfigurationSection seccion(String id) { return msg(id); }

    private void aDiscord(String id) {
        if (pl.discord() != null) pl.discord().publicarAviso(msg(id));
    }

    private List<String> ids(String seccion) {
        ConfigurationSection s = st.y().getConfigurationSection(seccion);
        return s == null ? new ArrayList<>() : new ArrayList<>(s.getKeys(false));
    }

    // ================================================================= sonidos y texto

    public void sonar(Player p, int idx) {
        String s;
        float pitch = 1f;
        switch (idx) {
            case 1: s = "ui.toast.challenge_complete"; break;
            case 2: s = "entity.wither.spawn"; break;
            case 3: s = "entity.ender_dragon.growl"; break;
            case 4: s = "entity.lightning_bolt.thunder"; break;
            case 5: s = "item.totem.use"; break;
            case 6: s = "block.end_portal.spawn"; break;
            case 7: s = "block.note_block.bell"; break;
            case 8: s = "entity.player.levelup"; pitch = 1.2f; break;
            case 9: return;
            default:
                if (idx >= 10 && idx <= 14) { s = pl.getConfig().getString("avisos.sonido_custom_" + (idx - 9), "tod4:custom_" + (idx - 9)); break; }
                return;
        }
        p.playSound(p.getLocation(), s, SoundCategory.MASTER, 10f, pitch);
    }

    private static String colorLocal(String txt, String tag, String col, String base) {
        String[] partes = txt.split(java.util.regex.Pattern.quote(tag), -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < partes.length; i++) {
            if (i % 2 == 0) sb.append(partes[i]);
            else sb.append(col).append(partes[i]).append("&r").append(base);
        }
        return sb.toString();
    }

    public String procesar(String txt, Paleta pal, boolean titulo, Player p) {
        String base = titulo ? pal.medio + "&l" : pal.medio;
        String t = txt;
        t = t.replace("(tic)", "✔").replace("(cruz)", "✖").replace("(estrella)", "✦").replace("(alerta)", "⚠")
             .replace("(calavera)", "☠").replace("(flecha)", "▶").replace("(corazon)", "❤")
             .replace("---", pal.oscuro + "&l&m----------------------------------------&r" + base);
        String[][] tags = {{"[ro]", "&4"}, {"[r]", "&c"}, {"[n]", "&6"}, {"[am]", "&e"}, {"[vo]", "&2"}, {"[v]", "&a"},
                {"[ci]", "&3"}, {"[c]", "&b"}, {"[ao]", "&1"}, {"[az]", "&9"}, {"[rs]", "&d"}, {"[m]", "&5"}, {"[b]", "&f"},
                {"[go]", "&8"}, {"[g]", "&7"}, {"[ng]", "&0"}};
        for (String[] tg : tags) t = colorLocal(t, tg[0], tg[1], base);
        t = colorLocal(t, "*", pal.brillante, base);
        t = colorLocal(t, "_", "&n", base);
        t = colorLocal(t, "%%", "&o", base);
        t = colorLocal(t, "~~", "&k", base);
        String dia = String.valueOf(pl.dia());
        t = t.replace("{JUGADOR}", p.getName()).replace("{jugador}", p.getName()).replace("{DIA}", dia).replace("{dia}", dia);
        return base + t;
    }

    // ================================================================= enviar

    /** Los renglones del mensaje tal como salen en el chat, pero sin el titulo, sin barras y sin renglones vacios. */
    public List<String> lineasSinTitulo(String id, Player p) {
        List<String> out = new ArrayList<>();
        if (id == null || !existe(id)) return out;
        ConfigurationSection m = msg(id);
        Paleta pal = Paleta.of(m.getInt("paleta", 6));
        for (String l : m.getStringList("lineas")) {
            String t = l.trim();
            if (t.isEmpty() || t.equals(".") || t.equalsIgnoreCase("vacio") || t.equalsIgnoreCase("espacio") || t.equals("---")) continue;
            out.add(procesar(l, pal, false, p));
        }
        return out;
    }

    /** Muestra el mensaje en el chat de un jugador (sin ruleta). */
    public void mostrar(String id, Player p) {
        ConfigurationSection m = msg(id);
        Paleta pal = Paleta.of(m.getInt("paleta", 6));
        sonar(p, m.getInt("sonido", 7));
        String barra = pal.oscuro + "&l&m----------------------------------------";
        p.sendMessage(Txt.c(barra));
        p.sendMessage(Txt.c(""));
        String ct = m.getString("titulo", "ninguno");
        if (ct != null && !ct.equals("ninguno")) {
            p.sendMessage(Txt.c("   " + procesar(ct, pal, true, p)));
            p.sendMessage(Txt.c(""));
        }
        boolean tag = false;
        for (String l : m.getStringList("lineas")) {
            if (l.equals(".") || l.equalsIgnoreCase("vacio") || l.equalsIgnoreCase("espacio")) { p.sendMessage(Txt.c("")); continue; }
            if (l.contains(p.getName())) tag = true;
            p.sendMessage(Txt.c("   " + procesar(l, pal, false, p)));
        }
        p.sendMessage(Txt.c(""));
        p.sendMessage(Txt.c(barra));
        if (tag) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 10f, 2f);
            p.sendActionBar(Txt.c("&e&l⚠ ¡FUISTE MENCIONADO! ⚠"));
        }
    }

    /** Manda el mensaje a todos (con ruleta si el mensaje la tiene). */
    public void enviarATodos(String id) {
        if (!existe(id)) return;
        ConfigurationSection m = msg(id);
        Paleta pal = Paleta.of(m.getInt("paleta", 6));
        if (!m.getBoolean("ruleta", true)) {
            for (Player p : Bukkit.getOnlinePlayers()) mostrar(id, p);
            aDiscord(id);
            return;
        }
        if (ruletaGlobal) {
            Bukkit.getScheduler().runTaskLater(pl, () -> enviarATodos(id), 40L);
            return;
        }
        ruletaGlobal = true;
        ruleta(Bukkit.getOnlinePlayers(), pal, null, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) mostrar(id, p);
            ruletaGlobal = false;
            aDiscord(id);
        }, ruletaModo(m) == 2);
    }

    /** Ruleta para la mision diaria u otros sistemas (sin mensaje guardado). */
    public void ruletaGlobal(Paleta pal, Runnable despues) {
        ruleta(Bukkit.getOnlinePlayers(), pal, null, despues);
    }

    /** Igual, con algo que pasa justo cuando sale la animacion del rojo (la vineta del Momento Truco). */
    public void ruletaGlobal(Paleta pal, Runnable alAnimar, Runnable despues) {
        ruleta(Bukkit.getOnlinePlayers(), pal, alAnimar, despues);
    }

    public void ruleta(Collection<? extends Player> quienes, Paleta destino, Runnable despues) {
        ruleta(quienes, destino, null, despues);
    }

    /**
     * La ruleta: gira con los colores visibles y frena en el de la paleta. Sin subtitulo.
     * Blanco y grises nunca aparecen mientras gira: solo entran en los ultimos cuadros, cuando esta por caer ahi.
     */
    public void ruleta(Collection<? extends Player> quienes, Paleta destino, Runnable alAnimar, Runnable despues) {
        ruleta(quienes, destino, alAnimar, despues, false);
    }

    /** {@code rapida}: gira el doble de rapido (la mitad de espera entre cuadro y cuadro). */
    public void ruleta(Collection<? extends Player> quienes, Paleta destino, Runnable alAnimar, Runnable despues, boolean rapida) {
        List<Player> ps = new ArrayList<>(quienes);
        int[] esperas = {1, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 4, 4, 5, 5, 6, 7, 8, 10, 13, 17, 22, 28};
        if (rapida) for (int i = 0; i < esperas.length; i++) esperas[i] = Math.max(1, (esperas[i] + 1) / 2);
        List<Paleta> vis = new ArrayList<>();
        for (Paleta p : Paleta.values()) if (p.visible) vis.add(p);
        int F = esperas.length;
        Paleta[] tira = new Paleta[F + 4];
        int centroFinal = F - 1 + 2;
        int ancla = destino.visible ? vis.indexOf(destino) : (int) (Math.random() * vis.size());
        for (int k = 0; k < tira.length; k++) {
            int off = k - centroFinal;
            tira[k] = vis.get(Math.floorMod(ancla + off, vis.size()));
        }
        tira[centroFinal] = destino;
        final int[] frame = {0};
        Runnable[] paso = new Runnable[1];
        paso[0] = () -> {
            int i = frame[0];
            if (i >= F) {
                String fc = destino.ruleta;
                String fin = "&7&k||&r " + fc + "&l■ " + fc + "&l■ " + fc + "&l■ " + fc + "&l■ " + fc + "&l■ &7&k||&r";
                for (Player p : ps) {
                    if (!p.isOnline()) continue;
                    p.showTitle(Title.title(Txt.c(fin), Txt.c(""), Title.Times.times(java.time.Duration.ZERO,
                            java.time.Duration.ofMillis(1500), java.time.Duration.ofMillis(1500))));
                    // siempre el sonido de experiencia cuando la ruleta elige el color
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 1f, 1f);
                }
                // como el mensajes.sk viejo: 2 segundos y despues el mensaje.
                // Si es ROJO (Momento Truco): a los 2 segundos la animacion + su sonido, y 7 segundos mas.
                long extra = 40L;
                if (destino == Paleta.ROJO) {
                    Bukkit.getScheduler().runTaskLater(pl, () -> {
                        for (String cmd : pl.getConfig().getStringList("avisos.momento_truco_comandos"))
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                        if (alAnimar != null) alAnimar.run();
                    }, 40L);
                    extra += pl.getConfig().getLong("avisos.momento_truco_espera_ticks", 140);
                }
                Bukkit.getScheduler().runTaskLater(pl, despues, extra);
                return;
            }
            StringBuilder sb = new StringBuilder("&7&k||&r ");
            for (int k = 0; k < 5; k++) sb.append(tira[i + k].ruleta).append("&l■ ");
            sb.append("&7&k||&r");
            for (Player p : ps) {
                if (!p.isOnline()) continue;
                p.showTitle(Title.title(Txt.c(sb.toString()), Txt.c(""), Title.Times.times(java.time.Duration.ZERO,
                        java.time.Duration.ofSeconds(1), java.time.Duration.ZERO)));
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 2f);
            }
            frame[0]++;
            Bukkit.getScheduler().runTaskLater(pl, paso[0], esperas[i]);
        };
        Bukkit.getScheduler().runTaskLater(pl, paso[0], rapida ? 10L : 20L);
    }

    // ================================================================= bienvenida

    @EventHandler
    public void join(PlayerJoinEvent e) {
        String act = st.y().getString("bienvenida_activa");
        if (act == null) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(pl, () -> { if (p.isOnline()) mostrarBienvenida(act, p); }, 40L);
    }

    public void mostrarBienvenida(String id, Player p) {
        ConfigurationSection b = bien(id);
        Paleta pal = Paleta.of(b.getInt("paleta", 1));
        sonar(p, b.getInt("sonido", 8));
        String barra = pal.oscuro + "&l&m----------------------------------------";
        p.sendMessage(Txt.c(barra));
        p.sendMessage(Txt.c(""));
        p.sendMessage(Txt.c("   " + pal.header()));
        p.sendMessage(Txt.c(""));
        for (String l : b.getStringList("lineas")) {
            if (l.equals(".") || l.equalsIgnoreCase("vacio") || l.equalsIgnoreCase("espacio")) p.sendMessage(Txt.c(""));
            else p.sendMessage(Txt.c("   " + procesar(l, pal, true, p)));
        }
        p.sendMessage(Txt.c(""));
        p.sendMessage(Txt.c(barra));
    }

    // ================================================================= menus

    public void abrirPanel(Player p) {
        Style.click(p);
        String act = st.y().getString("bienvenida_activa");
        Menu m = new Menu(3, "&8Panel de Mensajes TOD4");
        m.set(11, Items.of(Material.WRITABLE_BOOK, "&a&l1. Mensajes de Inicio (Bienvenida)", "", "&7Crea, guarda y activa presets de",
                "&7bienvenida con paletas de tonos.", "", "&7Estado actual: " + (act != null ? "&aActivo (&f" + act + "&a)" : "&cDesactivado"),
                "", "&e▶ Click para administrar"), e -> listaBienvenidas(p));
        m.set(15, Items.of(Material.ENDER_EYE, "&6&l2. Mandar un Mensaje", "", "&7Crea, guarda y envía comunicados",
                "&7a todo el servidor con paletas,", "&7símbolos, íconos y ruleta.", "", "&6▶ Click para administrar"), e -> listaMensajes(p));
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    private void listaBienvenidas(Player p) {
        Style.click(p);
        Menu m = new Menu(6, "&8Presets de Bienvenida");
        String act = st.y().getString("bienvenida_activa");
        int slot = 0;
        for (String id : ids("bienvenidas")) {
            if (slot >= 45) break;
            Paleta pal = Paleta.of(bien(id).getInt("paleta", 1));
            boolean on = id.equals(act);
            m.set(slot++, Items.of(on ? Material.ENCHANTED_BOOK : Material.BOOK, "&a&lBienvenida: &f" + id, "",
                    "&7• Estado: " + (on ? "&a&l✔ ACTIVO ACTUALMENTE" : "&c✖ Inactivo"), "&7• Paleta: " + pal.nombre, "",
                    "&e▶ Click para configurar"), e -> editorBienvenida(p, id));
        }
        for (int i = 45; i < 54; i++) m.set(i, Items.pane(Material.BLACK_STAINED_GLASS_PANE));
        m.set(45, Items.of(Material.ARROW, "&7◀ Volver al Menú Principal"), e -> abrirPanel(p));
        m.set(49, Items.of(Material.LIME_CONCRETE, "&a&l➕ Crear Nueva Bienvenida", "", "&a▶ Click para crear"), e -> {
            p.closeInventory();
            Style.info(p, "Escribe en el chat la &eID o Nombre &7para el preset &8(o cancel)&7:");
            ChatInput.ask(p, txt -> {
                ChatInput.cancel(p);
                String id = Txt.strip(txt).replace(' ', '_').replace('.', '_');
                ConfigurationSection b = bien(id);
                if (!b.contains("paleta")) { b.set("paleta", 1); b.set("sonido", 8); st.touch(); }
                editorBienvenida(p, id);
            });
        });
        m.open(p);
    }

    private void editorBienvenida(Player p, String id) {
        Style.click(p);
        ConfigurationSection b = bien(id);
        Paleta pal = Paleta.of(b.getInt("paleta", 1));
        Menu m = new Menu(4, "&8Bienvenida: " + id);
        m.set(10, Items.of(pal.item, "&e&l1. Paleta de Tonos", "", "&7Paleta actual: " + pal.nombre, "", "&7Vista del título:", " " + pal.header(),
                "", "&e▶ Click para cambiar"), e -> { b.set("paleta", pal.next().idx); st.touch(); editorBienvenida(p, id); });
        List<String> lore = new ArrayList<>();
        lore.add(""); lore.add("&7Renglones actuales:");
        List<String> lin = b.getStringList("lineas");
        if (lin.isEmpty()) lore.add(" &8• &7(Vacío)");
        for (String l : lin) lore.add(" &8• " + (l.equals(".") ? "&7(Renglón vacío)" : procesar(l, pal, true, p)));
        lore.add(""); lore.add("&a▶ Click para editar texto");
        m.set(12, Items.of(Material.WRITABLE_BOOK, "&a&l2. Editar Texto de Bienvenida", lore),
                e -> editarLineas(p, pal, true, l -> { b.set("lineas", l); st.touch(); editorBienvenida(p, id); }));
        int snd = b.getInt("sonido", 8);
        m.set(14, Items.of(DISCOS[Math.max(1, Math.min(snd, 14))], "&d&l3. Disco de Sonido", "", "&7Sonido actual:", SONIDOS_NOMBRE[Math.max(1, Math.min(snd, 14))], "",
                "&d▶ Click para cambiar"), e -> { int s = snd % 14 + 1; b.set("sonido", s); st.touch(); editorBienvenida(p, id); sonar(p, s); });
        boolean on = id.equals(st.y().getString("bienvenida_activa"));
        m.set(16, Items.of(on ? Material.LIME_DYE : Material.GRAY_DYE, on ? "&a&l4. Estado: ACTIVO ✔" : "&c&l4. Estado: INACTIVO ✖", "",
                on ? "&7Saldrá al entrar al server." : "&7Actualmente apagado.", "", on ? "&c▶ Click para DESACTIVAR" : "&a▶ Click para ACTIVAR"),
                e -> { st.set("bienvenida_activa", on ? null : id); editorBienvenida(p, id); });
        m.set(27, Items.of(Material.ARROW, "&7◀ Volver a Presets"), e -> listaBienvenidas(p));
        m.set(31, Items.of(Material.SPYGLASS, "&6&l👁 Vista Previa (Solo para ti)"), e -> { p.closeInventory(); mostrarBienvenida(id, p); });
        m.set(35, Items.of(Material.BARRIER, "&c&l🗑 Borrar esta Bienvenida"), e -> {
            if (id.equals(st.y().getString("bienvenida_activa"))) st.set("bienvenida_activa", null);
            st.set("bienvenidas." + id, null);
            listaBienvenidas(p);
        });
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    private ItemStack iconoDe(String id) {
        ConfigurationSection m = msg(id);
        Paleta pal = Paleta.of(m.getInt("paleta", 6));
        int dia = m.getInt("dia", 0);
        String ct = m.getString("titulo", "ninguno");
        ItemStack it = Items.of(iconoDia(dia), "&6&lMensaje: &f" + id, "", "&7• " + textoDia(dia), "&7• Paleta: " + pal.nombre + " &8(" + pal.uso + ")",
                "&7• Ruleta: " + switch (ruletaModo(m)) { case 0 -> "&cNo"; case 2 -> "&6Sí, x2"; default -> "&aSí"; }, "&7• Título en chat: &f" + ct, "", "&e▶ Click para configurar / enviar");
        if (dia > 0) it.setAmount(Math.min(64, dia));
        return it;
    }

    private void listaMensajes(Player p) {
        Style.click(p);
        Menu m = new Menu(6, "&8Mis Mensajes Guardados");
        List<String> l = ids("mensajes");
        ordenar(l);
        int slot = 0;
        for (String id : l) {
            if (slot >= 45) break;
            m.set(slot++, iconoDe(id), e -> editorMensaje(p, id));
        }
        for (int i = 45; i < 54; i++) m.set(i, Items.pane(Material.BLACK_STAINED_GLASS_PANE));
        m.set(45, Items.of(Material.ARROW, "&7◀ Volver al Menú Principal"), e -> abrirPanel(p));
        m.set(49, Items.of(Material.LIME_CONCRETE, "&a&l➕ Crear Nuevo Mensaje", "", "&a▶ Click para crear"), e -> {
            p.closeInventory();
            Style.info(p, "Escribe en el chat la &eID o Nombre &7para el nuevo mensaje &8(o cancel)&7:");
            ChatInput.ask(p, txt -> {
                ChatInput.cancel(p);
                String id = Txt.strip(txt).replace(' ', '_').replace('.', '_');
                ConfigurationSection c = msg(id);
                if (!c.contains("paleta")) {
                    c.set("paleta", 6); c.set("titulo", "ninguno"); c.set("sonido", 7); c.set("ruleta", true);
                    c.set("dia", pl.dia()); st.touch();
                }
                editorMensaje(p, id);
            });
        });
        m.open(p);
    }

    private void editorMensaje(Player p, String id) {
        Style.click(p);
        ConfigurationSection c = msg(id);
        Paleta pal = Paleta.of(c.getInt("paleta", 6));
        Menu m = new Menu(4, "&8Editando: " + id);
        m.set(10, Items.of(pal.item, "&e&l1. Paleta de Tonos", "", "&7Paleta actual: " + pal.nombre, "&7Uso: &f" + pal.uso, "",
                "&e▶ Click para rotar paleta"), e -> { c.set("paleta", pal.next().idx); st.touch(); editorMensaje(p, id); });
        String ct = c.getString("titulo", "ninguno");
        m.set(12, Items.of(Material.PAPER, "&e&l2. Título en el Chat (Encabezado)", "", "&7Sale arriba del texto en el chat:",
                "&f" + (ct.equals("ninguno") ? "&8(ninguno)" : procesar(ct, pal, true, p)), "", "&e▶ Click para editar"), e -> {
            p.closeInventory();
            guia(p, false);
            ChatInput.ask(p, txt -> { ChatInput.cancel(p); c.set("titulo", txt); st.touch(); editorMensaje(p, id); });
        });
        List<String> lore = new ArrayList<>();
        lore.add(""); lore.add("&7Renglones actuales en el chat:");
        List<String> lin = c.getStringList("lineas");
        if (lin.isEmpty()) lore.add(" &8• &7(Vacío)");
        for (String l : lin) lore.add(" &8• " + (l.equals(".") ? "&7(Renglón vacío)" : procesar(l, pal, false, p)));
        lore.add(""); lore.add("&a▶ Click para escribir el texto");
        m.set(14, Items.of(Material.WRITABLE_BOOK, "&a&l3. Texto del Mensaje (En el Chat)", lore),
                e -> editarLineas(p, pal, false, l -> { c.set("lineas", l); st.touch(); editorMensaje(p, id); }));
        int snd = c.getInt("sonido", 7);
        m.set(16, Items.of(DISCOS[Math.max(1, Math.min(snd, 14))], "&d&l4. Disco de Sonido", "", "&7Sonido actual:", SONIDOS_NOMBRE[Math.max(1, Math.min(snd, 14))], "",
                "&d▶ Click para rotar sonido"), e -> { int s = snd % 14 + 1; c.set("sonido", s); st.touch(); editorMensaje(p, id); sonar(p, s); });
        int dia = c.getInt("dia", 0);
        ItemStack diaIt = Items.of(iconoDia(dia), "&e&l5. Día del Mensaje: &f" + textoDia(dia), "",
                "&7El color dice el día y la lista", "&7se ordena por día. &fGeneral &7= sin día", "&7(avisos que sirven siempre).", "",
                "&e▶ Click: +1 | Click derecho: -1");
        if (dia > 0) diaIt.setAmount(Math.min(64, dia));
        m.set(20, diaIt, e -> { c.set("dia", Math.floorMod(dia + (e.isRightClick() ? -1 : 1), DIAS.length + 1)); st.touch(); editorMensaje(p, id); });
        // tres estados: desactivada -> activada -> activada x2 (gira el doble de rapido) -> desactivada
        int rm = ruletaModo(c);
        m.set(24, Items.of(rm == 0 ? Material.RED_DYE : rm == 1 ? Material.LIME_DYE : Material.ORANGE_DYE,
                rm == 0 ? "&c&l6. Ruleta: NO" : rm == 1 ? "&a&l6. Ruleta: SÍ" : "&6&l6. Ruleta: SÍ x2", "",
                rm == 0 ? "&7Sale directo en el chat." : rm == 1 ? "&7Antes del mensaje gira la ruleta." : "&7Gira la ruleta el doble de rápido.",
                "", "&7Desactivada → Activada → Activada x2", "&e▶ Click para cambiar"),
                e -> {
                    int nx = (rm + 1) % 3;
                    c.set("ruleta", nx != 0);
                    c.set("ruleta_x2", nx == 2);
                    st.touch();
                    editorMensaje(p, id);
                });
        m.set(27, Items.of(Material.ARROW, "&7◀ Volver a Mis Mensajes"), e -> listaMensajes(p));
        m.set(30, Items.of(Material.SPYGLASS, "&e&l👁 Vista Previa (Solo para ti)", "", "&7Muestra cómo queda sin enviarlo."), e -> {
            if (ruletaLocal.containsKey(p.getUniqueId())) { Style.err(p, "¡Ya hay una vista previa en curso!"); return; }
            p.closeInventory();
            if (!c.getBoolean("ruleta", true)) { mostrar(id, p); return; }
            ruletaLocal.put(p.getUniqueId(), true);
            ruleta(List.of(p), Paleta.of(c.getInt("paleta", 6)), null, () -> { mostrar(id, p); ruletaLocal.remove(p.getUniqueId()); }, ruletaModo(c) == 2);
        });
        m.set(32, Items.of(Material.LIME_CONCRETE, "&a&l📢 ¡ENVIAR MENSAJE A TODOS!", "", "&7Dispara este mensaje ahora mismo."), e -> {
            if (ruletaGlobal) { Style.err(p, "¡Ya hay un anuncio global en curso!"); return; }
            p.closeInventory();
            enviarATodos(id);
        });
        m.set(35, Items.of(Material.BARRIER, "&c&l🗑 Borrar este Mensaje"), e -> { st.set("mensajes." + id, null); listaMensajes(p); });
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    /** Selector de aviso (para vincular a un cambio de dificultad). null = ninguno. */
    public void elegir(Player p, String titulo, Consumer<String> listo) {
        Menu m = new Menu(6, titulo);
        List<String> l = ids("mensajes");
        ordenar(l);
        int slot = 0;
        for (String id : l) {
            if (slot >= 45) break;
            m.set(slot++, iconoDe(id), e -> listo.accept(id));
        }
        for (int i = 45; i < 54; i++) m.set(i, Items.pane(Material.BLACK_STAINED_GLASS_PANE));
        m.set(49, Items.of(Material.BARRIER, "&c&lSin aviso"), e -> listo.accept(null));
        m.open(p);
    }

    private void guia(Player p, boolean multi) {
        Style.raw(p, "&8&m----------------------------------------");
        Style.raw(p, "&6&lGUÍA RÁPIDA DE EDICIÓN:");
        Style.raw(p, "&8• &7Destacar: &e*hola* &8| &7Subrayado: &e_hola_");
        Style.raw(p, "&8• &7Cursiva: &e%%hola%% &8| &7Glitch: &e~~hola~~");
        Style.raw(p, "&8• &7Símbolos: &e(tic) (cruz) (estrella) (alerta) (calavera) (flecha) (corazon)");
        Style.raw(p, "&8• &7Colores: &c[r] &4[ro] &6[n] &e[am] &a[v] &b[c] &d[rs] &f[b] &7[g] &7[ng]");
        if (multi) {
            Style.raw(p, "&8• &eRenglón vacío: &7manda &e.");
            Style.raw(p, "&8• &cBorrar el último renglón: &7manda &e-1");
            Style.raw(p, "&a▶ Manda cada renglón por el chat. Al terminar escribe &eLISTO &a(o &ccancel&a).");
        } else {
            Style.raw(p, "&a▶ Escribe el texto en el chat (o &ccancel&a):");
        }
        Style.raw(p, "&8&m----------------------------------------");
    }

    private void editarLineas(Player p, Paleta pal, boolean titulo, Consumer<List<String>> listo) {
        p.closeInventory();
        guia(p, true);
        List<String> buf = new ArrayList<>();
        ChatInput.ask(p, txt -> {
            if (txt.equalsIgnoreCase("listo")) {
                if (buf.isEmpty()) { Style.err(p, "Todavía no escribiste ningún renglón."); return; }
                ChatInput.cancel(p);
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                listo.accept(buf);
                return;
            }
            if (txt.equals("-1")) {
                if (buf.isEmpty()) Style.err(p, "El texto ya está vacío.");
                else { buf.remove(buf.size() - 1); Style.err(p, "Se borró el último renglón. Puedes volver a escribirlo."); }
                return;
            }
            buf.add(txt);
            String muestra = txt.equals(".") ? "&7(Renglón vacío)" : procesar(txt, pal, titulo, p);
            Style.raw(p, "&a✔ Renglón " + buf.size() + ": &r" + muestra + " &8| Escribe &eLISTO &8o &e-1 &8para borrar");
        });
    }
}

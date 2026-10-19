package ar.tod4.eventos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.bossbar.BossBar;

/**
 * Ofrendas: un cofre (o barril, etc.) que acepta un item. Lo que se pone se consume al instante y suma al total;
 * al llegar a la cantidad, la "puerta" (una zona de bloques) se borra de a poco y se corren los comandos que tenga.
 * Mientras esta activa, todos ven una boss bar "Almas 0 / 30" que se va llenando.
 */
final class Ofrendas implements Listener {

    private final TOD4Eventos pl;
    private final Store st;        // ofrendas.yml (configuracion y progreso)
    private final Store guardado;  // puertas.yml (bloques de cada puerta, para poder resetear)
    private final Map<String, BossBar> barras = new HashMap<>();
    private final Map<String, String> porBloque = new HashMap<>();   // "mundo:x:y:z" del cofre -> id

    Ofrendas(TOD4Eventos pl) {
        this.pl = pl;
        this.st = new Store(pl, "ofrendas");
        this.guardado = new Store(pl, "puertas");
        indexar();
        Bukkit.getPluginManager().registerEvents(this, pl);
        // las que estaban activas cuando se apago el server vuelven a mostrar su barra
        for (String id : ids()) if (sec(id).getBoolean("activa") && !sec(id).getBoolean("abierta")) mostrar(id);
    }

    void apagar() {
        for (BossBar b : barras.values()) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(b);
        st.save();
        guardado.save();
    }

    // ================================================================= datos

    private List<String> ids() {
        ConfigurationSection s = st.y().getConfigurationSection("ofrendas");
        return s == null ? new ArrayList<>() : new ArrayList<>(s.getKeys(false));
    }

    private ConfigurationSection sec(String id) {
        ConfigurationSection s = st.y().getConfigurationSection("ofrendas." + id);
        return s != null ? s : st.y().createSection("ofrendas." + id);
    }

    private boolean existe(String id) { return st.y().isConfigurationSection("ofrendas." + id); }

    private static String clave(Block b) { return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ(); }

    private void indexar() {
        porBloque.clear();
        for (String id : ids()) {
            Location l = sec(id).getLocation("cofre");
            if (l != null && l.getWorld() != null) porBloque.put(clave(l.getBlock()), id);
        }
    }

    /** La ofrenda de ese inventario (cofre doble incluido), o null. */
    private String ofrendaDe(Inventory inv) {
        if (inv == null || porBloque.isEmpty()) return null;
        InventoryHolder h = inv.getHolder(false);
        if (h instanceof DoubleChest dc) {
            for (InventoryHolder lado : new InventoryHolder[]{dc.getLeftSide(), dc.getRightSide()})
                if (lado instanceof Chest ch) { String id = porBloque.get(clave(ch.getBlock())); if (id != null) return id; }
            return null;
        }
        if (h instanceof Container c) return porBloque.get(clave(c.getBlock()));
        return null;
    }

    // ================================================================= barra

    private String textoBarra(String id) {
        ConfigurationSection c = sec(id);
        return pl.getConfig().getString("ofrendas.barra", "&c&l{titulo} &f{n} &7/ &f{total}")
                .replace("{titulo}", c.getString("titulo", "Almas"))
                .replace("{n}", String.valueOf(Math.min(c.getInt("actuales"), c.getInt("necesarias", 1))))
                .replace("{total}", String.valueOf(c.getInt("necesarias", 1)));
    }

    private BossBar barra(String id) {
        return barras.computeIfAbsent(id, k -> {
            BossBar.Color col;
            try { col = BossBar.Color.valueOf(pl.getConfig().getString("ofrendas.color", "RED").toUpperCase()); }
            catch (Exception e) { col = BossBar.Color.RED; }
            return BossBar.bossBar(TOD4Eventos.c(textoBarra(id)), 0f, col, BossBar.Overlay.NOTCHED_10);
        });
    }

    private void actualizarBarra(String id) {
        ConfigurationSection c = sec(id);
        BossBar b = barra(id);
        b.name(TOD4Eventos.c(textoBarra(id)));
        b.progress(Math.max(0f, Math.min(1f, c.getInt("actuales") / (float) Math.max(1, c.getInt("necesarias", 1)))));
    }

    private void mostrar(String id) {
        actualizarBarra(id);
        for (Player p : Bukkit.getOnlinePlayers()) p.showBossBar(barra(id));
    }

    private void ocultar(String id) {
        BossBar b = barras.remove(id);
        if (b != null) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(b);
    }

    private void activar(String id) {
        sec(id).set("activa", true);
        st.touch();
        mostrar(id);
    }

    @EventHandler
    public void entra(PlayerJoinEvent e) {
        for (Map.Entry<String, BossBar> en : barras.entrySet()) e.getPlayer().showBossBar(en.getValue());
    }

    // ================================================================= entregas

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void clic(InventoryClickEvent e) {
        String id = ofrendaDe(e.getView().getTopInventory());
        if (id == null) return;
        Inventory inv = e.getView().getTopInventory();
        Player p = e.getWhoClicked() instanceof Player pp ? pp : null;
        Bukkit.getScheduler().runTask(pl, () -> procesar(id, inv, p));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void arrastra(InventoryDragEvent e) {
        String id = ofrendaDe(e.getView().getTopInventory());
        if (id == null) return;
        Inventory inv = e.getView().getTopInventory();
        Player p = e.getWhoClicked() instanceof Player pp ? pp : null;
        Bukkit.getScheduler().runTask(pl, () -> procesar(id, inv, p));
    }

    @EventHandler
    public void cierra(InventoryCloseEvent e) {
        String id = ofrendaDe(e.getInventory());
        if (id != null) procesar(id, e.getInventory(), e.getPlayer() instanceof Player p ? p : null);
    }

    /** Tolvas: pueden meter (cuenta), no sacar. */
    @EventHandler(ignoreCancelled = true)
    public void tolva(InventoryMoveItemEvent e) {
        if (ofrendaDe(e.getSource()) != null) { e.setCancelled(true); return; }
        String id = ofrendaDe(e.getDestination());
        if (id != null) { Inventory inv = e.getDestination(); Bukkit.getScheduler().runTask(pl, () -> procesar(id, inv, null)); }
    }

    private void procesar(String id, Inventory inv, Player quien) {
        if (!existe(id)) return;
        ConfigurationSection c = sec(id);
        if (c.getBoolean("abierta")) return;
        String item = c.getString("item");
        int necesarias = Math.max(1, c.getInt("necesarias", 1));
        int falta = necesarias - c.getInt("actuales");
        if (item == null || falta <= 0) return;
        int tomadas = 0;
        ItemStack[] cont = inv.getContents();
        for (int i = 0; i < cont.length && tomadas < falta; i++) {
            ItemStack it = cont[i];
            if (it == null || !TOD4Eventos.es(it, item)) continue;
            int t = Math.min(it.getAmount(), falta - tomadas);
            tomadas += t;
            if (t >= it.getAmount()) inv.setItem(i, null);
            else { it.setAmount(it.getAmount() - t); inv.setItem(i, it); }
        }
        if (tomadas == 0) return;
        if (!c.getBoolean("activa")) activar(id);
        c.set("actuales", c.getInt("actuales") + tomadas);
        st.touch();
        actualizarBarra(id);
        Location l = c.getLocation("cofre");
        if (l != null && l.getWorld() != null) {
            Location m = l.clone().add(0.5, 1.0, 0.5);
            sonido(m, "ofrendas.sonido_entrega", "block.respawn_anchor.charge", 1.5f, 1.0f);
            sonido(m, "ofrendas.sonido_entrega2", "particle.soul_escape", 1.5f, 0.8f);
            m.getWorld().spawnParticle(Particle.SOUL, m, 15 + tomadas * 2, 0.3, 0.4, 0.3, 0.03);
        }
        if (quien != null) quien.sendActionBar(TOD4Eventos.c("&c+" + tomadas + " &7" + c.getString("titulo", "Almas")
                + " &8(" + Math.min(c.getInt("actuales"), necesarias) + "/" + necesarias + ")"));
        if (c.getInt("actuales") >= necesarias) abrir(id);
    }

    private void sonido(Location l, String clave, String def, float vol, float tono) {
        String s = pl.getConfig().getString(clave, def);
        if (s != null && !s.isBlank()) l.getWorld().playSound(l, s, SoundCategory.MASTER, vol, tono);
    }

    // ================================================================= abrir / resetear

    private void abrir(String id) {
        ConfigurationSection c = sec(id);
        if (c.getBoolean("abierta")) return;
        c.set("abierta", true);
        c.set("actuales", Math.max(c.getInt("actuales"), c.getInt("necesarias", 1)));
        st.touch();
        actualizarBarra(id);
        // sonido para todos los del mundo de la puerta
        World w = Bukkit.getWorld(c.getString("puerta.mundo", ""));
        String son = pl.getConfig().getString("ofrendas.sonido_abrir", "block.end_portal.spawn");
        if (w != null && son != null && !son.isBlank()) for (Player p : w.getPlayers()) p.playSound(p.getLocation(), son, SoundCategory.MASTER, 1f, 0.8f);
        borrarPuerta(id);
        for (String cmd : c.getStringList("comandos")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        long ocultar = Math.max(1, pl.getConfig().getLong("ofrendas.ocultar_barra_seg", 6)) * 20L;
        Bukkit.getScheduler().runTaskLater(pl, () -> { if (sec(id).getBoolean("abierta")) { ocultar(id); sec(id).set("activa", false); st.touch(); } }, ocultar);
    }

    /** La puerta se borra de arriba hacia abajo, de a poco, con particulas. Se guarda como era para el reset. */
    private void borrarPuerta(String id) {
        ConfigurationSection c = sec(id);
        World w = Bukkit.getWorld(c.getString("puerta.mundo", ""));
        if (w == null) return;
        int x1 = c.getInt("puerta.x1"), y1 = c.getInt("puerta.y1"), z1 = c.getInt("puerta.z1");
        int x2 = c.getInt("puerta.x2"), y2 = c.getInt("puerta.y2"), z2 = c.getInt("puerta.z2");
        List<Block> bloques = new ArrayList<>();
        for (int y = y1; y <= y2; y++) for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
            Block b = w.getBlockAt(x, y, z);
            if (!b.getType().isAir()) bloques.add(b);
        }
        if (bloques.isEmpty()) return;
        bloques.sort(Comparator.comparingInt(Block::getY).reversed());
        List<String> antes = new ArrayList<>();
        for (Block b : bloques) antes.add(b.getX() + "," + b.getY() + "," + b.getZ() + "|" + b.getBlockData().getAsString());
        guardado.set(id, antes);
        guardado.save();
        int porTick = Math.max(1, pl.getConfig().getInt("ofrendas.bloques_por_tick", 40));
        final int[] i = {0};
        Bukkit.getScheduler().runTaskTimer(pl, t -> {
            for (int n = 0; n < porTick && i[0] < bloques.size(); n++, i[0]++) {
                Block b = bloques.get(i[0]);
                BlockData bd = b.getBlockData();
                if (i[0] % 3 == 0) w.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 8, 0.3, 0.3, 0.3, 0, bd);
                if (i[0] % 6 == 0) w.spawnParticle(Particle.SOUL_FIRE_FLAME, b.getLocation().add(0.5, 0.5, 0.5), 2, 0.2, 0.2, 0.2, 0.02);
                b.setType(org.bukkit.Material.AIR, false);
            }
            if (i[0] > 0 && i[0] % (porTick * 5) < porTick) w.playSound(bloques.get(Math.min(i[0], bloques.size()) - 1).getLocation(), "block.stone.break", SoundCategory.BLOCKS, 1f, 0.6f);
            if (i[0] >= bloques.size()) t.cancel();
        }, 1L, 1L);
    }

    private void reset(String id) {
        ConfigurationSection c = sec(id);
        World w = Bukkit.getWorld(c.getString("puerta.mundo", ""));
        List<String> antes = guardado.y().getStringList(id);
        if (w != null) for (String s : antes) {
            try {
                String[] pos = s.substring(0, s.indexOf('|')).split(",");
                BlockData bd = Bukkit.createBlockData(s.substring(s.indexOf('|') + 1));
                w.getBlockAt(Integer.parseInt(pos[0]), Integer.parseInt(pos[1]), Integer.parseInt(pos[2])).setBlockData(bd, false);
            } catch (Exception ignored) { }
        }
        guardado.set(id, null);
        guardado.save();
        c.set("actuales", 0);
        c.set("abierta", false);
        c.set("activa", false);
        st.touch();
        ocultar(id);
    }

    // ---- que no se rompa el cofre ni la puerta antes de tiempo (los admins en creativo si pueden)

    @EventHandler(ignoreCancelled = true)
    public void rompe(BlockBreakEvent e) {
        if (porBloque.containsKey(clave(e.getBlock())) && !(e.getPlayer().hasPermission("tod4eventos.admin") && e.getPlayer().getGameMode() == org.bukkit.GameMode.CREATIVE)) {
            e.setCancelled(true);
            return;
        }
        if (enPuertaCerrada(e.getBlock()) && !(e.getPlayer().hasPermission("tod4eventos.admin") && e.getPlayer().getGameMode() == org.bukkit.GameMode.CREATIVE))
            e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void explota(EntityExplodeEvent e) { e.blockList().removeIf(b -> porBloque.containsKey(clave(b)) || enPuertaCerrada(b)); }

    @EventHandler(ignoreCancelled = true)
    public void explotaBloque(BlockExplodeEvent e) { e.blockList().removeIf(b -> porBloque.containsKey(clave(b)) || enPuertaCerrada(b)); }

    private boolean enPuertaCerrada(Block b) {
        for (String id : ids()) {
            ConfigurationSection c = sec(id);
            if (c.getBoolean("abierta") || !b.getWorld().getName().equals(c.getString("puerta.mundo"))) continue;
            if (b.getX() >= c.getInt("puerta.x1") && b.getX() <= c.getInt("puerta.x2") && b.getY() >= c.getInt("puerta.y1")
                    && b.getY() <= c.getInt("puerta.y2") && b.getZ() >= c.getInt("puerta.z1") && b.getZ() <= c.getInt("puerta.z2")) return true;
        }
        return false;
    }

    // ================================================================= comando

    void comando(CommandSender s, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase() : "";
        Player p = s instanceof Player pp ? pp : null;
        String id = a.length > 1 ? a[1].toLowerCase() : null;
        if (!List.of("", "ayuda", "pos1", "pos2", "lista", "crear").contains(sub) && (id == null || !existe(id))) {
            TOD4Eventos.err(s, id == null ? "Falta el nombre de la ofrenda." : "No existe la ofrenda &f" + id + "&c. Mira &f/tod4eventos:ofrenda lista&c.");
            return;
        }
        switch (sub) {
            case "pos1", "pos2" -> { if (p != null) pl.marcar(p, sub.equals("pos1") ? 0 : 1); }
            case "crear" -> {
                if (p == null) return;
                if (id == null || a.length < 3) { TOD4Eventos.info(s, "Uso: &e/tod4eventos:ofrenda crear <nombre> <cantidad> [titulo...]"); return; }
                int n;
                try { n = Math.max(1, Integer.parseInt(a[2])); } catch (NumberFormatException ex) { TOD4Eventos.err(s, "La cantidad tiene que ser un número."); return; }
                Block b = TOD4Eventos.mirando(p);
                if (!(b.getState(false) instanceof Container)) { TOD4Eventos.err(s, "Mira el cofre (o barril) de la ofrenda mientras usas el comando."); return; }
                String item = TOD4Eventos.idDe(p.getInventory().getItemInMainHand());
                if (item == null) { TOD4Eventos.err(s, "Ten en la mano el ítem que acepta (por ejemplo el alma)."); return; }
                Location[] sel = pl.seleccion(p);
                if (sel == null) return;
                ConfigurationSection c = sec(id);
                c.set("cofre", b.getLocation());
                c.set("item", item);
                c.set("necesarias", n);
                c.set("actuales", 0);
                c.set("abierta", false);
                c.set("activa", false);
                c.set("titulo", a.length > 3 ? String.join(" ", java.util.Arrays.copyOfRange(a, 3, a.length)) : "Almas");
                ponerPuerta(c, sel);
                st.save();
                indexar();
                TOD4Eventos.ok(s, "Ofrenda &f" + id + "&a creada: acepta &f" + TOD4Eventos.nombre(item) + "&a, hacen falta &f" + n + "&a.");
                TOD4Eventos.info(s, "Para mostrar la barra antes de la primera entrega: &e/tod4eventos:ofrenda activar " + id);
            }
            case "puerta" -> {
                if (p == null) return;
                Location[] sel = pl.seleccion(p);
                if (sel == null) return;
                ponerPuerta(sec(id), sel);
                st.save();
                TOD4Eventos.ok(s, "Puerta de &f" + id + "&a cambiada.");
            }
            case "cofre" -> {
                if (p == null) return;
                Block b = TOD4Eventos.mirando(p);
                if (!(b.getState(false) instanceof Container)) { TOD4Eventos.err(s, "Mira un cofre o barril."); return; }
                sec(id).set("cofre", b.getLocation());
                st.save();
                indexar();
                TOD4Eventos.ok(s, "Cofre de &f" + id + "&a cambiado.");
            }
            case "item" -> {
                if (p == null) return;
                String item = TOD4Eventos.idDe(p.getInventory().getItemInMainHand());
                if (item == null) { TOD4Eventos.err(s, "Ten el ítem en la mano."); return; }
                sec(id).set("item", item);
                st.save();
                TOD4Eventos.ok(s, "Ahora &f" + id + "&a acepta &f" + TOD4Eventos.nombre(item) + "&a.");
            }
            case "cantidad" -> {
                if (a.length < 3) { TOD4Eventos.info(s, "Uso: &e/tod4eventos:ofrenda cantidad <nombre> <n>"); return; }
                try { sec(id).set("necesarias", Math.max(1, Integer.parseInt(a[2]))); } catch (NumberFormatException ex) { TOD4Eventos.err(s, "Tiene que ser un número."); return; }
                st.save();
                if (barras.containsKey(id)) actualizarBarra(id);
                TOD4Eventos.ok(s, "Ahora hacen falta &f" + a[2] + "&a.");
            }
            case "titulo" -> {
                if (a.length < 3) { TOD4Eventos.info(s, "Uso: &e/tod4eventos:ofrenda titulo <nombre> <texto...>"); return; }
                sec(id).set("titulo", String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)));
                st.save();
                if (barras.containsKey(id)) actualizarBarra(id);
                TOD4Eventos.ok(s, "Título cambiado.");
            }
            case "comando" -> {
                ConfigurationSection c = sec(id);
                List<String> l = c.getStringList("comandos");
                if (a.length >= 3 && a[2].equalsIgnoreCase("limpiar")) { c.set("comandos", null); st.save(); TOD4Eventos.ok(s, "Comandos borrados."); return; }
                if (a.length < 4 || !a[2].equalsIgnoreCase("agregar")) {
                    TOD4Eventos.info(s, "Uso: &e/tod4eventos:ofrenda comando <nombre> agregar <comando sin />&7 o &elimpiar");
                    for (String x : l) TOD4Eventos.raw(s, " &8- &f" + x);
                    return;
                }
                l.add(String.join(" ", java.util.Arrays.copyOfRange(a, 3, a.length)));
                c.set("comandos", l);
                st.save();
                TOD4Eventos.ok(s, "Al abrirse va a correr: &f" + l.get(l.size() - 1));
            }
            case "activar" -> { activar(id); TOD4Eventos.ok(s, "Barra de &f" + id + "&a visible para todos."); }
            case "ocultar" -> { ocultar(id); sec(id).set("activa", false); st.touch(); TOD4Eventos.ok(s, "Barra oculta."); }
            case "abrir" -> { abrir(id); TOD4Eventos.ok(s, "Ofrenda &f" + id + "&a abierta."); }
            case "reset" -> { reset(id); TOD4Eventos.ok(s, "Ofrenda &f" + id + "&a reseteada: la puerta volvió y el contador está en 0."); }
            case "borrar" -> {
                ocultar(id);
                st.set("ofrendas." + id, null);
                guardado.set(id, null);
                st.save();
                indexar();
                TOD4Eventos.ok(s, "Ofrenda &f" + id + "&a borrada.");
            }
            case "info" -> {
                ConfigurationSection c = sec(id);
                TOD4Eventos.raw(s, "&6■ &f" + id + " &7- " + c.getString("titulo", "Almas") + ": &f" + c.getInt("actuales") + "/" + c.getInt("necesarias")
                        + " &7| ítem: &f" + TOD4Eventos.nombre(c.getString("item")) + " &7| " + (c.getBoolean("abierta") ? "&aabierta" : "&ccerrada"));
            }
            case "lista" -> {
                if (ids().isEmpty()) { TOD4Eventos.info(s, "No hay ofrendas."); return; }
                for (String x : ids()) {
                    ConfigurationSection c = sec(x);
                    TOD4Eventos.raw(s, "&6■ &f" + x + " &7" + c.getInt("actuales") + "/" + c.getInt("necesarias") + " "
                            + (c.getBoolean("abierta") ? "&aabierta" : "&ccerrada") + (barras.containsKey(x) ? " &e(barra visible)" : ""));
                }
            }
            default -> {
                TOD4Eventos.info(s, "&6Ofrendas:");
                TOD4Eventos.raw(s, " &e/tod4eventos:ofrenda pos1 &7/ &epos2 &8- &7esquinas de la puerta (mirando el bloque)");
                TOD4Eventos.raw(s, " &e... crear <nombre> <cantidad> [titulo] &8- &7mirando el cofre y con el ítem en la mano");
                TOD4Eventos.raw(s, " &e... activar | ocultar | abrir | reset | info | borrar <nombre>");
                TOD4Eventos.raw(s, " &e... puerta | cofre | item <nombre> &7/ &ecantidad <nombre> <n> &7/ &etitulo <nombre> <texto>");
                TOD4Eventos.raw(s, " &e... comando <nombre> agregar <comando> &8- &7se corre al abrirse");
                TOD4Eventos.raw(s, " &e... lista");
            }
        }
    }

    private static void ponerPuerta(ConfigurationSection c, Location[] sel) {
        c.set("puerta.mundo", sel[0].getWorld().getName());
        c.set("puerta.x1", Math.min(sel[0].getBlockX(), sel[1].getBlockX()));
        c.set("puerta.y1", Math.min(sel[0].getBlockY(), sel[1].getBlockY()));
        c.set("puerta.z1", Math.min(sel[0].getBlockZ(), sel[1].getBlockZ()));
        c.set("puerta.x2", Math.max(sel[0].getBlockX(), sel[1].getBlockX()));
        c.set("puerta.y2", Math.max(sel[0].getBlockY(), sel[1].getBlockY()));
        c.set("puerta.z2", Math.max(sel[0].getBlockZ(), sel[1].getBlockZ()));
    }

    List<String> tab(String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("pos1", "pos2", "crear", "activar", "ocultar", "abrir", "reset", "info", "borrar",
                "puerta", "cofre", "item", "cantidad", "titulo", "comando", "lista"));
        else if (a.length == 2 && !List.of("pos1", "pos2", "lista", "crear").contains(a[0].toLowerCase())) out.addAll(ids());
        else if (a.length == 3 && a[0].equalsIgnoreCase("comando")) out.addAll(List.of("agregar", "limpiar"));
        return out;
    }
}

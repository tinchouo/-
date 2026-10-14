package ar.tod4.revivir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import ar.tod4.TOD4;
import ar.tod4.data.Store;
import ar.tod4.util.Items;
import ar.tod4.util.Menu;
import ar.tod4.util.Style;
import ar.tod4.util.Txt;
import net.kyori.adventure.title.Title;

/**
 * Resurreccion + almas + tumbas (portado de revivir.sk).
 * Arreglos: el menu del item de revivir deja usar tu inventario; los items se comparan por tipo
 * (o por ID de Oraxen), asi un item de Oraxen funciona aunque Oraxen le actualice los datos.
 * Nuevo: aviso en el chat cuando alguien usa un totem.
 */
public final class Revivir implements Listener {

    private final TOD4 pl;
    private final Store st;
    private final NamespacedKey SELECTOR;
    private final Map<String, String> ultimoAtacante = new HashMap<>();
    private final Map<String, Long> ultimoAtacanteT = new HashMap<>();
    private final Map<String, String> tumbaAbiertaPor = new HashMap<>();
    private final Map<UUID, Boolean> cinematicaPendiente = new HashMap<>();

    public Revivir(TOD4 pl) {
        this.pl = pl;
        this.st = new Store(pl, "revivir");
        this.SELECTOR = new NamespacedKey(pl, "selector_altar");
        Bukkit.getPluginManager().registerEvents(this, pl);
        for (World w : Bukkit.getWorlds()) w.setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
        if (!st.y().contains("item_normal")) {
            st.set("item_normal", new ItemStack(Material.BLAZE_ROD));
            st.set("item_normal_cant", 1);
            st.set("item_prem", new ItemStack(Material.NETHER_STAR));
            st.set("item_prem_cant", 1);
        }
    }

    public Store store() { return st; }

    // ================================================================= almas

    public boolean tieneAlma(String n) { return st.y().getBoolean("almas." + n, true); }

    public void setAlma(String n, boolean v) { st.set("almas." + n, v); }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!st.y().contains("almas." + p.getName())) setAlma(p.getName(), true);
        List<String> pend = st.y().getStringList("pendiente_spawn");
        if (pend.remove(p.getName())) {
            st.set("pendiente_spawn", pend);
            Bukkit.getScheduler().runTaskLater(pl, () -> descenso(p), 5L);
        }
    }

    public void cmdAlma(org.bukkit.command.CommandSender s, String[] a) {
        if (a.length < 2) { Style.info(s, "Uso: &e/alma <give|remove> <jugador>"); return; }
        OfflinePlayer t = Bukkit.getOfflinePlayer(a[1]);
        String n = t.getName() != null ? t.getName() : a[1];
        if (a[0].equalsIgnoreCase("give") || a[0].equalsIgnoreCase("dar")) {
            setAlma(n, true);
            Style.ok(s, "Le diste un alma a &e" + n + "&a.");
            if (t.getPlayer() != null) {
                Style.raw(t.getPlayer(), "&e✦ &e&l¡HAS RECUPERADO TU ALMA!");
                t.getPlayer().playSound(t.getPlayer().getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 5f, 1f);
            }
        } else if (a[0].equalsIgnoreCase("remove") || a[0].equalsIgnoreCase("quitar")) {
            setAlma(n, false);
            Style.ok(s, "Le quitaste el alma a &e" + n + "&a.");
            if (t.getPlayer() != null) {
                Style.raw(t.getPlayer(), "&c✧ &c&lTE HAS QUEDADO SIN ALMA.");
                t.getPlayer().playSound(t.getPlayer().getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 0.5f);
            }
        } else Style.info(s, "Uso: &e/alma <give|remove> <jugador>");
    }

    // ================================================================= items de revivir

    private ItemStack item(boolean prem) { return st.y().getItemStack(prem ? "item_prem" : "item_normal"); }

    private int cant(boolean prem) { return st.y().getInt(prem ? "item_prem_cant" : "item_normal_cant", 1); }

    /** "premium", "normal" o "ninguno" segun lo que tenga en la mano. */
    private String modo(ItemStack mano) {
        if (mano == null || mano.getType().isAir()) return "ninguno";
        ItemStack ip = item(true), in = item(false);
        if (ip != null && Items.sameKind(mano, ip) && mano.getAmount() >= cant(true)) return "premium";
        if (in != null && Items.sameKind(mano, in) && mano.getAmount() >= cant(false)) return "normal";
        return "ninguno";
    }

    private String nombreItem(boolean prem) {
        ItemStack it = item(prem);
        return it == null ? "&cVacío" : cant(prem) + "x " + Items.name(it);
    }

    @EventHandler(ignoreCancelled = true)
    public void craft(CraftItemEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        for (ItemStack m : e.getInventory().getMatrix()) {
            if (m == null) continue;
            if (esSelector(m)) { e.setCancelled(true); Style.err(p, "No puedes usar el Selector de Altar para craftear."); return; }
            ItemStack in = item(false), ip = item(true);
            if ((in != null && Items.sameKind(m, in)) || (ip != null && Items.sameKind(m, ip))) {
                e.setCancelled(true);
                Style.err(p, "No puedes gastar el ítem de resurrección en un crafteo común.");
                return;
            }
        }
    }

    // ================================================================= altar

    private Location altar() { return st.y().getLocation("altar"); }

    private boolean esAltar(Location l, double r) {
        Location a = altar();
        if (a == null || l == null || a.getWorld() == null || !a.getWorld().equals(l.getWorld())) return false;
        return a.distance(l) < r;
    }

    private ItemStack selector() {
        ItemStack it = Items.glow(Items.of(Material.BREEZE_ROD, "&6&lSelector de Altar &8(Click Derecho)",
                "&7Click derecho en un bloque para", "&7asignar o desasignar el Altar."));
        ItemMeta m = it.getItemMeta();
        m.getPersistentDataContainer().set(SELECTOR, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private boolean esSelector(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(SELECTOR, PersistentDataType.BYTE);
    }

    public void abrirAdmin(Player p) {
        Style.click(p);
        Menu m = new Menu(3, "&8Configuración del Altar");
        m.set(10, Items.of(Material.CHEST, "&6&l1. Ítem NORMAL (Gasta Alma)", "", "&7Actual: &e" + nombreItem(false), "",
                "&7Este ítem requerirá que el jugador", "&7tenga y consuma su propia alma.", "", "&e▶ Click para configurar"),
                e -> editorItem(p, false));
        m.set(11, Items.of(Material.ENDER_CHEST, "&b&l2. Ítem PREMIUM (NO Gasta Alma)", "", "&7Actual: &b" + nombreItem(true), "",
                "&7Este ítem salta la restricción de", "&7almas. Ideal para revivir gratis.", "", "&e▶ Click para configurar"),
                e -> editorItem(p, true));
        m.set(13, Items.glow(Items.of(Material.BREEZE_ROD, "&e&l3. Asignar Bloque del Altar", "",
                "&7Estado: " + (altar() != null ? "&aUbicación guardada ✔" : "&cSin asignar ✖"), "",
                "&7Te da la Vara de Breeze encantada.", "&7• Click Derecho en bloque: &aAsignar", "&7• Click Derecho en el Altar: &cDesasignar",
                "", "&e▶ Click para recibir varita")), e -> { p.closeInventory(); p.getInventory().addItem(selector()); });
        boolean prueba = st.y().getBoolean("modo_prueba", false);
        m.set(15, prueba
                ? Items.of(Material.GOLD_BLOCK, "&6&l4. Modo Prueba: &a&lACTIVADO", "", "&7Al morir NO te banea. Solo", "&7te deja en Espectador.", "", "&e▶ Click para APAGAR")
                : Items.of(Material.IRON_BLOCK, "&7&l4. Modo Prueba: &c&lAPAGADO", "", "&7Al morir corre la cinemática y", "&7te banea a los 8 segundos.", "", "&e▶ Click para ENCENDER"),
                e -> { st.set("modo_prueba", !prueba); abrirAdmin(p); });
        m.set(16, Items.of(Material.NETHER_STAR, "&6&l5. Obtener Ítems Actuales", "", "&7Te da la cantidad exacta de ambos", "&7ítems para hacer pruebas."), e -> {
            for (boolean prem : new boolean[]{false, true}) {
                ItemStack it = item(prem);
                if (it == null) continue;
                ItemStack c = it.clone();
                c.setAmount(cant(prem));
                p.getInventory().addItem(c);
            }
            Style.click(p);
        });
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    /** Editor del item: el slot del medio es libre, y tu inventario se usa normal. */
    private void editorItem(Player p, boolean prem) {
        Style.click(p);
        Menu m = new Menu(3, prem ? "&8Ítem Premium (Sin Alma)" : "&8Ítem Normal (Usa Alma)");
        m.fill(Material.BLACK_STAINED_GLASS_PANE);
        m.editable(13);
        ItemStack actual = item(prem);
        if (actual != null) {
            ItemStack c = actual.clone();
            c.setAmount(cant(prem));
            m.getInventory().setItem(13, c);
        }
        final boolean[] guardado = {false};
        Runnable guardar = () -> {
            if (guardado[0]) return;
            guardado[0] = true;
            ItemStack nuevo = m.getInventory().getItem(13);
            String k = prem ? "item_prem" : "item_normal";
            if (nuevo == null || nuevo.getType().isAir()) {
                st.set(k, null);
                st.set(k + "_cant", null);
                Style.err(p, "Se vació el ítem de revivir " + (prem ? "premium" : "normal") + ".");
                return;
            }
            ItemStack uno = nuevo.clone();
            uno.setAmount(1);
            st.set(k, uno);
            st.set(k + "_cant", nuevo.getAmount());
            String ox = Items.oraxenId(uno);
            Style.ok(p, "Requisito " + (prem ? "PREMIUM" : "NORMAL") + " actualizado: &e" + nuevo.getAmount() + "x " + Items.name(uno)
                    + (ox != null ? " &7(Oraxen: " + ox + ")" : "") + "&a.");
            // el item vuelve al jugador
            m.getInventory().setItem(13, null);
            p.getInventory().addItem(nuevo).values().forEach(r -> p.getWorld().dropItem(p.getLocation(), r));
        };
        m.set(22, Items.of(Material.ARROW, "&a&l✔ Guardar Ítem y Volver", "", "&7Poné el ítem (y la cantidad) en el", "&7hueco central y tocá acá."),
                e -> { guardar.run(); abrirAdmin(p); });
        m.onClose(e -> guardar.run());
        m.open(p);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void click(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Block b = e.getClickedBlock();
        // varita del altar
        if (p.isOp() && esSelector(p.getInventory().getItemInMainHand())) {
            e.setCancelled(true);
            if (esAltar(b.getLocation(), 0.5)) {
                st.set("altar", null);
                p.playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1f, 1f);
                Style.err(p, "Altar desasignado.");
                return;
            }
            st.set("altar", b.getLocation());
            p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.2f);
            Style.ok(p, "Altar asignado en &e" + b.getType().name().toLowerCase() + "&a.");
            return;
        }
        // tumba
        String tid = tumbaId(b);
        if (st.y().isConfigurationSection("tumbas." + tid)) {
            e.setCancelled(true);
            abrirTumba(p, tid);
            return;
        }
        // altar
        if (esAltar(b.getLocation(), 0.5)) {
            e.setCancelled(true);
            String modo = modo(p.getInventory().getItemInMainHand());
            if (modo.equals("ninguno")) { Style.err(p, "Debes tener en la mano el ítem de resurrección."); return; }
            if (modo.equals("normal") && !tieneAlma(p.getName())) { Style.err(p, "No tienes alma para revivir."); return; }
            menuAlmas(p, modo);
        }
    }

    private void menuAlmas(Player p, String modo) {
        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.2f);
        Menu m = new Menu(6, "&8Altar");
        for (int i = 45; i < 54; i++) m.set(i, Items.pane(Material.GRAY_STAINED_GLASS_PANE));
        List<String> muertos = st.y().getStringList("muertos");
        boolean prem = modo.equals("premium");
        if (muertos.isEmpty()) m.set(22, Items.of(Material.SOUL_LANTERN, "&7No hay jugadores a revivir"));
        int slot = 0;
        for (String n : muertos) {
            if (slot >= 45) break;
            String motivo = st.y().getString("motivo." + n, "causas desconocidas");
            ItemStack cab = cabeza(n);
            ItemMeta meta = cab.getItemMeta();
            meta.displayName(Txt.c("&6&l" + n));
            meta.lore(Txt.lines("", "&7Motivo: &c" + motivo, "", prem ? "&b✦ Costo Premium (Sin Alma):" : "&6✦ Costo del Ritual (Gasta Alma):",
                    "&f" + nombreItem(prem), "", "&e▶ Click para revivir a " + n));
            cab.setItemMeta(meta);
            m.set(slot++, cab, e -> {
                if (!st.y().getStringList("muertos").contains(n)) { p.closeInventory(); Style.err(p, "Ese jugador ya no está en el limbo."); return; }
                if (!modo(p.getInventory().getItemInMainHand()).equals(modo)) { p.closeInventory(); Style.err(p, "Ya no tienes el ítem requerido en la mano."); return; }
                if (!prem && !tieneAlma(p.getName())) { p.closeInventory(); Style.err(p, "Ya has sacrificado tu alma."); return; }
                confirmar(p, n, modo);
            });
        }
        m.set(49, Items.of(Material.BARRIER, "&cCerrar Altar"), e -> p.closeInventory());
        m.open(p);
    }

    private void confirmar(Player p, String n, String modo) {
        Style.click(p);
        Menu m = new Menu(3, "&8Confirmar Resurrección");
        m.set(11, Items.of(Material.NETHER_STAR, "&a&lSÍ, CONFIRMAR RITUAL", "", "&7Invocará a &e" + n + "&7 de vuelta al mundo."), e -> {
            boolean prem = modo.equals("premium");
            if (!st.y().getStringList("muertos").contains(n) || !modo(p.getInventory().getItemInMainHand()).equals(modo)) { p.closeInventory(); return; }
            if (!prem && !tieneAlma(p.getName())) { p.closeInventory(); return; }
            ItemStack mano = p.getInventory().getItemInMainHand();
            mano.setAmount(mano.getAmount() - cant(prem));
            p.getInventory().setItemInMainHand(mano.getAmount() <= 0 ? null : mano);
            if (!prem) setAlma(p.getName(), false);
            p.closeInventory();
            revivir(p.getName(), n);
            if (!prem) Bukkit.getScheduler().runTaskLater(pl, () -> {
                Style.raw(p, "&c✧ &c&lTE HAS QUEDADO SIN ALMA.");
                p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 0.5f);
            }, 310L);
        });
        m.set(15, Items.of(Material.BARRIER, "&c&lNO, CANCELAR", "", "&7El ritual se cancelará."), e -> menuAlmas(p, modo));
        m.fill(Material.BLACK_STAINED_GLASS_PANE).open(p);
    }

    /** Revive a alguien (desde el altar o con /revivir). */
    public void revivir(String quien, String n) {
        List<String> muertos = st.y().getStringList("muertos");
        muertos.remove(n);
        st.set("muertos", muertos);
        st.set("motivo." + n, null);
        st.set("dia." + n, null);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pardon " + n);
        Location a = altar();
        if (a != null) a.getWorld().strikeLightningEffect(a);
        for (Player o : Bukkit.getOnlinePlayers())
            o.playSound(o.getLocation(), pl.getConfig().getString("revivir.sonido_ritual", "tod4:revivir"), SoundCategory.MASTER, 1f, 1f);
        Style.broadcast("&e✦ &e&l" + quien + " &7ha revivido a &e&l" + n + "&7.");
        Player t = Bukkit.getPlayerExact(n);
        if (t != null) descenso(t);
        else {
            List<String> pend = st.y().getStringList("pendiente_spawn");
            if (!pend.contains(n)) pend.add(n);
            st.set("pendiente_spawn", pend);
        }
    }

    public void cmdRevivir(org.bukkit.command.CommandSender s, String[] a) {
        if (a.length < 1) { Style.info(s, "Uso: &e/revivir <jugador>"); return; }
        String n = a[0];
        for (String m : st.y().getStringList("muertos")) if (m.equalsIgnoreCase(n)) n = m;
        if (!st.y().getStringList("muertos").contains(n)) { Style.err(s, "El jugador &e" + n + " &cno está muerto."); return; }
        revivir(s.getName(), n);
    }

    public void cmdAltar(Player p, String[] a) {
        if (a.length == 0) { abrirAdmin(p); return; }
        List<String> muertos = st.y().getStringList("muertos");
        switch (a[0].toLowerCase()) {
            case "agregar": case "add":
                if (a.length < 2) return;
                if (muertos.contains(a[1])) { Style.err(p, a[1] + " ya está en la lista."); return; }
                muertos.add(a[1]);
                st.set("muertos", muertos);
                st.set("motivo." + a[1], "prueba de admin");
                st.set("dia." + a[1], pl.dia());
                Style.ok(p, "Agregaste a &e" + a[1] + " &aa la lista de caídos.");
                break;
            case "quitar": case "remove":
                if (a.length < 2) return;
                muertos.remove(a[1]);
                st.set("muertos", muertos);
                Style.ok(p, "Quitaste a &e" + a[1] + " &ade la lista.");
                break;
            case "limpiar":
                st.set("muertos", new ArrayList<String>());
                Style.ok(p, "Lista vaciada.");
                break;
            default:
                abrirAdmin(p);
        }
    }

    // ================================================================= muerte

    @EventHandler(ignoreCancelled = true)
    public void dano(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player v)) return;
        Entity at = e.getDamager();
        if (at instanceof Projectile pr && pr.getShooter() instanceof Entity sh) at = sh;
        String nom = at instanceof Player ap ? ap.getName() : traducir(at.getType().name());
        ultimoAtacante.put(v.getName(), nom);
        ultimoAtacanteT.put(v.getName(), System.currentTimeMillis());
    }

    private String traducir(String tipo) {
        String k = tipo.toLowerCase();
        ConfigurationSection t = pl.getConfig().getConfigurationSection("revivir.nombres_mobs");
        if (t != null && t.contains(k)) return t.getString(k);
        String n = k.replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private String motivo(Player p) {
        String at = null;
        Long t = ultimoAtacanteT.get(p.getName());
        if (t != null && System.currentTimeMillis() - t < 10000) at = ultimoAtacante.get(p.getName());
        EntityDamageEvent last = p.getLastDamageCause();
        if (last == null) return "";
        switch (last.getCause()) {
            case ENTITY_ATTACK: case ENTITY_SWEEP_ATTACK: case PROJECTILE:
                return at != null ? "asesinado por " + at : "asesinado";
            case FALL: return at != null ? "una caída tratando de escapar de " + at : "caer desde un lugar alto";
            case LAVA: return at != null ? "nadar en lava tratando de escapar de " + at : "nadar en lava";
            case FIRE: case FIRE_TICK: case HOT_FLOOR: return at != null ? "quemaduras en combate con " + at : "quemaduras fatales";
            case DROWNING: return at != null ? "ahogamiento tratando de escapar de " + at : "ahogamiento";
            case VOID: return "caer al vacío";
            case BLOCK_EXPLOSION: case ENTITY_EXPLOSION: return at != null ? "una explosión provocada por " + at : "una explosión";
            case MAGIC: case POISON: case WITHER: return "magia oscura";
            case STARVATION: return "morir de hambre";
            case SUFFOCATION: return "asfixia en una pared";
            case CONTACT: case THORNS: return at != null ? "espinas al luchar contra " + at : "pincharse hasta morir";
            default: return "";
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void muerte(PlayerDeathEvent e) {
        Player v = e.getEntity();
        String n = v.getName();
        Location loc = v.getLocation();
        st.set("loc_muerte." + n, loc);
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack it : v.getInventory().getContents()) if (it != null && !it.getType().isAir()) items.add(it.clone());
        v.getInventory().clear();
        e.getDrops().clear();
        e.setKeepInventory(false);
        crearTumba(v, loc, items);

        String mot = motivo(v);
        String barra;
        if (mot.isEmpty()) {
            st.set("motivo." + n, "causas desconocidas");
            e.deathMessage(Txt.c("&4☠ &c&l" + n + " &7ha muerto."));
            barra = " ha muerto";
        } else {
            st.set("motivo." + n, mot);
            e.deathMessage(Txt.c("&4☠ &c&l" + n + " &7ha muerto por &c" + mot + "&7."));
            barra = " ha muerto por " + mot;
        }
        List<String> muertos = st.y().getStringList("muertos");
        if (!muertos.contains(n)) {
            muertos.add(n);
            st.set("muertos", muertos);
            st.set("dia." + n, pl.dia());
        }
        cinematicaPendiente.put(v.getUniqueId(), true);
        for (String cmd : pl.getConfig().getStringList("revivir.comandos_muerte")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        for (Player o : Bukkit.getOnlinePlayers())
            o.playSound(o.getLocation(), pl.getConfig().getString("revivir.sonido_muerte", "minecraft:dedsafio"), SoundCategory.MASTER, 1f, 1f);
        Bukkit.getScheduler().runTask(pl, () -> { if (v.isDead()) v.spigot().respawn(); });
        String ab = "&2&l" + n + "&2" + barra;
        for (long d : new long[]{6, 46, 56}) Bukkit.getScheduler().runTaskLater(pl, () -> {
            for (Player o : Bukkit.getOnlinePlayers()) o.sendActionBar(Txt.c(ab));
        }, d);
        Bukkit.getScheduler().runTaskLater(pl, () -> { for (Player o : Bukkit.getOnlinePlayers()) o.sendActionBar(Txt.c("")); }, 112L);
    }

    @EventHandler
    public void respawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (cinematicaPendiente.remove(p.getUniqueId()) == null) return;
        Location l = st.y().getLocation("loc_muerte." + p.getName());
        if (l != null) e.setRespawnLocation(l);
        Bukkit.getScheduler().runTaskLater(pl, () -> p.setGameMode(GameMode.SPECTATOR), 1L);
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (st.y().getBoolean("modo_prueba", false) || !p.isOnline()) return;
            if (!st.y().getStringList("muertos").contains(p.getName())) return;
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban " + p.getName() + " Has muerto y has sido baneado automáticamente.");
            if (p.isOnline()) p.kick(Txt.c("&c&l¡HAS MUERTO!\n\n&7Has sido baneado automáticamente."));
        }, 161L);
    }

    // ================================================================= totem

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void totem(EntityResurrectEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        ItemStack t = null;
        for (ItemStack it : new ItemStack[]{p.getInventory().getItemInMainHand(), p.getInventory().getItemInOffHand()})
            if (it != null && it.getType() == Material.TOTEM_OF_UNDYING) { t = it; break; }
        String nombre = pl.getConfig().getString("revivir.nombre_totem", "Tótem Kui");
        if (t != null && t.hasItemMeta() && t.getItemMeta().hasDisplayName()) nombre = Txt.plain(t.getItemMeta().displayName());
        Style.broadcast(pl.getConfig().getString("revivir.mensaje_totem", "&6☩ &6&l{jugador} &7ha usado un &6&l{totem}&7.")
                .replace("{jugador}", p.getName()).replace("{totem}", nombre));
    }

    // ================================================================= tumbas

    private static String tumbaId(Block b) {
        return b.getWorld().getName() + "_" + b.getX() + "_" + b.getY() + "_" + b.getZ();
    }

    private ItemStack cabeza(String n) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) it.getItemMeta();
        sm.setOwningPlayer(Bukkit.getOfflinePlayer(n));
        it.setItemMeta(sm);
        return it;
    }

    private void crearTumba(Player p, Location loc, List<ItemStack> items) {
        Block b = loc.getBlock();
        for (int i = 0; i < 60; i++) {
            boolean ocupado = st.y().isConfigurationSection("tumbas." + tumbaId(b)) || esAltar(b.getLocation(), 0.5) || !b.getType().isAir();
            if (!ocupado) break;
            b = b.getRelative(0, 1, 0);
        }
        String id = tumbaId(b);
        b.setType(Material.PLAYER_HEAD);
        if (b.getState() instanceof Skull s) { s.setOwningPlayer(p); s.update(); }
        ItemStack cab = cabeza(p.getName());
        ItemMeta m = cab.getItemMeta();
        m.displayName(Txt.c("&fCabeza de &6" + p.getName()));
        m.lore(Txt.lines("&7#" + (10000 + new java.util.Random().nextInt(90000))));
        cab.setItemMeta(m);
        st.set("tumbas." + id + ".dueno", p.getName());
        st.set("tumbas." + id + ".cabeza", cab);
        for (int i = 0; i < items.size() && i < 54; i++) st.set("tumbas." + id + ".items." + i, items.get(i));
    }

    private void abrirTumba(Player p, String id) {
        String abierta = tumbaAbiertaPor.get(id);
        if (abierta != null && !abierta.equals(p.getName())) { Style.err(p, "Alguien más está revisando esta tumba."); return; }
        tumbaAbiertaPor.put(id, p.getName());
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.8f, 0.9f);
        Inventory inv = Bukkit.createInventory(new TumbaHolder(id), 54, Txt.c("&8Tumba de " + st.y().getString("tumbas." + id + ".dueno", "?")));
        ConfigurationSection s = st.y().getConfigurationSection("tumbas." + id + ".items");
        if (s != null) for (String k : s.getKeys(false)) {
            try { inv.setItem(Integer.parseInt(k), s.getItemStack(k)); } catch (NumberFormatException ignored) { }
        }
        p.openInventory(inv);
    }

    static final class TumbaHolder implements org.bukkit.inventory.InventoryHolder {
        final String id;
        TumbaHolder(String id) { this.id = id; }
        @Override public Inventory getInventory() { return null; }
    }

    @EventHandler
    public void cierraTumba(org.bukkit.event.inventory.InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof TumbaHolder h)) return;
        tumbaAbiertaPor.remove(h.id);
        if (!st.y().isConfigurationSection("tumbas." + h.id)) return;
        guardarTumba(h.id, e.getInventory());
        if (e.getPlayer() instanceof Player p) p.playSound(p.getLocation(), Sound.BLOCK_CHEST_CLOSE, 0.8f, 0.9f);
    }

    private void guardarTumba(String id, Inventory inv) {
        st.set("tumbas." + id + ".items", null);
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && !it.getType().isAir()) st.set("tumbas." + id + ".items." + i, it);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void rompe(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (esAltar(b.getLocation(), 0.5)) { e.setCancelled(true); Style.err(e.getPlayer(), "¡El Altar es indestructible!"); return; }
        String id = tumbaId(b);
        if (!st.y().isConfigurationSection("tumbas." + id)) return;
        e.setCancelled(true);
        String viendo = tumbaAbiertaPor.get(id);
        if (viendo != null) {
            Player v = Bukkit.getPlayerExact(viendo);
            if (v != null && v.getOpenInventory().getTopInventory().getHolder() instanceof TumbaHolder h && h.id.equals(id)) {
                guardarTumba(id, v.getOpenInventory().getTopInventory());
                tumbaAbiertaPor.remove(id);
                v.closeInventory();
            }
        }
        Location l = b.getLocation().add(0.5, 0.5, 0.5);
        b.setType(Material.AIR);
        ConfigurationSection s = st.y().getConfigurationSection("tumbas." + id + ".items");
        if (s != null) for (String k : s.getKeys(false)) {
            ItemStack it = s.getItemStack(k);
            if (it != null) b.getWorld().dropItemNaturally(l, it).getPersistentDataContainer()
                    .set(new NamespacedKey(pl, "tirado"), PersistentDataType.BYTE, (byte) 1);
        }
        ItemStack cab = st.y().getItemStack("tumbas." + id + ".cabeza");
        if (cab != null) b.getWorld().dropItemNaturally(l, cab);
        e.getPlayer().playSound(l, Sound.BLOCK_BONE_BLOCK_BREAK, 1f, 1f);
        st.set("tumbas." + id, null);
    }

    @EventHandler(ignoreCancelled = true)
    public void explota(EntityExplodeEvent e) { e.blockList().removeIf(this::protegido); }

    @EventHandler(ignoreCancelled = true)
    public void explotaBloque(BlockExplodeEvent e) { e.blockList().removeIf(this::protegido); }

    private boolean protegido(Block b) {
        return esAltar(b.getLocation(), 1.5) || st.y().isConfigurationSection("tumbas." + tumbaId(b));
    }

    @EventHandler(ignoreCancelled = true)
    public void liquido(BlockFromToEvent e) {
        if (st.y().isConfigurationSection("tumbas." + tumbaId(e.getToBlock()))) e.setCancelled(true);
    }

    // ================================================================= cinematica

    public void descenso(Player p) {
        p.setGameMode(GameMode.SURVIVAL);
        p.setHealth(Math.min(10.0, p.getMaxHealth()));
        p.setFoodLevel(10);
        for (PotionEffect ef : p.getActivePotionEffects()) p.removePotionEffect(ef.getType());
        Location base = altar() != null ? altar().clone() : p.getWorld().getSpawnLocation();
        Location aire = base.clone().add(0.5, 72, 0.5);
        p.teleport(aire);
        World w = aire.getWorld();
        w.spawnParticle(Particle.FLASH, aire, 1);
        w.spawnParticle(Particle.FIREWORK, aire, 300, 2, 1, 2, 0.2);
        w.spawnParticle(Particle.END_ROD, aire, 200, 3, 2, 3, 0.1);
        p.playSound(aire, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 2f, 0.8f);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 600, 0));
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 640, 4));
        p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 500, 0));
        w.strikeLightningEffect(p.getLocation());
        Location c1 = base.clone().add(0.5, 18, 0.5), c2 = base.clone().add(0.5, 54, 0.5);
        final int[] ciclos = {0};
        Bukkit.getScheduler().runTaskTimer(pl, task -> {
            if (!p.isOnline() || ciclos[0]++ >= 62) {
                task.cancel();
                if (p.isOnline()) {
                    p.playSound(p.getLocation(), pl.getConfig().getString("revivir.sonido_revivido", "tod4:revivido"), SoundCategory.MASTER, 1f, 1f);
                    p.showTitle(Title.title(Txt.c("&6&l¡HAS &e&lREVIVIDO!"), Txt.c(""), Title.Times.times(java.time.Duration.ofMillis(500),
                            java.time.Duration.ofMillis(500), java.time.Duration.ofMillis(500))));
                }
                return;
            }
            for (Location c : new Location[]{c1, c2}) {
                w.spawnParticle(Particle.END_ROD, c, 120, 0.08, 12, 0.08, 0, null, true);
                w.spawnParticle(Particle.FIREWORK, c, 80, 0.04, 12, 0.04, 0, null, true);
            }
        }, 0L, 5L);
    }
}

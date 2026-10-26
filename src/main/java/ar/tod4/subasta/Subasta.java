package ar.tod4.subasta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import ar.tod4.TOD4;
import ar.tod4.hook.Ext;
import ar.tod4.util.Items;
import ar.tod4.util.Style;
import ar.tod4.util.Txt;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Subasta simple: los admins eligen quienes pujan, empiezan la subasta y la cierran a mano (cuenta 1, 2, 3).
 * Todo se cuenta en monedas amarillas (1 roja = 10, 1 morada = 100) y en el chat sale ordenado con los iconos:
 * "WestCOL ha pujado 1 [morada] 2 [roja] 5 [amarilla]."
 * Al pujar solo se revisa que tenga las monedas; al cerrar se le cobran al que gano (con vuelto).
 */
public final class Subasta implements Listener {
    private static final NamespacedKey ORAXEN = new NamespacedKey("oraxen", "id");
    private static final Key FUENTE = Key.key("tod4", "monedas");

    private final TOD4 pl;
    private final NamespacedKey kPuja;
    private final Set<UUID> pujadores = new LinkedHashSet<>();
    private final Map<UUID, Long> ultimo = new HashMap<>();
    private boolean activa;
    private int mayor, minimo;
    private UUID ganador;

    public Subasta(TOD4 pl) {
        this.pl = pl;
        this.kPuja = new NamespacedKey(pl, "puja_rapida");
        for (String s : pl.datos().y().getStringList("subasta.pujadores")) {
            try { pujadores.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) { }
        }
        Bukkit.getPluginManager().registerEvents(this, pl);
    }

    // ================================================================= monedas

    /** Oraxen id -> valor en amarillas (de mayor a menor). */
    private Map<String, Integer> monedas() {
        Map<String, Integer> m = new LinkedHashMap<>();
        ConfigurationSection c = pl.getConfig().getConfigurationSection("subasta.monedas");
        if (c != null) for (String k : c.getKeys(false)) m.put(k, c.getInt(k));
        if (m.isEmpty()) { m.put("moneda_morada", 100); m.put("moneda_roja", 10); m.put("moneda", 1); }
        List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        l.sort((a, b) -> b.getValue() - a.getValue());
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : l) out.put(e.getKey(), e.getValue());
        return out;
    }

    private static String idDe(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(ORAXEN, PersistentDataType.STRING);
    }

    /** Cuanto tiene en monedas (en amarillas). */
    public int saldo(Player p) {
        Map<String, Integer> m = monedas();
        int t = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            Integer v = m.get(idDe(it));
            if (v != null) t += v * it.getAmount();
        }
        return t;
    }

    /** El icono de cada moneda (un caracter de la fuente tod4:monedas del pack). */
    private String icono(String id) {
        String def = switch (id) {
            case "moneda_morada" -> "";
            case "moneda_roja" -> "";
            default -> "";
        };
        return pl.getConfig().getString("subasta.iconos." + id, def);
    }

    /** 125 -> "1 [morada] 2 [roja] 5 [amarilla]" (sin las que esten en 0; las moradas pueden pasar de 9). */
    public Component monto(int n) {
        Component c = Component.empty();
        boolean primero = true;
        int resto = n;
        for (Map.Entry<String, Integer> e : monedas().entrySet()) {
            int q = resto / e.getValue();
            resto %= e.getValue();
            if (q <= 0) continue;
            if (!primero) c = c.append(Component.text(" "));
            c = c.append(Component.text(q + " ")).append(Component.text(icono(e.getKey())).font(FUENTE).color(NamedTextColor.WHITE));
            primero = false;
        }
        if (primero) c = c.append(Component.text("0"));
        return c;
    }

    /**
     * Cobra: saca todas sus monedas y le devuelve el vuelto ordenado (lo que no entre, al piso).
     * Devuelve false si no le alcanza (y no toca nada).
     */
    private boolean cobrar(Player p, int precio) {
        Map<String, Integer> m = monedas();
        int total = saldo(p);
        if (total < precio) return false;
        Map<String, ItemStack> muestra = new HashMap<>();
        ItemStack[] inv = p.getInventory().getContents();
        for (int i = 0; i < inv.length; i++) {
            String id = idDe(inv[i]);
            if (id == null || !m.containsKey(id)) continue;
            muestra.putIfAbsent(id, inv[i].clone());
            p.getInventory().setItem(i, null);
        }
        int vuelto = total - precio;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            int q = vuelto / e.getValue();
            vuelto %= e.getValue();
            if (q <= 0) continue;
            ItemStack base = muestra.get(e.getKey());
            if (base == null) base = Ext.oraxenItem(e.getKey());
            if (base == null) { pl.getLogger().warning("Subasta: no pude dar el vuelto de " + q + " " + e.getKey() + " a " + p.getName()); continue; }
            while (q > 0) {
                ItemStack it = base.clone();
                int n = Math.min(q, it.getMaxStackSize());
                it.setAmount(n);
                q -= n;
                for (ItemStack sobra : p.getInventory().addItem(it).values()) p.getWorld().dropItem(p.getLocation(), sobra);
            }
        }
        return true;
    }

    // ================================================================= pujar

    private ItemStack itemPuja() {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Txt.c("&b&lPuja rápida").decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Txt.c("&7Click derecho: &f+1 &7amarilla sobre la más alta").decoration(TextDecoration.ITALIC, false),
                Txt.c("&7Shift + click derecho: &f+10 &7(una roja)").decoration(TextDecoration.ITALIC, false),
                Txt.c("&7Otra cantidad: &e/pujar <cantidad en amarillas>").decoration(TextDecoration.ITALIC, false)));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(kPuja, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    private boolean esItemPuja(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(kPuja, PersistentDataType.BYTE);
    }

    private void darItem(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (esItemPuja(it)) return;
        for (ItemStack sobra : p.getInventory().addItem(itemPuja()).values()) p.getWorld().dropItem(p.getLocation(), sobra);
    }

    private void sacarItem(Player p) {
        ItemStack[] inv = p.getInventory().getContents();
        for (int i = 0; i < inv.length; i++) if (esItemPuja(inv[i])) p.getInventory().setItem(i, null);
    }

    /** Una puja. cantidad = total en amarillas. */
    public void pujar(Player p, int cantidad) {
        if (!activa) { Style.err(p, "No hay ninguna subasta en este momento."); return; }
        if (!pujadores.contains(p.getUniqueId())) { Style.err(p, "No estás en la lista de los que pujan."); return; }
        long ahora = System.currentTimeMillis();
        Long antes = ultimo.get(p.getUniqueId());
        if (antes != null && ahora - antes < 300) return;   // doble click
        ultimo.put(p.getUniqueId(), ahora);
        if (p.getUniqueId().equals(ganador)) { Style.err(p, "Ya vas ganando la subasta."); return; }
        if (cantidad <= mayor) {
            p.sendMessage(Txt.c(Style.PREFIX + "&cTiene que ser más que la puja más alta: ").append(monto(mayor).color(NamedTextColor.WHITE)));
            return;
        }
        if (cantidad < minimo) {
            p.sendMessage(Txt.c(Style.PREFIX + "&cLa puja mínima es ").append(monto(minimo).color(NamedTextColor.WHITE)));
            return;
        }
        int saldo = saldo(p);
        if (saldo < cantidad) {
            p.sendMessage(Txt.c(Style.PREFIX + "&cNo te alcanza. Tenés ").append(monto(saldo).color(NamedTextColor.WHITE)));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }
        mayor = cantidad;
        ganador = p.getUniqueId();
        Component msg = Component.text(p.getName() + " ha pujado ", NamedTextColor.AQUA)
                .append(monto(cantidad).color(NamedTextColor.AQUA)).append(Component.text(".", NamedTextColor.AQUA));
        Bukkit.broadcast(msg);
        for (Player o : Bukkit.getOnlinePlayers()) o.playSound(o.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 0.6f, 1.6f);
    }

    /** Puja rapida: +1 (o +10 agachado) sobre la mas alta, calculado en el momento (dos a la vez no empatan). */
    @EventHandler(priority = EventPriority.LOW)
    public void usaItem(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !esItemPuja(e.getItem())) return;
        e.setCancelled(true);
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        int base = Math.max(mayor, minimo > 0 ? minimo - 1 : 0);
        pujar(e.getPlayer(), base + (e.getPlayer().isSneaking() ? 10 : 1));
    }

    /** Si se desconecto con el item de puja y la subasta ya termino (o ya no puja), se le saca. */
    @EventHandler
    public void entra(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!activa || !pujadores.contains(p.getUniqueId())) sacarItem(p);
        else darItem(p);
    }

    @EventHandler(ignoreCancelled = true)
    public void tira(PlayerDropItemEvent e) {
        if (esItemPuja(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    /** /pujar [cantidad]  (sin cantidad = +1) */
    public void cmdPujar(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) return;
        if (a.length == 0) {
            pujar(p, Math.max(mayor, minimo > 0 ? minimo - 1 : 0) + 1);
            return;
        }
        try {
            int n = Integer.parseInt(a[0]);
            if (n <= 0) throw new NumberFormatException();
            pujar(p, n);
        } catch (NumberFormatException ex) {
            Style.err(p, "Uso: &e/pujar <cantidad en amarillas>&c. Ejemplo: &e/pujar 125 &c= 1 morada, 2 rojas y 5 amarillas.");
        }
    }

    // ================================================================= admin: /tod4 subasta

    private void guardarPujadores() {
        List<String> l = new ArrayList<>();
        for (UUID u : pujadores) l.add(u.toString());
        pl.datos().set("subasta.pujadores", l);
    }

    private static String nombre(UUID u) {
        OfflinePlayer o = Bukkit.getOfflinePlayer(u);
        return o.getName() == null ? u.toString().substring(0, 8) : o.getName();
    }

    public void cmd(CommandSender s, String[] a) {
        String sub = a.length > 1 ? a[1].toLowerCase() : "";
        switch (sub) {
            case "pujador" -> {
                if (a.length < 3) { Style.err(s, "Uso: &e/tod4 subasta pujador <jugador>"); return; }
                OfflinePlayer o = Bukkit.getOfflinePlayer(a[2]);
                if (pujadores.remove(o.getUniqueId())) {
                    Style.ok(s, "&f" + a[2] + " &aya no puja.");
                    if (o.getPlayer() != null) sacarItem(o.getPlayer());
                } else {
                    pujadores.add(o.getUniqueId());
                    Style.ok(s, "&f" + a[2] + " &apuede pujar.");
                    if (activa && o.getPlayer() != null) darItem(o.getPlayer());
                }
                guardarPujadores();
            }
            case "pujadores" -> {
                if (a.length > 2 && a[2].equalsIgnoreCase("borrar")) {
                    for (UUID u : pujadores) { Player p = Bukkit.getPlayer(u); if (p != null) sacarItem(p); }
                    pujadores.clear();
                    guardarPujadores();
                    Style.ok(s, "Lista de los que pujan vacía.");
                    return;
                }
                if (pujadores.isEmpty()) { Style.info(s, "Nadie puede pujar todavía. &e/tod4 subasta pujador <jugador>"); return; }
                List<String> n = new ArrayList<>();
                for (UUID u : pujadores) n.add((Bukkit.getPlayer(u) != null ? "&a" : "&7") + nombre(u));
                Style.info(s, "Pujan (&averde&7 = conectado): " + String.join("&7, ", n));
            }
            case "empezar" -> {
                if (activa) { Style.err(s, "Ya hay una subasta. &e/tod4 subasta cerrar &co &e/tod4 subasta cancelar"); return; }
                if (pujadores.isEmpty()) { Style.err(s, "Primero elegí quiénes pujan: &e/tod4 subasta pujador <jugador>"); return; }
                minimo = 0;
                if (a.length > 2) try { minimo = Math.max(0, Integer.parseInt(a[2])); } catch (NumberFormatException ignored) { }
                activa = true;
                mayor = 0;
                ganador = null;
                Component que = Component.empty();
                if (s instanceof Player p && !p.getInventory().getItemInMainHand().getType().isAir()) {
                    ItemStack it = p.getInventory().getItemInMainHand();
                    que = Component.text(" Se subasta: ", NamedTextColor.AQUA)
                            .append(Items.nombre(it).colorIfAbsent(NamedTextColor.WHITE).hoverEvent(it.asHoverEvent()))
                            .append(Component.text(it.getAmount() > 1 ? " x" + it.getAmount() : "", NamedTextColor.WHITE));
                }
                Bukkit.broadcast(Component.empty());
                Bukkit.broadcast(Txt.c("&b&l¡EMPIEZA LA SUBASTA!").append(que));
                if (minimo > 0) Bukkit.broadcast(Txt.c("&bPuja mínima: ").append(monto(minimo).color(NamedTextColor.WHITE)));
                Bukkit.broadcast(Component.empty());
                for (Player o : Bukkit.getOnlinePlayers()) o.playSound(o.getLocation(), Sound.BLOCK_BELL_USE, SoundCategory.MASTER, 1f, 1f);
                for (UUID u : pujadores) { Player p = Bukkit.getPlayer(u); if (p != null) darItem(p); }
            }
            case "reiniciar" -> {
                if (!activa) { Style.err(s, "No hay ninguna subasta."); return; }
                mayor = 0;
                ganador = null;
                Bukkit.broadcast(Txt.c("&b&lSUBASTA &7» &bSe reinició: empieza de cero."));
            }
            case "cerrar" -> {
                if (!activa) { Style.err(s, "No hay ninguna subasta."); return; }
                if (ganador == null) {
                    terminar();
                    Bukkit.broadcast(Txt.c("&b&lSUBASTA &7» &bTerminó sin pujas."));
                    return;
                }
                Player g = Bukkit.getPlayer(ganador);
                if (g == null) { Style.err(s, nombre(ganador) + " no está conectado: no le puedo cobrar. Esperalo, o &e/tod4 subasta reiniciar&c."); return; }
                if (!cobrar(g, mayor)) {
                    Style.err(s, g.getName() + " ya no tiene las monedas (tiene " + saldo(g) + " en amarillas). No se cerró: &e/tod4 subasta reiniciar &co cerrala de nuevo.");
                    return;
                }
                int precio = mayor;
                terminar();
                Bukkit.broadcast(Component.empty());
                Bukkit.broadcast(Txt.c("&b&l¡VENDIDO! &b" + g.getName() + " gana la subasta con ").append(monto(precio).color(NamedTextColor.AQUA)).append(Component.text(".", NamedTextColor.AQUA)));
                Bukkit.broadcast(Component.empty());
                for (Player o : Bukkit.getOnlinePlayers()) o.playSound(o.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 1f, 1f);
                Style.ok(s, "Ya le cobré a " + g.getName() + ". El ítem dáselo vos.");
            }
            case "cancelar" -> {
                if (!activa) { Style.err(s, "No hay ninguna subasta."); return; }
                terminar();
                Bukkit.broadcast(Txt.c("&b&lSUBASTA &7» &bSe canceló. No se cobró nada."));
            }
            case "estado" -> {
                if (!activa) { Style.info(s, "No hay ninguna subasta."); return; }
                s.sendMessage(Txt.c(Style.PREFIX + "&7Va ganando &f" + (ganador == null ? "nadie" : nombre(ganador)) + " &7con ").append(monto(mayor).color(NamedTextColor.WHITE)));
            }
            default -> {
                Style.info(s, "&e/tod4 subasta pujador <jugador> &7- agrega o saca a alguien de los que pujan");
                Style.info(s, "&e/tod4 subasta pujadores [borrar] &7- lista (o vaciarla)");
                Style.info(s, "&e/tod4 subasta empezar [mínimo] &7- con el ítem en la mano, lo muestra en el chat");
                Style.info(s, "&e/tod4 subasta reiniciar &7- vuelve a cero");
                Style.info(s, "&e/tod4 subasta cerrar &7- le cobra al que va ganando y lo anuncia");
                Style.info(s, "&e/tod4 subasta cancelar &7| &e/tod4 subasta estado");
            }
        }
    }

    private void terminar() {
        activa = false;
        for (UUID u : pujadores) { Player p = Bukkit.getPlayer(u); if (p != null) sacarItem(p); }
        mayor = 0;
        ganador = null;
    }

    public List<String> tab(String[] a) {
        if (a.length == 2) return List.of("pujador", "pujadores", "empezar", "reiniciar", "cerrar", "cancelar", "estado");
        if (a.length == 3 && a[1].equalsIgnoreCase("pujador")) {
            List<String> l = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) l.add(p.getName());
            return l;
        }
        return List.of();
    }
}

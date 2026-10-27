package ar.tod4.aether;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import ar.tod4.aether.util.Ext;
import ar.tod4.aether.util.Style;
import ar.tod4.aether.util.Txt;

/**
 * Loot de los cofres del Aether (plugins/TOD4Aether/loot.yml). El cofre sigue teniendo su tabla del datapack
 * (aether:chests/...), pero lo que trae lo decide el plugin al abrirlo:
 *   tier1 = tier0_small (ruinitas al final de las ramas)   tier2 = tier1_grid (casas)
 *   tier3 = ruin (ruinas, hongo, coliseo, islitas del molino)   tier4 = mega (arriba del molino, centro del coliseo)
 * En las islas de hongo un cofre mega da tier3 (ahi no va el tier maximo).
 * Los items quedan desordenados en el cofre, como el loot normal (el juego los reparte despues).
 */
final class Loot implements Listener {

    private static final Set<String> TABLAS = Set.of("chests/tier0_small", "chests/tier1_grid", "chests/ruin", "chests/mega");

    private final TOD4Aether pl;
    private final Random rnd = new Random();
    private YamlConfiguration y;
    private File f;

    Loot(TOD4Aether pl) {
        this.pl = pl;
        recargar();
        Bukkit.getPluginManager().registerEvents(this, pl);
    }

    void recargar() {
        f = new File(pl.getDataFolder(), "loot.yml");
        if (!f.exists()) pl.saveResource("loot.yml", false);
        y = YamlConfiguration.loadConfiguration(f);
    }

    static String tier(String tabla) {
        return switch (tabla) {
            case "chests/tier0_small" -> "tier1";
            case "chests/tier1_grid" -> "tier2";
            case "chests/ruin" -> "tier3";
            case "chests/mega" -> "tier4";
            default -> null;
        };
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void genera(LootGenerateEvent e) {
        NamespacedKey k = e.getLootTable().getKey();
        if (!k.getNamespace().equals("aether") || !TABLAS.contains(k.getKey())) return;
        if (!y.getBoolean("activado", true)) return;
        String t = tier(k.getKey());
        Location l = e.getLootContext().getLocation();
        if (t.equals("tier4") && l != null && l.getWorld() != null) {
            if (Ext.bioma(l).equals("mushroom_island")) t = "tier3";
        }
        List<ItemStack> out = tirar(t);
        if (out.isEmpty()) return;          // tier vacio en loot.yml: queda el del datapack
        e.setLoot(out);
    }

    /** Una tirada de un tier (los stacks ya partidos al maximo de cada item). */
    List<ItemStack> tirar(String tier) {
        List<ItemStack> out = new ArrayList<>();
        for (String linea : y.getStringList(tier)) {
            try {
                ItemStack it = renglon(linea);
                if (it != null) partir(it, out);
            } catch (Exception ex) {
                pl.getLogger().warning("[Loot] Renglon raro en " + tier + ": \"" + linea + "\" (" + ex.getMessage() + ")");
            }
        }
        return out;
    }

    /** "<item> <cantidad> [chance%]"  ->  item con su cantidad, o null si no salio. */
    private ItemStack renglon(String linea) {
        String[] p = linea.trim().split("\\s+");
        if (p.length == 0 || p[0].isEmpty()) return null;
        double chance = 100;
        String cant = "1";
        for (int i = 1; i < p.length; i++) {
            if (p[i].endsWith("%")) chance = Double.parseDouble(p[i].substring(0, p[i].length() - 1).replace(',', '.'));
            else cant = p[i];
        }
        if (rnd.nextDouble() * 100 >= chance) return null;
        int n = cantidad(cant);
        if (n <= 0) return null;
        ItemStack base = item(p[0]);
        if (base == null) {
            pl.getLogger().warning("[Loot] No existe el item " + p[0]);
            return null;
        }
        base.setAmount(n);
        return base;
    }

    /** "5", "2-5" o "0:25,1:50,2:25" (cantidad:peso). */
    private int cantidad(String s) {
        if (s.contains(":")) {
            List<int[]> op = new ArrayList<>();
            int total = 0;
            for (String x : s.split(",")) {
                String[] q = x.split(":");
                int c = Integer.parseInt(q[0].trim()), w = Integer.parseInt(q[1].trim());
                op.add(new int[]{c, w});
                total += w;
            }
            int r = rnd.nextInt(Math.max(1, total));
            for (int[] o : op) { if (r < o[1]) return o[0]; r -= o[1]; }
            return 0;
        }
        if (s.contains("-")) {
            String[] q = s.split("-");
            int a = Integer.parseInt(q[0].trim()), b = Integer.parseInt(q[1].trim());
            return a + rnd.nextInt(Math.max(1, b - a + 1));
        }
        return Integer.parseInt(s.trim());
    }

    /** Un item por nombre: guardado con /tod4:aether_loot guardar, un nombre de "items:", oraxen:id o minecraft:id. */
    private ItemStack item(String id) {
        String b64 = y.getString("guardados." + id);
        if (b64 != null) return ItemStack.deserializeBytes(Base64.getDecoder().decode(b64));
        String alias = y.getString("items." + id);
        if (alias != null && !alias.equals(id)) return item(alias);
        if (id.startsWith("oraxen:")) return Ext.oraxenItem(id.substring(7));
        Material m = Material.matchMaterial(id);
        return m == null || !m.isItem() || m.isAir() ? null : new ItemStack(m);
    }

    private static void partir(ItemStack it, List<ItemStack> out) {
        int n = it.getAmount(), max = Math.max(1, it.getMaxStackSize());
        while (n > 0) {
            ItemStack c = it.clone();
            c.setAmount(Math.min(max, n));
            n -= c.getAmount();
            out.add(c);
        }
    }

    List<String> nombres() {
        List<String> l = new ArrayList<>();
        if (y.isConfigurationSection("items")) l.addAll(y.getConfigurationSection("items").getKeys(false));
        if (y.isConfigurationSection("guardados")) for (String k : y.getConfigurationSection("guardados").getKeys(false)) if (!l.contains(k)) l.add(k);
        return l;
    }

    // ================================================================= comando

    /** /tod4:aether_loot guardar <nombre> | probar <tier> | ver <tier> */
    void comando(CommandSender s, String[] a) {
        String m = a.length > 0 ? a[0].toLowerCase() : "";
        switch (m) {
            case "guardar" -> {
                if (!(s instanceof Player p)) return;
                if (a.length < 2) { Style.info(s, "Uso: &e/tod4:aether_loot guardar <nombre> &7con el item en la mano (por ejemplo &fchip&7)."); return; }
                ItemStack mano = p.getInventory().getItemInMainHand();
                if (mano.getType().isAir()) { Style.err(s, "Ponete el item en la mano."); return; }
                ItemStack uno = mano.clone();
                uno.setAmount(1);
                y.set("guardados." + a[1].toLowerCase(), Base64.getEncoder().encodeToString(uno.serializeAsBytes()));
                guardar();
                Style.ok(s, "Guardado como &f" + a[1].toLowerCase() + "&a. En loot.yml se usa con ese nombre (ej: &f\"" + a[1].toLowerCase() + " 1-5\"&a).");
            }
            case "probar" -> {
                if (!(s instanceof Player p)) return;
                String t = a.length > 1 ? a[1].toLowerCase() : "tier1";
                if (!y.isList(t)) { Style.err(s, "Usa tier1, tier2, tier3 o tier4."); return; }
                List<ItemStack> l = tirar(t);
                Inventory inv = Bukkit.createInventory(null, 54, Txt.c("&8Prueba de " + t + " (no se guarda)"));
                List<Integer> slots = new ArrayList<>();
                for (int i = 0; i < 54; i++) slots.add(i);
                java.util.Collections.shuffle(slots, rnd);
                for (int i = 0; i < l.size() && i < 54; i++) inv.setItem(slots.get(i), l.get(i));
                p.openInventory(inv);
            }
            case "ver" -> {
                String t = a.length > 1 ? a[1].toLowerCase() : "";
                for (String tt : t.isEmpty() ? List.of("tier1", "tier2", "tier3", "tier4") : List.of(t)) {
                    Style.raw(s, "&e" + tt + ":");
                    for (String x : y.getStringList(tt)) Style.raw(s, "&7 - &f" + x);
                }
            }
            default -> Style.info(s, "Uso: &e/tod4:aether_loot guardar <nombre> &7| &eprobar <tier> &7| &ever [tier]&7. Las cantidades se cambian en &fplugins/TOD4Aether/loot.yml &7y &e/tod4:aether_recargar&7.");
        }
    }

    private void guardar() {
        try { y.save(f); } catch (IOException ex) { pl.getLogger().warning("No pude guardar loot.yml: " + ex.getMessage()); }
    }
}

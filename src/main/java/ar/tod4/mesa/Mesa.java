package ar.tod4.mesa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.enchantments.EnchantmentOffer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.enchantment.PrepareItemEnchantEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import org.bukkit.Bukkit;
import ar.tod4.TOD4;

/**
 * Mesa de encantamientos mejorada.
 *  - Hasta 29 estanterias: mesa vanilla (8 / 13 / 30). Opcional: el boton de 30 usa las reglas "medio".
 *  - Con 30 estanterias (el anillo vanilla tiene 32 lugares): botones 15 / 30 / 40, costo 1 / 2 / 3 como siempre.
 *  - Lo que sale lo decide este plugin con reglas por tipo de objeto (config.yml).
 */
public final class Mesa implements Listener {

    private final TOD4 pl;
    private YamlConfiguration conf;
    private boolean activa;
    private final NamespacedKey INDESTRUCTIBLE;

    public Mesa(TOD4 pl) {
        this.pl = pl;
        File f = new File(pl.getDataFolder(), "mesa.yml");
        if (!f.exists()) pl.saveResource("mesa.yml", false);
        conf = YamlConfiguration.loadConfiguration(f);
        // mesa.yml de una version vieja: se guarda como mesa_viejo.yml y se pone el nuevo
        if (conf.getInt("version", 1) < 2) {
            File viejo = new File(pl.getDataFolder(), "mesa_viejo.yml");
            if (viejo.exists()) viejo.delete();
            f.renameTo(viejo);
            pl.saveResource("mesa.yml", true);
            conf = YamlConfiguration.loadConfiguration(f);
        }
        this.INDESTRUCTIBLE = new NamespacedKey(pl, "indestructible");
        Bukkit.getPluginManager().registerEvents(this, pl);
    }

    public void setActiva(boolean v) { activa = v; }

    // Dia 2: Poder tambien sale en la ballesta (mesa mejorada y libros); la mesa vanilla lo hace PoderBallesta
    private boolean poderBallesta;
    private final Set<UUID> manejado = new HashSet<>();

    public void setPoderBallesta(boolean v) { poderBallesta = v; }

    /** Si esta mesa ya decidio los encantamientos de la ballesta de este jugador (y lo borra). */
    public boolean tomarManejado(Player p) { return manejado.remove(p.getUniqueId()); }

    /** Poder en una ballesta no es compatible en el juego: aca si, cuando el cambio esta prendido. */
    private boolean puede(Enchantment e, ItemStack item, boolean book) {
        if (book || canEnchant(e, item)) return true;
        return poderBallesta && item.getType() == Material.CROSSBOW && (e.getKey().getKey().equals("power") || e.getKey().getKey().equals("infinity"));
    }

    public void recargar() { conf = YamlConfiguration.loadConfiguration(new File(pl.getDataFolder(), "mesa.yml")); }


    static final String BAJO = "bajo", MEDIO = "medio", ALTO = "alto";

    /** Grupo de encantamientos de un tipo de objeto. */
    static final class Pool {
        final String[] main, minor, last;
        Pool(String[] main, String[] minor, String[] last) { this.main = main; this.minor = minor; this.last = last; }
    }

    static final Map<String, Pool> POOLS = new HashMap<>();
    static {
        String[] prot = {"protection", "fire_protection", "blast_protection", "projectile_protection"};
        String[] thorns = {"thorns"};
        String[] none = {};
        POOLS.put("espada", new Pool(new String[]{"sharpness", "smite", "bane_of_arthropods"},
                new String[]{"unbreaking", "looting", "sweeping_edge", "fire_aspect", "knockback"}, none));
        POOLS.put("herramienta", new Pool(new String[]{"efficiency"},
                new String[]{"unbreaking", "fortune", "silk_touch"}, none));
        POOLS.put("arco", new Pool(new String[]{"power"},
                new String[]{"unbreaking", "flame", "punch", "infinity"}, none));
        POOLS.put("ballesta", new Pool(new String[]{"quick_charge"},
                new String[]{"multishot", "piercing", "unbreaking"}, none));
        POOLS.put("tridente", new Pool(new String[]{"impaling"},
                new String[]{"loyalty", "riptide", "channeling", "unbreaking"}, none));
        POOLS.put("cana", new Pool(new String[]{"lure", "luck_of_the_sea"},
                new String[]{"lure", "luck_of_the_sea", "unbreaking"}, none));
        POOLS.put("casco", new Pool(prot, new String[]{"unbreaking", "respiration", "aqua_affinity"}, thorns));
        POOLS.put("pechera", new Pool(prot, new String[]{"unbreaking"}, thorns));
        POOLS.put("pantalones", new Pool(prot, new String[]{"unbreaking"}, thorns));
        POOLS.put("botas", new Pool(prot, new String[]{"unbreaking", "feather_falling", "depth_strider"}, thorns));
    }

    /** Bloques que dejan pasar el "poder" de la estanteria (tag vanilla #enchantment_power_transmitter = #replaceable). */
    static final Set<String> TRANSMITTER = new HashSet<>(List.of(
            "AIR", "CAVE_AIR", "VOID_AIR", "WATER", "LAVA", "SHORT_GRASS", "GRASS", "FERN", "DEAD_BUSH", "SEAGRASS",
            "TALL_SEAGRASS", "FIRE", "SOUL_FIRE", "SNOW", "VINE", "GLOW_LICHEN", "LIGHT", "TALL_GRASS", "LARGE_FERN",
            "STRUCTURE_VOID", "BUBBLE_COLUMN", "WARPED_ROOTS", "NETHER_SPROUTS", "CRIMSON_ROOTS", "HANGING_ROOTS"));

    private final Map<UUID, Long> seeds = new HashMap<>();
    private final Random seedRng = new Random();

    // ------------------------------------------------------------------ config

    private FileConfiguration cfg() { return conf; }

    private int lvl(int b) {
        int[] def = {15, 30, 40};
        return cfg().getInt("botones.boton" + b, def[b - 1]);
    }

    private double num(String path, double def) { return cfg().getDouble(path, def); }

    /** Lee "3:48,4:40,5:12" -> {{3,48},{4,40},{5,12}}. */
    static int[][] parseDist(String s) {
        List<int[]> out = new ArrayList<>();
        if (s != null) {
            for (String part : s.split(",")) {
                String[] kv = part.trim().split(":");
                if (kv.length != 2) continue;
                try {
                    int k = Integer.parseInt(kv[0].trim());
                    int w = Integer.parseInt(kv[1].trim());
                    if (w > 0) out.add(new int[]{k, w});
                } catch (NumberFormatException ignored) { }
            }
        }
        if (out.isEmpty()) out.add(new int[]{1, 1});
        return out.toArray(new int[0][]);
    }

    private String countDist(String tier, String cat) {
        String s = cfg().getString("niveles." + tier + ".cantidad." + cat, null);
        if (s == null) s = cfg().getString("niveles." + tier + ".cantidad.otros", "1:1");
        return s;
    }

    private double catNum(String tier, String what, String cat, double def) {
        String base = "niveles." + tier + "." + what + ".";
        return cfg().getDouble(base + cat, cfg().getDouble(base + "otros", def));
    }

    // ------------------------------------------------------------------ helpers

    static Enchantment ench(String key) {
        return Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
    }

    static String category(Material m) {
        String n = m.name();
        if (m == Material.BOOK) return "libro";
        if (n.endsWith("_SWORD")) return "espada";
        if (n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.endsWith("_AXE")) return "herramienta";
        if (n.equals("BOW")) return "arco";
        if (n.equals("CROSSBOW")) return "ballesta";
        if (n.equals("TRIDENT")) return "tridente";
        if (n.equals("FISHING_ROD")) return "cana";
        if (n.endsWith("_HELMET")) return "casco";
        if (n.endsWith("_CHESTPLATE")) return "pechera";
        if (n.endsWith("_LEGGINGS")) return "pantalones";
        if (n.endsWith("_BOOTS")) return "botas";
        return null;
    }

    static int countShelves(Block table) {
        int c = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) != 2 && Math.abs(dz) != 2) continue;
                for (int dy = 0; dy <= 1; dy++) {
                    if (table.getRelative(dx, dy, dz).getType() != Material.BOOKSHELF) continue;
                    Material mid = table.getRelative(dx / 2, dy, dz / 2).getType();
                    if (mid.isAir() || TRANSMITTER.contains(mid.name())) c++;
                }
            }
        }
        return c;
    }

    private long seed(Player p) {
        return seeds.computeIfAbsent(p.getUniqueId(), u -> seedRng.nextLong());
    }

    static boolean clash(Map<Enchantment, Integer> have, Enchantment e) {
        for (Enchantment k : have.keySet()) {
            if (k.equals(e) || conflicts(k, e) || conflicts(e, k)) return true;
        }
        return false;
    }

    static int pickInt(int[][] dist, Random r) {
        int tot = 0;
        for (int[] d : dist) tot += d[1];
        int x = r.nextInt(tot);
        for (int[] d : dist) { x -= d[1]; if (x < 0) return d[0]; }
        return dist[dist.length - 1][0];
    }

    private Enchantment pickWeighted(List<Enchantment> list, List<String> keys, Random r) {
        double tot = 0;
        double[] w = new double[list.size()];
        for (int i = 0; i < list.size(); i++) { w[i] = Math.max(0.0, num("pesos." + keys.get(i), 10)); tot += w[i]; }
        if (tot <= 0) return list.get(r.nextInt(list.size()));
        double x = r.nextDouble() * tot;
        for (int i = 0; i < list.size(); i++) { x -= w[i]; if (x < 0) return list.get(i); }
        return list.get(list.size() - 1);
    }


    // ------------------------------------------------------------------ llamadas a Enchantment
    // Se llaman por reflexion: en algunas versiones de Paper Enchantment es una clase y en otras una interfaz,
    // asi el mismo jar anda en 1.21.1 y en 1.21.4.
    private static java.lang.reflect.Method M_MAX, M_CONF, M_CAN;

    static java.lang.reflect.Method m(String name, Class<?>... args) {
        try { return Enchantment.class.getMethod(name, args); }
        catch (NoSuchMethodException ex) { throw new IllegalStateException(ex); }
    }

    static int maxLevel(Enchantment e) {
        try {
            if (M_MAX == null) M_MAX = m("getMaxLevel");
            return (Integer) M_MAX.invoke(e);
        } catch (ReflectiveOperationException ex) { return 1; }
    }

    static boolean conflicts(Enchantment a, Enchantment b) {
        try {
            if (M_CONF == null) M_CONF = m("conflictsWith", Enchantment.class);
            return (Boolean) M_CONF.invoke(a, b);
        } catch (ReflectiveOperationException ex) { return false; }
    }

    static boolean canEnchant(Enchantment e, ItemStack item) {
        try {
            if (M_CAN == null) M_CAN = m("canEnchantItem", ItemStack.class);
            return (Boolean) M_CAN.invoke(e, item);
        } catch (ReflectiveOperationException ex) { return true; }
    }

    // ------------------------------------------------------------------ tirada

    /** Devuelve los encantamientos (el primero es el principal). Vacio = dejar lo vanilla. */
    Map<Enchantment, Integer> roll(String cat, String tier, ItemStack item, Random r) {
        LinkedHashMap<Enchantment, Integer> out = new LinkedHashMap<>();
        boolean book = "libro".equals(cat);
        if (book) {
            double mc = num("libros.reparacion." + tier, 0);
            if (mc > 0 && r.nextDouble() < mc) {
                Enchantment m = ench("mending");
                if (m != null) {
                    out.put(m, 1);
                    Enchantment u = ench("unbreaking");
                    if (u != null && r.nextDouble() < num("libros.reparacion_con_irrompibilidad", 0.5)) out.put(u, maxLevel(u));
                    return out;
                }
            }
            int[][] cats = parseCats(cfg().getString("libros.categorias", "espada:3,herramienta:3,arco:2,casco:1,pechera:1,pantalones:1,botas:1"));
            cat = CAT_NAMES.get(pickInt(cats, r));
        }
        Pool p = POOLS.get(cat);
        if (p == null) return out;

        int[][] dist = parseDist(countDist(tier, book ? "libro" : cat));
        int n = pickInt(dist, r);
        int nMax = 1;
        for (int[] d : dist) nMax = Math.max(nMax, d[0]);

        // principal
        List<Enchantment> mains = new ArrayList<>();
        String[] mainKeys = p.main;
        if (poderBallesta && "ballesta".equals(cat)) mainKeys = new String[]{"quick_charge", "power"};
        for (String k : mainKeys) {
            Enchantment e = ench(k);
            if (e != null && puede(e, item, book)) mains.add(e);
        }
        if (mains.isEmpty()) return out;
        Enchantment main = mains.get(r.nextInt(mains.size()));
        int max = maxLevel(main);
        int below = (int) catNum(tier, "principal_debajo_del_maximo", cat, 1);
        int minL = Math.max(1, max - below);
        double top = catNum(tier, "tope", cat, 0.5) + catNum(tier, "extra_por_faltante", cat, 0.1) * (nMax - n);
        int lvl;
        if (minL >= max || r.nextDouble() < top) lvl = max;
        else lvl = minL + r.nextInt(max - minL);
        out.put(main, lvl);

        // secundarios
        boolean minorMax = cfg().getBoolean("niveles." + tier + ".secundarios_al_maximo", true);
        List<Enchantment> cand = new ArrayList<>();
        List<String> candKeys = new ArrayList<>();
        for (String k : p.minor) {
            Enchantment e = ench(k);
            if (e != null && (book || canEnchant(e, item))) { cand.add(e); candKeys.add(k); }
        }
        // dia 2: la ballesta tambien puede sacar Infinidad como secundario
        if (poderBallesta && "ballesta".equals(cat) && !book) {
            Enchantment inf = ench("infinity");
            if (inf != null) { cand.add(inf); candKeys.add("infinity"); }
        }
        while (out.size() < n) {
            for (int i = cand.size() - 1; i >= 0; i--) {
                if (clash(out, cand.get(i))) { cand.remove(i); candKeys.remove(i); }
            }
            if (cand.isEmpty()) break;
            Enchantment e = pickWeighted(cand, candKeys, r);
            out.put(e, minorLevel(e, minorMax, r));
        }
        // ultimos (espinas): solo si ya no queda nada mas y con poca chance
        for (String k : p.last) {
            if (out.size() >= n) break;
            Enchantment e = ench(k);
            if (e == null || (!book && !canEnchant(e, item)) || clash(out, e)) continue;
            if (r.nextDouble() < num("pesos_ultimos." + k, 0.25)) out.put(e, minorLevel(e, minorMax, r));
        }
        return out;
    }

    static int minorLevel(Enchantment e, boolean atMax, Random r) {
        int max = maxLevel(e);
        if (atMax || max <= 1) return max;
        int lo = (max + 1) / 2;
        return lo + r.nextInt(max - lo + 1);
    }

    static final List<String> CAT_NAMES = List.of("espada", "herramienta", "arco", "ballesta", "tridente", "cana",
            "casco", "pechera", "pantalones", "botas");

    static int[][] parseCats(String s) {
        List<int[]> out = new ArrayList<>();
        if (s != null) {
            for (String part : s.split(",")) {
                String[] kv = part.trim().split(":");
                if (kv.length != 2) continue;
                int idx = CAT_NAMES.indexOf(kv[0].trim());
                if (idx < 0) continue;
                try {
                    int w = Integer.parseInt(kv[1].trim());
                    if (w > 0) out.add(new int[]{idx, w});
                } catch (NumberFormatException ignored) { }
            }
        }
        if (out.isEmpty()) out.add(new int[]{0, 1});
        return out.toArray(new int[0][]);
    }

    // ------------------------------------------------------------------ plan por boton

    /** tiers[i] != null -> el boton i lo decide el plugin; costs[i] = nivel mostrado. */
    private String[] tiersFor(int shelves, int vanillaCost3) {
        if (shelves >= cfg().getInt("estanterias_mesa_grande", 30)) return new String[]{BAJO, MEDIO, ALTO};
        if (cfg().getBoolean("mejorar_boton_30_vanilla", true) && vanillaCost3 >= 30) return new String[]{null, null, MEDIO};
        return new String[]{null, null, null};
    }

    private Random rngFor(Player p, ItemStack item, int button) {
        return new Random(seed(p) * 1000003L + button * 7919L + item.getType().name().hashCode());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrepare(PrepareItemEnchantEvent e) {
        if (!activa) return;
        ItemStack item = e.getItem();
        String cat = category(item.getType());
        if (cat == null) return;
        EnchantmentOffer[] offers = e.getOffers();
        int shelves = countShelves(e.getEnchantBlock());
        int vCost3 = (offers.length > 2 && offers[2] != null) ? offers[2].getCost() : 0;
        String[] tiers = tiersFor(shelves, vCost3);
        boolean big = tiers[0] != null;
        for (int i = 0; i < 3 && i < offers.length; i++) {
            if (tiers[i] == null) continue;
            int cost = big ? lvl(i + 1) : vCost3;
            if (indestructible(cat, tiers[i], e.getEnchanter(), i)) {
                Enchantment u = ench("unbreaking");
                if (u != null) offers[i] = new EnchantmentOffer(u, maxLevel(u), cost);
                continue;
            }
            Map<Enchantment, Integer> res = roll(cat, tiers[i], item, rngFor(e.getEnchanter(), item, i));
            if (res.isEmpty()) continue;
            Map.Entry<Enchantment, Integer> first = res.entrySet().iterator().next();
            offers[i] = new EnchantmentOffer(first.getKey(), first.getValue(), cost);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent e) {
        if (!activa) return;
        Player p = e.getEnchanter();
        try {
            ItemStack item = e.getItem();
            String cat = category(item.getType());
            if (cat == null) return;
            int b = e.whichButton();
            if (b < 0 || b > 2) return;
            int shelves = countShelves(e.getEnchantBlock());
            String[] tiers = tiersFor(shelves, b == 2 ? e.getExpLevelCost() : 0);
            if (tiers[b] == null) return;
            if (indestructible(cat, tiers[b], p, b)) {
                // sale el Libro Indestructible: se cambia el libro de la mesa un tick despues
                Map<Enchantment, Integer> add = e.getEnchantsToAdd();
                add.clear();
                Enchantment u = ench("unbreaking");
                if (u != null) add.put(u, 1);
                org.bukkit.inventory.Inventory inv = e.getInventory();
                Bukkit.getScheduler().runTask(pl, () -> {
                    ItemStack cur = inv.getItem(0);
                    if (cur != null && cur.getType() == Material.ENCHANTED_BOOK) inv.setItem(0, libroIndestructible());
                    p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.4f);
                });
                return;
            }
            Map<Enchantment, Integer> res = roll(cat, tiers[b], item, rngFor(p, item, b));
            if (res.isEmpty()) return;
            Map<Enchantment, Integer> add = e.getEnchantsToAdd();
            add.clear();
            add.putAll(res);
            if ("ballesta".equals(cat)) manejado.add(p.getUniqueId());
        } finally {
            seeds.put(p.getUniqueId(), seedRng.nextLong());
        }
    }

    // ------------------------------------------------------------------ libro Indestructible

    /** Sale el Libro Indestructible en este boton (tirada aparte, siempre igual para la misma mesa y semilla). */
    private boolean indestructible(String cat, String tier, Player p, int boton) {
        if (!"libro".equals(cat)) return false;
        double ch = num("libros.indestructible." + tier, 0);
        if (ch <= 0) return false;
        return new Random(seed(p) * 31L + boton * 1_000_003L + 0x1D35L).nextDouble() < ch;
    }

    public ItemStack libroIndestructible() {
        ItemStack it = new ItemStack(Material.ENCHANTED_BOOK);
        org.bukkit.inventory.meta.ItemMeta m = it.getItemMeta();
        m.displayName(ar.tod4.util.Txt.c("&d&lLibro Indestructible"));
        m.lore(ar.tod4.util.Txt.lines("&7Combínalo en un yunque con", "&7cualquier ítem que se gaste:", "&7queda &dindestructible&7."));
        m.setEnchantmentGlintOverride(true);
        m.getPersistentDataContainer().set(INDESTRUCTIBLE, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private boolean esLibro(ItemStack it) {
        return it != null && it.getType() == Material.ENCHANTED_BOOK && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(INDESTRUCTIBLE, org.bukkit.persistence.PersistentDataType.BYTE);
    }

    /** Yunque: item que se gasta + Libro Indestructible = el item indestructible. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void yunque(org.bukkit.event.inventory.PrepareAnvilEvent e) {
        org.bukkit.inventory.AnvilInventory inv = e.getInventory();
        ItemStack izq = inv.getItem(0), der = inv.getItem(1);
        if (!esLibro(der)) return;
        if (izq == null || izq.getType().isAir() || izq.getType().getMaxDurability() <= 0 || esLibro(izq)
                || (izq.hasItemMeta() && izq.getItemMeta().isUnbreakable())) { e.setResult(null); return; }
        ItemStack out = izq.clone();
        out.setAmount(1);
        org.bukkit.inventory.meta.ItemMeta m = out.getItemMeta();
        m.setUnbreakable(true);
        m.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_UNBREAKABLE);
        List<net.kyori.adventure.text.Component> lore = m.hasLore() && m.lore() != null ? new ArrayList<>(m.lore()) : new ArrayList<>();
        lore.add(ar.tod4.util.Txt.c("&d✦ Indestructible"));
        m.lore(lore);
        out.setItemMeta(m);
        e.setResult(out);
        int costo = Math.max(1, cfg().getInt("libros.indestructible_costo_yunque", 10));
        Bukkit.getScheduler().runTask(pl, () -> {
            try { e.getView().setRepairCost(costo); } catch (Throwable ignored) { }
        });
        try { e.getView().setRepairCost(costo); } catch (Throwable ignored) { }
    }
}

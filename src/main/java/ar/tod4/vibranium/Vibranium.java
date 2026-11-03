package ar.tod4.vibranium;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlastFurnace;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.FurnaceSmeltEvent;
import org.bukkit.event.inventory.FurnaceStartSmeltEvent;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import ar.tod4.TOD4;
import ar.tod4.util.Txt;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Vibranium: los items y como se hacen.
 *  - Mena de Vibranium: sale de los meteoritos (solo con Toque de Seda).
 *  - Vibranium (barra): la mena en un ALTO HORNO, con BALDE DE LAVA de combustible (nada mas), 1 minuto cada una.
 *    Un balde alcanza para "barras_por_balde" barras. En el horno comun no se puede.
 *  - Lingote de Vibranium: 4 barras + 4 bloques de diamante, en cualquier orden (como el de netherite).
 *  - Mejora de Vibranium: sale en el cofre de cada meteorito. Se duplica con 7 lingotes de netherite y una Piedra de Meteorito.
 *  - Piedra de Meteorito: el borde del crater.
 * Las texturas van en el pack (tod4:mena_vibranium, tod4:vibranium, ...). config.yml -> vibranium.
 */
public final class Vibranium implements Listener {

    public enum Tipo { MENA, BARRA, LINGOTE, MEJORA, PIEDRA }

    private final TOD4 pl;
    private final NamespacedKey kTipo;
    private final NamespacedKey rBarra, rLingote, rMejora;
    /** Altos hornos prendidos con lava (los demas no cocinan vibranium). */
    private final Map<Location, Boolean> conLava = new HashMap<>();

    private static Vibranium INST;

    /** Para misiones, subastas, etc.: cada item de vibranium tiene un id como si fuera de Oraxen ("tod4_lingote_vibranium"). */
    public static String idDe(String tipo) {
        return switch (tipo) {
            case "MENA" -> "tod4_mena_vibranium";
            case "BARRA" -> "tod4_vibranium";
            case "LINGOTE" -> "tod4_lingote_vibranium";
            case "MEJORA" -> "tod4_mejora_vibranium";
            case "PIEDRA" -> "tod4_piedra_meteorito";
            default -> null;
        };
    }

    /** El item de ese id (o null si no es de vibranium). */
    public static ItemStack porId(String id) {
        if (INST == null || id == null) return null;
        for (Tipo t : Tipo.values()) if (id.equals(idDe(t.name()))) return INST.item(t, 1);
        return null;
    }

    public Vibranium(TOD4 pl) {
        INST = this;
        this.pl = pl;
        this.kTipo = new NamespacedKey(pl, "vibranium");
        this.rBarra = new NamespacedKey(pl, "vibranium_barra");
        this.rLingote = new NamespacedKey(pl, "vibranium_lingote");
        this.rMejora = new NamespacedKey(pl, "vibranium_mejora_copia");
        Bukkit.getPluginManager().registerEvents(this, pl);
        recetas();
    }

    private double d(String k, double def) { return pl.getConfig().getDouble("vibranium." + k, def); }

    // ================================================================= items

    private static final String COLOR = "<#9B7BFF>";

    public ItemStack item(Tipo t, int cant) {
        ItemStack it = new ItemStack(Material.PAPER, Math.max(1, cant));
        ItemMeta m = it.getItemMeta();
        String nombre, modelo;
        List<String> lore;
        switch (t) {
            case MENA -> {
                nombre = nombre("mena", "Mena de Vibranio"); modelo = "mena_vibranium";
                lore = List.of("&7Fúndela en un &falto horno&7.", "&7Solo funciona con &6balde de lava&7.");
            }
            case BARRA -> {
                nombre = nombre("barra", "Vibranio"); modelo = "vibranium";
                lore = List.of("&7Junta &f4 &7con &f4 bloques de diamante", "&7para un lingote.");
            }
            case LINGOTE -> {
                nombre = nombre("lingote", "Lingote de Vibranio"); modelo = "lingote_vibranium";
                lore = List.of("&7El metal más resistente que existe.");
            }
            case MEJORA -> {
                nombre = nombre("mejora", "Mejora de Vibranio"); modelo = "mejora_vibranium";
                lore = List.of("&7Plantilla de herrería.", "&7Se duplica con &f7 lingotes de netherite",
                        "&7y una &fPiedra de Meteorito&7.");
            }
            default -> {
                nombre = nombre("piedra", "Piedra de Meteorito"); modelo = "piedra_meteorito";
                lore = List.of("&7Roca que cayó del cielo.");
            }
        }
        m.displayName(Txt.c(COLOR + nombre).decoration(TextDecoration.ITALIC, false));
        m.lore(lore.stream().map(x -> Txt.c(x).decoration(TextDecoration.ITALIC, false)).toList());
        m.setItemModel(new NamespacedKey("tod4", modelo));
        m.getPersistentDataContainer().set(kTipo, PersistentDataType.STRING, t.name());
        if (t == Tipo.MEJORA || t == Tipo.LINGOTE) m.setRarity(org.bukkit.inventory.ItemRarity.EPIC);
        it.setItemMeta(m);
        return it;
    }

    private String nombre(String k, String def) { return pl.getConfig().getString("vibranium.nombres." + k, def); }

    public Tipo tipo(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        String s = it.getItemMeta().getPersistentDataContainer().get(kTipo, PersistentDataType.STRING);
        if (s == null) return null;
        try { return Tipo.valueOf(s); } catch (IllegalArgumentException e) { return null; }
    }

    // ================================================================= recetas

    /** Se vuelven a poner con /tod4 recargar (por si cambio el tiempo de coccion). */
    public void recetas() {
        for (NamespacedKey k : List.of(rBarra, rLingote, rMejora)) Bukkit.removeRecipe(k);
        int coccion = Math.max(20, (int) (d("coccion_seg", 60) * 20));
        Bukkit.addRecipe(new BlastingRecipe(rBarra, item(Tipo.BARRA, 1), new RecipeChoice.ExactChoice(item(Tipo.MENA, 1)),
                (float) d("experiencia", 1.0), coccion));

        // como el lingote de netherite: en cualquier orden
        org.bukkit.inventory.ShapelessRecipe lingote = new org.bukkit.inventory.ShapelessRecipe(rLingote, item(Tipo.LINGOTE, 1));
        RecipeChoice barra = new RecipeChoice.ExactChoice(item(Tipo.BARRA, 1));
        for (int i = 0; i < 4; i++) lingote.addIngredient(barra);
        lingote.addIngredient(4, Material.DIAMOND_BLOCK);
        Bukkit.addRecipe(lingote);

        ShapedRecipe mejora = new ShapedRecipe(rMejora, item(Tipo.MEJORA, 2));
        mejora.shape("NMN", "NPN", "NNN");
        mejora.setIngredient('N', Material.NETHERITE_INGOT);
        mejora.setIngredient('M', new RecipeChoice.ExactChoice(item(Tipo.MEJORA, 1)));
        mejora.setIngredient('P', new RecipeChoice.ExactChoice(item(Tipo.PIEDRA, 1)));
        Bukkit.addRecipe(mejora);
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) p.discoverRecipes(List.of(rLingote, rMejora));
    }

    @EventHandler
    public void entra(org.bukkit.event.player.PlayerJoinEvent e) {
        e.getPlayer().discoverRecipes(List.of(rLingote, rMejora));
        actualizar(e.getPlayer().getInventory());
    }

    // ================================================================= items viejos

    /**
     * Si se cambio un nombre en el config (o el plugin cambio algo del item), los items que ya tiene la gente se ponen
     * al dia, asi las recetas los siguen aceptando. Se hace al entrar, al abrir un cofre/horno y al agarrar del piso.
     */
    public void actualizar(org.bukkit.inventory.Inventory inv) {
        if (inv == null) return;
        ItemStack[] cont = inv.getContents();
        for (int i = 0; i < cont.length; i++) {
            ItemStack nuevo = alDia(cont[i]);
            if (nuevo != null) inv.setItem(i, nuevo);
        }
    }

    /** El item como tiene que ser ahora, o null si ya esta bien (o no es de vibranio). */
    private ItemStack alDia(ItemStack it) {
        Tipo t = tipo(it);
        if (t == null) return null;
        ItemStack bien = item(t, it.getAmount());
        return bien.isSimilar(it) ? null : bien;
    }

    /** Despues de /tod4 recargar: los de todos los que estan conectados. */
    public void actualizarTodos() { for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) actualizar(p.getInventory()); }

    @EventHandler
    public void abre(org.bukkit.event.inventory.InventoryOpenEvent e) {
        actualizar(e.getInventory());
        actualizar(e.getPlayer().getInventory());
    }

    @EventHandler(ignoreCancelled = true)
    public void agarra(org.bukkit.event.entity.EntityPickupItemEvent e) {
        ItemStack nuevo = alDia(e.getItem().getItemStack());
        if (nuevo != null) e.getItem().setItemStack(nuevo);
    }

    // ================================================================= alto horno: solo con lava

    private boolean esMena(Block b) {
        return b.getState() instanceof BlastFurnace f && tipo(f.getInventory().getSmelting()) == Tipo.MENA;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void combustible(FurnaceBurnEvent e) {
        Block b = e.getBlock();
        if (b.getType() != Material.BLAST_FURNACE) return;
        boolean lava = e.getFuel().getType() == Material.LAVA_BUCKET;
        conLava.put(b.getLocation(), lava);
        if (!esMena(b)) return;
        if (!lava) { e.setCancelled(true); return; }
        // un balde alcanza para N barras
        int coccion = Math.max(20, (int) (d("coccion_seg", 60) * 20));
        e.setBurnTime(coccion * Math.max(1, (int) d("barras_por_balde", 4)));
    }

    @EventHandler
    public void empieza(FurnaceStartSmeltEvent e) {
        if (tipo(e.getSource()) == Tipo.MENA) e.setTotalCookTime(Math.max(20, (int) (d("coccion_seg", 60) * 20)));
    }

    /** Si el alto horno estaba prendido con otra cosa (carbon...), el vibranium no sale. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void sale(FurnaceSmeltEvent e) {
        if (tipo(e.getSource()) != Tipo.MENA) return;
        if (!Boolean.TRUE.equals(conLava.get(e.getBlock().getLocation()))) e.setCancelled(true);
    }

    /** Los items de vibranium no son papel de verdad: no se usan en otras recetas (mapas, cohetes, libros...). */
    @EventHandler(priority = EventPriority.HIGH)
    public void otrasRecetas(org.bukkit.event.inventory.PrepareItemCraftEvent e) {
        if (e.getRecipe() instanceof org.bukkit.Keyed k && k.getKey().getNamespace().equals(kTipo.getNamespace())) return;
        for (ItemStack it : e.getInventory().getMatrix()) if (tipo(it) != null) { e.getInventory().setResult(null); return; }
    }

    /** /tod4 vibranium <tipo> [cant] [jugador] */
    public void cmd(org.bukkit.command.CommandSender s, String[] a) {
        if (a.length < 2) { ar.tod4.util.Style.err(s, "Uso: &e/tod4 vibranium <mena|barra|lingote|mejora|piedra> [cantidad] [jugador]"); return; }
        Tipo t;
        try { t = Tipo.valueOf(a[1].toUpperCase()); } catch (IllegalArgumentException ex) { ar.tod4.util.Style.err(s, "Tipo: mena, barra, lingote, mejora o piedra."); return; }
        int n = 1;
        if (a.length > 2) try { n = Math.max(1, Math.min(64, Integer.parseInt(a[2]))); } catch (NumberFormatException ignored) { }
        org.bukkit.entity.Player p = a.length > 3 ? Bukkit.getPlayerExact(a[3]) : (s instanceof org.bukkit.entity.Player pp ? pp : null);
        if (p == null) { ar.tod4.util.Style.err(s, "Jugador no encontrado."); return; }
        for (ItemStack sobra : p.getInventory().addItem(item(t, n)).values()) p.getWorld().dropItem(p.getLocation(), sobra);
        ar.tod4.util.Style.ok(s, n + "x " + t.name().toLowerCase() + " para " + p.getName() + ".");
    }
}

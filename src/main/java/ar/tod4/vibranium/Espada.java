package ar.tod4.vibranium;

import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.SmithingInventory;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.meta.ItemMeta;

import ar.tod4.TOD4;
import ar.tod4.util.Items;

/**
 * La espada: en la mesa de herreria, Mejora de Vibranio + Espada Apagada (Oraxen) + Lingote de Vibranio = la espada
 * activa (la "todsword" de Oraxen), con el dano y los encantamientos del config.
 * El dano y la velocidad se ponen como los de una espada normal de Minecraft (asi el juego muestra "10 de dano / 1,6 de
 * velocidad" en verde, y no el "-2,4" feo). Si Oraxen le vuelve a poner sus valores, se arreglan solos al entrar,
 * al cambiar de item en la mano y al abrir cofres.
 * config.yml -> espada
 */
public final class Espada implements Listener {

    private final TOD4 pl;
    private final NamespacedKey receta;
    // las mismas que usa Minecraft para las espadas normales: con estas el juego muestra el valor total en verde
    private static final NamespacedKey BASE_DANO = NamespacedKey.minecraft("base_attack_damage");
    private static final NamespacedKey BASE_VEL = NamespacedKey.minecraft("base_attack_speed");

    public Espada(TOD4 pl) {
        this.pl = pl;
        this.receta = new NamespacedKey(pl, "espada_vibranio");
        Bukkit.getPluginManager().registerEvents(this, pl);
        // los tres son de papel (los items de Oraxen y los del vibranio): la receta acepta papel y en
        // PrepareSmithingEvent se mira que sean justo esos tres
        registrar();
        // cuando el server termino de cargar, de nuevo (asi la mesa de herreria deja poner los items de papel)
        Bukkit.getScheduler().runTask(pl, this::registrar);
    }

    /**
     * Desde 1.21.2 la mesa de herreria solo deja poner en cada lugar los items que alguna receta usa ahi, y esa lista
     * se arma al cargar el server: con "true" se vuelve a armar y se les manda a los jugadores.
     */
    private void registrar() {
        Bukkit.removeRecipe(receta, false);
        RecipeChoice papel = new RecipeChoice.MaterialChoice(Material.PAPER);
        Bukkit.addRecipe(new SmithingTransformRecipe(receta, new ItemStack(Material.DIAMOND_SWORD), papel, papel, papel, false), true);
    }

    private void log(String m) { if (pl.getConfig().getBoolean("espada.debug", false)) pl.getLogger().info("[Espada] " + m); }

    private String s(String k, String def) { return pl.getConfig().getString("espada." + k, def); }

    private boolean esResultado(ItemStack it) { return s("oraxen_resultado", "todsword").equals(Items.oraxenId(it)); }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void herreria(PrepareSmithingEvent e) {
        SmithingInventory inv = e.getInventory();
        ItemStack plantilla = inv.getInputTemplate(), base = inv.getInputEquipment(), extra = inv.getInputMineral();
        boolean nuestra = e.getResult() != null && e.getResult().getType() == Material.DIAMOND_SWORD
                && plantilla != null && plantilla.getType() == Material.PAPER;
        boolean ok = "tod4_mejora_vibranium".equals(Items.oraxenId(plantilla))
                && s("oraxen_base", "espada_apagada").equals(Items.oraxenId(base))
                && "tod4_lingote_vibranium".equals(Items.oraxenId(extra));
        if (!ok) {
            if (plantilla != null || base != null || extra != null)
                log("herreria: plantilla=" + Items.oraxenId(plantilla) + " base=" + Items.oraxenId(base) + " mineral=" + Items.oraxenId(extra) + " (no es la receta)");
            if (nuestra) e.setResult(null);   // papeles cualquiera: nada
            return;
        }
        log("herreria: receta de la espada, armando el resultado");
        ItemStack r = ar.tod4.hook.Ext.oraxenItem(s("oraxen_resultado", "todsword"));
        if (r == null) {
            pl.getLogger().warning("[Espada] Oraxen no tiene el item \"" + s("oraxen_resultado", "todsword") + "\" (config.yml -> espada.oraxen_resultado).");
            e.setResult(null);
            return;
        }
        e.setResult(arreglar(r));
    }

    /** Le pone el dano, la velocidad y los encantamientos del config. Devuelve el mismo item. */
    public ItemStack arreglar(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        if (m == null) return it;
        double dano = pl.getConfig().getDouble("espada.dano", 10), vel = pl.getConfig().getDouble("espada.velocidad", 1.6);
        m.removeAttributeModifier(Attribute.ATTACK_DAMAGE);
        m.removeAttributeModifier(Attribute.ATTACK_SPEED);
        // el jugador ya tiene 1 de dano y 4 de velocidad de base: se suma lo que falta
        m.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(BASE_DANO, dano - 1, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        m.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(BASE_VEL, vel - 4, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        ConfigurationSection enc = pl.getConfig().getConfigurationSection("espada.encantamientos");
        Map<String, Object> lista = enc != null && !enc.getKeys(false).isEmpty() ? enc.getValues(false)
                : Map.of("sharpness", 5, "smite", 5, "bane_of_arthropods", 5);
        for (Map.Entry<String, Object> en : lista.entrySet()) {
            Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(en.getKey().toLowerCase()));
            if (ench == null) { pl.getLogger().warning("[Espada] No existe el encantamiento \"" + en.getKey() + "\"."); continue; }
            int nivel = en.getValue() instanceof Number n ? n.intValue() : 1;
            if (m.getEnchantLevel(ench) < nivel) m.addEnchant(ench, nivel, true);   // true: aunque no se puedan juntar (filo + castigo)
        }
        it.setItemMeta(m);
        return it;
    }

    /** Si la espada no tiene los valores buenos (Oraxen la actualizo, o es de antes), se los pone. */
    private void revisar(org.bukkit.inventory.Inventory inv) {
        if (inv == null) return;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType() != Material.DIAMOND_SWORD || !esResultado(it)) continue;
            ItemMeta m = it.getItemMeta();
            var mods = m.getAttributeModifiers(Attribute.ATTACK_DAMAGE);
            boolean bien = mods != null && mods.size() == 1 && mods.iterator().next().getKey().equals(BASE_DANO)
                    && Math.abs(mods.iterator().next().getAmount() - (pl.getConfig().getDouble("espada.dano", 10) - 1)) < 1e-6;
            if (!bien) inv.setItem(i, arreglar(it));
        }
    }

    @EventHandler
    public void entra(org.bukkit.event.player.PlayerJoinEvent e) { revisar(e.getPlayer().getInventory()); }

    @EventHandler
    public void mano(org.bukkit.event.player.PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(pl, () -> revisar(p.getInventory()));
    }

    @EventHandler
    public void abre(org.bukkit.event.inventory.InventoryOpenEvent e) { revisar(e.getInventory()); }
}

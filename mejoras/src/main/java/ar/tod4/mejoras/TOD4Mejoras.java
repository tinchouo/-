package ar.tod4.mejoras;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * TOD4 Mejoras: plantillas especiales (crafteadas) que se ponen en una pieza de armadura en la mesa de herreria
 * y dan efectos mientras la llevas puesta. Todo se configura en mejoras.yml; /mejoras para prender y apagar.
 */
public final class TOD4Mejoras extends JavaPlugin {

    final Map<String, Mejora> mejoras = new LinkedHashMap<>();
    final Set<Material> piezas = EnumSet.noneOf(Material.class);
    YamlConfiguration cfg;
    YamlConfiguration estado;
    private File estadoFile;
    NamespacedKey K_MEJORA;
    private final List<NamespacedKey> recetas = new ArrayList<>();
    Efectos efectos;
    Nightmare nightmare;

    @Override
    public void onEnable() {
        K_MEJORA = new NamespacedKey(this, "mejora");
        estadoFile = new File(getDataFolder(), "estado.yml");
        estado = YamlConfiguration.loadConfiguration(estadoFile);
        recargar();
        getServer().getPluginManager().registerEvents(new Herreria(this), this);
        efectos = new Efectos(this);
        getServer().getPluginManager().registerEvents(efectos, this);
        getServer().getPluginManager().registerEvents(new Menu.Listen(), this);
        nightmare = new Nightmare(this);
        getServer().getPluginManager().registerEvents(nightmare, this);
        Bukkit.updateRecipes();
    }

    @Override
    public void onDisable() {
        if (efectos != null) efectos.apagar();
        for (NamespacedKey k : recetas) Bukkit.removeRecipe(k);
        Bukkit.removeRecipe(new NamespacedKey(this, "nightmare"));
    }

    // ================================================================= config

    void recargar() {
        File f = new File(getDataFolder(), "mejoras.yml");
        if (!f.exists()) saveResource("mejoras.yml", false);
        cfg = YamlConfiguration.loadConfiguration(f);
        piezas.clear();
        for (String s : cfg.getStringList("piezas")) {
            Material m = Material.matchMaterial(s);
            if (m != null) piezas.add(m); else getLogger().warning("Pieza desconocida en mejoras.yml: " + s);
        }
        mejoras.clear();
        ConfigurationSection ms = cfg.getConfigurationSection("mejoras");
        if (ms != null) for (String id : ms.getKeys(false)) {
            ConfigurationSection c = ms.getConfigurationSection(id);
            if (c == null) continue;
            Mejora m = new Mejora(id, c);
            for (String err : m.errores) getLogger().warning("Mejora " + id + ": " + err);
            mejoras.put(id, m);
        }
        // recetas: plantilla de netherite en el medio y 8 del ingrediente alrededor
        for (NamespacedKey k : recetas) Bukkit.removeRecipe(k);
        recetas.clear();
        for (Mejora m : mejoras.values()) {
            NamespacedKey k = new NamespacedKey(this, "mejora_" + m.id);
            ShapedRecipe r = new ShapedRecipe(k, item(m, 1));
            r.shape("III", "ITI", "III");
            r.setIngredient('I', m.ingrediente);
            r.setIngredient('T', Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
            if (Bukkit.addRecipe(r)) recetas.add(k);
        }
        if (nightmare != null) nightmare.registrar();
        Bukkit.updateRecipes();
        getLogger().info(mejoras.size() + " mejoras cargadas.");
    }

    void guardarEstado() {
        try { estado.save(estadoFile); } catch (IOException e) { getLogger().warning("No pude guardar estado.yml: " + e.getMessage()); }
    }

    boolean crafteoOn(Mejora m) { return estado.getBoolean("crafteo." + m.id, true); }
    void setCrafteo(Mejora m, boolean v) { estado.set("crafteo." + m.id, v); guardarEstado(); }

    boolean efectoOn(Mejora m, Mejora.Efecto e) { return !estado.getStringList("apagados").contains(m.id + "." + e.id); }
    void setEfecto(Mejora m, Mejora.Efecto e, boolean on) {
        List<String> l = new ArrayList<>(estado.getStringList("apagados"));
        String k = m.id + "." + e.id;
        l.remove(k);
        if (!on) l.add(k);
        estado.set("apagados", l);
        guardarEstado();
    }

    boolean esReceta(NamespacedKey k) { return recetas.contains(k); }

    // ================================================================= items

    static Component c(String txt, String hex) {
        TextColor col = TextColor.fromHexString(hex);
        return Component.text(txt, col == null ? NamedTextColor.WHITE : col).decoration(TextDecoration.ITALIC, false);
    }

    static Component gris(String txt) { return Component.text(txt, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false); }

    /** El item de la mejora: plantilla de Silencio con su nombre, su textura y la marca invisible. */
    ItemStack item(Mejora m, int cant) {
        ItemStack it = new ItemStack(Material.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE, cant);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(c(m.nombre, m.color));
        List<Component> lore = new ArrayList<>();
        lore.add(gris("Mejora de armadura"));
        lore.add(Component.empty());
        for (String d : m.descripcion) lore.add(c("• " + d, m.color));
        lore.add(Component.empty());
        lore.add(Component.text("Ponela en una mesa de herrería con una", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("pieza de armadura de netherite.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        try { meta.setItemModel(new NamespacedKey("tod4", "mejora_" + m.id)); } catch (Throwable ignored) { }
        meta.getPersistentDataContainer().set(K_MEJORA, PersistentDataType.STRING, m.id);
        it.setItemMeta(meta);
        return it;
    }

    /** La mejora de este item (plantilla o pieza de armadura), o null. */
    Mejora mejoraDe(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        String id = it.getItemMeta().getPersistentDataContainer().get(K_MEJORA, PersistentDataType.STRING);
        return id == null ? null : mejoras.get(id);
    }

    // ================================================================= comando

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("tod4mejoras.admin")) { s.sendMessage(Component.text("No tenés permiso.", NamedTextColor.RED)); return true; }
        String sub = a.length > 0 ? a[0].toLowerCase() : "";
        switch (sub) {
            case "recargar" -> {
                recargar();
                s.sendMessage(Component.text("Mejoras recargadas (" + mejoras.size() + ").", NamedTextColor.GREEN));
            }
            case "dar" -> {
                if (a.length < 2 || !mejoras.containsKey(a[1].toLowerCase())) {
                    s.sendMessage(Component.text("Uso: /mejoras dar <" + String.join("|", mejoras.keySet()) + "> [jugador] [cantidad]", NamedTextColor.RED));
                    return true;
                }
                Player t = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : (s instanceof Player p ? p : null);
                if (t == null) { s.sendMessage(Component.text("Jugador no encontrado.", NamedTextColor.RED)); return true; }
                int n = 1;
                try { if (a.length > 3) n = Math.max(1, Math.min(64, Integer.parseInt(a[3]))); } catch (NumberFormatException ignored) { }
                t.getInventory().addItem(item(mejoras.get(a[1].toLowerCase()), n)).values().forEach(r -> t.getWorld().dropItem(t.getLocation(), r));
                s.sendMessage(Component.text("Entregada.", NamedTextColor.GREEN));
            }
            default -> {
                if (s instanceof Player p) Menu.principal(this, p);
                else s.sendMessage(Component.text("/mejoras recargar | /mejoras dar <mejora> [jugador] [cantidad]", NamedTextColor.YELLOW));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(List.of("recargar", "dar"));
        else if (a.length == 2 && a[0].equalsIgnoreCase("dar")) out.addAll(mejoras.keySet());
        else if (a.length == 3 && a[0].equalsIgnoreCase("dar")) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase();
        out.removeIf(x -> !x.toLowerCase().startsWith(last));
        return out;
    }
}

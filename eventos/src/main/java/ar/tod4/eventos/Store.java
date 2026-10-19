package ar.tod4.eventos;

import java.io.File;
import java.io.IOException;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/** Datos persistentes del plugin (plugins/TOD4Eventos/<nombre>.yml). Se guarda solo, unos segundos despues de cada cambio. */
public final class Store {

    private final Plugin plugin;
    private final File file;
    private YamlConfiguration y;
    private boolean dirty;

    public Store(Plugin plugin, String name) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), name + ".yml");
        this.y = YamlConfiguration.loadConfiguration(file);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> { if (dirty) save(); }, 100L, 100L);
    }

    public YamlConfiguration y() { return y; }

    public void set(String path, Object v) { y.set(path, v); dirty = true; }

    public void touch() { dirty = true; }

    public void save() {
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().warning("No pude guardar " + file.getName() + ": " + ex.getMessage());
        }
    }
}

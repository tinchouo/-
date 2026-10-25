package ar.tod4.mejoras;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.potion.PotionEffectType;

/** Una mejora leida de mejoras.yml. */
final class Mejora {

    enum Tipo { POCION, INMUNE, ATRIBUTO, EXPLOSIONES, PLANEAR, SONAR, SANADOR }

    /** Un efecto de la mejora (una linea de "efectos"). */
    static final class Efecto {
        final String id;          // clave en el yml (para prenderlo/apagarlo)
        final String texto;       // la linea tal cual
        final Tipo tipo;
        PotionEffectType pocion;  // POCION / INMUNE
        int nivel = 1;            // POCION
        Attribute atributo;       // ATRIBUTO
        double valor;             // ATRIBUTO / EXPLOSIONES (%) / PLANEAR (s) / SONAR (radio) / SANADOR (radio)

        Efecto(String id, String texto, Tipo tipo) { this.id = id; this.texto = texto; this.tipo = tipo; }
    }

    final String id, nombre, color;
    final TrimMaterial adorno;
    final Material ingrediente;
    final List<String> descripcion;
    final Map<String, Efecto> efectos = new LinkedHashMap<>();
    final List<String> errores = new ArrayList<>();

    Mejora(String id, ConfigurationSection c) {
        this.id = id;
        this.nombre = c.getString("nombre", "Mejora de " + id);
        this.color = c.getString("color", "#FFFFFF");
        TrimMaterial tm = Registry.TRIM_MATERIAL.get(NamespacedKey.minecraft(c.getString("adorno", "copper").toLowerCase(Locale.ROOT)));
        if (tm == null) { errores.add("adorno desconocido: " + c.getString("adorno")); tm = TrimMaterial.COPPER; }
        this.adorno = tm;
        Material ing = Material.matchMaterial(c.getString("ingrediente", "STONE"));
        if (ing == null) { errores.add("ingrediente desconocido: " + c.getString("ingrediente")); ing = Material.BARRIER; }
        this.ingrediente = ing;
        this.descripcion = c.getStringList("descripcion");
        ConfigurationSection ef = c.getConfigurationSection("efectos");
        if (ef != null) for (String k : ef.getKeys(false)) {
            Efecto e = leer(k, String.valueOf(ef.get(k)));
            if (e != null) efectos.put(k, e);
        }
    }

    private Efecto leer(String id, String linea) {
        String[] p = linea.trim().split("\\s+");
        if (p.length == 0) return null;
        try {
            switch (p[0].toLowerCase(Locale.ROOT)) {
                case "pocion": {
                    Efecto e = new Efecto(id, linea, Tipo.POCION);
                    e.pocion = Registry.EFFECT.get(NamespacedKey.minecraft(p[1].toLowerCase(Locale.ROOT)));
                    e.nivel = p.length > 2 ? Integer.parseInt(p[2]) : 1;
                    if (e.pocion == null) { errores.add("efecto desconocido: " + p[1]); return null; }
                    return e;
                }
                case "inmune": {
                    Efecto e = new Efecto(id, linea, Tipo.INMUNE);
                    e.pocion = Registry.EFFECT.get(NamespacedKey.minecraft(p[1].toLowerCase(Locale.ROOT)));
                    if (e.pocion == null) { errores.add("efecto desconocido: " + p[1]); return null; }
                    return e;
                }
                case "atributo": {
                    Efecto e = new Efecto(id, linea, Tipo.ATRIBUTO);
                    e.atributo = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(p[1].toLowerCase(Locale.ROOT)));
                    e.valor = Double.parseDouble(p[2]);
                    if (e.atributo == null) { errores.add("atributo desconocido: " + p[1]); return null; }
                    return e;
                }
                case "explosiones": { Efecto e = new Efecto(id, linea, Tipo.EXPLOSIONES); e.valor = Double.parseDouble(p[1]); return e; }
                case "planear": { Efecto e = new Efecto(id, linea, Tipo.PLANEAR); e.valor = Double.parseDouble(p[1]); return e; }
                case "sonar": { Efecto e = new Efecto(id, linea, Tipo.SONAR); e.valor = Double.parseDouble(p[1]); return e; }
                case "sanador": { Efecto e = new Efecto(id, linea, Tipo.SANADOR); e.valor = Double.parseDouble(p[1]); return e; }
                default:
                    errores.add("tipo de efecto desconocido: " + linea);
                    return null;
            }
        } catch (RuntimeException ex) {
            errores.add("linea mal escrita: " + linea);
            return null;
        }
    }
}

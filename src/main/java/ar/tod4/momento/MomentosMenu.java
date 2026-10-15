package ar.tod4.momento;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import ar.tod4.TOD4;
import ar.tod4.util.ChatInput;
import ar.tod4.util.Items;
import ar.tod4.util.Menu;
import ar.tod4.util.Obj;
import ar.tod4.util.Style;
import ar.tod4.util.Txt;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Menus de Momentos Truco (/tod4:momentostruco). Mismo estilo que el resto del plugin. */
public final class MomentosMenu {

    private final TOD4 pl;
    private final Momentos mo;
    private final NamespacedKey MARCA;

    public MomentosMenu(TOD4 pl, Momentos mo) {
        this.pl = pl;
        this.mo = mo;
        this.MARCA = new NamespacedKey(pl, "obj");
    }

    private void touch() { mo.st.touch(); }

    public void lista(Player p) {
        Style.click(p);
        Menu m = new Menu(6, "&4&l⚡ &8Momentos Truco");
        int s = 0;
        for (String id : mo.ids()) {
            if (s >= 45) break;
            boolean on = id.equals(mo.activo());
            ItemStack it = Items.of(mo.tipo(id).icono, "&c&l⚡ " + mo.nombre(id), "", "&7" + mo.objetivoCorto(id),
                    "&7Duración: &f" + mo.m(id).getInt("duracion", 240) + " s", "", on ? "&c&l● EN CURSO" : "&e▶ Click para administrar");
            if (on) Items.glow(it);
            m.set(s++, it, e -> editor(p, id));
        }
        for (int i = 45; i < 54; i++) m.set(i, Items.pane(Material.RED_STAINED_GLASS_PANE));
        m.set(49, Items.of(Material.LIME_CONCRETE, "&a&l➕ Crear Momento Truco"), e -> {
            p.closeInventory();
            Style.info(p, "Escribe el &enombre &7del momento &8(o cancel)&7:");
            ChatInput.ask(p, txt -> {
                ChatInput.cancel(p);
                String nom = Txt.strip(txt).trim();
                String id = nom.toLowerCase().replaceAll("[^a-z0-9áéíóúñ]+", "_");
                if (id.isEmpty()) { Style.err(p, "Nombre inválido."); return; }
                ConfigurationSection c = mo.m(id);
                if (!c.contains("nombre")) {
                    c.set("nombre", nom); c.set("duracion", 240); c.set("modo", "una_vez");
                    c.set("objetivo.tipo", "TOCAR"); c.set("objetivo.objs", List.of("tag:minecraft:beds")); touch();
                }
                editor(p, id);
            });
        });
        m.open(p);
    }

    void editor(Player p, String id) {
        Style.click(p);
        ConfigurationSection c = mo.m(id);
        boolean on = id.equals(mo.activo());
        Menu m = new Menu(4, "&4&l⚡ &8" + mo.nombre(id));
        if (on) m.set(10, Items.of(Material.RED_WOOL, "&c&l✖ CANCELAR EVENTO", "", "&7No cuenta a nadie como fallido."), e -> { mo.cancelar(p); editor(p, id); });
        else m.set(10, Items.of(Material.LIME_WOOL, "&a&l▶ INICIAR EVENTO", "", "&7Gira la ruleta roja y arranca.", "&7Participan los que están conectados."),
                e -> { p.closeInventory(); mo.iniciar(p, id); });
        m.set(11, Items.of(Material.NAME_TAG, "&e&lNombre", "", "&7" + mo.nombre(id), "", "&e▶ Click para cambiar"), e -> {
            p.closeInventory();
            Style.info(p, "Escribe el nuevo nombre &8(o cancel)&7:");
            ChatInput.ask(p, txt -> { ChatInput.cancel(p); c.set("nombre", Txt.strip(txt).trim()); touch(); editor(p, id); });
        });
        m.set(12, Items.of(mo.tipo(id).icono, "&6&lObjetivo", "", "&f" + mo.objetivoCorto(id), "&7Tipo: " + mo.tipo(id).label, "", "&e▶ Click para cambiar"),
                e -> objetivo(p, id));
        boolean final_ = "al_final".equals(c.getString("modo", "una_vez"));
        m.set(13, Items.of(final_ ? Material.CLOCK : Material.TOTEM_OF_UNDYING, final_ ? "&e&lModo: al final del tiempo" : "&e&lModo: una vez y a salvo", "",
                final_ ? "&7Tienes que estar así cuando termina." : "&7Lo haces una vez y quedas a salvo.", "&8(Tocar y Matar siempre son \"una vez\")", "",
                "&e▶ Click para cambiar"), e -> { c.set("modo", final_ ? "una_vez" : "al_final"); touch(); editor(p, id); });
        int dur = c.getInt("duracion", 240);
        m.set(14, Items.of(Material.CLOCK, "&e&lDuración: &f" + String.format("%d:%02d", dur / 60, dur % 60), "",
                "&7Click: +10 s | Click derecho: -10 s", "&7Shift: ±60 s"), e -> {
            int d = (e.isShiftClick() ? 60 : 10) * (e.isRightClick() ? -1 : 1);
            c.set("duracion", Math.max(10, dur + d)); touch(); editor(p, id);
        });
        m.set(15, Items.of(Material.SKELETON_SKULL, "&5&l☠ Consecuencias", "", "&7Qué le pasa al que falla.", "", "&e▶ Click para administrar"),
                e -> consecuencias(p, id));
        m.set(16, Items.of(Material.CHEST, "&6&lRecompensa", "", "&7Para los que lo consiguen (opcional).", "&7Ítems: &f" + mo.recompensas(id).size(), "",
                "&e▶ Click para editar"), e -> recompensas(p, id));
        String av = c.getString("aviso");
        m.set(22, Items.of(Material.ENCHANTED_BOOK, "&b&lAviso vinculado", "", "&7Se muestra después de la ruleta:", av != null ? "&f" + av : "&8ninguno", "",
                "&e▶ Click para elegir"), e -> pl.avisos().elegir(p, "&8Aviso para: " + mo.nombre(id), sel -> { c.set("aviso", sel); touch(); editor(p, id); }));
        m.set(27, Items.of(Material.ARROW, "&7◀ Volver"), e -> lista(p));
        m.set(35, Items.of(Material.BARRIER, "&c&l🗑 Borrar", "", "&cShift+click para borrar"), e -> {
            if (!e.isShiftClick()) { Style.err(p, "Usa shift+click para borrar."); return; }
            if (on) { Style.err(p, "Primero cancélalo."); return; }
            mo.st.set("momentos." + id, null); lista(p);
        });
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    private void recompensas(Player p, String id) {
        Menu m = new Menu(3, "&8Recompensa: " + mo.nombre(id));
        for (int i = 0; i < 27; i++) m.editable(i);
        List<ItemStack> l = mo.recompensas(id);
        for (int i = 0; i < l.size() && i < 27; i++) m.getInventory().setItem(i, l.get(i));
        m.onClose(e -> {
            List<ItemStack> nuevo = new ArrayList<>();
            for (ItemStack it : m.getInventory().getContents()) if (it != null && !it.getType().isAir()) nuevo.add(it.clone());
            mo.m(id).set("recompensas", nuevo); touch();
            Bukkit.getScheduler().runTask(pl, () -> editor(p, id));
        });
        m.open(p);
    }

    // ================================================================= objetivo

    private void objetivo(Player p, String id) {
        Style.click(p);
        ConfigurationSection c = mo.m(id);
        Menu m = new Menu(3, "&8Objetivo: " + mo.nombre(id));
        int[] slots = {10, 11, 12, 14, 15, 16};
        Momentos.Tipo[] ts = Momentos.Tipo.values();
        for (int i = 0; i < ts.length; i++) {
            Momentos.Tipo t = ts[i];
            boolean sel = t == mo.tipo(id);
            ItemStack it = Items.of(t.icono, (sel ? "&a&l" : "&e&l") + t.label, "", sel ? "&a✔ Elegido &7(click para configurar)" : "&e▶ Click para elegir");
            if (sel) Items.glow(it);
            m.set(slots[i], it, e -> {
                if (!sel) { c.set("objetivo.tipo", t.name()); c.set("objetivo.objs", new ArrayList<String>()); touch(); }
                configurar(p, id, t);
            });
        }
        m.set(18, Items.of(Material.ARROW, "&7◀ Volver"), e -> editor(p, id));
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    private void configurar(Player p, String id, Momentos.Tipo t) {
        ConfigurationSection c = mo.m(id);
        switch (t) {
            case DIMENSION: mundos(p, id); return;
            case ALTURA: altura(p, id); return;
            case CERCA: cerca(p, id); return;
            default: break;
        }
        // Tocar / Tener / Matar: items arriba
        Menu m = new Menu(6, "&8" + t.label);
        for (int i = 0; i < 27; i++) m.editable(i);
        int k = 0;
        for (String o : c.getStringList("objetivo.objs")) if (k < 27) m.getInventory().setItem(k++, icono(o));
        for (int i = 27; i < 54; i++) m.set(i, Items.pane(Material.GRAY_STAINED_GLASS_PANE));
        m.set(31, Items.of(Material.OAK_SIGN, "&e&lObjetivos", "", t == Momentos.Tipo.MATAR ? "&7Pon huevos de spawn." : "&7Pon bloques / ítems (vanilla u Oraxen).",
                "&7Cualquiera de ellos sirve."));
        final boolean[] listo = {false};
        Runnable guardar = () -> {
            if (listo[0]) return;
            listo[0] = true;
            List<String> objs = new ArrayList<>();
            for (int i = 0; i < 27; i++) {
                ItemStack it = m.getInventory().getItem(i);
                if (it == null || it.getType().isAir()) continue;
                String marca = it.hasItemMeta() ? it.getItemMeta().getPersistentDataContainer().get(MARCA, PersistentDataType.STRING) : null;
                String o = marca != null ? marca : Obj.deItem(it);
                if (marca == null) p.getInventory().addItem(it.clone()).values().forEach(x -> p.getWorld().dropItem(p.getLocation(), x));
                boolean esMob = o.startsWith("ent:") || o.startsWith("mm:");
                if ((t == Momentos.Tipo.MATAR) != esMob) continue;
                if (!objs.contains(o)) objs.add(o);
            }
            m.getInventory().clear();
            c.set("objetivo.objs", objs); touch();
        };
        if (t != Momentos.Tipo.TOCAR) {
            int cant = c.getInt("objetivo.cant", 1);
            ItemStack ci = Items.of(Material.CLOCK, "&e&lCantidad: &f" + cant, "", "&7Click: +1 | Click derecho: -1", "&7Shift: ±10");
            ci.setAmount(Math.max(1, Math.min(64, cant)));
            m.set(40, ci, e -> {
                int d = (e.isShiftClick() ? 10 : 1) * (e.isRightClick() ? -1 : 1);
                c.set("objetivo.cant", Math.max(1, cant + d)); touch();
                guardar.run(); configurar(p, id, t);
            });
        }
        if (t == Momentos.Tipo.TOCAR) {
            m.set(40, Items.of(Material.RED_BED, "&b&lAgregar: todas las camas"), e -> {
                guardar.run();
                List<String> l = c.getStringList("objetivo.objs");
                if (!l.contains("tag:minecraft:beds")) l.add("tag:minecraft:beds");
                c.set("objetivo.objs", l); touch(); configurar(p, id, t);
            });
        }
        if (t == Momentos.Tipo.MATAR) {
            m.set(42, Items.of(Material.WITHER_SKELETON_SKULL, "&d&lAgregar MythicMob"), e -> {
                guardar.run();
                p.closeInventory();
                Style.info(p, "Escribe el nombre interno del MythicMob &8(o cancel)&7:");
                ChatInput.ask(p, txt -> {
                    ChatInput.cancel(p);
                    List<String> l = c.getStringList("objetivo.objs");
                    String o = "mm:" + Txt.strip(txt).trim();
                    if (!l.contains(o)) l.add(o);
                    c.set("objetivo.objs", l); touch(); configurar(p, id, t);
                });
            });
        }
        m.set(45, Items.of(Material.ARROW, "&a&l✔ Guardar y volver"), e -> { guardar.run(); editor(p, id); });
        m.onClose(e -> guardar.run());
        m.open(p);
    }

    private ItemStack icono(String o) {
        ItemStack it = new ItemStack(Obj.icono(o));
        ItemMeta m = it.getItemMeta();
        m.displayName(Obj.nombre(o).color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        m.lore(Txt.lines("&8" + o, "&7Sácalo de aquí para quitarlo."));
        m.getPersistentDataContainer().set(MARCA, PersistentDataType.STRING, o);
        it.setItemMeta(m);
        return it;
    }

    private void mundos(Player p, String id) {
        ConfigurationSection c = mo.m(id);
        Menu m = new Menu(3, "&8Estar en: mundos");
        int s = 0;
        List<String> objs = c.getStringList("objetivo.objs");
        for (World w : Bukkit.getWorlds()) {
            if (s >= 18) break;
            String o = "dim:" + w.getName();
            boolean sel = objs.contains(o);
            ItemStack it = Items.of(sel ? Material.ENDER_EYE : Material.ENDER_PEARL, ar.tod4.sidebar.Sidebar.mundo(pl, w) + " &8(" + w.getName() + ")", "",
                    sel ? "&a✔ Elegido" : "&e▶ Click para elegir");
            if (sel) Items.glow(it);
            m.set(s++, it, e -> {
                List<String> l = c.getStringList("objetivo.objs");
                if (l.contains(o)) l.remove(o); else l.add(o);
                c.set("objetivo.objs", l); touch(); mundos(p, id);
            });
        }
        m.set(18, Items.of(Material.ARROW, "&a&l✔ Listo"), e -> editor(p, id));
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    private void altura(Player p, String id) {
        ConfigurationSection c = mo.m(id);
        int y = c.getInt("objetivo.y", 64);
        boolean arriba = c.getBoolean("objetivo.arriba", true);
        Menu m = new Menu(3, "&8Altura");
        m.set(10, Items.of(arriba ? Material.FEATHER : Material.ANVIL, arriba ? "&e&lArriba de la altura" : "&e&lAbajo de la altura", "", "&e▶ Click para cambiar"),
                e -> { c.set("objetivo.arriba", !arriba); touch(); altura(p, id); });
        m.set(12, Items.of(Material.RED_STAINED_GLASS_PANE, "&c-10 / -1 &7(derecho)"), e -> { c.set("objetivo.y", y - (e.isRightClick() ? 1 : 10)); touch(); altura(p, id); });
        m.set(13, Items.of(Material.LADDER, "&e&lY = &f" + y));
        m.set(14, Items.of(Material.LIME_STAINED_GLASS_PANE, "&a+10 / +1 &7(derecho)"), e -> { c.set("objetivo.y", y + (e.isRightClick() ? 1 : 10)); touch(); altura(p, id); });
        m.set(16, Items.of(Material.COMPASS, "&b&lUsar mi altura", "", "&7Tu Y actual: &f" + p.getLocation().getBlockY()),
                e -> { c.set("objetivo.y", p.getLocation().getBlockY()); touch(); altura(p, id); });
        m.set(18, Items.of(Material.ARROW, "&a&l✔ Listo"), e -> editor(p, id));
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    private void cerca(Player p, String id) {
        ConfigurationSection c = mo.m(id);
        int r = c.getInt("objetivo.radio", 10);
        boolean hay = c.getLocation("objetivo.punto") != null;
        Menu m = new Menu(3, "&8Punto");
        m.set(11, Items.of(Material.COMPASS, hay ? "&a&lPunto guardado ✔" : "&c&lSin punto", "", "&e▶ Click: usar mi posición"),
                e -> { c.set("objetivo.punto", p.getLocation()); touch(); cerca(p, id); });
        m.set(15, Items.of(Material.CLOCK, "&e&lRadio: &f" + r + " bloques", "", "&7Click: +5 | Click derecho: -5"),
                e -> { c.set("objetivo.radio", Math.max(1, r + (e.isRightClick() ? -5 : 5))); touch(); cerca(p, id); });
        m.set(18, Items.of(Material.ARROW, "&a&l✔ Listo"), e -> editor(p, id));
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    // ================================================================= consecuencias

    private void consecuencias(Player p, String id) {
        ConfigurationSection c = mo.m(id).getConfigurationSection("cons");
        if (c == null) c = mo.m(id).createSection("cons");
        final ConfigurationSection cc = c;
        Menu m = new Menu(5, "&4&l☠ &8Consecuencias");
        toggle(m, p, id, cc, 10, "instakill", Material.WITHER_SKELETON_SKULL, "💀 Insta Kill", "Muere ignorando el tótem.");
        toggle(m, p, id, cc, 11, "corazones_on", Material.RED_DYE, "❤ Quitar corazones: " + cc.getInt("corazones", 3),
                "Le baja la vida máxima.", "Click derecho: +1 | Shift+derecho: -1", "corazones", 1, 10);
        toggle(m, p, id, cc, 12, "medio", Material.REDSTONE, "🩸 Medio corazón", "Queda a medio corazón.");
        toggle(m, p, id, cc, 13, "explosion", Material.TNT, "💥 Explosión: fuerza " + cc.getInt("fuerza", 4),
                "Explota donde está.", "Click derecho: +1 | Shift+derecho: -1", "fuerza", 1, 10);
        toggle(m, p, id, cc, 14, "rayo", Material.LIGHTNING_ROD, "⚡ Rayo", "Le cae un rayo.");
        toggle(m, p, id, cc, 15, "tirar", Material.CHEST, "🎒 Tirar inventario", "Todo su inventario cae al piso.");
        toggle(m, p, id, cc, 16, "tp", Material.ENDER_PEARL, "📍 Teletransporte" + (cc.getLocation("tp_pos") != null ? " &a(✔ lugar)" : " &c(sin lugar)"),
                "Lo manda al lugar guardado.", "Click derecho: guardar tu posición", null, 0, 0);
        toggle(m, p, id, cc, 19, "mobs", Material.ZOMBIE_HEAD, "👹 Mobs: " + cc.getInt("mobs_cant", 3) + "x " + cc.getString("mobs_tipo", "zombie"),
                "Aparecen mobs donde está.", "Click derecho: elegir mob (chat)", null, 0, 0);
        toggle(m, p, id, cc, 20, "wither", Material.WITHER_ROSE, "☠ Wither", "Efecto por " + cc.getInt("efectos_seg", 10) + " s.");
        toggle(m, p, id, cc, 21, "poison", Material.POISONOUS_POTATO, "🧪 Veneno", "Efecto por " + cc.getInt("efectos_seg", 10) + " s.");
        toggle(m, p, id, cc, 22, "slowness", Material.COBWEB, "🐌 Lentitud", "Efecto por " + cc.getInt("efectos_seg", 10) + " s.");
        toggle(m, p, id, cc, 23, "darkness", Material.BLACK_CONCRETE, "🌑 Oscuridad", "Efecto por " + cc.getInt("efectos_seg", 10) + " s.");
        toggle(m, p, id, cc, 24, "freeze", Material.PACKED_ICE, "🧊 Congelar: " + cc.getInt("freeze_seg", 5) + " s",
                "No se puede mover.", "Click derecho: +1 s | Shift+derecho: -1 s", "freeze_seg", 1, 60);
        int seg = cc.getInt("efectos_seg", 10);
        m.set(31, Items.of(Material.CLOCK, "&e&lDuración de efectos: &f" + seg + " s", "", "&7Click: +5 | Click derecho: -5"),
                e -> { cc.set("efectos_seg", Math.max(5, seg + (e.isRightClick() ? -5 : 5))); touch(); consecuencias(p, id); });
        m.set(36, Items.of(Material.ARROW, "&7◀ Volver"), e -> editor(p, id));
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    private void toggle(Menu m, Player p, String id, ConfigurationSection c, int slot, String key, Material mat, String nombre, String desc) {
        toggle(m, p, id, c, slot, key, mat, nombre, desc, null, null, 0, 0);
    }

    private void toggle(Menu m, Player p, String id, ConfigurationSection c, int slot, String key, Material mat, String nombre, String desc,
                        String extra, String numKey, int min, int max) {
        boolean on = c.getBoolean(key, false);
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("&7Estado: " + (on ? "&aACTIVADO" : "&cDESACTIVADO"));
        lore.add("&7" + desc);
        if (extra != null) lore.add("&8" + extra);
        lore.add("");
        lore.add(on ? "&e▶ Click para desactivar" : "&e▶ Click para activar");
        ItemStack it = Items.of(on ? mat : Material.GRAY_WOOL, (on ? "&c&l" : "&7&l") + nombre, lore);
        m.set(slot, it, e -> {
            if (e.isRightClick()) {
                if (numKey != null) {
                    int v = c.getInt(numKey, min);
                    c.set(numKey, Math.max(min, Math.min(max, v + (e.isShiftClick() ? -1 : 1))));
                } else if (key.equals("tp")) {
                    c.set("tp_pos", p.getLocation());
                    Style.ok(p, "Lugar del teletransporte guardado.");
                } else if (key.equals("mobs")) {
                    p.closeInventory();
                    Style.info(p, "Escribe el mob y la cantidad: &ezombie 3 &7o &dmythic:EnderZombie 2 &8(o cancel)");
                    ChatInput.ask(p, txt -> {
                        ChatInput.cancel(p);
                        String[] a = Txt.strip(txt).trim().split("\\s+");
                        c.set("mobs_tipo", a[0].toLowerCase().startsWith("mythic:") ? "mythic:" + a[0].substring(7) : a[0].toLowerCase());
                        if (a.length > 1) try { c.set("mobs_cant", Math.max(1, Math.min(20, Integer.parseInt(a[1])))); } catch (NumberFormatException ignored) { }
                        touch(); consecuencias(p, id);
                    });
                    return;
                }
            } else {
                if (key.equals("tp") && !on && c.getLocation("tp_pos") == null) { Style.err(p, "Primero guarda el lugar (click derecho)."); return; }
                c.set(key, !on);
            }
            touch();
            consecuencias(p, id);
        });
    }
}

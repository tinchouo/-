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

    private static String raiz(String path) { return path.contains(".") ? path.substring(0, path.indexOf('.')) : path; }

    /** Vuelve al menu que corresponde: el del paso (regla o spawn) o el del momento. */
    private void volver(Player p, String id) {
        if (id.contains(".pasos.")) { if (mo.esSpawn(id)) pasoSpawn(p, id); else pasoEditor(p, id); }
        else editor(p, id);
    }

    public void lista(Player p) {
        Style.click(p);
        Menu m = new Menu(6, "&4&l⚡ &8Momentos Truco");
        int s = 0;
        for (String id : mo.ids()) {
            if (s >= 45) break;
            boolean on = id.equals(mo.activo());
            ItemStack it = Items.of(Material.REDSTONE_TORCH, "&c&l⚡ " + mo.nombre(id), "", "&7Pasos: &f" + mo.pasos(id).size(), "",
                    on ? (mo.enCurso() ? "&c&l● EN CURSO" : "&e&l● PREPARADO") : "&e▶ Click para administrar");
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
                if (!c.contains("nombre")) { c.set("nombre", nom); touch(); }
                editor(p, id);
            });
        });
        m.open(p);
    }

    /** El momento: preparar/terminar, nombre, pasos, portales. */
    void editor(Player p, String id) {
        Style.click(p);
        ConfigurationSection c = mo.m(id);
        boolean on = id.equals(mo.activo());
        Menu m = new Menu(4, "&4&l⚡ &8" + mo.nombre(id));
        if (on) {
            m.set(10, Items.of(Material.RED_WOOL, "&c&l✖ TERMINAR MOMENTO", "", "&7Termina todo sin castigar a nadie."), e -> { mo.cancelar(p); editor(p, id); });
            m.set(19, Items.of(Material.CHEST, "&e&lDarme los ítems otra vez", "", "&7Un ítem por paso."), e -> { mo.darItems(p); p.closeInventory(); });
        } else {
            m.set(10, Items.of(Material.LIME_WOOL, "&a&l▶ PREPARAR MOMENTO", "", "&7No empieza para los jugadores todavía:",
                    "&7recibes un ítem por paso y el momento", "&7arranca cuando activas el primero."), e -> { p.closeInventory(); mo.preparar(p, id); });
        }
        m.set(11, Items.of(Material.NAME_TAG, "&e&lNombre", "", "&7" + mo.nombre(id), "", "&e▶ Click para cambiar"), e -> {
            p.closeInventory();
            Style.info(p, "Escribe el nuevo nombre &8(o cancel)&7:");
            ChatInput.ask(p, txt -> { ChatInput.cancel(p); c.set("nombre", Txt.strip(txt).trim()); touch(); editor(p, id); });
        });
        m.set(12, Items.of(Material.WRITABLE_BOOK, "&6&lPasos", "", "&7Cosas que pueden pasar en este momento.", "&7Pasos: &f" + mo.pasos(id).size(), "", "&e▶ Click para administrar"),
                e -> pasos(p, id));
        boolean bloq = c.getBoolean("bloquear_portales", false);
        m.set(13, Items.of(bloq ? Material.OBSIDIAN : Material.END_PORTAL_FRAME, bloq ? "&c&lPortales: bloqueados" : "&a&lPortales: permitidos", "",
                "&7Si están bloqueados, nadie cambia de dimensión", "&7mientras dura el momento.", "", "&e▶ Click para cambiar"),
                e -> { c.set("bloquear_portales", !bloq); touch(); editor(p, id); });
        m.set(27, Items.of(Material.ARROW, "&7◀ Volver"), e -> lista(p));
        m.set(35, Items.of(Material.BARRIER, "&c&l🗑 Borrar", "", "&cShift+click para borrar"), e -> {
            if (!e.isShiftClick()) { Style.err(p, "Usa shift+click para borrar."); return; }
            if (on) { Style.err(p, "Primero termínalo."); return; }
            mo.st.set("momentos." + id, null); lista(p);
        });
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    private void pasos(Player p, String id) {
        Style.click(p);
        Menu m = new Menu(6, "&8Pasos: " + mo.nombre(id));
        int s = 0;
        for (String pid : mo.pasos(id)) {
            if (s >= 45) break;
            String path = id + ".pasos." + pid;
            boolean sp = mo.esSpawn(path);
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add("&7" + mo.objetivoCorto(path));
            lore.add("&7Sidebar: &f" + mo.textoSidebar(path));
            if (!sp) lore.add("&7Tiempo: &f" + (mo.m(path).getInt("duracion", 0) > 0 ? Momentos.duracion(mo.m(path).getInt("duracion", 0)) : "sin tiempo")
                    + (mo.m(path).getBoolean("gas.on", false) ? " &8| &2☣ gas" : ""));
            lore.add("");
            lore.add("&e▶ Click para editar");
            lore.add("&c▶ Shift+click derecho para borrar");
            m.set(s++, Items.of(sp ? Material.CREEPER_HEAD : mo.tipo(path).icono, "&c&l" + (s) + ". &f" + mo.nombre(path), lore), e -> {
                if (e.isShiftClick() && e.isRightClick()) { mo.st.set("momentos." + path, null); pasos(p, id); return; }
                volver(p, path);
            });
        }
        for (int i = 45; i < 54; i++) m.set(i, Items.pane(Material.RED_STAINED_GLASS_PANE));
        m.set(45, Items.of(Material.ARROW, "&7◀ Volver"), e -> editor(p, id));
        m.set(48, Items.of(Material.LIME_CONCRETE, "&a&l➕ Agregar regla", "", "&7Tocar, altura, punto, dimensión...", "&7con tiempo, gas y castigo."), e -> nuevoPaso(p, id, false));
        m.set(50, Items.of(Material.CREEPER_HEAD, "&a&l➕ Agregar spawn de mob", "", "&7Hace aparecer un mob en la posición", "&7de cada jugador."), e -> nuevoPaso(p, id, true));
        m.open(p);
    }

    private void nuevoPaso(Player p, String id, boolean spawn) {
        p.closeInventory();
        Style.info(p, "Escribe el &enombre &7del paso &8(o cancel)&7:");
        ChatInput.ask(p, txt -> {
            ChatInput.cancel(p);
            String nom = Txt.strip(txt).trim();
            if (nom.isEmpty()) { Style.err(p, "Nombre inválido."); return; }
            int n = mo.pasos(id).size() + 1;
            while (mo.existe(id + ".pasos." + n)) n++;
            String path = id + ".pasos." + n;
            ConfigurationSection c = mo.m(path);
            c.set("nombre", nom);
            if (spawn) {
                c.set("tipo_paso", "SPAWN"); c.set("spawn.mob", "creeper_cargado"); c.set("spawn.cant", 1); c.set("spawn.vida", 1); c.set("spawn.azar", 0);
            } else {
                c.set("tipo_paso", "REGLA"); c.set("duracion", 0); c.set("modo", "una_vez");
                c.set("objetivo.tipo", "TOCAR"); c.set("objetivo.objs", List.of("tag:minecraft:beds"));
            }
            touch();
            volver(p, path);
        });
    }

    /** Un paso de tipo regla. */
    void pasoEditor(Player p, String path) {
        Style.click(p);
        ConfigurationSection c = mo.m(path);
        String id = raiz(path);
        Menu m = new Menu(4, "&4&l⚡ &8" + mo.nombre(path));
        m.set(10, Items.of(Material.NAME_TAG, "&e&lNombre", "", "&7" + mo.nombre(path), "", "&e▶ Click para cambiar"), e -> {
            p.closeInventory();
            Style.info(p, "Escribe el nuevo nombre &8(o cancel)&7:");
            ChatInput.ask(p, txt -> { ChatInput.cancel(p); c.set("nombre", Txt.strip(txt).trim()); touch(); pasoEditor(p, path); });
        });
        m.set(11, Items.of(mo.tipo(path).icono, "&6&lObjetivo", "", "&f" + mo.objetivoCorto(path), "&7Tipo: " + mo.tipo(path).label, "", "&e▶ Click para cambiar"),
                e -> objetivo(p, path));
        boolean final_ = "al_final".equals(c.getString("modo", "una_vez"));
        m.set(12, Items.of(final_ ? Material.CLOCK : Material.TOTEM_OF_UNDYING, final_ ? "&e&lModo: al final del tiempo" : "&e&lModo: una vez y a salvo", "",
                final_ ? "&7Tienes que estar así cuando termina." : "&7Lo haces una vez y quedas a salvo.", "&8(Tocar y Matar siempre son \"una vez\")", "",
                "&e▶ Click para cambiar"), e -> { c.set("modo", final_ ? "una_vez" : "al_final"); touch(); pasoEditor(p, path); });
        int dur = c.getInt("duracion", 0);
        m.set(13, Items.of(Material.COMPASS, "&e&lTiempo: &f" + (dur > 0 ? String.format("%d:%02d", dur / 60, dur % 60) : "sin tiempo"), "",
                "&7Click: +10 s | Click derecho: -10 s", "&7Shift: ±60 s", "&7En 0 no hay reloj ni castigo por tiempo."), e -> {
            int d = (e.isShiftClick() ? 60 : 10) * (e.isRightClick() ? -1 : 1);
            c.set("duracion", Math.max(0, dur + d)); touch(); pasoEditor(p, path);
        });
        m.set(14, Items.of(Material.SKELETON_SKULL, "&5&l☠ Consecuencias", "", "&7Qué le pasa al que falla cuando", "&7se acaba el tiempo (castigos y gas).", "", "&e▶ Click para administrar"),
                e -> consecuencias(p, path));
        m.set(15, Items.of(Material.CHEST, "&6&lRecompensa", "", "&7Para los que lo consiguen (opcional).", "&7Ítems: &f" + mo.recompensas(path).size(), "",
                "&e▶ Click para editar"), e -> recompensas(p, path));
        textoYAviso(m, p, path, c, () -> pasoEditor(p, path));
        m.set(27, Items.of(Material.ARROW, "&7◀ Volver"), e -> pasos(p, id));
        m.set(35, Items.of(Material.BARRIER, "&c&l🗑 Borrar", "", "&cShift+click para borrar"), e -> {
            if (!e.isShiftClick()) { Style.err(p, "Usa shift+click para borrar."); return; }
            mo.st.set("momentos." + path, null); pasos(p, id);
        });
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    /** Un paso de tipo spawn de mob. */
    void pasoSpawn(Player p, String path) {
        Style.click(p);
        ConfigurationSection c = mo.m(path);
        String id = raiz(path);
        Menu m = new Menu(4, "&4&l⚡ &8" + mo.nombre(path));
        m.set(10, Items.of(Material.NAME_TAG, "&e&lNombre", "", "&7" + mo.nombre(path), "", "&e▶ Click para cambiar"), e -> {
            p.closeInventory();
            Style.info(p, "Escribe el nuevo nombre &8(o cancel)&7:");
            ChatInput.ask(p, txt -> { ChatInput.cancel(p); c.set("nombre", Txt.strip(txt).trim()); touch(); pasoSpawn(p, path); });
        });
        m.set(11, Items.of(Material.CREEPER_HEAD, "&6&lMob: &f" + c.getString("spawn.mob", "creeper_cargado"), "",
                "&e▶ Click: elegir con la vara", "&7  (click derecho al mob; sirve con MythicMobs)", "",
                "&7Click derecho: escribir el nombre", "&8  creeper_cargado, mythic:Id, zombie..."), e -> {
            if (e.isRightClick()) {
                p.closeInventory();
                Style.info(p, "Escribe el mob &8(o cancel)&7:");
                ChatInput.ask(p, txt -> { ChatInput.cancel(p); c.set("spawn.mob", Txt.strip(txt).trim()); touch(); pasoSpawn(p, path); });
                return;
            }
            p.closeInventory();
            mo.modoMob().entrar(p, false, obj -> { c.set("spawn.mob", ModoMob.paraSpawn(obj)); touch(); }, () -> pasoSpawn(p, path));
        });
        int cant = c.getInt("spawn.cant", 1);
        m.set(12, Items.of(Material.PAPER, "&e&lCantidad por jugador: &f" + cant, "", "&7Click: +1 | Click derecho: -1"),
                e -> { c.set("spawn.cant", Math.max(1, Math.min(20, cant + (e.isRightClick() ? -1 : 1)))); touch(); pasoSpawn(p, path); });
        double vida = c.getDouble("spawn.vida", 0);
        m.set(13, Items.of(Material.GOLDEN_APPLE, "&c&lVida: &f" + (vida > 0 ? (int) vida : "normal"), "", "&7Click: +1 | Click derecho: -1", "&7Shift: ±10", "&7En 0 usa la vida normal del mob."), e -> {
            int d = (e.isShiftClick() ? 10 : 1) * (e.isRightClick() ? -1 : 1);
            c.set("spawn.vida", Math.max(0, vida + d)); touch(); pasoSpawn(p, path);
        });
        int azar = c.getInt("spawn.azar", 0);
        m.set(14, Items.of(Material.PLAYER_HEAD, "&e&lA quién: &f" + (azar > 0 ? azar + " jugadores al azar" : "todos los jugadores"), "",
                "&7Click: +1 | Click derecho: -1", "&7En 0 le toca a todos.", "&7Los exentos nunca reciben mobs."),
                e -> { c.set("spawn.azar", Math.max(0, azar + (e.isRightClick() ? -1 : 1))); touch(); pasoSpawn(p, path); });
        textoYAviso(m, p, path, c, () -> pasoSpawn(p, path));
        m.set(27, Items.of(Material.ARROW, "&7◀ Volver"), e -> pasos(p, id));
        m.set(35, Items.of(Material.BARRIER, "&c&l🗑 Borrar", "", "&cShift+click para borrar"), e -> {
            if (!e.isShiftClick()) { Style.err(p, "Usa shift+click para borrar."); return; }
            mo.st.set("momentos." + path, null); pasos(p, id);
        });
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    /** Texto de la sidebar y aviso vinculado (los dos tipos de paso). */
    private void textoYAviso(Menu m, Player p, String path, ConfigurationSection c, Runnable otra) {
        m.set(19, Items.of(Material.OAK_SIGN, "&c&lRenglón de la sidebar", "", "&4■ &c" + mo.textoSidebar(path), "",
                "&7Sale con el cuadradito mientras el paso", "&7está activo (uno por paso).", "&7Arriba sale el aviso vinculado tal cual.", "", "&e▶ Click para cambiar"), e -> {
            p.closeInventory();
            Style.info(p, "Escribe el renglón de la sidebar &8(corto, o cancel)&7:");
            ChatInput.ask(p, txt -> { ChatInput.cancel(p); c.set("texto", Txt.strip(txt).trim()); touch(); otra.run(); });
        });
        boolean rul = c.getBoolean("ruleta", true);
        boolean spw = mo.esSpawn(path);
        m.set(21, Items.of(rul ? Material.LIME_DYE : Material.RED_DYE, rul ? "&a&lRuleta y aviso: SÍ" : "&c&lRuleta y aviso: NO", "",
                rul ? "&7Al activarlo gira la ruleta y sale el aviso" : "&7Se aplica al instante, sin ruleta (sorpresa).",
                rul ? "&7antes de que pase." : "&7El aviso, si tiene, sale igual.",
                spw ? "&7Spawn: el primer clic es el anuncio; después" : "",
                spw ? "&7el ítem spawnea el mob con cada clic." : "", "", "&e▶ Click para cambiar"),
                e -> { c.set("ruleta", !rul); touch(); otra.run(); });
        String av = c.getString("aviso");
        m.set(20, Items.of(Material.ENCHANTED_BOOK, "&b&lAviso vinculado", "", "&7Se muestra después de la ruleta", "&7y queda arriba en la sidebar (sin el título):", av != null ? "&f" + av : "&8ninguno", "",
                "&e▶ Click para elegir"), e -> pl.avisos().elegir(p, "&8Aviso para: " + mo.nombre(path), sel -> { c.set("aviso", sel); touch(); otra.run(); }));
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
            Bukkit.getScheduler().runTask(pl, () -> volver(p, id));
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
        m.set(18, Items.of(Material.ARROW, "&7◀ Volver"), e -> volver(p, id));
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
            m.set(41, Items.of(Material.BLAZE_ROD, "&c&lElegir mobs con la vara", "", "&7Click derecho a cada mob que cuente.", "&7Sirve con MythicMobs."), e -> {
                guardar.run();
                p.closeInventory();
                mo.modoMob().entrar(p, true, obj -> {
                    List<String> l = c.getStringList("objetivo.objs");
                    if (!l.contains(obj)) l.add(obj);
                    c.set("objetivo.objs", l); touch();
                }, () -> configurar(p, id, t));
            });
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
        m.set(45, Items.of(Material.ARROW, "&a&l✔ Guardar y volver"), e -> { guardar.run(); volver(p, id); });
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
        m.set(18, Items.of(Material.ARROW, "&a&l✔ Listo"), e -> volver(p, id));
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
        m.set(18, Items.of(Material.ARROW, "&a&l✔ Listo"), e -> volver(p, id));
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
        m.set(18, Items.of(Material.ARROW, "&a&l✔ Listo"), e -> volver(p, id));
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    // ================================================================= consecuencias

    private void consecuencias(Player p, String id) {
        ConfigurationSection c = mo.m(id).getConfigurationSection("cons");
        if (c == null) c = mo.m(id).createSection("cons");
        final ConfigurationSection cc = c;
        Menu m = new Menu(5, "&4&l☠ &8Consecuencias");
        toggle(m, p, id, cc, 10, "instakill", Material.WITHER_SKELETON_SKULL, "💀 Insta Kill", "Muere ignorando el tótem.");
        int corSeg = cc.getInt("corazones_seg", 0);
        toggle(m, p, id, cc, 11, "corazones_on", Material.RED_DYE, "❤ Quitar corazones: " + cc.getInt("corazones", 3),
                "Le baja la vida máxima " + (corSeg > 0 ? "por " + Momentos.duracion(corSeg) + " conectado." : "para siempre."),
                "Click derecho: +1 | Shift+derecho: -1", "corazones", 1, 10);
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
        // gas: se guarda en el paso (gas.*), arranca cuando se acaba el tiempo
        ConfigurationSection paso = mo.m(id);
        boolean gas = paso.getBoolean("gas.on", false);
        double gcor = paso.getDouble("gas.corazones", 2.5);
        double gcada = paso.getDouble("gas.cada", 1.0);
        m.set(25, Items.of(gas ? Material.SLIME_BALL : Material.GRAY_WOOL, (gas ? "&2&l" : "&7&l") + "☣ Gas tóxico: " + fmt(gcor) + " ❤ cada " + fmt(gcada) + " s", "",
                "&7Estado: " + (gas ? "&aACTIVADO" : "&cDESACTIVADO"),
                "&7Cuando se acaba el tiempo, le pega al que", "&7no cumple hasta que lo consiga.",
                "&7Ignora la armadura; la Resistencia lo baja.", "&8Solo funciona si el paso tiene tiempo.", "",
                "&eClick: activar / desactivar", "&eClick derecho: +0,5 ❤ | Shift+derecho: -0,5 ❤", "&eShift+click: cada cuántos segundos"), e -> {
            if (e.isShiftClick() && e.isRightClick()) paso.set("gas.corazones", Math.max(0.5, gcor - 0.5));
            else if (e.isRightClick()) paso.set("gas.corazones", Math.min(10, gcor + 0.5));
            else if (e.isShiftClick()) paso.set("gas.cada", gcada >= 5 ? 0.5 : gcada + 0.5);
            else paso.set("gas.on", !gas);
            touch(); consecuencias(p, id);
        });
        int seg = cc.getInt("efectos_seg", 10);
        m.set(30, Items.of(Material.CLOCK, "&e&lDuración de efectos: &f" + Momentos.duracion(seg), "", "&7Wither, veneno, lentitud y oscuridad.",
                "&7Solo corre mientras está conectado.", "", "&7Click: +5 s | Click derecho: -5 s", "&7Shift: ±5 min"),
                e -> { int d = e.isShiftClick() ? 300 : 5; cc.set("efectos_seg", Math.max(5, seg + (e.isRightClick() ? -d : d))); touch(); consecuencias(p, id); });
        m.set(32, Items.of(Material.RECOVERY_COMPASS, "&c&lDuración de corazones: &f" + (corSeg > 0 ? Momentos.duracion(corSeg) : "para siempre"), "",
                "&7Cuánto tiempo conectado tiene que estar", "&7para recuperar los corazones.", "&7En 0 no los recupera nunca.", "",
                "&7Click: +1 h | Click derecho: -1 h", "&7Shift: ±10 min"),
                e -> { int d = e.isShiftClick() ? 600 : 3600; cc.set("corazones_seg", Math.max(0, corSeg + (e.isRightClick() ? -d : d))); touch(); consecuencias(p, id); });
        m.set(36, Items.of(Material.ARROW, "&7◀ Volver"), e -> volver(p, id));
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((int) d) : String.valueOf(d).replace('.', ',');
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

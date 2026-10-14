package ar.tod4.misiones;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementDisplay;
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
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Menus de misiones: el panel de admin (/tod4:panel) y el del jugador (/tod4:misiones). */
public final class MisionesMenu {

    private final TOD4 pl;
    private final Misiones ms;
    private final NamespacedKey MARCA;

    public MisionesMenu(TOD4 pl, Misiones ms) {
        this.pl = pl;
        this.ms = ms;
        this.MARCA = new NamespacedKey(pl, "obj");
    }

    // ================================================================= jugador

    public void jugador(Player p) {
        Style.click(p);
        Menu m = new Menu(3, "&8Misiones");
        m.set(11, itemMision(p, Misiones.DIA), e -> {
            if (ms.estado(Misiones.DIA, p) == Misiones.Estado.COMPLETADA) { ms.reclamar(Misiones.DIA, p); jugador(p); }
        });
        m.set(15, itemMision(p, Misiones.OP), e -> {
            if (ms.estado(Misiones.OP, p) == Misiones.Estado.COMPLETADA) { ms.reclamar(Misiones.OP, p); jugador(p); }
        });
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    private ItemStack itemMision(Player p, String clase) {
        boolean dia = clase.equals(Misiones.DIA);
        String id = ms.activa(clase);
        if (id == null) return Items.of(Material.BARRIER, dia ? "&7Misión Diaria" : "&7Misión Opcional", "", "&7No hay ninguna misión activa.");
        Misiones.Estado est = ms.estado(clase, p);
        String col = dia ? "&a" : "&b";
        if (est == Misiones.Estado.BLOQUEADA)
            return Items.of(Material.BOOK, col + "&lMisión Opcional: &f" + ms.nombre(id), "", "&c🔒 BLOQUEADA", "", "&7Completá y reclamá la diaria primero.");
        List<Component> lore = new ArrayList<>();
        lore.add(Component.empty());
        List<Req> rs = ms.reqs(id);
        for (int i = 0; i < rs.size(); i++) lore.addAll(lineasReq(rs.get(i), ms.progreso(clase, p, i, rs.get(i))));
        lore.add(Component.empty());
        lore.add(Txt.c("&7Estado: " + Misiones.texto(est)));
        if (est == Misiones.Estado.COMPLETADA) lore.add(Txt.c("&6▶ Click para reclamar la recompensa"));
        ItemStack it = new ItemStack(est == Misiones.Estado.RECLAMADA ? Material.ENCHANTED_BOOK : (dia ? Material.WRITABLE_BOOK : Material.BOOK));
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Txt.c(col + "&l" + (dia ? "Misión Diaria: " : "Misión Opcional: ") + "&f" + ms.nombre(id)));
        meta.lore(lore);
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    /** "Conseguir: 3/10" y abajo cada cosa, con el nombre traducido al idioma del jugador. */
    List<Component> lineasReq(Req r, int prog) {
        List<Component> out = new ArrayList<>();
        int meta = Misiones.meta(r);
        String pr = prog >= meta ? "&a✔" : "&7" + prog + "/" + meta;
        String extra = r.tipo == Req.Tipo.CONSEGUIR && r.consumir ? " &8(se entrega)" : "";
        if (r.tipo == Req.Tipo.EVENTO) {
            out.add(Txt.c("&fEvento: " + pr));
            if (!r.texto.isEmpty()) out.add(Txt.c(" &8▪ &7" + r.texto));
            return out;
        }
        out.add(Txt.c("&f" + r.tipo.label + ": " + pr + extra));
        for (String o : r.objs)
            out.add(Txt.c(" &8▪ ").append(Obj.nombre(o).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        return out;
    }

    // ================================================================= admin

    public void panel(Player p) {
        Style.click(p);
        Menu m = new Menu(3, "&8Panel de Misiones");
        String d = ms.activa(Misiones.DIA), o = ms.activa(Misiones.OP);
        m.set(11, Items.of(Material.LIME_DYE, "&a&lMisiones Diarias", "", "&7Activa: " + (d != null ? "&f" + ms.nombre(d) : "&8ninguna"), "",
                "&e▶ Click para administrar"), e -> lista(p, Misiones.DIA));
        m.set(15, Items.of(Material.LIGHT_BLUE_DYE, "&b&lMisiones Opcionales", "", "&7Activa: " + (o != null ? "&f" + ms.nombre(o) : "&8ninguna"), "",
                "&e▶ Click para administrar"), e -> lista(p, Misiones.OP));
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    private void lista(Player p, String clase) {
        Style.click(p);
        boolean dia = clase.equals(Misiones.DIA);
        Menu m = new Menu(6, dia ? "&8Misiones Diarias" : "&8Misiones Opcionales");
        String act = ms.activa(clase);
        int slot = 0;
        for (String id : ms.ids(clase)) {
            if (slot >= 45) break;
            boolean on = id.equals(act);
            ItemStack it = Items.of(on ? Material.ENCHANTED_BOOK : Material.BOOK, (dia ? "&a&l" : "&b&l") + ms.nombre(id), "",
                    "&7Requisitos: &f" + ms.reqs(id).size(), "&7Recompensas: &f" + ms.recompensas(id).size(), "",
                    on ? "&a&l✔ ACTIVA" : "&8Inactiva", "", "&e▶ Click para editar");
            m.set(slot++, it, e -> editor(p, id));
        }
        for (int i = 45; i < 54; i++) m.set(i, Items.pane(Material.BLACK_STAINED_GLASS_PANE));
        m.set(45, Items.of(Material.ARROW, "&7◀ Volver"), e -> panel(p));
        m.set(49, Items.of(Material.LIME_CONCRETE, "&a&l➕ Crear Misión", "", "&a▶ Click para crear"), e -> {
            p.closeInventory();
            Style.info(p, "Escribí el &enombre &7de la misión &8(o cancel)&7:");
            ChatInput.ask(p, txt -> {
                ChatInput.cancel(p);
                String nom = Txt.strip(txt).trim();
                String id = nom.toLowerCase().replaceAll("[^a-z0-9áéíóúñ]+", "_");
                if (id.isEmpty()) { Style.err(p, "Nombre inválido."); return; }
                if (ms.existe(id)) { Style.err(p, "Ya existe una misión con ese nombre."); editor(p, id); return; }
                ms.crear(id, clase);
                ms.store().set("misiones." + id + ".nombre", nom);
                Style.ok(p, "Misión &f" + nom + " &acreada.");
                editor(p, id);
            });
        });
        m.open(p);
    }

    private void editor(Player p, String id) {
        Style.click(p);
        String clase = ms.clase(id);
        boolean on = id.equals(ms.activa(clase));
        Menu m = new Menu(4, "&8Misión: " + ms.nombre(id));
        m.set(10, Items.of(Material.NAME_TAG, "&e&lNombre", "", "&7Actual: &f" + ms.nombre(id), "", "&e▶ Click para cambiar"), e -> {
            p.closeInventory();
            Style.info(p, "Escribí el nuevo nombre &8(o cancel)&7:");
            ChatInput.ask(p, txt -> { ChatInput.cancel(p); ms.store().set("misiones." + id + ".nombre", Txt.strip(txt).trim()); editor(p, id); });
        });
        m.set(12, Items.of(Material.WRITABLE_BOOK, "&a&lRequisitos", "", "&7Cantidad: &f" + ms.reqs(id).size(), "", "&e▶ Click para editar"),
                e -> requisitos(p, id));
        m.set(14, Items.of(Material.CHEST, "&6&lRecompensas", "", "&7Ítems: &f" + ms.recompensas(id).size(), "",
                "&7Poné los ítems que se dan al reclamar.", "", "&e▶ Click para editar"), e -> recompensas(p, id));
        m.set(16, Items.of(on ? Material.LIME_DYE : Material.RED_DYE, on ? "&a&lACTIVA" : "&c&lINACTIVA", "",
                on ? "&7Está activa ahora." : (clase.equals(Misiones.DIA) ? "&7Al activarla gira la ruleta verde." : "&7Al activarla sale en el chat."),
                "", on ? "&c▶ Click para desactivar" : "&a▶ Click para activar"), e -> {
            if (on) ms.desactivar(clase, true);
            else {
                if (ms.reqs(id).isEmpty()) { Style.err(p, "La misión no tiene requisitos."); return; }
                p.closeInventory();
                ms.activar(clase, id);
                return;
            }
            editor(p, id);
        });
        m.set(27, Items.of(Material.ARROW, "&7◀ Volver"), e -> lista(p, clase));
        m.set(35, Items.of(Material.BARRIER, "&c&l🗑 Borrar Misión", "", "&cShift+click para borrar"), e -> {
            if (!e.isShiftClick()) { Style.err(p, "Usá shift+click para borrar."); return; }
            ms.borrar(id);
            Style.ok(p, "Misión borrada.");
            lista(p, clase);
        });
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    private void recompensas(Player p, String id) {
        Menu m = new Menu(3, "&8Recompensas: " + ms.nombre(id));
        for (int i = 0; i < 27; i++) m.editable(i);
        List<ItemStack> l = ms.recompensas(id);
        for (int i = 0; i < l.size() && i < 27; i++) m.getInventory().setItem(i, l.get(i));
        m.onClose(e -> {
            List<ItemStack> nuevo = new ArrayList<>();
            for (ItemStack it : m.getInventory().getContents()) if (it != null && !it.getType().isAir()) nuevo.add(it.clone());
            ms.guardarRecompensas(id, nuevo);
            Style.ok(p, "Recompensas guardadas (&f" + nuevo.size() + "&a).");
            Bukkit.getScheduler().runTask(pl, () -> editor(p, id));
        });
        m.open(p);
    }

    // ------------------------------------------------------------------ requisitos

    private void requisitos(Player p, String id) {
        Style.click(p);
        Menu m = new Menu(6, "&8Requisitos: " + ms.nombre(id));
        List<Req> rs = ms.reqs(id);
        for (int i = 0; i < rs.size() && i < 45; i++) {
            final int idx = i;
            Req r = rs.get(i);
            ItemStack it = new ItemStack(r.tipo.icono);
            ItemMeta meta = it.getItemMeta();
            meta.displayName(Txt.c("&e&l" + (i + 1) + ". " + r.tipo.label));
            List<Component> lore = new ArrayList<>(lineasReq(r, 0));
            lore.add(Component.empty());
            lore.add(Txt.c("&e▶ Click para editar"));
            lore.add(Txt.c("&c▶ Shift+click para borrar"));
            meta.lore(lore);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            it.setItemMeta(meta);
            m.set(i, it, e -> {
                if (e.isShiftClick()) {
                    List<Req> l = ms.reqs(id);
                    l.remove(idx);
                    ms.guardarReqs(id, l);
                    requisitos(p, id);
                } else editarReq(p, id, idx);
            });
        }
        for (int i = 45; i < 54; i++) m.set(i, Items.pane(Material.BLACK_STAINED_GLASS_PANE));
        m.set(45, Items.of(Material.ARROW, "&7◀ Volver"), e -> editor(p, id));
        m.set(49, Items.of(Material.LIME_CONCRETE, "&a&l➕ Agregar Requisito"), e -> elegirTipo(p, id));
        m.open(p);
    }

    private void elegirTipo(Player p, String id) {
        Style.click(p);
        Menu m = new Menu(3, "&8Tipo de requisito");
        int[] slots = {10, 11, 12, 14, 15, 16};
        Req.Tipo[] ts = Req.Tipo.values();
        for (int i = 0; i < ts.length; i++) {
            Req.Tipo t = ts[i];
            List<String> lore = new ArrayList<>();
            lore.add("");
            for (String d : t.desc) lore.add("&7" + d);
            m.set(slots[i], Items.of(t.icono, "&e&l" + t.label, lore), e -> {
                List<Req> l = ms.reqs(id);
                l.add(new Req(t));
                ms.guardarReqs(id, l);
                editarReq(p, id, l.size() - 1);
            });
        }
        m.set(18, Items.of(Material.ARROW, "&7◀ Volver"), e -> requisitos(p, id));
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    private void editarReq(Player p, String id, int idx) {
        List<Req> l = ms.reqs(id);
        if (idx >= l.size()) { requisitos(p, id); return; }
        Req r = l.get(idx);
        switch (r.tipo) {
            case LOGRO: logros(p, id, idx, 0); break;
            case DIMENSION: mundos(p, id, idx); break;
            case EVENTO:
                p.closeInventory();
                Style.info(p, "Escribí la descripción del evento &8(o cancel)&7:");
                ChatInput.ask(p, txt -> {
                    ChatInput.cancel(p);
                    List<Req> l2 = ms.reqs(id);
                    l2.get(idx).texto = txt;
                    ms.guardarReqs(id, l2);
                    requisitos(p, id);
                });
                break;
            default: objetivos(p, id, idx);
        }
    }

    private ItemStack iconoObj(String o) {
        ItemStack it = new ItemStack(Obj.icono(o));
        ItemMeta m = it.getItemMeta();
        m.displayName(Obj.nombre(o).color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        m.lore(Txt.lines("&8" + o, "&7Sacalo de acá para quitarlo."));
        m.getPersistentDataContainer().set(MARCA, PersistentDataType.STRING, o);
        it.setItemMeta(m);
        return it;
    }

    /** Conseguir / Picar / Matar: arriba se ponen los items (se suman), abajo los botones. */
    private void objetivos(Player p, String id, int idx) {
        Req r = ms.reqs(id).get(idx);
        Menu m = new Menu(6, "&8" + r.tipo.label + ": objetivos");
        for (int i = 0; i < 27; i++) m.editable(i);
        int k = 0;
        for (String o : r.objs) if (k < 27) m.getInventory().setItem(k++, iconoObj(o));
        for (int i = 27; i < 36; i++) m.set(i, Items.pane(Material.BLACK_STAINED_GLASS_PANE));
        String ayuda;
        switch (r.tipo) {
            case PICAR: ayuda = "&7Poné bloques (o de Oraxen). Se suman entre sí."; break;
            case MATAR: ayuda = "&7Poné huevos de spawn. Mythic: botón de abajo."; break;
            default: ayuda = "&7Poné los ítems (vanilla u Oraxen). Se suman entre sí.";
        }
        m.set(31, Items.of(Material.OAK_SIGN, "&e&lObjetivos", "", ayuda, "&7Arriba hay 27 lugares."));
        final int[] cant = {r.cant};
        final boolean[] consumir = {r.consumir};
        Runnable[] pintar = new Runnable[1];
        final boolean[] guardado = {false};
        Runnable guardar = () -> {
            if (guardado[0]) return;
            guardado[0] = true;
            List<String> objs = new ArrayList<>();
            List<ItemStack> devolver = new ArrayList<>();
            for (int i = 0; i < 27; i++) {
                ItemStack it = m.getInventory().getItem(i);
                if (it == null || it.getType().isAir()) continue;
                String marca = it.hasItemMeta() ? it.getItemMeta().getPersistentDataContainer().get(MARCA, PersistentDataType.STRING) : null;
                String o = marca != null ? marca : Obj.deItem(it);
                if (marca == null) devolver.add(it.clone());
                if (r.tipo == Req.Tipo.MATAR && !(o.startsWith("ent:") || o.startsWith("mm:"))) continue;
                if (r.tipo != Req.Tipo.MATAR && (o.startsWith("ent:") || o.startsWith("mm:"))) continue;
                if (!objs.contains(o)) objs.add(o);
            }
            m.getInventory().clear();
            for (ItemStack d : devolver) p.getInventory().addItem(d).values().forEach(x -> p.getWorld().dropItem(p.getLocation(), x));
            List<Req> l = ms.reqs(id);
            if (idx < l.size()) {
                l.get(idx).objs = objs;
                l.get(idx).cant = cant[0];
                l.get(idx).consumir = consumir[0];
                ms.guardarReqs(id, l);
            }
        };
        pintar[0] = () -> {
            m.set(38, Items.of(Material.RED_STAINED_GLASS_PANE, "&c-10"), e -> { cant[0] = Math.max(1, cant[0] - 10); pintar[0].run(); });
            m.set(39, Items.of(Material.RED_STAINED_GLASS_PANE, "&c-1"), e -> { cant[0] = Math.max(1, cant[0] - 1); pintar[0].run(); });
            ItemStack c = Items.of(Material.CLOCK, "&e&lCantidad: &f" + cant[0], "", "&7Total que hay que juntar", "&7(sumando todos los objetivos).");
            c.setAmount(Math.max(1, Math.min(64, cant[0])));
            m.set(40, c);
            m.set(41, Items.of(Material.LIME_STAINED_GLASS_PANE, "&a+1"), e -> { cant[0] += 1; pintar[0].run(); });
            m.set(42, Items.of(Material.LIME_STAINED_GLASS_PANE, "&a+10"), e -> { cant[0] += 10; pintar[0].run(); });
            if (r.tipo == Req.Tipo.CONSEGUIR) {
                m.set(47, Items.of(consumir[0] ? Material.HOPPER : Material.BARRIER, consumir[0] ? "&6&lSe entrega: SÍ" : "&7&lSe entrega: NO", "",
                        consumir[0] ? "&7Al reclamar se le sacan los ítems." : "&7Cuenta lo que consigue desde que", consumir[0] ? "&7(cuenta lo que tenga encima)." : "&7se activó la misión.",
                        "", "&e▶ Click para cambiar"), e -> { consumir[0] = !consumir[0]; pintar[0].run(); });
            }
            if (r.tipo == Req.Tipo.PICAR) {
                m.set(51, Items.of(Material.DIAMOND_ORE, "&b&lGrupos rápidos", "", "&7Todos los diamantes, todo el oro, etc.", "", "&e▶ Click para agregar"),
                        e -> { guardar.run(); grupos(p, id, idx); });
            }
            if (r.tipo == Req.Tipo.MATAR) {
                m.set(51, Items.of(Material.WITHER_SKELETON_SKULL, "&d&lAgregar MythicMob", "", "&7Escribís el nombre interno del mob.", "", "&e▶ Click para agregar"), e -> {
                    guardar.run();
                    p.closeInventory();
                    Style.info(p, "Escribí el nombre interno del MythicMob &8(o cancel)&7:");
                    ChatInput.ask(p, txt -> {
                        ChatInput.cancel(p);
                        List<Req> l = ms.reqs(id);
                        String o = "mm:" + Txt.strip(txt).trim();
                        if (!l.get(idx).objs.contains(o)) l.get(idx).objs.add(o);
                        ms.guardarReqs(id, l);
                        objetivos(p, id, idx);
                    });
                });
            }
        };
        pintar[0].run();
        for (int i = 36; i < 54; i++) if (m.getInventory().getItem(i) == null) m.set(i, Items.pane(Material.GRAY_STAINED_GLASS_PANE));
        m.set(45, Items.of(Material.ARROW, "&a&l✔ Guardar y volver"), e -> { guardar.run(); requisitos(p, id); });
        m.onClose(e -> guardar.run());
        m.open(p);
    }

    private void grupos(Player p, String id, int idx) {
        Menu m = new Menu(3, "&8Grupos rápidos");
        int s = 0;
        for (String k : Obj.GRUPOS.keySet()) {
            String o = "tag:" + k;
            m.set(s++, Items.of(Obj.grupoIcono(k), "&f" + Obj.GRUPOS.get(k), "", "&e▶ Click para agregar"), e -> {
                List<Req> l = ms.reqs(id);
                if (!l.get(idx).objs.contains(o)) l.get(idx).objs.add(o);
                ms.guardarReqs(id, l);
                objetivos(p, id, idx);
            });
        }
        m.set(22, Items.of(Material.ARROW, "&7◀ Volver"), e -> objetivos(p, id, idx));
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    private void logros(Player p, String id, int idx, int pag) {
        Req r = ms.reqs(id).get(idx);
        List<Advancement> todos = new ArrayList<>();
        Iterator<Advancement> it = Bukkit.advancementIterator();
        while (it.hasNext()) {
            Advancement a = it.next();
            if (a.getKey().getKey().startsWith("recipes/") || a.getDisplay() == null) continue;
            todos.add(a);
        }
        todos.sort((a, b) -> a.getKey().toString().compareTo(b.getKey().toString()));
        Menu m = new Menu(6, "&8Logros (" + (pag + 1) + "/" + Math.max(1, (todos.size() + 44) / 45) + ")");
        for (int i = 0; i < 45 && pag * 45 + i < todos.size(); i++) {
            Advancement a = todos.get(pag * 45 + i);
            String o = "adv:" + a.getKey();
            boolean sel = r.objs.contains(o);
            AdvancementDisplay d = a.getDisplay();
            ItemStack ic = d.icon() != null ? d.icon().clone() : new ItemStack(Material.KNOWLEDGE_BOOK);
            ItemMeta meta = ic.getItemMeta();
            meta.displayName(d.title().color(sel ? NamedTextColor.GREEN : NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
            meta.lore(Txt.lines("&8" + a.getKey(), "", sel ? "&a✔ Elegido &7(click para sacar)" : "&e▶ Click para elegir"));
            meta.setEnchantmentGlintOverride(sel);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            ic.setItemMeta(meta);
            m.set(i, ic, e -> {
                List<Req> l = ms.reqs(id);
                Req rr = l.get(idx);
                if (rr.objs.contains(o)) rr.objs.remove(o); else rr.objs.add(o);
                rr.cant = Math.max(1, rr.objs.size());
                ms.guardarReqs(id, l);
                logros(p, id, idx, pag);
            });
        }
        for (int i = 45; i < 54; i++) m.set(i, Items.pane(Material.BLACK_STAINED_GLASS_PANE));
        m.set(45, Items.of(Material.ARROW, "&a&l✔ Listo"), e -> requisitos(p, id));
        if (pag > 0) m.set(48, Items.of(Material.SPECTRAL_ARROW, "&7◀ Página anterior"), e -> logros(p, id, idx, pag - 1));
        if ((pag + 1) * 45 < todos.size()) m.set(50, Items.of(Material.SPECTRAL_ARROW, "&7Página siguiente ▶"), e -> logros(p, id, idx, pag + 1));
        m.set(49, Items.of(Material.CLOCK, "&e&lHay que tener: &f" + r.cant, "", "&7Por defecto, todos los elegidos.", "&e▶ Click: -1 | Click derecho: +1"), e -> {
            List<Req> l = ms.reqs(id);
            Req rr = l.get(idx);
            rr.cant = Math.max(1, Math.min(Math.max(1, rr.objs.size()), rr.cant + (e.isRightClick() ? 1 : -1)));
            ms.guardarReqs(id, l);
            logros(p, id, idx, pag);
        });
        m.open(p);
    }

    private void mundos(Player p, String id, int idx) {
        Req r = ms.reqs(id).get(idx);
        Menu m = new Menu(3, "&8Ir a: mundos");
        int s = 0;
        for (World w : Bukkit.getWorlds()) {
            if (s >= 18) break;
            String o = "dim:" + w.getName();
            boolean sel = r.objs.contains(o);
            ItemStack it = Items.of(sel ? Material.ENDER_EYE : Material.ENDER_PEARL, ar.tod4.sidebar.Sidebar.mundo(pl, w) + " &8(" + w.getName() + ")", "",
                    sel ? "&a✔ Elegido" : "&e▶ Click para elegir");
            if (sel) Items.glow(it);
            m.set(s++, it, e -> {
                List<Req> l = ms.reqs(id);
                if (l.get(idx).objs.contains(o)) l.get(idx).objs.remove(o); else l.get(idx).objs.add(o);
                ms.guardarReqs(id, l);
                mundos(p, id, idx);
            });
        }
        m.set(18, Items.of(Material.ARROW, "&a&l✔ Listo"), e -> requisitos(p, id));
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }
}

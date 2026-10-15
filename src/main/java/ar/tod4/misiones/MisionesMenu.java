package ar.tod4.misiones;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import ar.tod4.TOD4;
import ar.tod4.hook.Ext;
import ar.tod4.sidebar.Sidebar;
import ar.tod4.util.ChatInput;
import ar.tod4.util.Items;
import ar.tod4.util.Menu;
import ar.tod4.util.Obj;
import ar.tod4.util.Style;
import ar.tod4.util.Txt;
import io.papermc.paper.advancement.AdvancementDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Menus de misiones.
 *  Jugador (/misiones): igual al de antes, con cada objetivo por item (Casco 0/1, Pechera 1/1 ✔...).
 *  Admin (/tod4:panel): Diarias / Opcionales -> lista -> mision (sus objetivos como iconos + agregar, recompensas,
 *  activar, renombrar, borrar) -> cada objetivo con su editor.
 */
public final class MisionesMenu {

    private final TOD4 pl;
    private final Misiones ms;
    private final NamespacedKey MARCA;
    final ModoMobs mobs;

    private static final Pattern ID_OK = Pattern.compile("[A-Za-z0-9_\\-]+");

    public MisionesMenu(TOD4 pl, Misiones ms) {
        this.pl = pl;
        this.ms = ms;
        this.MARCA = new NamespacedKey(pl, "obj");
        this.mobs = new ModoMobs(pl, ms, this);
    }

    /** Al apagar: devuelve el inventario a los que estaban en el modo Mobs. */
    public void cerrarTodo() { mobs.cerrarTodo(); }

    // ================================================================= utilidades

    private static Component linea(String antes, Component nombre, String despues) {
        var b = Component.text().decoration(TextDecoration.ITALIC, false).append(Txt.c(antes));
        b.append(nombre.colorIfAbsent(NamedTextColor.GRAY));
        if (despues != null) b.append(Txt.c(despues));
        return b.build();
    }

    private static ItemStack conLore(ItemStack it, String nombre, List<Component> lore) {
        ItemMeta m = it.getItemMeta();
        m.displayName(Txt.c(nombre));
        m.lore(lore);
        m.addItemFlags(ItemFlag.values());
        it.setItemMeta(m);
        return it;
    }

    private static String si(boolean b) { return b ? "&aSÍ" : "&cNO"; }

    private Objetivo buscar(List<Objetivo> l, String uid) {
        for (Objetivo o : l) if (o.uid.equals(uid)) return o;
        return null;
    }

    /** Carga la lista fresca, cambia ese objetivo y guarda. */
    void editar(String id, String uid, Consumer<Objetivo> f) {
        List<Objetivo> l = ms.objetivos(id);
        Objetivo o = buscar(l, uid);
        if (o == null) return;
        f.accept(o);
        ms.guardarObjetivos(id, l);
    }

    Objetivo objetivo(String id, String uid) { return buscar(ms.objetivos(id), uid); }

    /** Item que representa un objetivo en un cofre de edicion (con marca para leerlo de vuelta). */
    private ItemStack icono(String obj) {
        if (obj.startsWith("mat:")) {
            Material m = Material.matchMaterial(obj.substring(4));
            if (m != null && m.isItem() && !m.isAir()) return new ItemStack(m);
        }
        if (obj.startsWith("ox:")) {
            ItemStack it = Ext.oraxenItem(obj.substring(3));
            if (it != null) return it;
        }
        ItemStack it = new ItemStack(Obj.icono(obj));
        ItemMeta m = it.getItemMeta();
        m.displayName(Obj.nombre(obj).colorIfAbsent(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        m.lore(Txt.lines("&8" + obj));
        m.getPersistentDataContainer().set(MARCA, PersistentDataType.STRING, obj);
        it.setItemMeta(m);
        return it;
    }

    private String marca(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(MARCA, PersistentDataType.STRING);
    }

    /** Lee el cofre de edicion: objetivo -> cantidad (en conjunto, 1 cada uno). */
    private LinkedHashMap<String, Integer> leer(Player p, Inventory inv, Objetivo o) {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        Set<String> malos = new HashSet<>();
        for (int i = 0; i < 27; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir()) continue;
            String obj = marca(it);
            if (obj == null) {
                switch (o.tipo) {
                    case MATAR:
                        obj = Obj.deItem(it);
                        if (!obj.startsWith("ent:")) { malos.add(Items.name(it) + " (usa huevos o la vara)"); continue; }
                        break;
                    case PICAR:
                        obj = Obj.deItemPuro(it);
                        if (obj.startsWith("mat:") && !it.getType().isBlock()) { malos.add(Items.name(it) + " (no es un bloque)"); continue; }
                        break;
                    default:
                        obj = Obj.deItemPuro(it);
                }
            }
            int n = o.conjunto ? 1 : it.getAmount();
            if (o.conjunto) out.put(obj, 1);
            else out.merge(obj, n, Integer::sum);
        }
        if (!malos.isEmpty()) Style.err(p, "No se guardaron: &f" + String.join(", ", malos));
        return out;
    }

    // ================================================================= jugador (/misiones)

    public void jugador(Player p) {
        Style.click(p);
        ms.verificar(p);
        Menu m = new Menu(4, "&8Misiones Activas");
        m.set(4, Items.pane(Material.LIME_STAINED_GLASS_PANE));
        m.set(31, Items.pane(Material.LIGHT_BLUE_STAINED_GLASS_PANE));
        for (String clase : new String[]{Misiones.DIA, Misiones.OP}) {
            boolean dia = clase.equals(Misiones.DIA);
            int slot = dia ? 11 : 15;
            String id = ms.activa(clase);
            if (id == null) {
                m.set(slot, Items.of(Material.BARRIER, "&c🔒 Bloqueado", " ", "&7No hay ninguna misión " + Misiones.nombreClase(clase) + " activa."));
                continue;
            }
            m.set(slot, libro(p, clase, id), e -> {
                if (ms.estado(clase, p) == Misiones.Estado.COMPLETADA) { ms.reclamar(clase, p); jugador(p); }
            });
            if (ms.estado(clase, p) != Misiones.Estado.BLOQUEADA) m.set(dia ? 20 : 24, premios(clase, id));
        }
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    private ItemStack libro(Player p, String clase, String id) {
        boolean dia = clase.equals(Misiones.DIA);
        String titulo = (dia ? "&a&lMisión Diaria: &f" : "&b&lMisión Opcional: &f") + ms.nombre(id);
        Misiones.Estado est = ms.estado(clase, p);
        if (est == Misiones.Estado.BLOQUEADA)
            return Items.of(Material.BOOK, titulo, " ", ms.textoEstado(est), " ", "&7Completa y reclama la Diaria primero.");
        List<Component> lore = loreJugador(p, clase, id);
        lore.add(Txt.c(" "));
        lore.add(Txt.c("&fEstado: " + ms.textoEstado(est)));
        if (est == Misiones.Estado.COMPLETADA) lore.add(Txt.c("&e&l★ ¡Click para reclamar!"));
        ItemStack it = conLore(new ItemStack(est == Misiones.Estado.COMPLETADA ? Material.ENCHANTED_BOOK : Material.BOOK), titulo, lore);
        return it;
    }

    /**
     * Los objetivos como los ve el jugador.
     *  Por separado:  Conseguir:  /  ▪ Casco de diamante  0/1  /  ▪ Pechera de diamante  1/1 ✔
     *  En conjunto:   Picar: 4/20  /  ▪ Mena de diamante  /  ▪ Mena de diamante de pizarra
     */
    private List<Component> loreJugador(Player p, String clase, String id) {
        List<Component> l = new ArrayList<>();
        String col = Misiones.color(clase);
        String ultimo = null;
        for (Objetivo o : ms.objetivos(id)) {
            if (o.vacio()) continue;
            String header = "&f" + o.tipo.visible + (o.tipo.consumible() && o.consumir ? " &c(Se consume al reclamar)" : "");
            if (o.conjunto && o.tipo != Objetivo.Tipo.EVENTO) {
                // la cantidad va al lado del titulo y abajo la lista normal
                Objetivo.Meta m = o.metas().get(0);
                int prog = ms.progreso(clase, p, o, m);
                boolean ok = prog >= m.cant;
                l.add(Txt.c(" "));
                l.add(Txt.c(header + "&f: " + cuenta(prog, m.cant, col)));
                ultimo = null;
                if (o.oculto && !ok) { l.add(linea("  &8▪ ", Component.text("???"), null)); continue; }
                for (String x : m.objs) l.add(linea("  &8▪ ", Obj.nombre(x), null));
                continue;
            }
            if (!header.equals(ultimo)) { l.add(Txt.c(" ")); l.add(Txt.c(header + "&f:")); ultimo = header; }
            for (Objetivo.Meta m : o.metas()) {
                int prog = ms.progreso(clase, p, o, m);
                boolean ok = prog >= m.cant;
                boolean ocultar = o.oculto && !ok;
                Component nom;
                if (ocultar) nom = Component.text("???");
                else if (o.tipo == Objetivo.Tipo.EVENTO) nom = Txt.c("&7" + o.texto);
                else nom = Obj.nombre(m.objs.get(0));
                l.add(linea("  &8▪ ", nom, "  " + cuenta(prog, m.cant, col)));
            }
        }
        return l;
    }

    private static String cuenta(int prog, int cant, String col) {
        return prog >= cant ? col + prog + "&7/" + col + cant + " " + col + "✔" : "&e" + prog + "&7/&f" + cant;
    }

    private ItemStack premios(String clase, String id) {
        List<Component> lore = new ArrayList<>();
        lore.add(Txt.c(" "));
        List<ItemStack> r = ms.recompensas(id);
        if (r.isEmpty()) lore.add(Txt.c("&7Sin recompensas configuradas."));
        for (ItemStack it : r) {
            lore.add(linea("&8▪ &d" + it.getAmount() + "x ", Items.nombre(it).colorIfAbsent(NamedTextColor.WHITE), null));
        }
        return conLore(new ItemStack(Material.FEATHER), clase.equals(Misiones.DIA) ? "&d&lRecompensas Diarias" : "&d&lRecompensas Opcionales", lore);
    }

    // ================================================================= admin: panel y listas

    public void panel(Player p) {
        Style.click(p);
        Menu m = new Menu(3, "&8Gestor de Misiones TOD4");
        m.set(11, Items.of(Material.EMERALD, "&a&lMisiones Diarias", "", "&7Activa: &f" + activaTxt(Misiones.DIA),
                "&7Creadas: &f" + ms.ids(Misiones.DIA).size(), "", "&e▶ Click para abrir"), e -> lista(p, Misiones.DIA));
        m.set(15, Items.of(Material.DIAMOND, "&b&lMisiones Opcionales", "", "&7Activa: &f" + activaTxt(Misiones.OP),
                "&7Creadas: &f" + ms.ids(Misiones.OP).size(), "", "&e▶ Click para abrir"), e -> lista(p, Misiones.OP));
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    private String activaTxt(String clase) {
        String id = ms.activa(clase);
        return id == null ? "&7ninguna" : ms.nombre(id) + " &8(" + id + ")";
    }

    private void lista(Player p, String clase) {
        Style.click(p);
        boolean dia = clase.equals(Misiones.DIA);
        Menu m = new Menu(6, dia ? "&8Misiones Diarias" : "&8Misiones Opcionales");
        List<String> ids = ms.ids(clase);
        for (int i = 0; i < ids.size() && i < 45; i++) {
            String id = ids.get(i);
            boolean act = id.equals(ms.activa(clase));
            int n = 0;
            for (Objetivo o : ms.objetivos(id)) n += o.metas().size();
            ItemStack it = Items.of(act ? Material.ENCHANTED_BOOK : Material.PAPER, (act ? Misiones.color(clase) : "&f") + ms.nombre(id)
                            + (act ? " &8[&aACTIVA&8]" : ""),
                    "&8ID: " + id, "", "&7Objetivos: &f" + n, "&7Recompensas: &f" + ms.recompensas(id).size(), "", "&e▶ Click para editar");
            m.set(i, it, e -> mision(p, id));
        }
        for (int i = 45; i < 54; i++) m.set(i, Items.pane(Material.BLACK_STAINED_GLASS_PANE));
        m.set(45, Items.of(Material.ARROW, "&7◀ Volver"), e -> panel(p));
        m.set(49, Items.of(Material.LIME_DYE, "&a&l➕ Crear nueva",
                "", "&7Escribes en el chat: &fID Nombre", "&7La ID va pegada (sin espacios) y es única.",
                "&7Ej: &f" + (dia ? "3 Armadura de diamante" : "op3 Cazador de pandas"), "", "&e▶ Click para crear"), e -> crear(p, clase));
        m.open(p);
    }

    private void crear(Player p, String clase) {
        p.closeInventory();
        Style.info(p, "Escribe en el chat &fID Nombre &7(ej: &f" + (clase.equals(Misiones.DIA) ? "3 Armadura de diamante" : "op3 Cazador")
                + "&7). Escribe &ccancel &7para cancelar.");
        ChatInput.ask(p, txt -> {
            String t = txt.trim();
            int sp = t.indexOf(' ');
            String id = sp < 0 ? t : t.substring(0, sp);
            String nombre = sp < 0 ? id : t.substring(sp + 1).trim();
            if (!ID_OK.matcher(id).matches()) { Style.err(p, "La ID solo puede tener letras, números, - y _. Prueba de nuevo."); return; }
            if (ms.existe(id)) {
                Style.err(p, "Ya existe una misión con la ID &f" + id + " &c(" + ms.nombre(id) + "). Prueba con otra.");
                return;
            }
            ChatInput.cancel(p);
            ms.crear(id, nombre.isEmpty() ? id : nombre, clase);
            Style.ok(p, "Misión creada: &f" + nombre + " &8(ID " + id + ")");
            mision(p, id);
        });
    }

    // ================================================================= admin: una mision

    void mision(Player p, String id) {
        if (!ms.existe(id)) { panel(p); return; }
        Style.click(p);
        String clase = ms.clase(id);
        boolean act = id.equals(ms.activa(clase));
        Menu m = new Menu(6, "&8Misión " + id + ": " + Txt.strip(ms.nombre(id)));
        m.set(4, Items.of(act ? Material.ENCHANTED_BOOK : Material.BOOK, Misiones.color(clase) + "&l" + ms.nombre(id),
                "&8ID: " + id, "&7Tipo: &f" + (clase.equals(Misiones.DIA) ? "Diaria" : "Opcional"), "&7Activa: " + si(act),
                "", "&7Abajo están sus objetivos.", "&7Cada uno se edita con click."));
        List<Objetivo> l = ms.objetivos(id);
        int s = 9;
        for (int i = 0; i < l.size() && s < 36; i++) {
            Objetivo o = l.get(i);
            m.set(s++, iconoObjetivo(i + 1, o), e -> {
                if (e.isShiftClick() && e.isRightClick()) {
                    List<Objetivo> ll = ms.objetivos(id);
                    ll.removeIf(x -> x.uid.equals(o.uid));
                    ms.guardarObjetivos(id, ll);
                    Style.ok(p, "Objetivo borrado.");
                    mision(p, id);
                } else {
                    abrirObjetivo(p, id, o.uid);
                }
            });
        }
        if (s < 36) m.set(s, Items.of(Material.LIME_DYE, "&a&l➕ Agregar objetivo", "", "&7Conseguir, fabricar, picar, matar,", "&7logro, ir a una dimensión o evento.",
                "", "&e▶ Click para agregar"), e -> tipos(p, id));
        for (int i = 36; i < 54; i++) m.set(i, Items.pane(i < 45 ? Material.GRAY_STAINED_GLASS_PANE : Material.BLACK_STAINED_GLASS_PANE));
        m.set(45, Items.of(Material.ARROW, "&7◀ Volver"), e -> lista(p, clase));
        m.set(47, Items.of(Material.CHEST, "&d&lRecompensas", "", "&7Ítems: &f" + ms.recompensas(id).size(), "", "&e▶ Click para editar"),
                e -> recompensas(p, id));
        if (act) {
            m.set(49, Items.of(Material.REDSTONE_TORCH, "&c&lDesactivar misión", "", "&7Está activa ahora.", "", "&c▶ Click para desactivar"), e -> {
                ms.desactivar(clase, true);
                mision(p, id);
            });
        } else {
            m.set(49, Items.of(Material.NETHER_STAR, "&a&lActivar misión", "", "&7Reemplaza a la " + Misiones.nombreClase(clase) + " activa",
                    "&7y empieza de cero para todos.", "", "&a▶ Click para activar"), e -> {
                if (ms.objetivos(id).stream().allMatch(Objetivo::vacio)) { Style.err(p, "La misión no tiene objetivos."); return; }
                p.closeInventory();
                ms.activar(clase, id);
            });
        }
        m.set(51, Items.of(Material.NAME_TAG, "&e&lRenombrar", "", "&7Nombre: &f" + ms.nombre(id), "&7(la ID no cambia)", "", "&e▶ Click para cambiar"), e -> {
            p.closeInventory();
            Style.info(p, "Escribe el nuevo nombre. &ccancel &7para cancelar.");
            ChatInput.ask(p, txt -> { ChatInput.cancel(p); ms.renombrar(id, txt.trim()); Style.ok(p, "Nombre cambiado."); mision(p, id); });
        });
        m.set(53, Items.of(Material.TNT, "&c&lBorrar misión", "", "&7Se pide confirmación."), e -> confirmarBorrar(p, id));
        m.open(p);
    }

    private ItemStack iconoObjetivo(int n, Objetivo o) {
        List<Component> lore = new ArrayList<>();
        lore.add(Txt.c("&8" + o.tipo.label));
        lore.add(Txt.c(" "));
        if (o.tipo == Objetivo.Tipo.EVENTO) {
            lore.add(Txt.c("&7" + o.texto));
        } else if (o.entradas.isEmpty()) {
            lore.add(Txt.c("&c(vacío: click para elegir)"));
        } else if (o.conjunto) {
            lore.add(Txt.c("&7Total entre todos: &f" + o.total));
            int k = 0;
            for (String x : o.entradas.keySet()) {
                if (k++ == 10) { lore.add(Txt.c("  &8... y " + (o.entradas.size() - 10) + " más")); break; }
                lore.add(linea("  &8▪ ", Obj.nombre(x), null));
            }
        } else {
            int k = 0;
            for (Map.Entry<String, Integer> x : o.entradas.entrySet()) {
                if (k++ == 10) { lore.add(Txt.c("  &8... y " + (o.entradas.size() - 10) + " más")); break; }
                boolean uno = o.tipo == Objetivo.Tipo.LOGRO || o.tipo == Objetivo.Tipo.DIMENSION;
                lore.add(linea("  &8▪ ", Obj.nombre(x.getKey()), uno ? null : " &fx" + x.getValue()));
            }
        }
        lore.add(Txt.c(" "));
        if (o.tipo != Objetivo.Tipo.EVENTO)
            lore.add(Txt.c("&7Modo: &f" + (o.conjunto ? "en conjunto" : "cada uno por separado")));
        if (o.tipo.consumible()) lore.add(Txt.c("&7Se consume al reclamar: " + si(o.consumir)));
        if (o.tipo == Objetivo.Tipo.PICAR) lore.add(Txt.c("&7Bloques puestos cuentan: " + si(!o.sinPuestos)));
        lore.add(Txt.c("&7Oculto: " + si(o.oculto)));
        lore.add(Txt.c(" "));
        lore.add(Txt.c("&e▶ Click: editar"));
        lore.add(Txt.c("&c▶ Shift + click derecho: borrar"));
        Material ic = o.tipo.icono;
        if (!o.entradas.isEmpty() && o.tipo.conCofre()) ic = Obj.icono(o.entradas.keySet().iterator().next());
        return conLore(new ItemStack(ic), "&e&l" + n + ". " + o.tipo.label, lore);
    }

    private void confirmarBorrar(Player p, String id) {
        Menu m = new Menu(3, "&8¿Borrar " + Txt.strip(ms.nombre(id)) + "?");
        m.set(11, Items.of(Material.LIME_CONCRETE, "&a&lSÍ, borrar", "", "&7Se borra para siempre."), e -> {
            String clase = ms.clase(id);
            ms.borrar(id);
            Style.ok(p, "Misión borrada.");
            lista(p, clase);
        });
        m.set(15, Items.of(Material.RED_CONCRETE, "&c&lNO, volver"), e -> mision(p, id));
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    private void recompensas(Player p, String id) {
        Menu m = new Menu(4, "&8Recompensas: " + Txt.strip(ms.nombre(id)));
        for (int i = 0; i < 27; i++) m.editable(i);
        List<ItemStack> r = ms.recompensas(id);
        for (int i = 0; i < r.size() && i < 27; i++) m.getInventory().setItem(i, r.get(i).clone());
        Runnable guardar = () -> {
            List<ItemStack> l = new ArrayList<>();
            for (int i = 0; i < 27; i++) {
                ItemStack it = m.getInventory().getItem(i);
                if (it != null && !it.getType().isAir()) l.add(it.clone());
            }
            ms.guardarRecompensas(id, l);
        };
        for (int i = 27; i < 36; i++) m.set(i, Items.pane(Material.BLACK_STAINED_GLASS_PANE));
        m.set(31, Items.of(Material.ARROW, "&a◀ Volver y guardar", "", "&7Pon arriba lo que se gana."), e -> { guardar.run(); mision(p, id); });
        m.onClose(e -> guardar.run());
        m.open(p);
    }

    // ================================================================= admin: agregar objetivo

    private void tipos(Player p, String id) {
        Style.click(p);
        Menu m = new Menu(3, "&8¿Qué tipo de objetivo?");
        int s = 10;
        for (Objetivo.Tipo t : Objetivo.Tipo.values()) {
            List<String> lore = new ArrayList<>();
            lore.add("");
            for (String d : t.desc) lore.add(d);
            lore.add("");
            lore.add("&e▶ Click para agregar");
            m.set(s++, Items.of(t.icono, "&f&l" + t.label, lore), e -> {
                List<Objetivo> l = ms.objetivos(id);
                Objetivo o = new Objetivo(t);
                if (t == Objetivo.Tipo.EVENTO) o.texto = "Evento";
                l.add(o);
                ms.guardarObjetivos(id, l);
                if (t == Objetivo.Tipo.EVENTO) textoEvento(p, id, o.uid);
                else abrirObjetivo(p, id, o.uid);
            });
        }
        m.set(22, Items.of(Material.ARROW, "&7◀ Volver"), e -> mision(p, id));
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    void abrirObjetivo(Player p, String id, String uid) {
        Objetivo o = objetivo(id, uid);
        if (o == null) { mision(p, id); return; }
        Style.click(p);
        switch (o.tipo) {
            case LOGRO: logros(p, id, uid, 0); break;
            case DIMENSION: mundos(p, id, uid); break;
            case EVENTO: evento(p, id, uid); break;
            default: cofre(p, id, uid); break;
        }
    }

    // ---- botones comunes de abajo

    private ItemStack botonModo(Objetivo o) {
        if (o.conjunto) return Items.of(Material.HOPPER, "&f&lModo: &bTodos en conjunto",
                "", "&7Todos suman al &fmismo contador&7.", "&7Ej: mena de diamante o de pizarra.", "&7La cantidad la pone el reloj.",
                "", "&e▶ Click: cambiar a &fcada uno por separado");
        return Items.of(Material.MAP, "&f&lModo: &eCada uno por separado",
                "", "&7Cada uno es un objetivo aparte.", "&7Ej: casco 0/1, pechera 0/1...",
                "", "&e▶ Click: cambiar a &ftodos en conjunto");
    }

    /** Cambia el modo. Al pasar a "en conjunto" las cantidades se borran (1 cada uno) y el reloj arranca con la suma. */
    private void cambiarModo(Objetivo x) {
        if (!x.conjunto) {
            int sum = 0;
            for (int v : x.entradas.values()) sum += v;
            x.conjunto = true;
            x.total = Math.max(1, sum);
            x.entradas.replaceAll((k, v) -> 1);
        } else {
            x.conjunto = false;
        }
    }

    private ItemStack botonReloj(Objetivo o) {
        return Items.of(Material.CLOCK, "&e&lCantidad total: &f" + o.total,
                "", "&7Entre todos los elegidos.", "", "&e▶ Click: +1  &7| &eClick derecho: -1", "&e▶ Shift: +10 / -10");
    }

    private int delta(InventoryClickEvent e) {
        int d = e.isShiftClick() ? 10 : 1;
        return e.isRightClick() ? -d : d;
    }

    private ItemStack botonOculto(Objetivo o) {
        return Items.of(o.oculto ? Material.ENDER_EYE : Material.ENDER_PEARL, "&5&lOculto: " + si(o.oculto),
                "", "&7Si es SÍ, el jugador ve &f???", "&7hasta que lo completa (para spoilers).", "", "&e▶ Click para cambiar");
    }

    private void escribirCantidad(Player p, String id, String uid, Runnable volver) {
        p.closeInventory();
        Style.info(p, "Escribe la cantidad total (un número). &ccancel &7para cancelar.");
        ChatInput.ask(p, txt -> {
            Integer leido = numero(txt);
            if (leido == null) { Style.err(p, "Tiene que ser un número (1 o más)."); return; }
            final int n = leido;
            ChatInput.cancel(p);
            editar(id, uid, x -> x.total = n);
            volver.run();
        });
    }

    // ---- cofre: Conseguir / Fabricar / Picar / Matar

    void cofre(Player p, String id, String uid) {
        Objetivo o = objetivo(id, uid);
        if (o == null) { mision(p, id); return; }
        Menu m = new Menu(4, "&8" + o.tipo.label + " (pon los ítems arriba)");
        for (int i = 0; i < 27; i++) m.editable(i);
        int s = 0;
        for (Map.Entry<String, Integer> e : o.entradas.entrySet()) {
            int cant = o.conjunto ? 1 : Math.max(1, e.getValue());
            while (cant > 0 && s < 27) {
                ItemStack it = icono(e.getKey());
                int q = Math.min(cant, Math.max(1, it.getMaxStackSize()));
                it.setAmount(q);
                m.getInventory().setItem(s++, it);
                cant -= q;
            }
        }
        Runnable guardar = () -> editar(id, uid, x -> x.entradas = leer(p, m.getInventory(), x));
        Runnable reabrir = () -> cofre(p, id, uid);
        for (int i = 27; i < 36; i++) m.set(i, Items.pane(Material.BLACK_STAINED_GLASS_PANE));
        m.set(27, Items.of(Material.ARROW, "&a◀ Volver y guardar"), e -> { guardar.run(); mision(p, id); });
        m.set(29, botonModo(o), e -> { guardar.run(); editar(id, uid, this::cambiarModo); reabrir.run(); });
        if (o.conjunto) {
            m.set(30, botonReloj(o), e -> { guardar.run(); editar(id, uid, x -> x.total = Math.max(1, x.total + delta(e))); reabrir.run(); });
            m.set(31, Items.of(Material.OAK_SIGN, "&e&l✎ Escribir la cantidad", "", "&7Para números grandes."),
                    e -> { guardar.run(); escribirCantidad(p, id, uid, reabrir); });
        } else {
            m.set(30, Items.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7Cantidad: &fla de cada stack",
                    "", "&7Pon la cantidad que quieras en el ítem.", "&7Varios stacks del mismo se suman."));
        }
        switch (o.tipo) {
            case TENER: case FABRICAR:
                m.set(32, Items.of(o.consumir ? Material.LAVA_BUCKET : Material.BUCKET, "&6&lSe consume al reclamar: " + si(o.consumir),
                        "", "&7Si es SÍ, al reclamar se le sacan", "&7los ítems del inventario.", "", "&e▶ Click para cambiar"),
                        e -> { guardar.run(); editar(id, uid, x -> x.consumir = !x.consumir); reabrir.run(); });
                break;
            case PICAR:
                m.set(32, Items.of(o.sinPuestos ? Material.GRASS_BLOCK : Material.BRICKS, "&6&lBloques puestos por jugadores: " + (o.sinPuestos ? "&cNO cuentan" : "&aSÍ cuentan"),
                        "", "&7Si NO cuentan, no se puede poner y", "&7romper el mismo bloque para hacer trampa.", "", "&e▶ Click para cambiar"),
                        e -> { guardar.run(); editar(id, uid, x -> x.sinPuestos = !x.sinPuestos); reabrir.run(); });
                break;
            case MATAR:
                m.set(32, Items.of(Material.BLAZE_ROD, "&c&lElegir mobs con la vara",
                        "", "&7Te da una vara: click derecho a un mob", "&7para agregarlo (MythicMobs, panda marrón...).",
                        "&7Los huevos de spawn también sirven aquí.", "", "&e▶ Click para entrar"),
                        e -> { guardar.run(); p.closeInventory(); mobs.entrar(p, id, uid); });
                break;
            default: break;
        }
        m.set(33, botonOculto(o), e -> { guardar.run(); editar(id, uid, x -> x.oculto = !x.oculto); reabrir.run(); });
        m.set(35, Items.of(Material.BOOK, "&f&l¿Cómo funciona?", ayuda(o.tipo)));
        m.onClose(e -> guardar.run());
        m.open(p);
    }

    private List<String> ayuda(Objetivo.Tipo t) {
        List<String> l = new ArrayList<>();
        l.add("");
        for (String d : t.desc) l.add(d);
        l.add("");
        switch (t) {
            case MATAR: l.add("&7Pon &fhuevos de spawn &7arriba o usa la vara."); break;
            case PICAR: l.add("&7Pon arriba los &fbloques &7que tiene que picar."); break;
            default: l.add("&7Pon arriba los &fítems &7(también de Oraxen)."); break;
        }
        l.add("&7Por separado: la cantidad es la del stack.");
        l.add("&7En conjunto: todos suman y la cantidad");
        l.add("&7la pone el reloj.");
        return l;
    }

    // ---- logros

    private void logros(Player p, String id, String uid, int pag) {
        Objetivo o = objetivo(id, uid);
        if (o == null) { mision(p, id); return; }
        List<Advancement> todos = new ArrayList<>();
        Iterator<Advancement> it = Bukkit.advancementIterator();
        while (it.hasNext()) {
            Advancement a = it.next();
            if (a.getKey().getKey().startsWith("recipes/") || a.getDisplay() == null) continue;
            todos.add(a);
        }
        // primero los del pack de TOD4 (no minecraft), despues los vanilla
        todos.sort((a, b) -> {
            boolean va = a.getKey().getNamespace().equals("minecraft"), vb = b.getKey().getNamespace().equals("minecraft");
            if (va != vb) return va ? 1 : -1;
            return a.getKey().toString().compareTo(b.getKey().toString());
        });
        int pags = Math.max(1, (todos.size() + 44) / 45);
        Menu m = new Menu(6, "&8Logros (" + (pag + 1) + "/" + pags + ")");
        for (int i = 0; i < 45 && pag * 45 + i < todos.size(); i++) {
            Advancement a = todos.get(pag * 45 + i);
            String obj = "adv:" + a.getKey();
            boolean sel = o.entradas.containsKey(obj);
            AdvancementDisplay d = a.getDisplay();
            ItemStack ic = d.icon() != null ? d.icon().clone() : new ItemStack(Material.KNOWLEDGE_BOOK);
            ItemMeta meta = ic.getItemMeta();
            meta.displayName(d.title().color(sel ? NamedTextColor.GREEN : NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
            meta.lore(Txt.lines("&8" + a.getKey(), "", sel ? "&a✔ Elegido &7(click para sacar)" : "&e▶ Click para elegir"));
            meta.setEnchantmentGlintOverride(sel);
            meta.addItemFlags(ItemFlag.values());
            ic.setItemMeta(meta);
            m.set(i, ic, e -> {
                editar(id, uid, x -> { if (x.entradas.remove(obj) == null) x.entradas.put(obj, 1); x.total = Math.min(x.total, Math.max(1, x.entradas.size())); });
                logros(p, id, uid, pag);
            });
        }
        botonesSinCofre(m, 45, p, id, uid, o, () -> logros(p, id, uid, pag));
        if (pag > 0) m.set(46, Items.of(Material.SPECTRAL_ARROW, "&7◀ Página anterior"), e -> logros(p, id, uid, pag - 1));
        if (pag + 1 < pags) m.set(52, Items.of(Material.SPECTRAL_ARROW, "&7Página siguiente ▶"), e -> logros(p, id, uid, pag + 1));
        m.open(p);
    }

    /** Fila de abajo para Logro / Ir a: volver, modo, reloj, oculto. */
    private void botonesSinCofre(Menu m, int inicio, Player p, String id, String uid, Objetivo o, Runnable reabrir) {
        for (int i = inicio; i < inicio + 9; i++) m.set(i, Items.pane(Material.BLACK_STAINED_GLASS_PANE));
        m.set(inicio, Items.of(Material.ARROW, "&a◀ Volver"), e -> mision(p, id));
        m.set(inicio + 2, botonModo(o), e -> { editar(id, uid, this::cambiarModo); reabrir.run(); });
        if (o.conjunto) {
            m.set(inicio + 3, botonReloj(o), e -> {
                editar(id, uid, x -> x.total = Math.max(1, Math.min(Math.max(1, x.entradas.size()), x.total + delta(e))));
                reabrir.run();
            });
        }
        m.set(inicio + 5, botonOculto(o), e -> { editar(id, uid, x -> x.oculto = !x.oculto); reabrir.run(); });
        m.set(inicio + 4, Items.of(Material.BOOK, "&f&l¿Cómo funciona?", ayudaSinCofre(o.tipo)));
    }

    private List<String> ayudaSinCofre(Objetivo.Tipo t) {
        List<String> l = new ArrayList<>();
        l.add("");
        for (String d : t.desc) l.add(d);
        l.add("");
        l.add("&7Click en los de arriba para elegirlos.");
        l.add("&7Por separado: tiene que hacer todos.");
        l.add("&7En conjunto: el reloj dice cuántos de ellos.");
        return l;
    }

    // ---- ir a (mundos)

    private void mundos(Player p, String id, String uid) {
        Objetivo o = objetivo(id, uid);
        if (o == null) { mision(p, id); return; }
        Menu m = new Menu(4, "&8Ir a: elige los mundos");
        int s = 0;
        for (World w : Bukkit.getWorlds()) {
            if (s >= 27) break;
            String obj = "dim:" + w.getName();
            boolean sel = o.entradas.containsKey(obj);
            ItemStack it = Items.of(sel ? Material.ENDER_EYE : Material.ENDER_PEARL, Sidebar.mundo(pl, w) + " &8(" + w.getName() + ")", "",
                    sel ? "&a✔ Elegido &7(click para sacar)" : "&e▶ Click para elegir");
            if (sel) Items.glow(it);
            m.set(s++, it, e -> {
                editar(id, uid, x -> { if (x.entradas.remove(obj) == null) x.entradas.put(obj, 1); x.total = Math.min(x.total, Math.max(1, x.entradas.size())); });
                mundos(p, id, uid);
            });
        }
        for (int i = s; i < 27; i++) m.set(i, Items.pane(Material.GRAY_STAINED_GLASS_PANE));
        botonesSinCofre(m, 27, p, id, uid, o, () -> mundos(p, id, uid));
        m.open(p);
    }

    // ---- evento

    private void evento(Player p, String id, String uid) {
        Objetivo o = objetivo(id, uid);
        if (o == null) { mision(p, id); return; }
        Menu m = new Menu(3, "&8Evento");
        m.set(11, Items.of(Material.NAME_TAG, "&d&lDescripción", "", "&f" + o.texto, "", "&e▶ Click para cambiar"), e -> textoEvento(p, id, uid));
        m.set(13, botonOculto(o), e -> { editar(id, uid, x -> x.oculto = !x.oculto); evento(p, id, uid); });
        m.set(15, Items.of(Material.BOOK, "&f&l¿Cómo se completa?", "", "&7Con el comando:",
                "&e/tod4:completar <jugador|todos> " + id + " evento", "", "&7(sin \"evento\" se completa la misión entera)"));
        m.set(18, Items.of(Material.ARROW, "&a◀ Volver"), e -> mision(p, id));
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    private void textoEvento(Player p, String id, String uid) {
        p.closeInventory();
        Style.info(p, "Escribe la descripción del evento (lo que ve el jugador). &ccancel &7para cancelar.");
        ChatInput.ask(p, txt -> {
            ChatInput.cancel(p);
            editar(id, uid, x -> x.texto = txt.trim());
            evento(p, id, uid);
        });
    }

    private static Integer numero(String s) {
        try {
            int n = Integer.parseInt(s.trim());
            return n >= 1 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

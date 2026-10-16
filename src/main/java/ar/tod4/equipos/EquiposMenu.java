package ar.tod4.equipos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import ar.tod4.equipos.Equipos.Equipo;
import ar.tod4.util.Items;
import ar.tod4.util.Menu;
import ar.tod4.util.Style;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

/** Menu de admins: todos los equipos, sus miembros, cambiar de lider, sacar gente y borrar. */
final class EquiposMenu {

    private final Equipos eq;

    EquiposMenu(Equipos eq) { this.eq = eq; }

    private static ItemStack nombrado(ItemStack it, Component nombre) {
        it.editMeta(m -> m.displayName(nombre.decoration(TextDecoration.ITALIC, false)));
        return it;
    }

    void lista(Player p) {
        Menu m = new Menu(6, "&8Equipos");
        int i = 0;
        for (Equipo e : eq.todos()) {
            if (i >= 45) break;
            List<String> lore = new ArrayList<>();
            lore.add("&7Nombre: &f" + e.nombre);
            lore.add("&7Miembros: &f" + e.miembros.size());
            lore.add("&7Líder: &f" + (e.lider == null ? "nadie" : Equipos.nombreDe(e.lider) + " ©"));
            lore.add("");
            lore.add("&e▶ Clic para ver");
            ItemStack it = nombrado(Items.of(Equipos.lana(e.color), "x", lore), Component.text("[" + e.tag + "] " + e.nombre, e.color));
            m.set(i++, it, c -> equipo(p, e));
        }
        m.set(49, Items.of(Material.BOOK, "&eCrear un equipo", "&7/tod4:equipo crear <tag> <color> <nombre>",
                "&7Ej: &f/tod4:equipo crear GOC rojo Guardians of Chuño"));
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    private void equipo(Player p, Equipo e) {
        if (eq.equipo(e.tag) == null) { lista(p); return; }
        Menu m = new Menu(6, "&8Equipo [" + e.tag + "]");
        int i = 0;
        for (UUID u : e.miembros) {
            if (i >= 45) break;
            boolean lid = u.equals(e.lider);
            boolean on = Bukkit.getPlayer(u) != null;
            ItemStack it = Equipos.cabeza(u, "&f" + Equipos.nombreDe(u) + (lid ? " ©" : ""),
                    List.of(lid ? "&7Líder del equipo" : "&7Miembro", on ? "&aConectado" : "&8Desconectado", "", "&e▶ Clic para opciones"));
            m.set(i++, it, c -> miembro(p, e, u));
        }
        m.set(45, Items.of(Material.ARROW, "&7◀ Volver"), c -> lista(p));
        m.set(49, nombrado(Items.of(Equipos.lana(e.color), "x", "&7" + e.miembros.size() + " miembros"), Component.text("[" + e.tag + "] " + e.nombre, e.color)));
        m.set(53, Items.of(Material.BARRIER, "&cBorrar equipo", "&7Se saca a todos los miembros."), c -> confirmarBorrar(p, e));
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    private void miembro(Player p, Equipo e, UUID u) {
        Menu m = new Menu(3, "&8" + Equipos.nombreDe(u));
        boolean lid = u.equals(e.lider);
        m.set(11, Items.of(Material.NETHER_STAR, lid ? "&7Ya es el líder" : "&eHacer líder", "&7Le da el liderazgo (©)."), c -> {
            if (!e.miembros.contains(u) || u.equals(e.lider)) return;
            eq.hacerLider(e, u);
            Style.ok(p, Equipos.nombreDe(u) + " ahora es el líder de [" + e.tag + "].");
            equipo(p, e);
        });
        m.set(15, Items.of(Material.RED_CONCRETE, "&cSacar del equipo"), c -> {
            if (!e.miembros.contains(u)) { equipo(p, e); return; }
            eq.quitarAdmin(e, u);
            Style.ok(p, Equipos.nombreDe(u) + " ya no pertenece a ningún equipo.");
            equipo(p, e);
        });
        m.set(22, Items.of(Material.ARROW, "&7◀ Volver"), c -> equipo(p, e));
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }

    private void confirmarBorrar(Player p, Equipo e) {
        Menu m = new Menu(3, "&8¿Borrar [" + e.tag + "]?");
        m.set(11, Items.of(Material.LIME_CONCRETE, "&aSí, borrar"), c -> {
            eq.borrar(e);
            Style.ok(p, "Equipo [" + e.tag + "] eliminado.");
            lista(p);
        });
        m.set(15, Items.of(Material.RED_CONCRETE, "&cCancelar"), c -> equipo(p, e));
        m.fill(Material.GRAY_STAINED_GLASS_PANE).open(p);
    }
}

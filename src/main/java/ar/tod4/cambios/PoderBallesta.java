package ar.tod4.cambios;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Piglin;
import org.bukkit.entity.Pillager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;

import ar.tod4.TOD4;
import net.kyori.adventure.text.Component;

/**
 * Dia 2: los encantamientos Poder e Infinidad tambien valen en la ballesta (Infinidad: no gasta flechas comunes, como en el arco).
 *  - Mesa mejorada: lo decide Mesa (ver Mesa.setPoderBallesta). Mesa vanilla (o un boton que la mesa mejorada no decide): aca, con una chance.
 *  - Yunque: ballesta + libro con Poder.
 *  - Danio: lo aplica el juego solo (Poder se lee del arma que disparo la flecha), igual que en el arco.
 *  - Mobs: algunos pillagers y piglins nacen con Poder en la ballesta.
 */
final class PoderBallesta implements Listener {
    private final TOD4 pl;
    private final Cambios cb;

    PoderBallesta(TOD4 pl, Cambios cb) {
        this.pl = pl;
        this.cb = cb;
        Bukkit.getPluginManager().registerEvents(this, pl);
    }

    private boolean on() { return cb.on("d2.poder"); }

    private static Enchantment poder() { return Registry.ENCHANTMENT.get(NamespacedKey.minecraft("power")); }

    // ------------------------------------------------------------------ mesa vanilla

    private static Enchantment infinidad() { return Registry.ENCHANTMENT.get(NamespacedKey.minecraft("infinity")); }

    private static Enchantment reparacion() { return Registry.ENCHANTMENT.get(NamespacedKey.minecraft("mending")); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void mesa(EnchantItemEvent e) {
        if (e.getItem().getType() != Material.CROSSBOW) return;
        boolean laMesaMejoradaLoDecidio = pl.mesa().tomarManejado(e.getEnchanter());
        if (!on() || laMesaMejoradaLoDecidio) return;
        Map<Enchantment, Integer> add = e.getEnchantsToAdd();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Enchantment po = poder();
        if (po != null && !add.containsKey(po) && r.nextDouble() < pl.getConfig().getDouble("cambios.poder_ballesta_mesa", 0.4)) {
            // como en el juego: Poder I desde nivel 1, II desde 11, III desde 21, IV desde 31, V desde 41
            int lvl = Math.max(1, Math.min(po.getMaxLevel(), (e.getExpLevelCost() - 1) / 10 + 1));
            add.put(po, lvl);
        }
        // Infinidad (no va con Reparacion, como en el arco)
        Enchantment inf = infinidad(), rep = reparacion();
        if (inf != null && !add.containsKey(inf) && (rep == null || !add.containsKey(rep))
                && r.nextDouble() < pl.getConfig().getDouble("cambios.infinidad_ballesta_mesa", 0.15))
            add.put(inf, 1);
    }

    // ------------------------------------------------------------------ yunque

    @EventHandler(priority = EventPriority.HIGH)
    public void yunque(PrepareAnvilEvent e) {
        if (!on()) return;
        AnvilInventory inv = e.getInventory();
        ItemStack izq = inv.getItem(0), der = inv.getItem(1);
        if (izq == null || izq.getType() != Material.CROSSBOW || der == null || der.getType() != Material.ENCHANTED_BOOK) return;
        if (!(der.getItemMeta() instanceof EnchantmentStorageMeta libro)) return;
        Enchantment po = poder(), inf = infinidad(), rep = reparacion();
        int delLibroPo = po == null ? 0 : libro.getStoredEnchantLevel(po);
        int delLibroInf = inf == null ? 0 : libro.getStoredEnchantLevel(inf);
        if (delLibroPo <= 0 && delLibroInf <= 0) return;

        ItemStack res = e.getResult();
        boolean habia = res != null && !res.getType().isAir();
        ItemStack out = habia ? res.clone() : izq.clone();
        out.setAmount(1);
        if (!habia) {
            // el juego no arma nada (el libro solo trae Poder/Infinidad): se agregan tambien los demas encantamientos compatibles del libro
            for (Map.Entry<Enchantment, Integer> en : libro.getStoredEnchants().entrySet()) {
                Enchantment k = en.getKey();
                if (k.equals(po) || k.equals(inf) || !k.canEnchantItem(izq)) continue;
                boolean choca = false;
                for (Enchantment ya : out.getEnchantments().keySet()) if (!ya.equals(k) && (ya.conflictsWith(k) || k.conflictsWith(ya))) choca = true;
                if (choca) continue;
                int actual = out.getEnchantmentLevel(k), nuevo = en.getValue();
                int lvl = actual == nuevo ? Math.min(k.getMaxLevel(), nuevo + 1) : Math.max(actual, nuevo);
                out.addUnsafeEnchantment(k, lvl);
            }
        }
        int costo = 0;
        if (delLibroPo > 0) {
            int actual = out.getEnchantmentLevel(po);
            int lvl = actual == delLibroPo ? Math.min(po.getMaxLevel(), delLibroPo + 1) : Math.max(actual, delLibroPo);
            out.addUnsafeEnchantment(po, lvl);
            costo += Math.max(1, lvl);
        }
        // Infinidad: no va con Reparacion (como en el arco)
        if (delLibroInf > 0 && (rep == null || out.getEnchantmentLevel(rep) == 0) && out.getEnchantmentLevel(inf) == 0) {
            out.addUnsafeEnchantment(inf, 1);
            costo += 4;
        }
        if (out.isSimilar(izq) && !habia) return;   // no se agrego nada

        int penalidad = 0;
        if (izq.getItemMeta() instanceof Repairable r) penalidad = r.getRepairCost();
        if (!habia) {
            ItemMeta m = out.getItemMeta();
            String nombre = e.getView().getRenameText();
            if (nombre != null && !nombre.isBlank()) m.displayName(Component.text(nombre));
            if (m instanceof Repairable r) r.setRepairCost(penalidad * 2 + 1);
            out.setItemMeta(m);
        }
        e.setResult(out);
        int total = habia ? safeCost(e) + Math.max(1, costo) : Math.max(1, costo) + penalidad;
        Bukkit.getScheduler().runTask(pl, () -> fijar(e, total));
        fijar(e, total);
    }

    private static int safeCost(PrepareAnvilEvent e) {
        try { return e.getView().getRepairCost(); } catch (Throwable t) { return 0; }
    }

    private static void fijar(PrepareAnvilEvent e, int costo) {
        try { e.getView().setRepairCost(costo); } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ danio
    // No hace falta nada: desde 1.21 el juego aplica Poder segun el arma que disparo la flecha (tambien la ballesta).
    // Antes se sumaba aca otra vez y la ballesta pegaba el doble.

    // ------------------------------------------------------------------ mobs

    @EventHandler(ignoreCancelled = true)
    public void nace(CreatureSpawnEvent e) {
        if (!(e.getEntity() instanceof Pillager || e.getEntity() instanceof Piglin) || !on()) return;
        Enchantment po = poder();
        EntityEquipment eq = e.getEntity().getEquipment();
        if (po == null || eq == null) return;
        ItemStack mano = eq.getItemInMainHand();
        if (mano.getType() != Material.CROSSBOW || mano.containsEnchantment(po)) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (r.nextDouble() >= pl.getConfig().getDouble("cambios.poder_ballesta_mobs", 0.1)) return;
        mano = mano.clone();
        mano.addUnsafeEnchantment(po, 1 + r.nextInt(4));
        eq.setItemInMainHand(mano);
    }
}

package ar.tod4.hook;

import java.lang.reflect.Method;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;

/** Oraxen y MythicMobs por reflexion (si no estan instalados, todo devuelve null y no rompe nada). */
public final class Ext {
    private Ext() {}

    /** Nombre interno de MythicMobs de una entidad, o null si es un mob normal. */
    public static String mythic(Entity e) {
        try {
            Class<?> mb = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object inst = mb.getMethod("inst").invoke(null);
            Object api = inst.getClass().getMethod("getAPIHelper").invoke(inst);
            Boolean es = (Boolean) api.getClass().getMethod("isMythicMob", Entity.class).invoke(api, e);
            if (es == null || !es) return null;
            Object am = api.getClass().getMethod("getMythicMobInstance", Entity.class).invoke(api, e);
            Object type = am.getClass().getMethod("getType").invoke(am);
            return (String) type.getClass().getMethod("getInternalName").invoke(type);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method OX_BLOCK;
    private static boolean oxTried;

    /** ID de Oraxen del bloque (por ejemplo la Mena de Truco), o null. */
    public static String oraxenBlock(Block b) {
        try {
            if (!oxTried) {
                oxTried = true;
                Class<?> c = Class.forName("io.th0rgal.oraxen.api.OraxenBlocks");
                OX_BLOCK = c.getMethod("getOraxenBlock", Location.class);
            }
            if (OX_BLOCK == null) return null;
            Object mech = OX_BLOCK.invoke(null, b.getLocation());
            if (mech == null) return null;
            return (String) mech.getClass().getMethod("getItemID").invoke(mech);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Item de Oraxen por ID (para mostrar su nombre), o null. */
    public static ItemStack oraxenItem(String id) {
        try {
            Class<?> c = Class.forName("io.th0rgal.oraxen.api.OraxenItems");
            Object builder = c.getMethod("getItemById", String.class).invoke(null, id);
            if (builder == null) return null;
            return (ItemStack) builder.getClass().getMethod("build").invoke(builder);
        } catch (Throwable t) {
            return null;
        }
    }
}

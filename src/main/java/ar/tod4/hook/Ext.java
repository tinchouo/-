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

    /** Nombre visible (Display) de un mob de MythicMobs por su nombre interno, o null. */
    public static String mythicNombre(String interno) {
        try {
            Class<?> mb = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object inst = mb.getMethod("inst").invoke(null);
            Object mm = inst.getClass().getMethod("getMobManager").invoke(inst);
            Object opt = mm.getClass().getMethod("getMythicMob", String.class).invoke(mm, interno);
            if (!(opt instanceof java.util.Optional<?> o) || o.isEmpty()) return null;
            Object mob = o.get();
            Object dn = mob.getClass().getMethod("getDisplayName").invoke(mob);
            if (dn == null) return null;
            Object txt;
            try { txt = dn.getClass().getMethod("get").invoke(dn); } catch (NoSuchMethodException e) { txt = dn.toString(); }
            String s = txt == null ? null : String.valueOf(txt);
            return s == null || s.isBlank() ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Le manda una senal a un mob de MythicMobs (para "~onSignal:NOMBRE" en su archivo). Devuelve false si no es de Mythic.
     */
    public static boolean mythicSignal(Entity e, String senal) {
        try {
            Class<?> mb = Class.forName("io.lumine.mythic.bukkit.MythicBukkit");
            Object inst = mb.getMethod("inst").invoke(null);
            Object mm = inst.getClass().getMethod("getMobManager").invoke(inst);
            Object opt = mm.getClass().getMethod("getActiveMob", java.util.UUID.class).invoke(mm, e.getUniqueId());
            if (!(opt instanceof java.util.Optional<?> o) || o.isEmpty()) return false;
            Object am = o.get();
            Object yo = Class.forName("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt", Entity.class).invoke(null, e);
            for (Method m : am.getClass().getMethods()) {
                if (m.getName().equals("signalMob") && m.getParameterCount() == 2 && m.getParameterTypes()[1] == String.class) {
                    m.invoke(am, yo, senal);
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
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

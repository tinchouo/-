package ar.tod4.mejoras;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;

/**
 * Hace brillar mobs solo para un jugador (con ProtocolLib), sin titilar: mientras el mob esta en la lista del jugador,
 * todo lo que el server manda de ese mob (se prende fuego, nada, etc.) sale con el brillo puesto.
 * Esta clase se carga solo si ProtocolLib esta.
 */
final class Brillo {
    private final ProtocolManager pm;
    /** jugador -> (id de entidad -> entidad) que ve brillar. */
    private final Map<UUID, Map<Integer, Entity>> ven = new ConcurrentHashMap<>();

    Brillo(TOD4Mejoras pl) {
        this.pm = ProtocolLibrary.getProtocolManager();
        pm.addPacketListener(new PacketAdapter(pl, ListenerPriority.HIGHEST, PacketType.Play.Server.ENTITY_METADATA) {
            @Override
            public void onPacketSending(PacketEvent e) {
                Map<Integer, Entity> s = ven.get(e.getPlayer().getUniqueId());
                if (s == null || s.isEmpty()) return;
                int id = e.getPacket().getIntegers().read(0);
                if (!s.containsKey(id)) return;
                // el mismo paquete puede ir a varios jugadores: se toca una copia
                PacketContainer pc = e.getPacket().deepClone();
                List<WrappedDataValue> l = pc.getDataValueCollectionModifier().read(0);
                boolean cambio = false;
                for (WrappedDataValue v : l) {
                    if (v.getIndex() == 0 && v.getValue() instanceof Byte b && (b & 0x40) == 0) { v.setValue((byte) (b | 0x40)); cambio = true; }
                }
                if (!cambio) return;
                pc.getDataValueCollectionModifier().write(0, l);
                e.setPacket(pc);
            }
        });
    }

    /** Brillan exactamente estos mobs (los que ya no estan se apagan). Devuelve cuantos son nuevos. */
    int actualizar(Player p, Collection<Entity> mobs) {
        Map<Integer, Entity> s = ven.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>());
        int nuevos = 0;
        List<Integer> ids = new ArrayList<>();
        for (Entity en : mobs) {
            ids.add(en.getEntityId());
            if (s.put(en.getEntityId(), en) == null) { enviar(p, en, true); nuevos++; }
        }
        for (Integer id : new ArrayList<>(s.keySet())) {
            if (ids.contains(id)) continue;
            Entity en = s.remove(id);
            if (en != null && en.isValid() && p.isOnline()) enviar(p, en, false);
        }
        return nuevos;
    }

    void limpiar(Player p) {
        Map<Integer, Entity> s = ven.remove(p.getUniqueId());
        if (s == null || !p.isOnline()) return;
        for (Entity en : s.values()) if (en.isValid()) enviar(p, en, false);
    }

    void salio(Player p) { ven.remove(p.getUniqueId()); }

    /** Los mismos datos que manda el juego (fuego, agachado, nadando, invisible...) mas el brillo. */
    private void enviar(Player p, Entity en, boolean brilla) {
        byte f = 0;
        if (en.getFireTicks() > 0 || en.isVisualFire()) f |= 0x01;
        if (en.isSneaking()) f |= 0x02;
        if (en instanceof LivingEntity le) {
            if (le.isSwimming()) f |= 0x10;
            if (le.isInvisible()) f |= 0x20;
            if (le.isGliding()) f |= (byte) 0x80;
        }
        if (brilla || en.isGlowing()) f |= 0x40;
        try {
            PacketContainer pc = pm.createPacket(PacketType.Play.Server.ENTITY_METADATA);
            pc.getIntegers().write(0, en.getEntityId());
            pc.getDataValueCollectionModifier().write(0, List.of(new WrappedDataValue(0, WrappedDataWatcher.Registry.get(Byte.class), f)));
            pm.sendServerPacket(p, pc);
        } catch (Throwable ignored) { }
    }
}

package ar.tod4.mejoras;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;

/** Hace brillar un mob solo para un jugador (con ProtocolLib). Esta clase se carga solo si ProtocolLib esta. */
final class Brillo {
    private final TOD4Mejoras pl;
    private final ProtocolManager pm;

    Brillo(TOD4Mejoras pl) {
        this.pl = pl;
        this.pm = ProtocolLibrary.getProtocolManager();
    }

    void mostrar(Player p, Entity en, int ticks) {
        enviar(p, en, true);
        Bukkit.getScheduler().runTaskLater(pl, () -> { if (p.isOnline() && en.isValid()) enviar(p, en, false); }, ticks);
    }

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

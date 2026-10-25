package ar.tod4.mejoras;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Los efectos de las mejoras que tiene puestas cada jugador. No se suman: dos piezas con lo mismo = una.
 *  - Pociones: se renuevan cada medio segundo (duran 15 s); si una pocion o un faro da mas, manda eso.
 *  - Inmunidades, atributos, explosiones, planear, sonar y sanador.
 */
final class Efectos implements Listener {
    private static final int DURA = 300;   // ticks de las pociones de la mejora (se renuevan antes de que bajen de 10 s)

    private final TOD4Mejoras pl;
    private int tick;

    /** Lo que le toca a cada jugador ahora (se recalcula cada medio segundo). */
    private static final class Estado {
        final Map<PotionEffectType, Integer> pociones = new HashMap<>();
        final Set<PotionEffectType> inmune = new HashSet<>();
        final Map<NamespacedKey, Object[]> atributos = new HashMap<>();   // clave -> {Attribute, valor}
        double explosiones, planear, sonar, sanador;
    }

    private final Map<UUID, Estado> estados = new HashMap<>();
    private final Map<UUID, Set<PotionEffectType>> dadas = new HashMap<>();
    private final Map<UUID, Boolean> salto = new HashMap<>();
    private final Set<UUID> planeado = new HashSet<>();
    private Brillo brillo;

    Efectos(TOD4Mejoras pl) {
        this.pl = pl;
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") != null) {
            try { brillo = new Brillo(pl); } catch (Throwable t) { pl.getLogger().warning("Sin sonar: ProtocolLib no respondio (" + t + ")."); }
        } else pl.getLogger().warning("Sin ProtocolLib: la Mejora de Noche no va a mostrar los mobs.");
        Bukkit.getScheduler().runTaskTimer(pl, this::cadaMedioSegundo, 20L, 10L);
    }

    void apagar() {
        for (Player p : Bukkit.getOnlinePlayers()) { estados.remove(p.getUniqueId()); aplicar(p, new Estado()); }
    }

    // ================================================================= que tiene puesto

    private Estado calcular(Player p) {
        Estado e = new Estado();
        Set<String> vistas = new HashSet<>();
        for (ItemStack it : p.getInventory().getArmorContents()) {
            if (it == null || !pl.piezas.contains(it.getType())) continue;
            Mejora m = pl.mejoraDe(it);
            if (m == null || !vistas.add(m.id)) continue;      // la misma mejora dos veces no suma
            for (Mejora.Efecto ef : m.efectos.values()) {
                if (!pl.efectoOn(m, ef)) continue;
                switch (ef.tipo) {
                    case POCION -> e.pociones.merge(ef.pocion, ef.nivel - 1, Math::max);
                    case INMUNE -> e.inmune.add(ef.pocion);
                    case ATRIBUTO -> e.atributos.put(new NamespacedKey(pl, "m_" + m.id + "_" + ef.id), new Object[]{ef.atributo, ef.valor});
                    case EXPLOSIONES -> e.explosiones = Math.max(e.explosiones, ef.valor);
                    case PLANEAR -> e.planear = Math.max(e.planear, ef.valor);
                    case SONAR -> e.sonar = Math.max(e.sonar, ef.valor);
                    case SANADOR -> e.sanador = Math.max(e.sanador, ef.valor);
                }
            }
        }
        return e;
    }

    private void cadaMedioSegundo() {
        tick++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Estado e = calcular(p);
            estados.put(p.getUniqueId(), e);
            aplicar(p, e);
            if (p.isOnGround() || p.isInWater() || p.isFlying()) planeado.remove(p.getUniqueId());
            if (e.sonar > 0 && tick % 10 == 0 && brillo != null) sonar(p, e.sonar);          // cada 5 s
            if (e.sanador > 0 && tick % 2 == 0) sanar(p, e.sanador);                            // cada 1 s
        }
    }

    private void aplicar(Player p, Estado e) {
        // pociones
        Set<PotionEffectType> antes = dadas.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
        for (Map.Entry<PotionEffectType, Integer> en : e.pociones.entrySet()) {
            PotionEffect cur = p.getPotionEffect(en.getKey());
            boolean masFuerte = cur != null && (cur.getAmplifier() > en.getValue()
                    || (cur.getAmplifier() == en.getValue() && (cur.isInfinite() || cur.getDuration() > DURA)));
            if (!masFuerte) p.addPotionEffect(new PotionEffect(en.getKey(), DURA, en.getValue(), true, false, true));
            antes.add(en.getKey());
        }
        for (PotionEffectType t : new ArrayList<>(antes)) {
            if (e.pociones.containsKey(t)) continue;
            antes.remove(t);
            PotionEffect cur = p.getPotionEffect(t);
            // se saca solo si es la de la mejora (no una pocion ni un faro)
            if (cur != null && !cur.isInfinite() && cur.getDuration() <= DURA && cur.isAmbient() && !cur.hasParticles()) p.removePotionEffect(t);
        }
        // inmunidades: si ya lo tenia, se va
        for (PotionEffectType t : e.inmune) if (p.hasPotionEffect(t)) p.removePotionEffect(t);
        // atributos
        Set<Attribute> usados = new HashSet<>();
        for (Mejora m : pl.mejoras.values()) for (Mejora.Efecto ef : m.efectos.values()) if (ef.atributo != null) usados.add(ef.atributo);
        for (Object[] v : e.atributos.values()) usados.add((Attribute) v[0]);
        for (Attribute a : usados) {
            AttributeInstance ai = p.getAttribute(a);
            if (ai == null) continue;
            for (AttributeModifier mod : new ArrayList<>(ai.getModifiers())) {
                NamespacedKey k = mod.getKey();
                if (!k.getNamespace().equals(pl.getName().toLowerCase())) continue;
                Object[] v = e.atributos.get(k);
                if (v == null || v[0] != a) ai.removeModifier(mod);
            }
        }
        for (Map.Entry<NamespacedKey, Object[]> en : e.atributos.entrySet()) {
            AttributeInstance ai = p.getAttribute((Attribute) en.getValue()[0]);
            if (ai == null || ai.getModifier(en.getKey()) != null) continue;
            ai.addModifier(new AttributeModifier(en.getKey(), (Double) en.getValue()[1], AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
        }
    }

    // ================================================================= inmunidades y explosiones

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void efecto(EntityPotionEffectEvent e) {
        if (!(e.getEntity() instanceof Player p) || e.getNewEffect() == null) return;
        if (e.getAction() != EntityPotionEffectEvent.Action.ADDED && e.getAction() != EntityPotionEffectEvent.Action.CHANGED) return;
        Estado st = estados.get(p.getUniqueId());
        if (st != null && st.inmune.contains(e.getNewEffect().getType())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void explosion(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION && e.getCause() != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) return;
        Estado st = estados.get(p.getUniqueId());
        if (st != null && st.explosiones > 0) e.setDamage(e.getDamage() * Math.max(0, 1 - st.explosiones / 100.0));
    }

    // ================================================================= planear

    @EventHandler
    public void input(PlayerInputEvent e) {
        Player p = e.getPlayer();
        boolean ahora = e.getInput().isJump();
        Boolean antes = salto.put(p.getUniqueId(), ahora);
        if (!ahora || Boolean.TRUE.equals(antes)) return;                // solo cuando recien lo toca
        Estado st = estados.get(p.getUniqueId());
        if (st == null || st.planear <= 0) return;
        if (p.isOnGround() || p.isFlying() || p.isGliding() || p.isInWater() || p.isClimbing() || p.isInsideVehicle()) return;
        if (p.getGameMode() == GameMode.SPECTATOR || !planeado.add(p.getUniqueId())) return;   // una vez por salto
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, (int) (st.planear * 20), 0, false, false, true));
        p.setFallDistance(0f);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PHANTOM_FLAP, SoundCategory.PLAYERS, 0.6f, 1.4f);
    }

    @EventHandler
    public void sale(PlayerQuitEvent e) {
        UUID u = e.getPlayer().getUniqueId();
        estados.remove(u); dadas.remove(u); salto.remove(u); planeado.remove(u);
    }

    // ================================================================= sonar (Noche)

    private void sonar(Player p, double r) {
        List<Entity> mobs = new ArrayList<>();
        for (Entity en : p.getNearbyEntities(r, r, r)) {
            if (en instanceof Enemy && en instanceof LivingEntity le && !le.isDead() && le.getLocation().distanceSquared(p.getLocation()) <= r * r) mobs.add(en);
        }
        if (mobs.isEmpty()) return;
        for (Entity en : mobs) brillo.mostrar(p, en, 40);   // 2 segundos
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 0.35f, 1.6f);
    }

    // ================================================================= sanador

    private Method mismoEquipo;
    private Object equipos;
    private boolean buscado;

    /** true si los dos estan en el mismo equipo de TOD4 (o si no se usa el equipo). */
    private boolean companeros(Player a, Player b) {
        if (!pl.cfg.getBoolean("sanador_solo_equipo", true)) return true;
        if (!buscado) {
            buscado = true;
            try {
                org.bukkit.plugin.Plugin t = Bukkit.getPluginManager().getPlugin("TOD4");
                if (t != null) {
                    equipos = t.getClass().getMethod("equipos").invoke(t);
                    mismoEquipo = equipos.getClass().getMethod("mismoEquipo", UUID.class, UUID.class);
                }
            } catch (Throwable ignored) { mismoEquipo = null; }
            if (mismoEquipo == null) pl.getLogger().warning("No encontre los equipos de TOD4: el Sanador cura a cualquiera que este cerca.");
        }
        if (mismoEquipo == null) return true;
        try { return (Boolean) mismoEquipo.invoke(equipos, a.getUniqueId(), b.getUniqueId()); } catch (Throwable t) { return false; }
    }

    private void sanar(Player p, double r) {
        if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) return;
        boolean alguien = false;
        for (Player o : p.getWorld().getPlayers()) {
            if (o == p || o.isDead() || o.getGameMode() == GameMode.SPECTATOR) continue;
            if (o.getLocation().distanceSquared(p.getLocation()) > r * r || !companeros(p, o)) continue;
            alguien = true;
            PotionEffect cur = o.getPotionEffect(PotionEffectType.REGENERATION);
            if (cur == null || (cur.getAmplifier() == 0 && cur.getDuration() < 60))
                o.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 60, 0, true, false, true));
        }
        if (!alguien) return;
        // circulo verde relleno en el piso, mientras haya alguien para curar
        Location c = p.getLocation();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < 18; i++) {
            double d = Math.sqrt(rnd.nextDouble()) * r, a = rnd.nextDouble() * Math.PI * 2;
            p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, c.getX() + Math.cos(a) * d, c.getY() + 0.15, c.getZ() + Math.sin(a) * d, 1, 0, 0, 0, 0);
        }
    }

    static PotionEffectType tipo(String n) { return Registry.EFFECT.get(NamespacedKey.minecraft(n)); }
}

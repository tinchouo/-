package ar.tod5.servidor;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.EntityEffect;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Los totems de TOD5 (todos son totems de Minecraft con un dato propio):
 *  - chapucero  (comun)       te salva pero te deja con medio corazon, sin ningun efecto
 *  - empuje     (poco comun)  te salva y empuja lejos a los enemigos cercanos
 *  - caballero  (raro)        dos en uno: te salva, te da un escudo de corazones y queda como un totem normal
 *  - vitalidad  (raro)        en la otra mano: +2 corazones maximos (atributo del item, lo maneja Minecraft); tambien salva
 *  - slasher    (raro)        en la otra mano: +20% de dano (atributo del item); tambien salva
 *  - sacrificio (raro)        te salva y te cura TODA la vida, pero te saca 2 corazones maximos para siempre (no baja de 1 corazon)
 *  - equipo     (epico)       te salva y cura 6 corazones + Velocidad, Regeneracion y Resistencia I (10 s) a tu equipo a 5 bloques
 *  - guardian = "TODtem" (legendario) funciona desde el inventario (no desde una mochila)
 *  - ancestral  (mitico)      te salva, te cura toda la vida, empuja a los enemigos; en la otra mano +2 corazones
 *  - estrella   (exotico)     clic derecho: 10 s sin recibir dano (no mata de un golpe). Si te salva, tambien te da la estrella.
 * ORDEN al morir (lo hace el plugin, no Minecraft): 1) la mano secundaria, 2) la mano principal, 3) el TODtem del inventario.
 * Todo es liviano: solo se hace algo al morir, al usar la estrella y una cuenta cada 4 ticks si hay alguna estrella activa.
 */
final class Totems implements Listener {
	static final NamespacedKey TOTEM = new NamespacedKey("tod5", "totem");
	static final NamespacedKey SACRIFICIO = new NamespacedKey("tod5", "sacrificio");
	static final NamespacedKey VITALIDAD = new NamespacedKey("tod5", "vitalidad"), SLASHER = new NamespacedKey("tod5", "slasher");
	private static final long ESTRELLA_MS = 10_000;
	private static final String[] IDS = { "chapucero", "empuje", "caballero", "vitalidad", "slasher", "sacrificio", "equipo", "guardian", "ancestral", "estrella" };

	private final TOD5Servidor plugin;
	private final Map<UUID, Long> estrella = new HashMap<>();

	Totems(TOD5Servidor plugin) {
		this.plugin = plugin;
		Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 4L, 4L);
	}

	// ================= los items =================
	ItemStack crear(String id) {
		ItemStack it = new ItemStack(Material.TOTEM_OF_UNDYING);
		ItemMeta m = it.getItemMeta();
		String nombre, rareza; List<String> lore;
		switch (id) {
			case "estrella" -> { nombre = "Tótem Estrella"; rareza = "exotico"; lore = List.of("Haz clic derecho para usarlo.", "10 segundos de invencibilidad.", "No mata a los enemigos de un golpe."); }
			case "chapucero" -> { nombre = "Tótem Chapucero"; rareza = "comun"; lore = List.of("Te salva de morir,", "pero te deja con medio corazón."); }
			case "caballero" -> { nombre = "Tótem del Cuy Caballero"; rareza = "raro"; lore = List.of("Te salva y te da un escudo de corazones.", "Después queda como un tótem normal."); }
			case "guardian" -> { nombre = "TODtem"; rareza = "legendario"; lore = List.of("Funciona desde el inventario", "(no desde una mochila)."); }
			case "empuje" -> { nombre = "Tótem de Empuje"; rareza = "poco_comun"; lore = List.of("Te salva y empuja lejos", "a los enemigos cercanos."); }
			case "slasher" -> { nombre = "Tótem Slasher"; rareza = "raro"; lore = List.of("En la otra mano: +20% de daño.", "También te salva como un tótem normal."); }
			case "equipo" -> { nombre = "Tótem de Equipo"; rareza = "epico"; lore = List.of("Te salva y cura a tu equipo cercano", "(5 bloques): 6 corazones, Velocidad,", "Regeneración y Resistencia por 10 s."); }
			case "ancestral" -> { nombre = "Tótem Ancestral"; rareza = "mitico"; lore = List.of("Te salva, te cura toda la vida", "y empuja a los enemigos.", "En la otra mano: +2 corazones máximos."); }
			case "vitalidad" -> { nombre = "Tótem de Vitalidad"; rareza = "raro"; lore = List.of("En la otra mano: +2 corazones máximos.", "También te salva como un tótem normal."); }
			default -> { id = "sacrificio"; nombre = "Tótem del Sacrificio"; rareza = "raro"; lore = List.of("Te salva y te cura toda la vida,", "pero te quita 2 corazones máximos para siempre."); }
		}
		m.itemName(Component.text(nombre));
		List<Component> l = new ArrayList<>();
		for (String s : lore) l.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
		m.lore(l);
		m.getPersistentDataContainer().set(TOTEM, PersistentDataType.STRING, id);
		m.getPersistentDataContainer().set(Crafteos.RAREZA, PersistentDataType.STRING, rareza);
		if (id.equals("slasher")) {
			m.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(SLASHER, 0.2, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.OFFHAND));
		}
		if (id.equals("vitalidad") || id.equals("ancestral")) {
			m.addAttributeModifier(Attribute.MAX_HEALTH, new AttributeModifier(VITALIDAD, 4.0, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.OFFHAND));
			m.removeItemFlags(ItemFlag.HIDE_ATTRIBUTES);
		}
		it.setItemMeta(m);
		return it;
	}
	static String tipo(ItemStack it) {
		if (it == null || it.getType() != Material.TOTEM_OF_UNDYING || !it.hasItemMeta()) return null;
		return it.getItemMeta().getPersistentDataContainer().get(TOTEM, PersistentDataType.STRING);
	}
	List<JsonObject> itemsParaTablet() {
		List<JsonObject> l = new ArrayList<>();
		for (String id : IDS) { JsonObject j = new JsonObject(); j.addProperty("id", "totem_" + id); j.add("item", Crafteos.itemJson(crear(id))); l.add(j); }
		return l;
	}

	// ================= al morir: el plugin elige el totem (mano secundaria, mano principal, TODtem del inventario) =================
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void alMorir(EntityDamageEvent e) {
		if (!(e.getEntity() instanceof Player p)) return;
		DamageCause c = e.getCause();
		if (c == DamageCause.VOID || c == DamageCause.KILL || c == DamageCause.SUICIDE) return;   // como en Minecraft: esto no lo salva nada
		if (p.getHealth() - e.getFinalDamage() > 0) return;
		var inv = p.getInventory();
		int lugar = -1;   // 40 = mano secundaria
		if (sirve(p, inv.getItemInOffHand())) lugar = 40;
		else if (sirve(p, inv.getItemInMainHand())) lugar = inv.getHeldItemSlot();
		else for (int i = 0; i < 36; i++) if ("guardian".equals(tipo(inv.getItem(i))) && sirve(p, inv.getItem(i))) { lugar = i; break; }
		if (lugar < 0) return;
		ItemStack it = inv.getItem(lugar);
		String t = tipo(it);
		e.setCancelled(true);
		it.setAmount(it.getAmount() - 1);
		inv.setItem(lugar, it.getAmount() <= 0 ? null : it);
		// lo mismo que hace Minecraft con un totem
		p.setHealth(1.0);
		for (PotionEffect pe : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(pe.getType());
		p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 900, 1));
		p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 1));
		p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0));
		p.playEffect(EntityEffect.PROTECTED_FROM_DEATH);
		if (t != null) especial(p, t, lugar);
	}
	/** Si este item es un totem que puede salvarte ahora (el del Sacrificio no te deja con menos de 1 corazon). */
	private static boolean sirve(Player p, ItemStack it) {
		if (it == null || it.getType() != Material.TOTEM_OF_UNDYING) return false;
		if ("sacrificio".equals(tipo(it))) { AttributeInstance v = p.getAttribute(Attribute.MAX_HEALTH); return v == null || v.getValue() - 4.0 >= 2.0; }
		return true;
	}
	private void especial(Player p, String t, int lugar) {
		switch (t) {
			case "chapucero" -> {
				p.removePotionEffect(PotionEffectType.REGENERATION);
				p.removePotionEffect(PotionEffectType.ABSORPTION);
				p.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
				p.setAbsorptionAmount(0);
			}
			case "sacrificio" -> sacrificar(p);
			case "caballero" -> {
				p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 20 * 30, 2));   // +6 corazones amarillos por 30 s
				ItemStack normal = new ItemStack(Material.TOTEM_OF_UNDYING);
				ItemStack ahi = p.getInventory().getItem(lugar);
				if (ahi == null || ahi.getType().isAir()) p.getInventory().setItem(lugar, normal);   // en el mismo lugar, ya como totem normal
				else p.getInventory().addItem(normal).values().forEach(r -> p.getWorld().dropItem(p.getLocation(), r));
			}
			case "empuje" -> empujar(p);
			case "equipo" -> curarEquipo(p);
			case "ancestral" -> { AttributeInstance v = p.getAttribute(Attribute.MAX_HEALTH); if (v != null) p.setHealth(v.getValue()); empujar(p); }
			case "estrella" -> activarEstrella(p);
			default -> { }
		}
	}
	/** Empuja lejos a los enemigos que estan a 6 bloques. */
	private static void empujar(Player p) {
		for (org.bukkit.entity.Entity en : p.getNearbyEntities(6, 4, 6)) {
			if (!(en instanceof org.bukkit.entity.Enemy)) continue;
			org.bukkit.util.Vector d = en.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
			if (d.lengthSquared() < 0.01) d = new org.bukkit.util.Vector(1, 0, 0);
			en.setVelocity(d.normalize().multiply(1.8).setY(0.6));
		}
		p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1f, 0.8f);
		p.getWorld().spawnParticle(Particle.GUST_EMITTER_SMALL, p.getLocation().add(0, 1, 0), 1);
	}
	/** Cura a los del mismo equipo (y a vos) a 5 bloques, sin sacarles los efectos que ya tienen. */
	private void curarEquipo(Player p) {
		Equipos.Equipo mio = plugin.equipos == null ? null : plugin.equipos.de(p.getUniqueId());
		List<Player> a = new ArrayList<>();
		a.add(p);
		if (mio != null) for (Player o : p.getWorld().getPlayers()) {
			if (o == p || o.getLocation().distanceSquared(p.getLocation()) > 25) continue;
			Equipos.Equipo q = plugin.equipos.de(o.getUniqueId());
			if (q != null && q.id.equals(mio.id)) a.add(o);
		}
		for (Player o : a) {
			AttributeInstance v = o.getAttribute(Attribute.MAX_HEALTH);
			double max = v == null ? 20 : v.getValue();
			if (o != p) o.setHealth(Math.min(max, o.getHealth() + 12));
			for (PotionEffectType tp : List.of(PotionEffectType.SPEED, PotionEffectType.REGENERATION, PotionEffectType.RESISTANCE)) {
				PotionEffect ya = o.getPotionEffect(tp);
				if (ya == null || (ya.getAmplifier() == 0 && ya.getDuration() >= 0 && ya.getDuration() < 200)) o.addPotionEffect(new PotionEffect(tp, 200, 0));
			}
			o.getWorld().spawnParticle(Particle.HEART, o.getLocation().add(0, 2, 0), 4, 0.4, 0.3, 0.4);
		}
	}

	/** Por si Minecraft igual salva con un totem (no deberia: el plugin se adelanta): aplica lo especial. */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void alSalvar(EntityResurrectEvent e) {
		if (!(e.getEntity() instanceof Player p)) return;
		EquipmentSlot mano = e.getHand() == null ? EquipmentSlot.HAND : e.getHand();
		String t = tipo(p.getInventory().getItem(mano));
		if (t == null) return;
		int lugar = mano == EquipmentSlot.OFF_HAND ? 40 : p.getInventory().getHeldItemSlot();
		Bukkit.getScheduler().runTask(plugin, () -> especial(p, t, lugar));
	}

	private void sacrificar(Player p) {
		AttributeInstance vida = p.getAttribute(Attribute.MAX_HEALTH);
		if (vida == null) return;
		AttributeModifier viejo = vida.getModifier(SACRIFICIO);
		double total = (viejo == null ? 0 : viejo.getAmount()) - 4.0;
		if (viejo != null) vida.removeModifier(viejo);
		vida.addModifier(new AttributeModifier(SACRIFICIO, total, AttributeModifier.Operation.ADD_NUMBER));
		p.setHealth(vida.getValue());   // cura toda la vida (con el maximo nuevo)
		p.sendMessage(Component.text("Perdiste 2 corazones máximos. Los corazones rojos te los devuelven.", NamedTextColor.RED));
	}

	// ================= la estrella =================
	@EventHandler
	public void alUsar(PlayerInteractEvent e) {
		if (e.getHand() != EquipmentSlot.HAND || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
		Player p = e.getPlayer();
		ItemStack it = p.getInventory().getItemInMainHand();
		if (!"estrella".equals(tipo(it))) return;
		e.setCancelled(true);
		if (p.getGameMode() != GameMode.CREATIVE) it.setAmount(it.getAmount() - 1);
		activarEstrella(p);
	}
	private void activarEstrella(Player p) {
		estrella.put(p.getUniqueId(), System.currentTimeMillis() + ESTRELLA_MS);
		p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.6f);
	}
	/** Sin dano mientras dura la estrella (el vacio y /kill siguen valiendo). */
	@EventHandler(priority = EventPriority.LOWEST)
	public void sinDano(EntityDamageEvent e) {
		if (!(e.getEntity() instanceof Player p) || !estrella.containsKey(p.getUniqueId())) return;
		DamageCause c = e.getCause();
		if (c == DamageCause.VOID || c == DamageCause.KILL || c == DamageCause.SUICIDE) return;
		e.setCancelled(true);
	}
	@EventHandler public void alSalir(PlayerQuitEvent e) { estrella.remove(e.getPlayer().getUniqueId()); }

	/** Cada 4 ticks: particulas y cuenta regresiva de las estrellas activas (casi siempre no hay ninguna). */
	private void tick() {
		if (estrella.isEmpty()) return;
		long ahora = System.currentTimeMillis();
		estrella.entrySet().removeIf(en -> {
			Player p = Bukkit.getPlayer(en.getKey());
			if (p == null) return true;
			long resta = en.getValue() - ahora;
			if (resta <= 0) {
				p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 1f, 1.4f);
				p.sendActionBar(Component.empty());
				return true;
			}
			p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0), 2, 0.35, 0.6, 0.35, 0.01);
			p.sendActionBar(Component.text("★ Estrella: " + (resta / 1000 + 1) + " s", NamedTextColor.YELLOW));
			return false;
		});
	}
}

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
 * Los totems de TOD5 (todos son totems de Minecraft con un dato propio; el normal de Minecraft no se toca):
 *  - estrella   (exotico)    clic derecho: 10 s sin recibir dano (no mata de un golpe). Si te salva de morir, tambien te da la estrella.
 *  - chapucero  (comun)      te salva pero te deja con medio corazon, sin ningun efecto
 *  - caballero  (legendario) te salva, te da un escudo de corazones extra y despues queda como un totem normal
 *  - guardian   (legendario) funciona desde cualquier casilla del inventario (no hace falta tenerlo en la mano)
 *  - vitalidad  (raro)       en la otra mano: +2 corazones maximos (lo hace Minecraft solo, con un atributo del item); tambien salva como un totem normal
 *  - sacrificio (raro)       te salva y te cura TODA la vida, pero te saca 2 corazones maximos para siempre (menos de 1 corazon no baja)
 * Todo es liviano: solo se hace algo al morir, al usar la estrella y una cuenta cada 4 ticks por cada estrella activa.
 */
final class Totems implements Listener {
	static final NamespacedKey TOTEM = new NamespacedKey("tod5", "totem");
	static final NamespacedKey SACRIFICIO = new NamespacedKey("tod5", "sacrificio");
	static final NamespacedKey VITALIDAD = new NamespacedKey("tod5", "vitalidad");
	private static final long ESTRELLA_MS = 10_000;
	private static final String[] IDS = { "estrella", "chapucero", "caballero", "guardian", "vitalidad", "sacrificio" };

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
			case "caballero" -> { nombre = "Tótem del Cuy Caballero"; rareza = "legendario"; lore = List.of("Te salva y te da un escudo de corazones.", "Después queda como un tótem normal."); }
			case "guardian" -> { nombre = "Tótem Guardián"; rareza = "legendario"; lore = List.of("Funciona desde cualquier casilla", "del inventario."); }
			case "vitalidad" -> { nombre = "Tótem de Vitalidad"; rareza = "raro"; lore = List.of("En la otra mano: +2 corazones máximos.", "También te salva como un tótem normal."); }
			default -> { id = "sacrificio"; nombre = "Tótem del Sacrificio"; rareza = "raro"; lore = List.of("Te salva y te cura toda la vida,", "pero te quita 2 corazones máximos para siempre."); }
		}
		m.itemName(Component.text(nombre));
		List<Component> l = new ArrayList<>();
		for (String s : lore) l.add(Component.text(s, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
		m.lore(l);
		m.getPersistentDataContainer().set(TOTEM, PersistentDataType.STRING, id);
		m.getPersistentDataContainer().set(Crafteos.RAREZA, PersistentDataType.STRING, rareza);
		if (id.equals("vitalidad")) {
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

	// ================= cuando un totem de la mano te salva =================
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void alSalvar(EntityResurrectEvent e) {
		if (!(e.getEntity() instanceof Player p)) return;
		EquipmentSlot mano = e.getHand();
		if (mano == null) mano = p.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
		String t = tipo(p.getInventory().getItem(mano));
		if (t == null) return;
		final EquipmentSlot lugar = mano;
		switch (t) {
			case "sacrificio" -> {
				AttributeInstance vida = p.getAttribute(Attribute.MAX_HEALTH);
				if (vida != null && vida.getValue() - 4.0 < 2.0) {
					e.setCancelled(true);
					p.sendMessage(Component.text("El Tótem del Sacrificio no puede dejarte con menos de un corazón.", NamedTextColor.RED));
					return;
				}
				Bukkit.getScheduler().runTask(plugin, () -> sacrificar(p));
			}
			case "chapucero" -> Bukkit.getScheduler().runTask(plugin, () -> {
				p.removePotionEffect(PotionEffectType.REGENERATION);
				p.removePotionEffect(PotionEffectType.ABSORPTION);
				p.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
				p.setAbsorptionAmount(0);
				p.setHealth(1.0);
			});
			case "caballero" -> Bukkit.getScheduler().runTask(plugin, () -> {
				p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 20 * 30, 2));   // +6 corazones amarillos por 30 s
				ItemStack normal = new ItemStack(Material.TOTEM_OF_UNDYING);
				if (p.getInventory().getItem(lugar).getType().isAir()) p.getInventory().setItem(lugar, normal);   // el mismo lugar, ya como totem normal
				else p.getInventory().addItem(normal).values().forEach(r -> p.getWorld().dropItem(p.getLocation(), r));
			});
			case "estrella" -> Bukkit.getScheduler().runTask(plugin, () -> activarEstrella(p));
			default -> { }
		}
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

	// ================= el guardian (desde el inventario) =================
	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void alMorir(EntityDamageEvent e) {
		if (!(e.getEntity() instanceof Player p)) return;
		DamageCause c = e.getCause();
		if (c == DamageCause.VOID || c == DamageCause.KILL || c == DamageCause.SUICIDE) return;
		if (p.getHealth() + p.getAbsorptionAmount() - e.getFinalDamage() > 0) return;
		// si hay un totem en la mano, lo usa Minecraft (el guardian queda para otra vez)
		if (p.getInventory().getItemInMainHand().getType() == Material.TOTEM_OF_UNDYING || p.getInventory().getItemInOffHand().getType() == Material.TOTEM_OF_UNDYING) return;
		ItemStack[] todo = p.getInventory().getContents();
		for (int i = 0; i < todo.length; i++) {
			if (!"guardian".equals(tipo(todo[i]))) continue;
			e.setCancelled(true);
			todo[i].setAmount(todo[i].getAmount() - 1);
			p.getInventory().setItem(i, todo[i].getAmount() <= 0 ? null : todo[i]);
			p.setHealth(1.0);
			for (PotionEffect pe : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(pe.getType());
			p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 900, 1));
			p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 1));
			p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0));
			p.playEffect(EntityEffect.PROTECTED_FROM_DEATH);
			return;
		}
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

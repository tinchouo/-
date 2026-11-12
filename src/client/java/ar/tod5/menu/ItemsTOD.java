package ar.tod5.menu;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * Los items del mod y su pestaña "Trucofdeath" en el creativo.
 * Monedas: Trucoin (amarilla), Fracoin (roja), Tinchoin (morada) y Truquita (la ficha de casino).
 *
 * El server es Paper (no conoce items de mods), asi que cada item del mod es un item base con:
 *  - el dibujo del mod (item_model tod5:trucoin...),
 *  - el nombre del mod (item.tod5.trucoin, traducido en lang),
 *  - la marca del plugin (PublicBukkitValues tod5:moneda), asi el plugin la reconoce (no sirve para craftear oro).
 * Para el jugador es un item nuevo; sacarlo del creativo en el server anda sin problemas.
 */
public final class ItemsTOD {
	public static final String[] MONEDAS = { "trucoin", "fracoin", "tinchoin", "truquita" };

	/** Una moneda del mod (la misma que da /tod moneda en el plugin). */
	public static ItemStack moneda(String nombre) {
		ItemStack it = new ItemStack(Items.GOLD_NUGGET);
		it.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath("tod5", nombre));
		it.set(DataComponents.ITEM_NAME, Component.translatable("item.tod5." + nombre));
		CompoundTag bukkit = new CompoundTag();
		bukkit.putString("tod5:moneda", nombre);
		CompoundTag raiz = new CompoundTag();
		raiz.put("PublicBukkitValues", bukkit);
		it.set(DataComponents.CUSTOM_DATA, CustomData.of(raiz));
		return it;
	}

	private static CreativeModeTab pestana;
	/** Los items propios del evento que manda el server (los crea un editor en la tablet, modo admin > Items). */
	private static java.util.List<ItemStack> propios = java.util.List.of();

	public static void registrar() {
		pestana = CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
			.title(Component.translatable("itemGroup.tod5.trucofdeath"))
			.icon(() -> moneda("trucoin"))
			.displayItems((params, out) -> {
				for (String m : MONEDAS) out.accept(moneda(m));
				for (ItemStack it : propios) if (!it.isEmpty()) out.accept(it.copy());
			})
			.build();
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath("tod5", "trucofdeath"), pestana);
	}

	/** Llegaron items propios nuevos: rearmo la pestaña del creativo. */
	public static void actualizar(java.util.List<ar.tod5.menu.tablet.TabletDatos.ItemPropio> items) {
		java.util.List<ItemStack> l = new java.util.ArrayList<>();
		for (var i : items) l.add(i.item());
		boolean igual = l.size() == propios.size();
		for (int k = 0; igual && k < l.size(); k++) igual = ItemStack.matches(l.get(k), propios.get(k));
		if (igual) return;
		propios = l;
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (pestana != null && mc.level != null && mc.player != null) {
			try { pestana.buildContents(new CreativeModeTab.ItemDisplayParameters(mc.level.enabledFeatures(), mc.player.canUseGameMasterBlocks(), mc.level.registryAccess())); }
			catch (RuntimeException e) { org.slf4j.LoggerFactory.getLogger("TOD5").warn("TOD5 no pude rearmar la pestaña del creativo: {}", e.toString()); }
		}
	}
}

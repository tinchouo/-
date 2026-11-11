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

	public static void registrar() {
		CreativeModeTab pestana = CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
			.title(Component.translatable("itemGroup.tod5.trucofdeath"))
			.icon(() -> moneda("trucoin"))
			.displayItems((params, out) -> { for (String m : MONEDAS) out.accept(moneda(m)); })
			.build();
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath("tod5", "trucofdeath"), pestana);
	}
}

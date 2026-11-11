package ar.tod5.menu;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Los items propios del mod y su pestaña "Trucofdeath" en el creativo.
 * Monedas: Trucoin (amarilla), Fracoin (roja), Tinchoin (morada) y Truquita (la ficha de casino).
 * El dibujo esta en assets/tod5/items + models/item (a la mitad en la mano) y el nombre en lang (item.tod5.*).
 *
 * OJO: son items del mod; el server Paper no los conoce. En un solo jugador andan completos;
 * para el server hay que resolverlo aparte (pendiente).
 */
public final class ItemsTOD {
	public static Item TRUCOIN, FRACOIN, TINCHOIN, TRUQUITA;

	private static Item item(String nombre) {
		ResourceKey<Item> k = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("tod5", nombre));
		return Registry.register(BuiltInRegistries.ITEM, k, new Item(new Item.Properties().setId(k)));
	}

	public static void registrar() {
		TRUCOIN = item("trucoin");
		FRACOIN = item("fracoin");
		TINCHOIN = item("tinchoin");
		TRUQUITA = item("truquita");
		CreativeModeTab pestana = CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
			.title(Component.translatable("itemGroup.tod5.trucofdeath"))
			.icon(() -> new ItemStack(TRUCOIN))
			.displayItems((params, out) -> { out.accept(TRUCOIN); out.accept(FRACOIN); out.accept(TINCHOIN); out.accept(TRUQUITA); })
			.build();
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, Identifier.fromNamespaceAndPath("tod5", "trucofdeath"), pestana);
	}
}

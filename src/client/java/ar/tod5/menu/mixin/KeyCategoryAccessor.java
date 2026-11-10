package ar.tod5.menu.mixin;

import java.util.List;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Para poner la seccion TRUCOFDEATH primera en Controles. */
@Mixin(KeyMapping.Category.class)
public interface KeyCategoryAccessor {
	@Accessor("SORT_ORDER")
	static List<KeyMapping.Category> tod5$orden() { throw new AssertionError(); }
}

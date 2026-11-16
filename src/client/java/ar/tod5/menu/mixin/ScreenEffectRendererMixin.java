package ar.tod5.menu.mixin;

import ar.tod5.menu.GravedadCliente;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Con la gravedad cambiada, "tengo la cara metida en un bloque" (la pantalla tapada con la textura del bloque) se mira en los ojos de verdad.
 * Minecraft lo mira en los pies + 1,62 para arriba del mundo, que de costado o cabeza abajo cae afuera del cuerpo (y te tapaba la vista al acercarte a un bloque).
 */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {
	@Inject(method = "getViewBlockingState", at = @At("HEAD"), cancellable = true)
	private static void tod5$ojos(Player p, CallbackInfoReturnable<BlockState> cir) {
		if (p != Minecraft.getInstance().player || GravedadCliente.normal()) return;
		Vec3 ojo = GravedadCliente.ojos(p, 1.0F);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		BlockState res = null;
		for (int i = 0; i < 8 && res == null; i++) {
			pos.set(ojo.x + ((i & 1) - 0.5) * 0.1, ojo.y + (((i >> 1) & 1) - 0.5) * 0.1, ojo.z + (((i >> 2) & 1) - 0.5) * 0.1);
			BlockState b = p.level().getBlockState(pos);
			if (b.getRenderShape() != RenderShape.INVISIBLE && b.isViewBlocking(p.level(), pos)) res = b;
		}
		cir.setReturnValue(res);
	}
}

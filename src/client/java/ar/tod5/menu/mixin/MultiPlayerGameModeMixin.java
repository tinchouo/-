package ar.tod5.menu.mixin;

import ar.tod5.menu.GravedadCliente;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Gravedad de costado: al usar un item (arco, perla, balde...) el server recibe hacia donde mira de verdad en el mundo. */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
	@Unique private float tod5$yaw, tod5$pitch;
	@Unique private boolean tod5$cambiado;

	@Inject(method = "useItem", at = @At("HEAD"))
	private void tod5$aMundo(Player p, InteractionHand mano, CallbackInfoReturnable<InteractionResult> cir) {
		this.tod5$cambiado = GravedadCliente.costado(p);
		if (!this.tod5$cambiado) return;
		this.tod5$yaw = p.getYRot(); this.tod5$pitch = p.getXRot();
		float[] m = GravedadCliente.aMundo(GravedadCliente.de(p), this.tod5$yaw, this.tod5$pitch);
		p.setYRot(m[0]); p.setXRot(m[1]);
	}
	@Inject(method = "useItem", at = @At("RETURN"))
	private void tod5$volver(Player p, InteractionHand mano, CallbackInfoReturnable<InteractionResult> cir) {
		if (!this.tod5$cambiado) return;
		p.setYRot(this.tod5$yaw); p.setXRot(this.tod5$pitch);
		this.tod5$cambiado = false;
	}
}

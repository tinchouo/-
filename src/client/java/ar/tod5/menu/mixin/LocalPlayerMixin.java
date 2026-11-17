package ar.tod5.menu.mixin;

import ar.tod5.menu.GravedadCliente;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gravedad de costado, jugador propio:
 *  - al server se le manda hacia donde mira de verdad (en el mundo), no el yaw/pitch del marco
 *  - no se usa el "salir de adentro de un bloque" ni el salto automatico de Minecraft (miran el piso de abajo del mundo)
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
	@Unique private float tod5$yaw, tod5$pitch;
	@Unique private boolean tod5$cambiado;

	@Inject(method = "sendPosition", at = @At("HEAD"))
	private void tod5$aMundo(CallbackInfo ci) {
		LocalPlayer p = (LocalPlayer) (Object) this;
		this.tod5$cambiado = GravedadCliente.costado(p);
		if (!this.tod5$cambiado) return;
		this.tod5$yaw = p.getYRot(); this.tod5$pitch = p.getXRot();
		float[] m = GravedadCliente.aMundo(GravedadCliente.de(p), this.tod5$yaw, this.tod5$pitch);
		p.setYRot(m[0]); p.setXRot(m[1]);
	}
	@Inject(method = "sendPosition", at = @At("RETURN"))
	private void tod5$volver(CallbackInfo ci) {
		if (!this.tod5$cambiado) return;
		LocalPlayer p = (LocalPlayer) (Object) this;
		p.setYRot(this.tod5$yaw); p.setXRot(this.tod5$pitch);
		this.tod5$cambiado = false;
	}

	/** Plataformas que se mueven: me arrastra al principio de mi tick (ver PlataformasCliente.arrastrar). */
	@Inject(method = "tick", at = @At("HEAD"))
	private void tod5$plataforma(CallbackInfo ci) { ar.tod5.menu.PlataformasCliente.arrastrar((LocalPlayer) (Object) this); }

	@Inject(method = "moveTowardsClosestSpace", at = @At("HEAD"), cancellable = true)
	private void tod5$sinEmpujar(double x, double z, CallbackInfo ci) {
		if (GravedadCliente.costado((LocalPlayer) (Object) this)) ci.cancel();
	}
	@Inject(method = "updateAutoJump", at = @At("HEAD"), cancellable = true)
	private void tod5$sinAutoSalto(float x, float z, CallbackInfo ci) {
		if (GravedadCliente.costado((LocalPlayer) (Object) this)) ci.cancel();
	}
}

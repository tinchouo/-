package ar.tod5.menu.mixin;

import ar.tod5.menu.GravedadCliente;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Gravedad de costado: el movimiento lo hace el mod (dentro del marco), y la pose no cambia por la caja acostada. */
@Mixin(Player.class)
public abstract class PlayerMixin {
	@Inject(method = "travel", at = @At("HEAD"), cancellable = true)
	private void tod5$viajar(Vec3 entrada, CallbackInfo ci) {
		Player p = (Player) (Object) this;
		if (GravedadCliente.viajar(p, entrada)) { ci.cancel(); return; }
		GravedadCliente.volarCabezaAbajo(p);
	}

	@Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
	private void tod5$pose(CallbackInfo ci) {
		Player p = (Player) (Object) this;
		if (p.level().isClientSide() && GravedadCliente.costado(p)) { p.setPose(GravedadCliente.pose(p)); ci.cancel(); }
	}

	@Inject(method = "canPlayerFitWithinBlocksAndEntitiesWhen", at = @At("HEAD"), cancellable = true)
	private void tod5$entra(Pose pose, CallbackInfoReturnable<Boolean> cir) {
		Player p = (Player) (Object) this;
		if (!p.level().isClientSide()) return;
		if (GravedadCliente.costado(p)) { cir.setReturnValue(true); return; }
		if (GravedadCliente.invertida(p)) {
			// cabeza abajo: la caja cuelga del techo (los pies arriba), asi que se mide desde arriba
			EntityDimensions d = p.getDimensions(pose);
			double techo = p.getBoundingBox().maxY;
			cir.setReturnValue(p.level().noCollision(p, d.makeBoundingBox(p.getX(), techo - d.height(), p.getZ()).deflate(1.0E-7)));
		}
	}

	/** Agachado en el borde: con la gravedad cambiada se mira el piso de cada uno (el de Minecraft mira el de abajo del mundo). */
	@Inject(method = "maybeBackOffFromEdge", at = @At("HEAD"), cancellable = true)
	private void tod5$borde(Vec3 mov, MoverType tipo, CallbackInfoReturnable<Vec3> cir) {
		Player p = (Player) (Object) this;
		if (!p.level().isClientSide() || (tipo != MoverType.SELF && tipo != MoverType.PLAYER)) return;
		Vec3 r = GravedadCliente.borde(p, mov);
		if (r != null) cir.setReturnValue(r);
	}
}

package ar.tod5.menu.mixin;

import ar.tod5.menu.CartelEstado;
import ar.tod5.menu.GravedadCliente;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Avatar;
import ar.tod5.menu.EquiposCliente;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * - Gravedad dada vuelta: los jugadores se ven cabeza abajo.
 * - El cartel de arriba de la cabeza (solo ahi, no en el Tab), de arriba hacia abajo:
 *     "20 ❤" (corazon rojo) y, si tiene absorcion, "4 ❤" (corazon amarillo) al lado
 *     el nombre, del color del equipo (blanco si no tiene); los admins: "[Admin] nombre" en amarillo
 *     "<Equipo>" del color del equipo (si tiene)
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	@Inject(method = "isEntityUpsideDown(Lnet/minecraft/world/entity/Avatar;)Z", at = @At("HEAD"), cancellable = true)
	private void tod5$cabezaAbajo(Avatar e, CallbackInfoReturnable<Boolean> cir) {
		if (GravedadCliente.invertida(e)) cir.setReturnValue(true);
	}

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
	private void tod5$cartel(Avatar e, AvatarRenderState s, float parcial, CallbackInfo ci) {
		CartelEstado c = (CartelEstado) s;
		// la mochila en la espalda
		c.tod5$mochila().clear();
		net.minecraft.world.item.ItemStack m = ar.tod5.menu.MochilasCliente.espalda(e.getUUID());
		if (!m.isEmpty()) net.minecraft.client.Minecraft.getInstance().getItemModelResolver().updateForLiving(c.tod5$mochila(), m, net.minecraft.world.item.ItemDisplayContext.HEAD, e);
		if (s.nameTag == null) { c.tod5$vida(null); return; }   // Minecraft decidio no mostrar el nombre (lejos, invisible, regla del equipo...)
		EquiposCliente.Info inf = EquiposCliente.de(e.getUUID());
		String nombre = e.getName().getString();
		ChatFormatting colorAdmin = ChatFormatting.YELLOW;
		if (inf != null && inf.admin()) {
			s.nameTag = Component.literal("[Admin] " + nombre).withStyle(colorAdmin);
		} else {
			s.nameTag = Component.literal(nombre).withStyle(s2 -> inf != null && inf.rgb() >= 0 ? s2.withColor(inf.rgb()) : s2.withColor(ChatFormatting.WHITE));
		}
		s.scoreText = inf != null && inf.equipo() != null ? Component.literal("<" + inf.equipo() + ">").withStyle(s2 -> s2.withColor(inf.rgb() >= 0 ? inf.rgb() : 0xFFFFFF)) : null;
		int vida = (int) Math.ceil(Math.max(0, e.getHealth()));
		int extra = (int) Math.ceil(Math.max(0, e.getAbsorptionAmount()));
		MutableComponent v = Component.literal(vida + " ").withStyle(ChatFormatting.WHITE).append(Component.literal("❤").withStyle(ChatFormatting.RED));
		if (extra > 0) v.append(Component.literal("  " + extra + " ").withStyle(ChatFormatting.WHITE)).append(Component.literal("❤").withStyle(ChatFormatting.YELLOW));
		c.tod5$vida(v);
	}

	@Inject(method = "submitNameTag(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/CameraRenderState;)V", at = @At("HEAD"), cancellable = true)
	private void tod5$dibujarCartel(AvatarRenderState s, PoseStack pose, SubmitNodeCollector col, CameraRenderState cam, CallbackInfo ci) {
		Component vida = ((CartelEstado) s).tod5$vida();
		if (vida == null || s.nameTag == null) return;   // lo de siempre
		ci.cancel();
		float linea = 9.0F * 1.15F * 0.025F;
		int corrida = s.showExtraEars ? -10 : 0;
		pose.pushPose();
		if (s.scoreText != null) {
			col.submitNameTag(pose, s.nameTagAttachment, corrida, s.scoreText, !s.isDiscrete, s.lightCoords, s.distanceToCameraSq, cam);
			pose.translate(0.0F, linea, 0.0F);
		}
		col.submitNameTag(pose, s.nameTagAttachment, corrida, s.nameTag, !s.isDiscrete, s.lightCoords, s.distanceToCameraSq, cam);
		pose.translate(0.0F, linea, 0.0F);
		col.submitNameTag(pose, s.nameTagAttachment, corrida, vida, !s.isDiscrete, s.lightCoords, s.distanceToCameraSq, cam);
		pose.popPose();
	}
}

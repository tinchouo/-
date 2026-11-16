package ar.tod5.menu.mixin;

import ar.tod5.menu.GravedadCliente;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gravedad cambiada: la camara gira (de a poco, medio segundo) y se pone en los ojos nuevos.
 * Para arriba: rueda 180 grados. De costado: todo el marco gira 90 grados hacia la pared.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow @Final private static Vector3f FORWARDS;
	@Shadow @Final private static Vector3f UP;
	@Shadow @Final private static Vector3f LEFT;
	@Shadow @Final private Quaternionf rotation;
	@Shadow @Final private Vector3f forwards;
	@Shadow @Final private Vector3f up;
	@Shadow @Final private Vector3f left;
	@Shadow private float xRot;
	@Shadow private float yRot;
	@Shadow protected abstract void setPosition(Vec3 vec3);
	@Shadow protected abstract void move(float f, float g, float h);
	@Shadow private float getMaxZoom(float f) { return f; }

	@Inject(method = "setup", at = @At("TAIL"))
	private void tod5$gravedad(Level level, Entity entity, boolean detached, boolean mirrored, float parcial, CallbackInfo ci) {
		if (entity != Minecraft.getInstance().player || !(entity instanceof LivingEntity le) || entity.isPassenger()) return;
		if (GravedadCliente.normal()) return;
		this.rotation.set(GravedadCliente.camara(this.yRot, this.xRot, parcial));
		FORWARDS.rotate(this.rotation, this.forwards);
		UP.rotate(this.rotation, this.up);
		LEFT.rotate(this.rotation, this.left);
		this.setPosition(GravedadCliente.ojos(le, parcial));
		if (detached) {   // tercera persona: se aleja desde los ojos nuevos
			float d = le.getScale() * (float) le.getAttributeValue(Attributes.CAMERA_DISTANCE);
			this.move(-this.getMaxZoom(d), 0.0F, 0.0F);
		}
	}
}

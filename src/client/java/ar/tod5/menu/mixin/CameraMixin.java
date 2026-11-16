package ar.tod5.menu.mixin;

import ar.tod5.menu.GravedadCliente;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gravedad dada vuelta: la camara gira 180 grados (de a poco) y baja a la altura de los ojos cabeza abajo. */
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
	@Shadow private Vec3 position;
	@Shadow protected abstract void setPosition(Vec3 vec3);
	@Shadow private float eyeHeight;
	@Shadow private float eyeHeightOld;
	@Shadow protected abstract void move(float f, float g, float h);
	@Shadow private float getMaxZoom(float f) { return f; }

	@Inject(method = "setup", at = @At("TAIL"))
	private void tod5$gravedad(Level level, Entity entity, boolean detached, boolean mirrored, float parcial, CallbackInfo ci) {
		if (entity != Minecraft.getInstance().player || !(entity instanceof LivingEntity le) || entity.isPassenger()) return;
		float t = GravedadCliente.giro(parcial);
		if (t <= 0.0001f) return;
		float g = (float) (Math.PI / 180.0);
		this.rotation.rotationYXZ((float) Math.PI - this.yRot * g, -this.xRot * g, (float) Math.PI * t);
		FORWARDS.rotate(this.rotation, this.forwards);
		UP.rotate(this.rotation, this.up);
		LEFT.rotate(this.rotation, this.left);
		// los ojos cabeza abajo: cerca del borde de abajo de la caja (en el techo)
		Vec3 ojos = new Vec3(Mth.lerp(parcial, entity.xo, entity.getX()),
			Mth.lerp(parcial, entity.yo, entity.getY()) + Mth.lerp(parcial, this.eyeHeightOld, this.eyeHeight) + GravedadCliente.bajarOjos(le, parcial),
			Mth.lerp(parcial, entity.zo, entity.getZ()));
		this.setPosition(ojos);
		if (detached) {   // tercera persona: se aleja desde los ojos nuevos (si no, se metia en el techo)
			float d = le.getScale() * (float) le.getAttributeValue(Attributes.CAMERA_DISTANCE);
			this.move(-this.getMaxZoom(d), 0.0F, 0.0F);
		}
	}
}

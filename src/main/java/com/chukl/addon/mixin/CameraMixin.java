package com.chukl.addon.mixin;

import com.chukl.addon.modules.VulxtsFreeLook;
import com.chukl.addon.modules.VulxtsFreecam;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPos(Vec3d pos);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    /** Freecam: replace the camera position and rotation after vanilla has set them up. */
    @Inject(method = "update", at = @At("TAIL"))
    private void chukl$freecam(World area, Entity focusedEntity, boolean thirdPerson, boolean inverseView, float tickProgress, CallbackInfo ci) {
        VulxtsFreecam freecam = VulxtsFreecam.get();
        if (freecam != null && freecam.isDetached()) {
            this.setRotation(freecam.getInterpolatedYaw(tickProgress), freecam.getInterpolatedPitch(tickProgress));
            this.setPos(freecam.getInterpolatedPos(tickProgress));
        }
    }

    /** FreeLook: the camera uses its own yaw/pitch instead of the player's. */
    @ModifyArgs(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;setRotation(FF)V"))
    private void chukl$freeLookRotation(Args args) {
        VulxtsFreeLook freeLook = VulxtsFreeLook.get();
        if (freeLook != null && freeLook.isLooking()) {
            VulxtsFreecam freecam = VulxtsFreecam.get();
            if (freecam == null || !freecam.isDetached()) {
                args.set(0, freeLook.getCameraYaw());
                args.set(1, freeLook.getCameraPitch());
            }
        }
    }

    /** FreeLook "through walls": the third-person camera ignores wall collision. */
    @Inject(method = "clipToSpace", at = @At("HEAD"), cancellable = true)
    private void chukl$freeLookThroughWalls(float distance, CallbackInfoReturnable<Float> cir) {
        VulxtsFreeLook freeLook = VulxtsFreeLook.get();
        if (freeLook != null && freeLook.seeThroughWalls()) {
            VulxtsFreecam freecam = VulxtsFreecam.get();
            if (freecam == null || !freecam.isDetached()) cir.setReturnValue(distance);
        }
    }
}

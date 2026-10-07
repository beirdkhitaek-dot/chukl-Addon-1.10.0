package com.chukl.addon.mixin;

import com.chukl.addon.modules.VulxtsFreeLook;
import com.chukl.addon.modules.VulxtsFreecam;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class EntityMixin {
    /** Freecam: keep your own body visible to yourself while detached. */
    @Inject(method = "isInvisibleTo", at = @At("HEAD"), cancellable = true)
    private void chukl$freecamSeeOwnBody(PlayerEntity viewer, CallbackInfoReturnable<Boolean> cir) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && (Object) this == mc.player && viewer == mc.player) {
            VulxtsFreecam freecam = VulxtsFreecam.get();
            if (freecam != null && freecam.isDetached() && freecam.showPlayerModel()) cir.setReturnValue(false);
        }
    }

    /** FreeLook camera mode: the mouse rotates the camera instead of the player. */
    @Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
    private void chukl$freeLookTurn(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if ((Object) this == mc.player) {
            VulxtsFreeLook freeLook = VulxtsFreeLook.get();
            if (freeLook != null && freeLook.cameraMode()) {
                freeLook.addCameraLook(cursorDeltaX, cursorDeltaY);
                ci.cancel();
            }
        }
    }
}

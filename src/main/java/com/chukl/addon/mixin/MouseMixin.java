package com.chukl.addon.mixin;

import com.chukl.addon.modules.VulxtsFreecam;
import net.minecraft.client.Mouse;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public abstract class MouseMixin {
    @Shadow
    private double cursorDeltaX;

    @Shadow
    private double cursorDeltaY;

    /** Freecam: the mouse turns the camera instead of the player. */
    @Inject(method = "updateMouse", at = @At("HEAD"), cancellable = true)
    private void chukl$freecamMouse(double timeDelta, CallbackInfo ci) {
        VulxtsFreecam freecam = VulxtsFreecam.get();
        if (freecam == null || !freecam.isDetached()) return;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen != null) return;

        double sensitivity = mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
        double factor = sensitivity * sensitivity * sensitivity * 8.0;
        double dx = this.cursorDeltaX * factor * freecam.getLookSensitivity();
        double dy = this.cursorDeltaY * factor * freecam.getLookSensitivity();

        freecam.setRotation(freecam.getCurrentYaw() + (float) dx * 0.15F, freecam.getCurrentPitch() + (float) dy * 0.15F);

        this.cursorDeltaX = 0.0;
        this.cursorDeltaY = 0.0;
        ci.cancel();
    }

    /** Freecam: scroll wheel changes the fly speed. */
    @Inject(method = "onMouseScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void chukl$freecamScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        VulxtsFreecam freecam = VulxtsFreecam.get();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (freecam != null && mc.currentScreen == null && window == mc.getWindow().getHandle()
            && freecam.adjustSpeedFromScroll(vertical)) {
            ci.cancel();
        }
    }
}

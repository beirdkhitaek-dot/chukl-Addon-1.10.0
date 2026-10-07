package com.chukl.addon.mixin;

import com.chukl.addon.modules.VulxtsFreecam;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public class KeyboardInputMixin {
    /** Freecam: the keys fly the camera, so put the cached walking input back on the body. */
    @Inject(method = "tick", at = @At("TAIL"))
    private void chukl$freecamReapplyBodyInput(CallbackInfo ci) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && mc.player.input == (Object) this) {
            VulxtsFreecam freecam = VulxtsFreecam.get();
            if (freecam != null && freecam.isDetached()) VulxtsFreecam.reapplyBodyInput(mc);
        }
    }
}

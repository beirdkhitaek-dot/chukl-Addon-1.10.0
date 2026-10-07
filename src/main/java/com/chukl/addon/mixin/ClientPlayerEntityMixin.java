package com.chukl.addon.mixin;

import com.chukl.addon.modules.VulxtsFreecam;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin {
    @Inject(method = "tickMovement", at = @At("HEAD"))
    private void chukl$freecamBeforeTickMovement(CallbackInfo ci) {
        chukl$reapply();
    }

    @Inject(method = "sendMovementPackets", at = @At("HEAD"))
    private void chukl$freecamBeforeSendPackets(CallbackInfo ci) {
        chukl$reapply();
    }

    private void chukl$reapply() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == (Object) this) {
            VulxtsFreecam freecam = VulxtsFreecam.get();
            if (freecam != null && freecam.isDetached()) VulxtsFreecam.reapplyBodyInput(mc);
        }
    }
}

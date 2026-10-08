package com.chukl.addon.modules.water;

import com.chukl.addon.modules.VulxtsFreecam;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

/** Stand-in for Water's Freecam: tells the tracers where to start from. */
final class Freecam {
    private Freecam() {
    }

    /** While the camera is detached, tracers start at your body; otherwise at the camera. */
    static Vec3d resolveTracerOrigin(Vec3d camPos, float tickDelta) {
        VulxtsFreecam freecam = VulxtsFreecam.get();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (freecam != null && freecam.isDetached() && mc.player != null) {
            return mc.player.getLerpedPos(tickDelta).add(0.0, mc.player.getStandingEyeHeight(), 0.0);
        }
        return camPos;
    }
}

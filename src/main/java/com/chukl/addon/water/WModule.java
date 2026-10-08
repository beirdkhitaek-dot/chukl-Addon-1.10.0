package com.chukl.addon.water;

import com.chukl.addon.ChuklAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.network.packet.Packet;

import java.util.HashSet;
import java.util.Set;

/**
 * Base class that lets Water Client modules run inside Meteor. It keeps Water's overridable hooks
 * (onEnable, onDisable, onTick, onRender, onPacketReceive, onPacketSend) and its addSetting() call.
 */
public abstract class WModule extends Module {
    protected static final MinecraftClient mc = MinecraftClient.getInstance();

    private final Set<String> usedIds = new HashSet<>();

    public WModule(String name, Category category) {
        super(ChuklAddon.RENDER_CATEGORY, "water-" + Setting.kebab(name), name + " (from Water Client)");
    }

    public void addSetting(Setting<?> setting) {
        String id = Setting.kebab(setting.getName());
        String unique = id;
        int n = 2;
        while (!usedIds.add(unique)) unique = id + "-" + n++;
        setting.attach(settings.getDefaultGroup(), unique);
    }

    public boolean isEnabled() {
        return isActive();
    }

    public void setEnabled(boolean enabled) {
        if (enabled != isActive()) toggle();
    }

    // ----- Water hooks, override these -----

    public void onEnable() {
    }

    public void onDisable() {
    }

    public void onTick() {
    }

    public void onRender(MatrixStack matrices, float tickDelta) {
    }

    public void onPacketReceive(Packet<?> packet) {
    }

    public boolean onPacketSend(Packet<?> packet) {
        return false;
    }

    // ----- Meteor wiring -----

    @Override
    public void onActivate() {
        onEnable();
    }

    @Override
    public void onDeactivate() {
        onDisable();
    }

    @EventHandler
    private void chukl$tick(TickEvent.Post event) {
        onTick();
    }

    @EventHandler
    private void chukl$render(Render3DEvent event) {
        RenderUtils.begin(event);
        try {
            onRender(event.matrices, event.tickDelta);
        } finally {
            RenderUtils.end();
        }
    }

    @EventHandler
    private void chukl$receive(PacketEvent.Receive event) {
        onPacketReceive(event.packet);
    }

    @EventHandler
    private void chukl$send(PacketEvent.Send event) {
        if (onPacketSend(event.packet)) event.cancel();
    }
}

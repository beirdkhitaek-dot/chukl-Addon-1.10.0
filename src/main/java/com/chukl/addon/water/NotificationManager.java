package com.chukl.addon.water;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

/** Water's pop-up notifications become a chat message. */
public final class NotificationManager {
    public static final NotificationManager INSTANCE = new NotificationManager();

    private NotificationManager() {
    }

    public void push(String title, String body, ItemStack icon, int rgb) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud == null) return;
        mc.inGameHud.getChatHud().addMessage(Text.literal("§b[Chukl Render] §f" + title + " §7" + body));
    }
}

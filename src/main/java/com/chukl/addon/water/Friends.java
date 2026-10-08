package com.chukl.addon.water;

import java.awt.Color;

/** Water's Friends lookups, answered from Meteor's friends list. */
public final class Friends {
    private Friends() {
    }

    public static boolean isEspColor() {
        return true;
    }

    public static boolean isFriend(String name) {
        return name != null && meteordevelopment.meteorclient.systems.friends.Friends.get().get(name) != null;
    }

    public static Color getColor() {
        return new Color(0, 255, 255, 255);
    }
}

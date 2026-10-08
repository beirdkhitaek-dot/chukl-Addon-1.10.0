package com.chukl.addon.water;

/** Stand-in for Water's client settings class; only the values the ported modules read. */
public final class WaterPlus {
    private WaterPlus() {
    }

    public static boolean notificationsEnabled() {
        return false;
    }

    public static float tracerLineWidth() {
        return 1.0F;
    }

    public static int getBackgroundARGB() {
        return 0xCC101018;
    }

    public static int getAccentARGB() {
        return 0xFF00C8FF;
    }
}

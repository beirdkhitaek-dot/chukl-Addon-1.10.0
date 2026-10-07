package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.option.Perspective;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

/**
 * Vulxts' FreeLook, ported to Meteor: look around in third person without turning your player (or the other
 * way round).
 *
 *  - Player mode: the mouse turns the player, the arrow keys orbit the camera.
 *  - Camera mode: the mouse orbits the camera, the arrow keys turn the player.
 */
public class VulxtsFreeLook extends Module {
    public enum Mode {
        Player,
        Camera
    }

    private static VulxtsFreeLook instance;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode").description("Which one the mouse rotates.")
        .defaultValue(Mode.Player).build());

    private final Setting<Boolean> togglePerspective = sgGeneral.add(new BoolSetting.Builder()
        .name("toggle-perspective").description("Switch to third person when turned on, and back when turned off.")
        .defaultValue(true).build());

    private final Setting<Boolean> throughWalls = sgGeneral.add(new BoolSetting.Builder()
        .name("through-walls").description("The third-person camera ignores wall collision.")
        .defaultValue(false).build());

    private final Setting<Double> sensitivity = sgGeneral.add(new DoubleSetting.Builder()
        .name("camera-sensitivity").description("How fast the camera moves in Camera mode (higher = slower).")
        .defaultValue(8.0).range(0.1, 10).sliderRange(0.1, 10)
        .visible(() -> mode.get() == Mode.Camera).build());

    private final Setting<Boolean> arrows = sgGeneral.add(new BoolSetting.Builder()
        .name("arrows-control-opposite").description("Use the arrow keys to rotate the other one.")
        .defaultValue(true).build());

    private final Setting<Double> arrowSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("arrow-speed").description("Rotation speed with the arrow keys.")
        .defaultValue(4.0).range(0, 10).sliderRange(0, 10)
        .visible(arrows::get).build());

    private float cameraYaw;
    private float cameraPitch;
    private Perspective perspectiveBefore;

    public VulxtsFreeLook() {
        super(ChuklAddon.CATEGORY, "vulxts-freelook", "Vulxts' FreeLook: more rotation options in third person.");
        instance = this;
    }

    public static VulxtsFreeLook get() {
        return instance;
    }

    @Override
    public void onActivate() {
        if (mc.player == null) return;

        cameraYaw = mc.player.getYaw();
        cameraPitch = mc.player.getPitch();
        perspectiveBefore = mc.options.getPerspective();

        if (togglePerspective.get() && perspectiveBefore != Perspective.THIRD_PERSON_BACK) {
            mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
        }
    }

    @Override
    public void onDeactivate() {
        if (perspectiveBefore != null && togglePerspective.get() && mc.options.getPerspective() != perspectiveBefore) {
            mc.options.setPerspective(perspectiveBefore);
        }
        perspectiveBefore = null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (arrows.get() && mc.currentScreen == null) {
            long win = mc.getWindow().getHandle();
            boolean left = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_LEFT) == GLFW.GLFW_PRESS;
            boolean right = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_RIGHT) == GLFW.GLFW_PRESS;
            boolean up = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_UP) == GLFW.GLFW_PRESS;
            boolean down = GLFW.glfwGetKey(win, GLFW.GLFW_KEY_DOWN) == GLFW.GLFW_PRESS;

            float step = (float) (arrowSpeed.get() * 2.0) * 0.5F; // total degrees per tick
            float dYaw = (right ? step : 0) - (left ? step : 0);
            float dPitch = (down ? step : 0) - (up ? step : 0);

            if (mode.get() == Mode.Player) {
                cameraYaw += dYaw;
                cameraPitch += dPitch;
            } else {
                mc.player.setYaw(mc.player.getYaw() + dYaw);
                mc.player.setPitch(mc.player.getPitch() + dPitch);
            }
        }

        mc.player.setPitch(MathHelper.clamp(mc.player.getPitch(), -90.0F, 90.0F));
        cameraPitch = MathHelper.clamp(cameraPitch, -90.0F, 90.0F);
    }

    // ---------- accessors for the mixins ----------

    /** True while the module is on and the camera uses its own rotation. */
    public boolean isLooking() {
        return isActive() && mc.player != null;
    }

    public boolean seeThroughWalls() {
        return isLooking() && throughWalls.get();
    }

    public boolean cameraMode() {
        return isLooking() && mode.get() == Mode.Camera;
    }

    public void addCameraLook(double deltaX, double deltaY) {
        float sens = sensitivity.get().floatValue();
        if (sens <= 0.0F) sens = 1.0F;

        cameraYaw += (float) (deltaX / sens);
        cameraPitch = MathHelper.clamp(cameraPitch + (float) (deltaY / sens), -90.0F, 90.0F);
    }

    public float getCameraYaw() {
        return cameraYaw;
    }

    public float getCameraPitch() {
        return cameraPitch;
    }
}

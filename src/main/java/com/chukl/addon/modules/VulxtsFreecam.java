package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import com.chukl.addon.mixin.InputAccessor;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

/**
 * Vulxts' Freecam, ported to Meteor.
 *
 * Detaches the camera so WASD/Space/Shift fly it around while your body keeps doing whatever you were doing
 * when you turned it on (walking, sneaking...). The mouse turns the camera, the scroll wheel changes the speed,
 * and chunks keep loading around you.
 */
public class VulxtsFreecam extends Module {
    private static VulxtsFreecam instance;
    private static final double SCROLL_SPEED_STEP = 0.1;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed").description("Camera fly speed. The scroll wheel changes it too.")
        .defaultValue(1.0).range(0.1, 50).sliderRange(0.1, 10).build());

    private final Setting<Double> verticalMultiplier = sgGeneral.add(new DoubleSetting.Builder()
        .name("vertical-speed").description("Up/down speed multiplier.")
        .defaultValue(1.0).range(0.2, 5).sliderRange(0.2, 5).build());

    private final Setting<Boolean> smoothing = sgGeneral.add(new BoolSetting.Builder()
        .name("smoothing").description("Ease camera movement.")
        .defaultValue(true).build());

    private final Setting<Boolean> showPlayerModel = sgGeneral.add(new BoolSetting.Builder()
        .name("show-own-body").description("Render your body while detached (switches to third person if needed).")
        .defaultValue(true).build());

    private final Setting<Double> lookSensitivity = sgGeneral.add(new DoubleSetting.Builder()
        .name("look-sensitivity").description("Camera look sensitivity.")
        .defaultValue(0.5).range(0.1, 2).sliderRange(0.1, 2).build());

    private final Setting<Integer> chunkDistance = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-distance").description("Chunks to keep loaded while the camera is detached.")
        .defaultValue(12).range(2, 32).sliderRange(2, 32).build());

    private double currentX, currentY, currentZ;
    private double prevX, prevY, prevZ;
    private float currentYaw, currentPitch, prevYaw, prevPitch;
    private boolean savedFlying;
    private boolean savedChunkCulling;
    private Perspective perspectiveBefore;
    private boolean switchedPerspective;
    private ChunkPos lastSyncedChunk;
    private int lastSyncedLoadDistance = Integer.MIN_VALUE;
    private boolean activationPending;
    private boolean detached;

    // Movement keys held when the module was enabled; the body keeps doing this while you fly the camera
    private boolean latchedForward, latchedBack, latchedLeft, latchedRight, latchedJump, latchedSneak, latchedSprint;

    public VulxtsFreecam() {
        super(ChuklAddon.CATEGORY, "vulxts-freecam",
            "Vulxts' Freecam: detached camera (WASD = fly). Your body keeps walking.");
        instance = this;
    }

    public static VulxtsFreecam get() {
        return instance;
    }

    // ---------- lifecycle ----------

    @Override
    public void onActivate() {
        clearLatches();
        if (mc.player != null) captureLatches();
        activationPending = true;
        detached = false;
    }

    @Override
    public void onDeactivate() {
        clearLatches();
        activationPending = false;
        detached = false;

        if (switchedPerspective && perspectiveBefore != null) {
            mc.options.setPerspective(perspectiveBefore);
        }
        switchedPerspective = false;

        mc.chunkCullingEnabled = savedChunkCulling;
        if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();

        restoreChunkLoading();
        if (mc.player != null) mc.player.getAbilities().flying = savedFlying;
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        if (isActive()) toggle();
    }

    private void completeActivation() {
        ClientPlayerEntity player = mc.player;
        if (!activationPending || player == null || mc.world == null) return;

        currentX = prevX = player.getX();
        currentY = prevY = player.getY() + player.getEyeHeight(player.getPose());
        currentZ = prevZ = player.getZ();
        currentYaw = prevYaw = player.getYaw();
        currentPitch = prevPitch = player.getPitch();
        savedFlying = player.getAbilities().flying;

        switchedPerspective = false;
        if (showPlayerModel.get()) {
            perspectiveBefore = mc.options.getPerspective();
            if (perspectiveBefore.isFirstPerson()) {
                mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);
                switchedPerspective = true;
            }
        }

        activationPending = false;
        detached = true;
        lastSyncedChunk = null;
        lastSyncedLoadDistance = Integer.MIN_VALUE;

        // Show chunks outside the normal culling frustum while the camera is somewhere else
        savedChunkCulling = mc.chunkCullingEnabled;
        mc.chunkCullingEnabled = false;

        syncChunkLoading();
        reapplyBodyInput(mc);
    }

    // ---------- input latches ----------

    private void captureLatches() {
        GameOptions o = mc.options;
        ClientPlayerEntity p = mc.player;
        PlayerInput in = p != null ? p.input.playerInput : PlayerInput.DEFAULT;

        latchedForward = o.forwardKey.isPressed() || in.forward();
        latchedBack = o.backKey.isPressed() || in.backward();
        latchedLeft = o.leftKey.isPressed() || in.left();
        latchedRight = o.rightKey.isPressed() || in.right();
        latchedJump = o.jumpKey.isPressed() || in.jump();
        latchedSneak = o.sneakKey.isPressed() || in.sneak() || (p != null && p.isSneaking());
        latchedSprint = o.sprintKey.isPressed() || in.sprint();
    }

    private void clearLatches() {
        latchedForward = latchedBack = latchedLeft = latchedRight = false;
        latchedJump = latchedSneak = latchedSprint = false;
    }

    /** Puts the cached walking input back on the player (called from the input/player mixins). */
    public static void reapplyBodyInput(MinecraftClient mc) {
        VulxtsFreecam f = instance;
        if (f == null || !f.isDetached() || mc.player == null) return;

        ClientPlayerEntity p = mc.player;
        InputAccessor acc = (InputAccessor) p.input;
        acc.chukl$setPlayerInput(new PlayerInput(f.latchedForward, f.latchedBack, f.latchedLeft, f.latchedRight,
            f.latchedJump, f.latchedSneak, f.latchedSprint));

        float sideways = (f.latchedLeft ? 1.0F : 0.0F) - (f.latchedRight ? 1.0F : 0.0F);
        float forward = (f.latchedForward ? 1.0F : 0.0F) - (f.latchedBack ? 1.0F : 0.0F);
        acc.chukl$setMovementVector(new Vec2f(sideways, forward).normalize());
        p.setSneaking(f.latchedSneak);
    }

    // ---------- tick ----------

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        completeActivation();
        if (!detached || mc.player == null) return;

        prevX = currentX;
        prevY = currentY;
        prevZ = currentZ;
        prevYaw = currentYaw;
        prevPitch = currentPitch;

        float spd = speed.get().floatValue();
        float vert = verticalMultiplier.get().floatValue();
        float ease = smoothing.get() ? 0.5F : 1.0F;

        GameOptions o = mc.options;
        double forward = 0, side = 0, up = 0;
        if (o.forwardKey.isPressed()) forward++;
        if (o.backKey.isPressed()) forward--;
        if (o.leftKey.isPressed()) side++;
        if (o.rightKey.isPressed()) side--;
        if (o.jumpKey.isPressed()) up++;
        if (o.sneakKey.isPressed()) up--;

        // Creative flying would fight the latched body input
        if (mc.player.getAbilities().creativeMode) mc.player.getAbilities().flying = false;

        double yawRad = Math.toRadians(currentYaw);
        double dx = -Math.sin(yawRad) * forward * spd + Math.cos(yawRad) * side * spd;
        double dz = Math.cos(yawRad) * forward * spd + Math.sin(yawRad) * side * spd;
        double dy = up * spd * vert;

        currentX += dx * ease;
        currentY += dy * ease;
        currentZ += dz * ease;

        syncChunkLoading();
    }

    // ---------- chunk loading ----------

    private void syncChunkLoading() {
        if (!detached || mc.world == null || mc.player == null) return;

        ClientChunkManager cm = mc.world.getChunkManager();
        ChunkPos cp = mc.player.getChunkPos();
        int wanted = MathHelper.clamp(chunkDistance.get(), 2, 32);
        int load = Math.min(32, Math.max(wanted, mc.options.getClampedViewDistance()));

        if (load != lastSyncedLoadDistance) {
            cm.updateLoadDistance(load);
            lastSyncedLoadDistance = load;
        }

        if (lastSyncedChunk == null || cp.x != lastSyncedChunk.x || cp.z != lastSyncedChunk.z) {
            cm.setChunkMapCenter(cp.x, cp.z);
            lastSyncedChunk = cp;
            if (mc.worldRenderer != null) mc.worldRenderer.scheduleTerrainUpdate();
        }
    }

    private void restoreChunkLoading() {
        lastSyncedChunk = null;
        lastSyncedLoadDistance = Integer.MIN_VALUE;
        if (mc.world == null) return;

        ClientChunkManager cm = mc.world.getChunkManager();
        if (mc.player != null) {
            ChunkPos cp = mc.player.getChunkPos();
            cm.setChunkMapCenter(cp.x, cp.z);
        }
        cm.updateLoadDistance(mc.options.getClampedViewDistance());
        if (mc.worldRenderer != null) mc.worldRenderer.scheduleTerrainUpdate();
    }

    // ---------- accessors for the mixins ----------

    /** True only while the camera is actually detached (not just enabled and waiting for a world). */
    public boolean isDetached() {
        return isActive() && detached;
    }

    public boolean showPlayerModel() {
        return showPlayerModel.get();
    }

    public boolean adjustSpeedFromScroll(double scroll) {
        if (!isDetached() || scroll == 0.0) return false;
        double next = speed.get() + Math.copySign(SCROLL_SPEED_STEP, scroll);
        speed.set(Math.max(0.1, Math.min(50.0, next)));
        return true;
    }

    public Vec3d getInterpolatedPos(float t) {
        return new Vec3d(MathHelper.lerp((double) t, prevX, currentX),
            MathHelper.lerp((double) t, prevY, currentY),
            MathHelper.lerp((double) t, prevZ, currentZ));
    }

    public float getInterpolatedYaw(float t) {
        return MathHelper.lerp(t, prevYaw, currentYaw);
    }

    public float getInterpolatedPitch(float t) {
        return MathHelper.lerp(t, prevPitch, currentPitch);
    }

    public void setRotation(float yaw, float pitch) {
        currentYaw = yaw;
        currentPitch = MathHelper.clamp(pitch, -90.0F, 90.0F);
    }

    public float getCurrentYaw() {
        return currentYaw;
    }

    public float getCurrentPitch() {
        return currentPitch;
    }

    public float getLookSensitivity() {
        return lookSensitivity.get().floatValue();
    }
}

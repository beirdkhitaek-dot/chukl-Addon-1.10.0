package com.chukl.addon.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/** Simple line-of-sight checks from the player's eyes, used when "see-through-walls" is off. */
public final class LineOfSight {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private LineOfSight() {}

    private static BlockHitResult cast(Vec3d target) {
        return mc.world.raycast(new RaycastContext(
            mc.player.getEyePos(),
            target,
            RaycastContext.ShapeType.OUTLINE,
            RaycastContext.FluidHandling.NONE,
            mc.player
        ));
    }

    /** True if nothing solid is between your eyes and the center of this block (the block itself doesn't count). */
    public static boolean canSeeBlock(BlockPos pos) {
        if (mc.world == null || mc.player == null) return false;

        BlockHitResult hit = cast(Vec3d.ofCenter(pos));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(pos);
    }

    /** True if any of the box's center or inset corner points can be seen from your eyes. */
    public static boolean canSeeBox(Box box) {
        if (mc.world == null || mc.player == null) return false;

        double inset = 0.5;
        double x1 = box.minX + inset, x2 = box.maxX - inset;
        double y1 = box.minY + inset, y2 = box.maxY - inset;
        double z1 = box.minZ + inset, z2 = box.maxZ - inset;

        Vec3d[] points = {
            new Vec3d((box.minX + box.maxX) / 2.0, (box.minY + box.maxY) / 2.0, (box.minZ + box.maxZ) / 2.0),
            new Vec3d(x1, y1, z1), new Vec3d(x2, y1, z1), new Vec3d(x1, y1, z2), new Vec3d(x2, y1, z2),
            new Vec3d(x1, y2, z1), new Vec3d(x2, y2, z1), new Vec3d(x1, y2, z2), new Vec3d(x2, y2, z2)
        };

        for (Vec3d point : points) {
            if (cast(point).getType() == HitResult.Type.MISS) return true;
        }
        return false;
    }
}

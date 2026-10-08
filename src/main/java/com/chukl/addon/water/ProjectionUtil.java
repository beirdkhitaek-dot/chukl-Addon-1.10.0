package com.chukl.addon.water;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Stand-in for Water's ProjectionUtil: turns a world position into a screen position.
 * It uses the camera rotation and your FOV instead of the render matrices, so it is close but not pixel exact.
 */
public final class ProjectionUtil {
    public static Matrix4f modelViewMatrix = new Matrix4f();
    public static Matrix4f projectionMatrix = new Matrix4f();

    private ProjectionUtil() {
    }

    public static final class ScreenProjection {
        public boolean visible;
        /** Screen x (GUI pixels). */
        public double doubleVal;
        /** Screen y (GUI pixels). */
        public double doubleVal2;
        /** Depth in 0..1 (0 = near, 1 = far). */
        public double doubleVal3;
        /** Distance in front of the camera. */
        public double doubleVal4;
    }

    public static boolean projectToScreen(Matrix4f modelView, Matrix4f projection, double wx, double wy, double wz, ScreenProjection out) {
        MinecraftClient mc = MinecraftClient.getInstance();
        out.visible = false;
        if (mc.gameRenderer == null || mc.getWindow() == null) return false;

        Camera cam = RenderUtils.getCamera();
        var camPos = RenderUtils.getCameraPos(cam);

        Vector3f v = new Vector3f((float) (wx - camPos.x), (float) (wy - camPos.y), (float) (wz - camPos.z));
        new Quaternionf(cam.getRotation()).conjugate().transform(v);

        double depth = -v.z; // the camera looks along -Z in view space
        if (depth <= 0.05) return false;

        double fov = Math.toRadians(mc.options.getFov().getValue());
        double tan = Math.tan(fov / 2.0);
        double aspect = (double) mc.getWindow().getFramebufferWidth() / Math.max(1, mc.getWindow().getFramebufferHeight());

        double ndcX = v.x / depth / (tan * aspect);
        double ndcY = v.y / depth / tan;

        out.doubleVal = (ndcX * 0.5 + 0.5) * mc.getWindow().getScaledWidth();
        out.doubleVal2 = (0.5 - ndcY * 0.5) * mc.getWindow().getScaledHeight();
        out.doubleVal3 = Math.min(1.0, depth / 256.0);
        out.doubleVal4 = depth;
        out.visible = Math.abs(ndcX) <= 1.5 && Math.abs(ndcY) <= 1.5;
        return true;
    }
}

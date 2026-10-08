package com.chukl.addon.water;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;

import java.awt.Color;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Water's RenderUtils, rebuilt on top of Meteor's renderer.
 *
 * Water modules give coordinates relative to the camera; the batches add the camera position back and draw
 * through Meteor's Render3DEvent renderer. Batches only draw while a render event is running (that is
 * handled by WModule), and everything is drawn at flush time.
 */
public final class RenderUtils {
    private static Render3DEvent current;
    private static Method cameraPosMethod;

    private RenderUtils() {
    }

    static void begin(Render3DEvent event) {
        current = event;
    }

    static void end() {
        current = null;
    }

    // ---------- batches ----------

    public static PersistentBatch createPersistentBatch() {
        return new PersistentBatch();
    }

    public static PersistentBatch createStorageBoxBatch() {
        return new PersistentBatch();
    }

    public static PersistentBatch createTracerBatch() {
        return new PersistentBatch();
    }

    public static WorldBatch beginWorldBatch(MatrixStack matrices) {
        return new WorldBatch();
    }

    public static void restoreWorldDepthState() {
    }

    public static boolean isWorldBoxVisible(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return true;
    }

    public static void renderFilledBox(MatrixStack m, double x1, double y1, double z1, double x2, double y2, double z2, Color color) {
        WorldBatch b = beginWorldBatch(m);
        b.renderFilledBox(x1, y1, z1, x2, y2, z2, color);
        b.flush();
    }

    public static void renderOutlineBox(MatrixStack m, double x1, double y1, double z1, double x2, double y2, double z2, Color color) {
        WorldBatch b = beginWorldBatch(m);
        b.renderOutlineBox(x1, y1, z1, x2, y2, z2, color);
        b.flush();
    }

    public static void renderLine(MatrixStack m, Color color, Vec3d start, Vec3d end) {
        renderLine(m, color, start, end, 1.0F);
    }

    public static void renderLine(MatrixStack m, Color color, Vec3d start, Vec3d end, float lw) {
        WorldBatch b = beginWorldBatch(m);
        b.renderLine(color, start, end, lw);
        b.flush();
    }

    // ---------- camera helpers (same math as Water) ----------

    public static Camera getCamera() {
        return MinecraftClient.getInstance().gameRenderer.getCamera();
    }

    public static Vec3d getCameraPos(Camera camera) {
        if (cameraPosMethod == null) {
            for (Method m : Camera.class.getMethods()) {
                if (m.getReturnType() == Vec3d.class && m.getParameterCount() == 0) {
                    cameraPosMethod = m;
                    break;
                }
            }
        }
        try {
            return (Vec3d) cameraPosMethod.invoke(camera);
        } catch (Exception e) {
            return MinecraftClient.getInstance().player.getCameraPosVec(1.0F);
        }
    }

    public static Vec3d getCameraForward(Camera camera) {
        return new Vec3d(0.0, 0.0, 1.0).rotateX(-((float) Math.toRadians(camera.getPitch())))
            .rotateY(-((float) Math.toRadians(camera.getYaw()))).normalize();
    }

    public static Vec3d getCameraRight(Camera camera) {
        return new Vec3d(1.0, 0.0, 0.0).rotateY(-((float) Math.toRadians(camera.getYaw()))).normalize();
    }

    public static Vec3d getCameraUp(Vec3d forward, Vec3d right) {
        return forward.crossProduct(right).normalize();
    }

    public static Vec3d getSpreadTracerEnd(double tx, double ty, double tz, Vec3d fwd, Vec3d right, Vec3d up, double endDist, double behindSpread) {
        double r = tx * right.x + ty * right.y + tz * right.z;
        double u = tx * up.x + ty * up.y + tz * up.z;
        double f = tx * fwd.x + ty * fwd.y + tz * fwd.z;
        double sf = Math.max(Math.abs(f), 0.25);
        double pr = r / sf;
        double pu = u / sf;
        if (f <= 0.0) {
            double pl = Math.hypot(pr, pu);
            if (pl < behindSpread) {
                if (pl < 1.0E-4) {
                    pr = behindSpread;
                    pu = 0.0;
                } else {
                    double s = behindSpread / pl;
                    pr *= s;
                    pu *= s;
                }
            }
        }
        return fwd.add(right.multiply(pr)).add(up.multiply(pu)).normalize().multiply(endDist);
    }

    public static Vec3d getSpreadTracerEnd(Vec3d t, Vec3d fwd, Vec3d right, Vec3d up, double endDist, double behindSpread) {
        return getSpreadTracerEnd(t.x, t.y, t.z, fwd, right, up, endDist, behindSpread);
    }

    /** Water clamps off-screen tracer ends to the screen edge; here the target is used as it is. */
    public static Vec3d getClampedTracerEnd(Vec3d camRelTarget, Vec3d worldTarget, Vec3d fwd, Vec3d right, Vec3d up, double projDist) {
        return camRelTarget;
    }

    // ---------- drawing ----------

    private static meteordevelopment.meteorclient.utils.render.color.Color mc(Color c) {
        return new meteordevelopment.meteorclient.utils.render.color.Color(c.getRed(), c.getGreen(), c.getBlue(), c.getAlpha());
    }

    private enum Kind {FILL, OUTLINE, LINE, GLASS}

    private record Op(Kind kind, double x1, double y1, double z1, double x2, double y2, double z2, Color color) {}

    private static void draw(List<Op> ops) {
        Render3DEvent e = current;
        if (e == null || ops.isEmpty()) return;

        Vec3d cam = getCameraPos(getCamera());
        for (Op op : ops) {
            double x1 = op.x1 + cam.x, y1 = op.y1 + cam.y, z1 = op.z1 + cam.z;
            double x2 = op.x2 + cam.x, y2 = op.y2 + cam.y, z2 = op.z2 + cam.z;
            var color = mc(op.color);

            switch (op.kind) {
                case FILL, GLASS -> e.renderer.box(x1, y1, z1, x2, y2, z2, color, color, ShapeMode.Sides, 0);
                case OUTLINE -> e.renderer.box(x1, y1, z1, x2, y2, z2, color, color, ShapeMode.Lines, 0);
                case LINE -> e.renderer.line(x1, y1, z1, x2, y2, z2, color);
            }
        }
    }

    public static final class WorldBatch {
        private final List<Op> ops = new ArrayList<>();

        private WorldBatch() {
        }

        public void renderFilledBox(double x1, double y1, double z1, double x2, double y2, double z2, Color color) {
            ops.add(new Op(Kind.FILL, x1, y1, z1, x2, y2, z2, color));
        }

        public void renderFilledBoxTriangles(double x1, double y1, double z1, double x2, double y2, double z2, Color color) {
            renderFilledBox(x1, y1, z1, x2, y2, z2, color);
        }

        public void renderOutlineBox(double x1, double y1, double z1, double x2, double y2, double z2, Color color) {
            ops.add(new Op(Kind.OUTLINE, x1, y1, z1, x2, y2, z2, color));
        }

        public void renderLine(Color color, Vec3d start, Vec3d end, float lineWidth) {
            ops.add(new Op(Kind.LINE, start.x, start.y, start.z, end.x, end.y, end.z, color));
        }

        /** Water's glass panes become thin filled slabs. */
        public void renderGlassPane(double x1, double y, double z1, double x2, double z2, Color tint, float intensity, double time) {
            if (tint.getAlpha() <= 0 || intensity <= 0.0F || x2 <= x1 || z2 <= z1) return;
            ops.add(new Op(Kind.GLASS, x1, y, z1, x2, y + 0.02, z2, tint));
        }

        public void flush() {
            draw(ops);
            ops.clear();
        }

        public void flushWithDepth() {
            flush();
        }
    }

    public static final class PersistentBatch implements AutoCloseable {
        private final List<Op> ops = new ArrayList<>();

        private PersistentBatch() {
        }

        public void begin(MatrixStack m) {
            ops.clear();
        }

        public void addFilledBox(double x1, double y1, double z1, double x2, double y2, double z2, Color color) {
            ops.add(new Op(Kind.FILL, x1, y1, z1, x2, y2, z2, color));
        }

        public void addOutlineBox(double x1, double y1, double z1, double x2, double y2, double z2, Color color) {
            ops.add(new Op(Kind.OUTLINE, x1, y1, z1, x2, y2, z2, color));
        }

        public void addLine(Vec3d start, Vec3d end, Color color, float width) {
            ops.add(new Op(Kind.LINE, start.x, start.y, start.z, end.x, end.y, end.z, color));
        }

        public void flush() {
            draw(ops);
            ops.clear();
        }

        public void flushWithDepth() {
            flush();
        }

        public void flushFill() {
            flush();
        }

        @Override
        public void close() {
            ops.clear();
        }
    }
}

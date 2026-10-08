package com.chukl.addon.modules.water;

import com.chukl.addon.water.Category;
import com.chukl.addon.water.ModeSetting;
import com.chukl.addon.water.RenderUtils;
import com.chukl.addon.water.Setting;
import com.chukl.addon.water.WModule;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Vector3f;

import java.awt.Color;

/** Water's China Hat / Nimbus above your head, drawn through Meteor's renderer. */
public final class ChinaHat extends WModule {
   private static final int SEGMENTS = 48;
   private static final int NIMBUS_SEGMENTS = 120;
   private final ModeSetting mode = new ModeSetting("Mode", "China Hat", "China Hat", "Nimbus");
   private final Setting<Boolean> headAnchor = new Setting<>("Head Anchor", true);
   private final Setting<Color> color = new Setting<>("Color", new Color(32, 116, 255, 255));
   private final Setting<Double> radius = new Setting<>("Radius", 0.62, 0.35, 1.0);

   public ChinaHat() {
      super("China Hat", Category.RENDER);
      this.addSetting(this.mode);
      this.addSetting(this.headAnchor);
      this.addSetting(this.color);
      this.addSetting(this.radius);
   }

   @Override
   public void onRender(MatrixStack matrices, float tickDelta) {
      if (mc.world == null || mc.player == null) return;
      if (mc.options.getPerspective() == Perspective.FIRST_PERSON) return;

      double x = mc.player.lastRenderX + (mc.player.getX() - mc.player.lastRenderX) * tickDelta;
      double y = mc.player.lastRenderY + (mc.player.getY() - mc.player.lastRenderY) * tickDelta;
      double z = mc.player.lastRenderZ + (mc.player.getZ() - mc.player.lastRenderZ) * tickDelta;
      double hatY = y + mc.player.getHeight() - (mc.player.isSneaking() ? 0.25 : 0.05);

      boolean anchor = this.headAnchor.getValue();
      float pitch = mc.player.getPitch(tickDelta);
      float yaw = mc.player.getYaw(tickDelta);
      float pitchRad = (float) Math.toRadians(pitch);
      float yawRad = (float) Math.toRadians(-yaw);
      float offsetY = anchor ? (float) (Math.abs(pitch) / 90.0 * 0.3) : 0.0F;
      float offsetZ = anchor ? 0.05F : 0.0F;

      Transform t = new Transform(x, hatY, z, anchor, pitchRad, yawRad, offsetY, offsetZ);
      if (this.mode.check("Nimbus")) {
         this.renderNimbus(t);
      } else {
         this.renderChinaHat(t);
      }
   }

   /** Turns hat-space points (like Water's matrix stack did) into world points. */
   private record Transform(double x, double y, double z, boolean anchor, float pitchRad, float yawRad, float offsetY, float offsetZ) {
      Vector3f apply(float lx, float ly, float lz) {
         Vector3f v = new Vector3f(lx, ly + offsetY, lz + offsetZ);
         if (anchor) {
            v.rotateX(pitchRad);
            v.rotateY(yawRad);
         }
         return v;
      }

      double wx(Vector3f v) {
         return x + v.x;
      }

      double wy(Vector3f v) {
         return y + v.y;
      }

      double wz(Vector3f v) {
         return z + v.z;
      }
   }

   private void tri(Transform t, float[] a, float[] b, float[] c, Color col) {
      Vector3f p1 = t.apply(a[0], a[1], a[2]);
      Vector3f p2 = t.apply(b[0], b[1], b[2]);
      Vector3f p3 = t.apply(c[0], c[1], c[2]);
      RenderUtils.triangleWorld(t.wx(p1), t.wy(p1), t.wz(p1), t.wx(p2), t.wy(p2), t.wz(p2), t.wx(p3), t.wy(p3), t.wz(p3), col);
   }

   private void seg(Transform t, float[] a, float[] b, Color col) {
      Vector3f p1 = t.apply(a[0], a[1], a[2]);
      Vector3f p2 = t.apply(b[0], b[1], b[2]);
      RenderUtils.lineWorld(t.wx(p1), t.wy(p1), t.wz(p1), t.wx(p2), t.wy(p2), t.wz(p2), col);
   }

   private void renderChinaHat(Transform t) {
      float r = this.radius.getValue().floatValue();
      Color selected = this.color.getValue();
      float rimY = 0.035F;

      for (int i = 0; i < SEGMENTS; i++) {
         float a1 = (float) (Math.PI * 2 * i / SEGMENTS);
         float a2 = (float) (Math.PI * 2 * (i + 1) / SEGMENTS);
         float[] apex = {0.0F, 0.38F, 0.0F};
         float[] r1 = {(float) Math.cos(a1) * r, rimY, (float) Math.sin(a1) * r};
         float[] r2 = {(float) Math.cos(a2) * r, rimY, (float) Math.sin(a2) * r};
         tri(t, apex, r1, r2, shade(selected, a1, 1.0F));

         float[] center = {0.0F, rimY - 0.012F, 0.0F};
         float[] u1 = {r1[0], rimY - 0.012F, r1[2]};
         float[] u2 = {r2[0], rimY - 0.012F, r2[2]};
         tri(t, center, u2, u1, shade(selected, a1, 0.62F));

         seg(t, r1, r2, new Color(selected.getRed(), selected.getGreen(), selected.getBlue(), 255));
      }
   }

   private void renderNimbus(Transform t) {
      Color selected = this.color.getValue();
      Color line = new Color(selected.getRed(), selected.getGreen(), selected.getBlue(), 255);
      float r = Math.min(0.55F, this.radius.getValue().floatValue());

      for (int i = 0; i < NIMBUS_SEGMENTS; i++) {
         float a1 = (float) (Math.PI * 2 * i / NIMBUS_SEGMENTS);
         float a2 = (float) (Math.PI * 2 * (i + 1) / NIMBUS_SEGMENTS);
         seg(t, new float[]{(float) Math.cos(a1) * r, 0.1F, (float) Math.sin(a1) * r},
            new float[]{(float) Math.cos(a2) * r, 0.1F, (float) Math.sin(a2) * r}, line);
      }
   }

   private static Color shade(Color base, float angle, float brightness) {
      float highlight = 0.72F + Math.max(0.0F, (float) Math.cos(angle - 0.65F)) * 0.28F;
      float factor = highlight * brightness;
      return new Color(
         Math.min(255, Math.round(base.getRed() * factor)),
         Math.min(255, Math.round(base.getGreen() * factor)),
         Math.min(255, Math.round(base.getBlue() * factor)),
         255
      );
   }
}

package com.chukl.addon.modules.water;

import com.chukl.addon.water.Category;
import com.chukl.addon.water.WModule;
import com.chukl.addon.water.Setting;
import com.chukl.addon.water.RenderUtils;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;

public final class JumpCircles extends WModule {
   private static final int SEGMENTS = 128;
   private final Setting<Float> lifetime = new Setting<>("Lifetime (s)", 1.5F, 0.1F, 5.0F);
   private final Setting<Float> startRadius = new Setting<>("Start Radius", 0.55F, 0.1F, 3.0F);
   private final Setting<Float> endRadius = new Setting<>("End Radius", 1.8F, 0.2F, 6.0F);
   private final Setting<Float> lineWidth = new Setting<>("Line Width", 2.0F, 0.5F, 6.0F);
   private final Setting<Color> color = new Setting<>("Color", new Color(120, 220, 255, 255));
   private final Setting<Boolean> glowMode = new Setting<>("Glow Mode", false);
   private final Setting<Boolean> glowFilled = new Setting<>("Glow Filled", false);
   private final List<JumpCircles.Circle> circles = new ArrayList<>();
   private boolean wasOnGround = true;
   private double lastGroundX = 0.0;
   private double lastGroundY = 0.0;
   private double lastGroundZ = 0.0;

   public JumpCircles() {
      super("JumpCircles", Category.RENDER);
      this.addSetting(this.lifetime);
      this.addSetting(this.startRadius);
      this.addSetting(this.endRadius);
      this.addSetting(this.lineWidth);
      this.addSetting(this.color);
      this.addSetting(this.glowMode);
      this.addSetting(this.glowFilled);
   }

   @Override
   public void onEnable() {
      this.circles.clear();
      this.wasOnGround = true;
   }

   @Override
   public void onDisable() {
      this.circles.clear();
   }

   @Override
   public void onTick() {
      if (mc.player != null) {
         boolean onGround = mc.player.isOnGround();
         if (onGround) {
            this.lastGroundX = mc.player.getX();
            this.lastGroundY = mc.player.getY();
            this.lastGroundZ = mc.player.getZ();
         }

         if (this.wasOnGround && !onGround && mc.player.getVelocity().y > 0.0) {
            this.circles.add(new Circle(this.lastGroundX, this.lastGroundY + 0.02, this.lastGroundZ, System.currentTimeMillis()));
         }

         this.wasOnGround = onGround;
         long now = System.currentTimeMillis();
         long lifeMs = (long)(this.lifetime.getValue() * 1000.0F);
         Iterator<JumpCircles.Circle> it = this.circles.iterator();

         while (it.hasNext()) {
            if (now - it.next().bornMs >= lifeMs) {
               it.remove();
            }
         }
      }
   }

   @Override
   public void onRender(MatrixStack matrices, float tickDelta) {
      if (mc.world != null && mc.player != null && !this.circles.isEmpty()) {
         Camera cam = RenderUtils.getCamera();
         if (cam != null) {
            Vec3d camPos = RenderUtils.getCameraPos(cam);
            long now = System.currentTimeMillis();
            long lifeMs = (long)(this.lifetime.getValue() * 1000.0F);
            double sR = this.startRadius.getValue().floatValue();
            double eR = this.endRadius.getValue().floatValue();
            float lw = this.lineWidth.getValue();
            Color base = this.color.getValue();
            int baseAlpha = base.getAlpha();
            matrices.push();
            RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);

            for (JumpCircles.Circle c : this.circles) {
               float age = (float)(now - c.bornMs) / (float)lifeMs;
               if (age < 0.0F) {
                  age = 0.0F;
               }

               if (age > 1.0F) {
                  age = 1.0F;
               }

               float ease = 1.0F - (1.0F - age) * (1.0F - age) * (1.0F - age);
               double radius = sR + (eR - sR) * ease;
               int alpha = (int)(baseAlpha * (1.0F - ease));
               if (alpha > 0) {
                  Color col = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
                  double cx = c.doubleVal - camPos.x;
                  double cy = c.doubleVal2 - camPos.y;
                  double cz = c.doubleVal3 - camPos.z;
                  Vec3d[] points = new Vec3d[129];

                  for (int i = 0; i <= 128; i++) {
                     double ang = (Math.PI * 2) * (i / 128.0);
                     points[i] = new Vec3d(cx + Math.cos(ang) * radius, cy, cz + Math.sin(ang) * radius);
                  }

                  if (this.glowFilled.getValue()) {
                     int r0 = base.getRed();
                     int g0 = base.getGreen();
                     int b0 = base.getBlue();
                     int rings = 32;
                     float maxLw = lw * 8.0F;

                     for (int k = 1; k <= rings; k++) {
                        double t = (double)k / rings;
                        double innerRadius = radius * (1.0 - t);
                        if (!(innerRadius < 0.05)) {
                           int aFill = Math.max(1, (int)(alpha * (1.0 - t * 0.6) * 0.55));
                           Color fillCol = new Color(r0, g0, b0, aFill);
                           Vec3d[] ring = new Vec3d[129];

                           for (int i = 0; i <= 128; i++) {
                              double ang = (Math.PI * 2) * (i / 128.0);
                              ring[i] = new Vec3d(cx + Math.cos(ang) * innerRadius, cy, cz + Math.sin(ang) * innerRadius);
                           }

                           drawRing(batch, ring, fillCol, maxLw);
                        }
                     }
                  }

                  if (this.glowMode.getValue()) {
                     int r = base.getRed();
                     int g = base.getGreen();
                     int b = base.getBlue();
                     Color h1 = new Color(r, g, b, Math.max(1, alpha / 16));
                     Color h2 = new Color(r, g, b, Math.max(1, alpha / 12));
                     Color h3 = new Color(r, g, b, Math.max(1, alpha / 9));
                     Color h4 = new Color(r, g, b, Math.max(1, alpha / 6));
                     Color h5 = new Color(r, g, b, Math.max(1, alpha / 4));
                     Color h6 = new Color(r, g, b, Math.max(1, alpha / 2));
                     Color core1 = new Color(r, g, b, Math.min(255, (int)(alpha * 1.0F)));
                     Color core2 = new Color(255, 255, 255, Math.min(255, (int)(alpha * 1.4F)));
                     drawRing(batch, points, h1, lw * 18.0F);
                     drawRing(batch, points, h2, lw * 14.0F);
                     drawRing(batch, points, h3, lw * 11.0F);
                     drawRing(batch, points, h4, lw * 8.5F);
                     drawRing(batch, points, h5, lw * 6.0F);
                     drawRing(batch, points, h6, lw * 4.0F);
                     drawRing(batch, points, core1, lw * 2.8F);
                     drawRing(batch, points, core2, lw * 1.6F);
                  } else {
                     drawRing(batch, points, col, lw);
                  }
               }
            }

            batch.flush();
            matrices.pop();
         }
      }
   }

   private static void drawRing(RenderUtils.WorldBatch batch, Vec3d[] points, Color col, float width) {
      for (int i = 1; i < points.length; i++) {
         batch.renderLine(col, points[i - 1], points[i], width);
      }
   }

   final class Circle {
      final double doubleVal;
      final double doubleVal2;
      final double doubleVal3;
      final long bornMs;

      Circle(double x, double y, double z, long bornMs) {
         this.doubleVal = x;
         this.doubleVal2 = y;
         this.doubleVal3 = z;
         this.bornMs = bornMs;
      }
   }
}


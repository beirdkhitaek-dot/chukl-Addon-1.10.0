package com.chukl.addon.modules.water;

import com.chukl.addon.water.Category;
import com.chukl.addon.water.WModule;
import com.chukl.addon.water.WaterPlus;
import com.chukl.addon.water.MobsSetting;
import com.chukl.addon.water.Setting;
import com.chukl.addon.water.RenderUtils;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class MobESP extends WModule {
   private static final double TRACER_START_DISTANCE = 150.0;
   private static final double TRACER_END_DISTANCE = 24.0;
   private static final double TRACER_BEHIND_MIN_SPREAD = 2.75;
   private final MobsSetting mobs = new MobsSetting("Mobs");
   private final Setting<Double> alpha = new Setting<>("Alpha", 100.0, 0.0, 255.0);
   private final Setting<Double> range = new Setting<>("Range", 128.0, 16.0, 512.0);
   private final Setting<Boolean> tracers = new Setting<>("Tracers", false);
   private final Setting<Color> outlineColor = new Setting<>("Outline color", new Color(255, 80, 80));
   private final Setting<Color> fillColor = new Setting<>("Fill color", new Color(255, 80, 80));
   private final Setting<Color> tracerColor = new Setting<>("Tracer color", new Color(255, 80, 80));

   public MobESP() {
      super("Mob ESP", Category.RENDER);
      this.addSetting(this.mobs);
      this.addSetting(this.alpha);
      this.addSetting(this.range);
      this.addSetting(this.tracers);
      this.addSetting(this.outlineColor);
      this.addSetting(this.fillColor);
      this.addSetting(this.tracerColor);
   }

   @Override
   public void onRender(MatrixStack matrices, float tickDelta) {
      if (mc.world != null && mc.player != null) {
         Set<EntityType<?>> targets = this.mobs.getSelectedMobs();
         if (!targets.isEmpty()) {
            Camera cam = RenderUtils.getCamera();
            if (cam != null) {
               Vec3d camPos = RenderUtils.getCameraPos(cam);
               double camX = camPos.x;
               double camY = camPos.y;
               double camZ = camPos.z;
               double maxRangeSq = this.range.getValue() * this.range.getValue();
               int alphaValue = this.clampAlpha(this.alpha.getValue());
               boolean renderTracers = this.tracers.getValue();
               Color boxColor = this.applyOpacity(this.outlineColor.getValue(), alphaValue);
               Color fill = this.applyOpacity(this.fillColor.getValue(), Math.max(0, alphaValue / 3));
               Color tracer = renderTracers ? this.applyOpacity(this.tracerColor.getValue(), 255) : null;
               Vec3d cameraForward = renderTracers ? RenderUtils.getCameraForward(cam) : null;
               Vec3d cameraRight = renderTracers ? RenderUtils.getCameraRight(cam) : null;
               Vec3d cameraUp = renderTracers ? RenderUtils.getCameraUp(cameraForward, cameraRight) : null;
               Vec3d tracerStart = renderTracers ? cameraForward.multiply(150.0) : null;
               List<MobESP.RenderData> renderData = new ArrayList<>();

               for (Entity entity : mc.world.getEntities()) {
                  if (entity != mc.player
                     && !(entity instanceof PlayerEntity)
                     && entity instanceof LivingEntity
                     && entity.isAlive()
                     && targets.contains(entity.getType())) {
                     Vec3d lerped = this.getLerpedPosCompat(entity, tickDelta);
                     double dx = lerped.x - camX;
                     double dy = lerped.y - camY;
                     double dz = lerped.z - camZ;
                     double distSq = dx * dx + dy * dy + dz * dz;
                     if (!(distSq > maxRangeSq)) {
                        double halfWidth = entity.getWidth() / 2.0;
                        double height = entity.getHeight();
                        double tracerTargetY = dy + height * 0.5;
                        renderData.add(new RenderData(dx, dy, dz, tracerTargetY, halfWidth, height));
                     }
                  }
               }

               if (!renderData.isEmpty()) {
                  matrices.push();
                  RenderUtils.WorldBatch boxBatch = RenderUtils.beginWorldBatch(matrices);

                  for (MobESP.RenderData d : renderData) {
                     boxBatch.renderOutlineBox(
                        d.doubleVal - d.halfWidth,
                        d.doubleVal2,
                        d.doubleVal3 - d.halfWidth,
                        d.doubleVal + d.halfWidth,
                        d.doubleVal2 + d.height,
                        d.doubleVal3 + d.halfWidth,
                        boxColor
                     );
                     boxBatch.renderFilledBox(
                        d.doubleVal - d.halfWidth,
                        d.doubleVal2,
                        d.doubleVal3 - d.halfWidth,
                        d.doubleVal + d.halfWidth,
                        d.doubleVal2 + d.height,
                        d.doubleVal3 + d.halfWidth,
                        fill
                     );
                  }

                  boxBatch.flush();
                  if (renderTracers) {
                     RenderUtils.WorldBatch tb = RenderUtils.beginWorldBatch(matrices);

                     for (MobESP.RenderData d : renderData) {
                        Vec3d tracerEnd = RenderUtils.getSpreadTracerEnd(
                           d.doubleVal, d.tracerTargetY, d.doubleVal3, cameraForward, cameraRight, cameraUp, 24.0, 2.75
                        );
                        tb.renderLine(tracer, tracerStart, tracerEnd, WaterPlus.tracerLineWidth());
                     }

                     tb.flush();
                  }

                  matrices.pop();
               }
            }
         }
      }
   }

   private int clampAlpha(double value) {
      int a = (int)Math.round(value);
      return Math.max(0, Math.min(255, a));
   }

   private Color applyOpacity(Color base, int alphaValue) {
      int combined = Math.max(0, Math.min(255, Math.round(base.getAlpha() / 255.0F * alphaValue)));
      return new Color(base.getRed(), base.getGreen(), base.getBlue(), combined);
   }

   private Vec3d getLerpedPosCompat(Entity e, float tickDelta) {
      try {
         return e.getLerpedPos(tickDelta);
      } catch (Throwable var10) {
         double x = MathHelper.lerp(tickDelta, e.lastRenderX, e.getX());
         double y = MathHelper.lerp(tickDelta, e.lastRenderY, e.getY());
         double z = MathHelper.lerp(tickDelta, e.lastRenderZ, e.getZ());
         return new Vec3d(x, y, z);
      }
   }

   final static class RenderData {
      final double doubleVal;
      final double doubleVal2;
      final double doubleVal3;
      final double tracerTargetY;
      final double halfWidth;
      final double height;

      RenderData(double dx, double dy, double dz, double tracerTargetY, double halfWidth, double height) {
         this.doubleVal = dx;
         this.doubleVal2 = dy;
         this.doubleVal3 = dz;
         this.tracerTargetY = tracerTargetY;
         this.halfWidth = halfWidth;
         this.height = height;
      }
   }
}


package com.chukl.addon.modules.water;

import com.chukl.addon.water.Category;
import com.chukl.addon.water.WModule;
import com.chukl.addon.water.Setting;
import com.chukl.addon.water.NameProtectUtil;
import com.chukl.addon.water.RenderUtils;
import com.water.utils.renderer.ProjectionUtil;
import java.awt.Color;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2fStack;

public final class PearlESP extends WModule {
   public static PearlESP instance;
   private final Setting<Double> alpha = new Setting<>("Alpha", 180.0, 0.0, 255.0);
   private final Setting<Boolean> tracers = new Setting<>("Tracers", true);
   private final Setting<Double> tracerW = new Setting<>("Tracer Width", 1.5, 0.5, 5.0);
   private final Setting<Boolean> showOwner = new Setting<>("Player Name", true);
   private final Setting<Color> color = new Setting<>("Color", new Color(148, 0, 211));
   private final List<double[]> hudPositions = new CopyOnWriteArrayList<>();

   public PearlESP() {
      super("Pearl ESP", Category.RENDER);
      instance = this;
      this.addSetting(this.alpha);
      this.addSetting(this.tracers);
      this.addSetting(this.tracerW);
      this.addSetting(this.showOwner);
      this.addSetting(this.color);
   }

   public static void renderHud(DrawContext context, float tickDelta) {
      PearlESP mod = instance;
      if (mod != null && mod.isEnabled() && mod.showOwner.getValue()) {
         if (mc.world != null && mc.player != null && !mc.options.hudHidden) {
            Camera cam = RenderUtils.getCamera();
            if (cam != null) {
               Vec3d camPos = RenderUtils.getCameraPos(cam);

               for (Entity entity : mc.world.getEntities()) {
                  if (entity instanceof EnderPearlEntity pearl && pearl.getOwner() != null) {
                     String name = NameProtectUtil.replace(pearl.getOwner().getName().getString());
                     if (!name.isEmpty()) {
                        double wx = MathHelper.lerp(tickDelta, pearl.lastRenderX, pearl.getX());
                        double wy = MathHelper.lerp(tickDelta, pearl.lastRenderY, pearl.getY()) + 0.5;
                        double wz = MathHelper.lerp(tickDelta, pearl.lastRenderZ, pearl.getZ());
                        ProjectionUtil.ScreenProjection proj = new ProjectionUtil.ScreenProjection();
                        if (ProjectionUtil.projectToScreen(ProjectionUtil.modelViewMatrix, ProjectionUtil.projectionMatrix, wx, wy, wz, proj)
                           && proj.visible
                           && !(proj.doubleVal3 < 0.0)
                           && !(proj.doubleVal3 > 1.0)
                           && !(proj.doubleVal4 <= 0.0)) {
                           float scale = (float)(0.5 * mc.getWindow().getScaledWidth() * 0.025 / proj.doubleVal4);
                           if (Float.isFinite(scale) && !(scale <= 0.0F)) {
                              Matrix3x2fStack mat = context.getMatrices();
                              mat.pushMatrix();
                              mat.translate((float)proj.doubleVal, (float)proj.doubleVal2);
                              mat.scale(scale, scale);
                              Color c = mod.color.getValue();
                              int nameCol = 0xFF000000 | c.getRed() << 16 | c.getGreen() << 8 | c.getBlue();
                              int w = mc.textRenderer.getWidth(name);
                              context.drawText(mc.textRenderer, name, -(w / 2), -4, nameCol, true);
                              mat.popMatrix();
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   @Override
   public void onRender(MatrixStack matrices, float tickDelta) {
      if (mc.world != null && mc.player != null) {
         Camera cam = RenderUtils.getCamera();
         if (cam != null) {
            Vec3d camPos = RenderUtils.getCameraPos(cam);
            Vec3d cameraForward = RenderUtils.getCameraForward(cam);
            Vec3d cameraRight = RenderUtils.getCameraRight(cam);
            Vec3d cameraUp = RenderUtils.getCameraUp(cameraForward, cameraRight);
            Vec3d tracerStart = cameraForward.multiply(150.0);
            int a = Math.max(0, Math.min(255, (int)Math.round(this.alpha.getValue())));
            Color c = this.color.getValue();
            Color fill = new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
            Color solid = new Color(c.getRed(), c.getGreen(), c.getBlue(), 255);
            boolean hasPearls = false;

            for (Entity e : mc.world.getEntities()) {
               if (e instanceof EnderPearlEntity) {
                  hasPearls = true;
                  break;
               }
            }

            if (hasPearls) {
               matrices.push();

               try {
                  RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);

                  for (Entity entity : mc.world.getEntities()) {
                     if (entity instanceof EnderPearlEntity pearl) {
                        double x = MathHelper.lerp(tickDelta, pearl.lastRenderX, pearl.getX()) - camPos.x;
                        double y = MathHelper.lerp(tickDelta, pearl.lastRenderY, pearl.getY()) - camPos.y;
                        double z = MathHelper.lerp(tickDelta, pearl.lastRenderZ, pearl.getZ()) - camPos.z;
                        batch.renderFilledBox(x - 0.25, y - 0.25, z - 0.25, x + 0.25, y + 0.25, z + 0.25, fill);
                        batch.renderOutlineBox(x - 0.25, y - 0.25, z - 0.25, x + 0.25, y + 0.25, z + 0.25, solid);
                        if (this.tracers.getValue()) {
                           Vec3d end = RenderUtils.getSpreadTracerEnd(x, y + 0.25, z, cameraForward, cameraRight, cameraUp, 24.0, 2.75);
                           batch.renderLine(solid, tracerStart, end, this.tracerW.getValue().floatValue());
                        }
                     }
                  }

                  batch.flush();
               } finally {
               }

               matrices.pop();
            }
         }
      }
   }
}


package com.chukl.addon.modules.water;

import com.chukl.addon.water.Category;
import com.chukl.addon.water.WModule;
import com.chukl.addon.water.Friends;
import com.chukl.addon.water.Setting;
import com.chukl.addon.water.RenderUtils;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class PlayerESP extends WModule {
   private static final double TRACER_START_DISTANCE = 150.0;
   private static final double TRACER_END_DISTANCE = 24.0;
   private static final double TRACER_BEHIND_MIN_SPREAD = 2.75;
   private final Setting<Double> fillAlpha = new Setting<>("Fill Alpha", 180.0, 0.0, 255.0);
   private final Setting<Double> range = new Setting<>("Range", 256.0, 16.0, 512.0);
   private final Setting<Boolean> tracers = new Setting<>("Tracers", false);
   private final Setting<Boolean> arrows = new Setting<>("Arrows", false);
   private final Setting<Double> tracerWidth = new Setting<>("Tracer Width", 0.5, 0.1, 2.0);
   private final Setting<Double> arrowSize = new Setting<>("Arrow Size", 3.0, 1.0, 8.0);
   private final Setting<Color> fillColor = new Setting<>("Fill color", new Color(255, 0, 0));
   private final Setting<Color> tracerColor = new Setting<>("Tracer color", new Color(255, 0, 0));
   private RenderUtils.PersistentBatch fillBatch;
   private RenderUtils.PersistentBatch tracerBatch;

   public PlayerESP() {
      super("Player ESP", Category.RENDER);
      this.addSetting(this.fillAlpha);
      this.addSetting(this.range);
      this.addSetting(this.tracers);
      this.addSetting(this.arrows);
      this.addSetting(this.tracerWidth);
      this.addSetting(this.arrowSize);
      this.addSetting(this.fillColor);
      this.addSetting(this.tracerColor);
   }

   @Override
   public void onEnable() {
      this.fillBatch = RenderUtils.createPersistentBatch();
      this.tracerBatch = RenderUtils.createPersistentBatch();
   }

   @Override
   public void onDisable() {
      if (this.fillBatch != null) {
         this.fillBatch.close();
         this.fillBatch = null;
      }

      if (this.tracerBatch != null) {
         this.tracerBatch.close();
         this.tracerBatch = null;
      }
   }

   @Override
   public void onRender(MatrixStack matrices, float tickDelta) {
      if (mc.world != null && mc.player != null) {
         Camera cam = RenderUtils.getCamera();
         if (cam != null) {
            if (this.fillBatch == null) {
               this.fillBatch = RenderUtils.createPersistentBatch();
            }

            if (this.tracerBatch == null) {
               this.tracerBatch = RenderUtils.createPersistentBatch();
            }

            Vec3d camPos = RenderUtils.getCameraPos(cam);
            Vec3d camFwd = RenderUtils.getCameraForward(cam);
            double camX = camPos.x;
            double camY = camPos.y;
            double camZ = camPos.z;
            double maxRangeSq = this.range.getValue() * this.range.getValue();
            int fillA = this.clampAlpha(this.fillAlpha.getValue());
            boolean doTracers = this.tracers.getValue();
            Vec3d tracerOrigin = Freecam.resolveTracerOrigin(camPos, tickDelta);
            Vec3d tracerStart = tracerOrigin.equals(camPos) ? camFwd.multiply(0.1) : tracerOrigin.subtract(camPos);
            List<PlayerESP.RenderData> renderData = new ArrayList<>();

            for (PlayerEntity player : mc.world.getPlayers()) {
               if (player != mc.player && player.isAlive() && !player.isSpectator()) {
                  boolean friend = Friends.isEspColor() && Friends.isFriend(this.getFriendLookupName(player));
                  Vec3d lerped = this.getLerpedPosCompat(player, tickDelta);
                  double dx = lerped.x - camX;
                  double dy = lerped.y - camY;
                  double dz = lerped.z - camZ;
                  if (!(dx * dx + dy * dy + dz * dz > maxRangeSq)) {
                     Color base = friend ? Friends.getColor() : this.fillColor.getValue();
                     Color tc = friend ? Friends.getColor() : this.tracerColor.getValue();
                     renderData.add(
                        new RenderData(
                           dx,
                           dy,
                           dz,
                           dy + player.getHeight() * 0.5,
                           player.getWidth() / 2.0,
                           player.getHeight(),
                           this.applyOpacity(base, fillA),
                           this.applyOpacity(tc, 255)
                        )
                     );
                  }
               }
            }

            if (!renderData.isEmpty()) {
               this.fillBatch.begin(matrices);

               for (PlayerESP.RenderData d : renderData) {
                  this.fillBatch
                     .addFilledBox(
                        d.doubleVal - d.doubleVal4,
                        d.doubleVal2,
                        d.doubleVal3 - d.doubleVal4,
                        d.doubleVal + d.doubleVal4,
                        d.doubleVal2 + d.height,
                        d.doubleVal3 + d.doubleVal4,
                        d.fill
                     );
               }

               this.fillBatch.flush();
               if (doTracers || this.arrows.getValue()) {
                  float w = this.tracerWidth.getValue().floatValue();
                  boolean drawLines = doTracers;
                  boolean drawArrows = this.arrows.getValue();
                  float arrowS = this.arrowSize.getValue().floatValue();
                  this.tracerBatch.begin(matrices);

                  for (PlayerESP.RenderData d : renderData) {
                     Vec3d target = new Vec3d(d.doubleVal, d.tracerY, d.doubleVal3);
                     Color base = d.tracer;
                     if (drawLines) {
                        this.tracerBatch.addLine(tracerStart, target, withAlpha(base, 25), w + 0.8F);
                        this.tracerBatch.addLine(tracerStart, target, withAlpha(base, 200), w);
                        this.tracerBatch.addLine(tracerStart, target, withAlpha(blendToWhite(base, 0.6F), 150), Math.max(w - 0.2F, 0.2F));
                     }

                     if (drawArrows) {
                        Vec3d toTarget = target.subtract(tracerStart);
                        double len = toTarget.length();
                        if (!(len < 0.5)) {
                           Vec3d dir = toTarget.normalize();
                           Vec3d arrowCenter = tracerStart.add(dir.multiply(len * 0.4));
                           Vec3d up = new Vec3d(0.0, 1.0, 0.0);
                           Vec3d right = dir.crossProduct(up).normalize();
                           if (right.lengthSquared() < 0.001) {
                              right = dir.crossProduct(new Vec3d(1.0, 0.0, 0.0)).normalize();
                           }

                           double spread = arrowS * 0.12;
                           double tipLen = arrowS * 0.25;
                           Vec3d tip = arrowCenter.add(dir.multiply(tipLen));
                           Vec3d wingL = arrowCenter.subtract(dir.multiply(tipLen * 0.3)).add(right.multiply(spread));
                           Vec3d wingR = arrowCenter.subtract(dir.multiply(tipLen * 0.3)).subtract(right.multiply(spread));
                           float aw = w + 1.5F;
                           Color arrowCol = withAlpha(base, 255);
                           this.tracerBatch.addLine(wingL, tip, arrowCol, aw);
                           this.tracerBatch.addLine(wingR, tip, arrowCol, aw);
                           this.tracerBatch.addLine(wingL, wingR, withAlpha(base, 200), aw * 0.6F);
                        }
                     }
                  }

                  this.tracerBatch.flush();
               }
            }
         }
      }
   }

   private static Color withAlpha(Color c, int a) {
      return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
   }

   private static Color blendToWhite(Color c, float t) {
      int r = (int)(c.getRed() + (255 - c.getRed()) * t);
      int g = (int)(c.getGreen() + (255 - c.getGreen()) * t);
      int b = (int)(c.getBlue() + (255 - c.getBlue()) * t);
      return new Color(r, g, b);
   }

   private int clampAlpha(double v) {
      return Math.max(0, Math.min(255, (int)Math.round(v)));
   }

   private Color applyOpacity(Color base, int a) {
      int combined = Math.max(0, Math.min(255, Math.round(base.getAlpha() / 255.0F * a)));
      return new Color(base.getRed(), base.getGreen(), base.getBlue(), combined);
   }

   private Vec3d getLerpedPosCompat(PlayerEntity player, float tickDelta) {
      try {
         return player.getLerpedPos(tickDelta);
      } catch (Throwable var4) {
         return new Vec3d(
            MathHelper.lerp(tickDelta, player.lastRenderX, player.getX()),
            MathHelper.lerp(tickDelta, player.lastRenderY, player.getY()),
            MathHelper.lerp(tickDelta, player.lastRenderZ, player.getZ())
         );
      }
   }

   private String getFriendLookupName(PlayerEntity player) {
      if (player == null) {
         return "";
      } else {
         try {
            Object profile = player.getGameProfile();
            if (profile != null) {
               try {
                  if (profile.getClass().getMethod("getName").invoke(profile) instanceof String s && !s.isBlank()) {
                     return s;
                  }
               } catch (Throwable var6) {
               }

               try {
                  if (profile.getClass().getMethod("name").invoke(profile) instanceof String s && !s.isBlank()) {
                     return s;
                  }
               } catch (Throwable var5) {
               }
            }
         } catch (Throwable var7) {
         }

         return player.getName().getString();
      }
   }

   final static class RenderData {
      final double doubleVal;
      final double doubleVal2;
      final double doubleVal3;
      final double tracerY;
      final double doubleVal4;
      final double height;
      final Color fill;
      final Color tracer;

      RenderData(double dx, double dy, double dz, double tracerY, double hw, double height, Color fill, Color tracer) {
         this.doubleVal = dx;
         this.doubleVal2 = dy;
         this.doubleVal3 = dz;
         this.tracerY = tracerY;
         this.doubleVal4 = hw;
         this.height = height;
         this.fill = fill;
         this.tracer = tracer;
      }
   }
}


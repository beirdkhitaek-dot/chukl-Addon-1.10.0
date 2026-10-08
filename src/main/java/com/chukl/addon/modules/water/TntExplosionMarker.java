package com.chukl.addon.modules.water;

import com.chukl.addon.water.Category;
import com.chukl.addon.water.WModule;
import com.chukl.addon.water.Setting;
import com.chukl.addon.water.RenderUtils;
import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

public final class TntExplosionMarker extends WModule {
   private static final double CHUNK_THICKNESS = 0.12;
   private final Setting<Integer> alpha = new Setting<>("Alpha", 80, 0, 255);
   private final Setting<Boolean> chatNotify = new Setting<>("Chat Notify", true);
   private final Map<ChunkPos, Double> markedChunks = new ConcurrentHashMap<>();

   public TntExplosionMarker() {
      super("TNT Explosion Marker", Category.RENDER);
      this.addSetting(this.alpha);
      this.addSetting(this.chatNotify);
   }

   @Override
   public void onDisable() {
      this.markedChunks.clear();
   }

   @Override
   public void onPacketReceive(Packet<?> packet) {
      if (mc.world != null && mc.player != null && packet != null) {
         String simpleName = packet.getClass().getSimpleName();
         if (simpleName.equals("class_2673")) {
            this.debugFields(packet);
         }

         if (simpleName.toLowerCase().contains("explosion") || simpleName.equals("class_2673")) {
            Vec3d pos = this.readExplosionPos(packet);
            if (pos == null) {
               System.out.println("[TNT] Position null für: null");
            } else if (!Double.isNaN(pos.x) && !Double.isNaN(pos.y) && !Double.isNaN(pos.z)) {
               ChunkPos chunk = new ChunkPos((int)Math.floor(pos.x) >> 4, (int)Math.floor(pos.z) >> 4);
               this.markedChunks.put(chunk, pos.y);
               System.out.println("[TNT] Chunk markiert: " + chunk.x + ", " + chunk.z);
               if (this.chatNotify.getValue() && mc.inGameHud != null) {
                  mc.inGameHud.getChatHud().addMessage(Text.literal("§c[TNT] §fChunk markiert §7(" + chunk.x + ", " + chunk.z + ")"));
               }
            }
         }
      }
   }

   @Override
   public void onRender(MatrixStack matrices, float tickDelta) {
      if (mc.world != null && mc.player != null) {
         if (!this.markedChunks.isEmpty()) {
            Camera cam = RenderUtils.getCamera();
            if (cam != null) {
               Vec3d cameraPos = RenderUtils.getCameraPos(cam);
               Color fill = new Color(255, 60, 60, this.alpha.getValue());
               Color line = new Color(255, 60, 60, 255);
               matrices.push();

               try {
                  RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);

                  for (Entry<ChunkPos, Double> entry : this.markedChunks.entrySet()) {
                     ChunkPos chunk = entry.getKey();
                     double worldY = entry.getValue();
                     double x1 = (chunk.x << 4) - cameraPos.x;
                     double z1 = (chunk.z << 4) - cameraPos.z;
                     double x2 = x1 + 16.0;
                     double z2 = z1 + 16.0;
                     double y = worldY - cameraPos.y;
                     double y2 = y + 0.12;
                     batch.renderFilledBox(x1, y, z1, x2, y2, z2, fill);
                     batch.renderOutlineBox(x1, y, z1, x2, y2, z2, line);
                  }

                  batch.flush();
               } catch (Exception var28) {
                  System.out.println("[TNT] Render-Fehler: " + var28.getMessage());
                  var28.printStackTrace();
               } finally {
                  matrices.pop();
               }
            }
         }
      }
   }

   private Vec3d readExplosionPos(Object packet) {
      Object center = this.call(packet, "center");
      if (center == null) {
         center = this.call(packet, "getCenter");
      }

      if (center instanceof Vec3d vec) {
         return vec;
      } else {
         Double x = this.readDouble(packet, "x", "getX");
         Double y = this.readDouble(packet, "y", "getY");
         Double z = this.readDouble(packet, "z", "getZ");
         if (x != null && y != null && z != null) {
            return new Vec3d(x, y, z);
         } else {
            Float fx = this.readFloat(packet, "x", "getX");
            Float fy = this.readFloat(packet, "y", "getY");
            Float fz = this.readFloat(packet, "z", "getZ");
            if (fx != null && fy != null && fz != null) {
               return new Vec3d(fx.floatValue(), fy.floatValue(), fz.floatValue());
            } else {
               Vec3d vec = this.findVec3dField(packet);
               if (vec != null) {
                  return vec;
               } else {
                  x = this.findDoubleField(packet, "x");
                  y = this.findDoubleField(packet, "y");
                  z = this.findDoubleField(packet, "z");
                  return x != null && y != null && z != null ? new Vec3d(x, y, z) : null;
               }
            }
         }
      }
   }

   private void debugFields(Object packet) {
      System.out.println("[TNT-DEBUG] Felder in " + packet.getClass().getSimpleName() + ":");

      for (Class<?> cls = packet.getClass(); cls != null; cls = cls.getSuperclass()) {
         for (Field field : cls.getDeclaredFields()) {
            try {
               field.setAccessible(true);
               System.out.println("  " + field.getName() + " (" + field.getType().getSimpleName() + ") = " + field.get(packet));
            } catch (Throwable var8) {
            }
         }
      }
   }

   private Object call(Object obj, String methodName) {
      for (Class<?> cls = obj.getClass(); cls != null; cls = cls.getSuperclass()) {
         for (Method method : cls.getDeclaredMethods()) {
            if (method.getName().equals(methodName) && method.getParameterCount() == 0) {
               try {
                  method.setAccessible(true);
                  return method.invoke(obj);
               } catch (Throwable var9) {
               }
            }
         }
      }

      return null;
   }

   private Double readDouble(Object obj, String methodA, String methodB) {
      Object value = this.call(obj, methodA);
      if (!(value instanceof Number)) {
         value = this.call(obj, methodB);
      }

      return value instanceof Number n ? n.doubleValue() : null;
   }

   private Float readFloat(Object obj, String methodA, String methodB) {
      Object value = this.call(obj, methodA);
      if (!(value instanceof Float)) {
         value = this.call(obj, methodB);
      }

      return value instanceof Float f ? f : null;
   }

   private Vec3d findVec3dField(Object obj) {
      for (Class<?> cls = obj.getClass(); cls != null; cls = cls.getSuperclass()) {
         for (Field field : cls.getDeclaredFields()) {
            try {
               field.setAccessible(true);
               if (field.get(obj) instanceof Vec3d vec) {
                  return vec;
               }
            } catch (Throwable var9) {
            }
         }
      }

      return null;
   }

   private Double findDoubleField(Object obj, String partName) {
      for (Class<?> cls = obj.getClass(); cls != null; cls = cls.getSuperclass()) {
         for (Field field : cls.getDeclaredFields()) {
            if (field.getName().toLowerCase().contains(partName)) {
               try {
                  field.setAccessible(true);
                  if (field.get(obj) instanceof Number n) {
                     return n.doubleValue();
                  }
               } catch (Throwable var10) {
               }
            }
         }
      }

      return null;
   }
}


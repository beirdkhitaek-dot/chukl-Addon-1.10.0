package com.chukl.addon.modules.water;

import com.chukl.addon.water.Category;
import com.chukl.addon.water.WModule;
import com.chukl.addon.water.Setting;
import com.chukl.addon.water.RenderUtils;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

public final class ScanOverlay extends WModule {
   private static final double RENDER_Y = 63.0;
   private static final double SLAB_H = 0.02;
   private static final int TRACER_ALPHA = 255;
   private static final int TRACER_GLOW_ALPHA = 70;
   private static final int TRACER_MID_ALPHA = 140;
   private static final float TRACER_GLOW_SIZE = 1.9F;
   private static final int VANISH_THRESHOLD_TICKS = 40;
   private static final int MIN_SEEN_TICKS = 60;
   private static final double UNDERGROUND_Y = 40.0;
   private static final double REPEAT_RADIUS = 64.0;
   private static final int REPEAT_COUNT = 2;
   private static final long CHUNK_LOAD_WINDOW_MS = 3000L;
   private final Setting<Boolean> tracers = new Setting<>("Tracers", true);
   private final Setting<Double> tracerWidth = new Setting<>("Tracer Width", 0.6, 0.05, 4.0);
   private final Map<ChunkPos, ScanOverlay.FlagData> flaggedChunks = new ConcurrentHashMap<>();
   private final Map<UUID, ScanOverlay.PlayerTrack> tracked = new ConcurrentHashMap<>();
   private final List<long[]> recentChunkLoads = new ArrayList<>();

   public ScanOverlay() {
      super("Scan Overlay", Category.RENDER);
      this.addSetting(this.tracers);
      this.addSetting(this.tracerWidth);
   }

   @Override
   public void onEnable() {
      this.clearState();
   }

   @Override
   public void onDisable() {
      this.clearState();
   }

   private void clearState() {
      this.flaggedChunks.clear();
      this.tracked.clear();
      synchronized (this.recentChunkLoads) {
         this.recentChunkLoads.clear();
      }
   }

   @Override
   public void onPacketReceive(Packet<?> packet) {
      if (mc.world != null && mc.player != null) {
         if (packet instanceof ChunkDataS2CPacket chunkPacket) {
            long now = System.currentTimeMillis();
            synchronized (this.recentChunkLoads) {
               this.recentChunkLoads.add(new long[]{chunkPacket.getChunkX(), chunkPacket.getChunkZ(), now});
               this.recentChunkLoads.removeIf(entry -> now - entry[2] > 3000L);
            }
         }
      }
   }

   @Override
   public void onTick() {
      if (mc.world != null && mc.player != null) {
         int simDist = (Integer)mc.options.getSimulationDistance().getValue();
         ChunkPos ownChunk = mc.player.getChunkPos();
         this.flaggedChunks.keySet().removeIf(chunk -> Math.abs(chunk.x - ownChunk.x) > simDist || Math.abs(chunk.z - ownChunk.z) > simDist);
         Set<UUID> visibleNow = new HashSet<>();

         for (AbstractClientPlayerEntity player : mc.world.getPlayers()) {
            if (player != mc.player) {
               UUID id = player.getUuid();
               visibleNow.add(id);
               ScanOverlay.PlayerTrack track = this.tracked
                  .computeIfAbsent(id, ignored -> new PlayerTrack(player.getName().getString()));
               track.doubleVal = player.getX();
               track.doubleVal2 = player.getY();
               track.doubleVal3 = player.getZ();
               track.ticksSeen++;
               track.ticksGone = 0;
               track.wasUnderground = player.getY() < 40.0;
               track.flagged = false;
               track.visits.add(new long[]{(long)track.doubleVal, (long)track.doubleVal3, System.currentTimeMillis()});

               while (track.visits.size() > 50) {
                  track.visits.removeFirst();
               }
            }
         }

         for (Entry<UUID, ScanOverlay.PlayerTrack> entry : this.tracked.entrySet()) {
            if (!visibleNow.contains(entry.getKey())) {
               ScanOverlay.PlayerTrack track = entry.getValue();
               track.ticksGone++;
               if (!track.flagged && track.wasUnderground && track.ticksSeen >= 60 && track.ticksGone >= 40) {
                  track.flagged = true;
                  this.flaggedChunks.put(new ChunkPos((int)Math.floor(track.doubleVal) >> 4, (int)Math.floor(track.doubleVal3) >> 4), new FlagData(10));
               }

               if (!track.flagged && track.visits.size() >= 2) {
                  ChunkPos repeat = this.findRepeatArea(track);
                  if (repeat != null) {
                     track.flagged = true;
                     this.flaggedChunks.compute(repeat, (key, old) -> new FlagData(old == null ? 8 : old.confidence + 3));
                  }
               }
            }
         }

         this.tracked.entrySet().removeIf(entryx -> ((ScanOverlay.PlayerTrack)entryx.getValue()).ticksGone > 1200);
         this.checkPhantomChunks();
      }
   }

   private ChunkPos findRepeatArea(ScanOverlay.PlayerTrack track) {
      List<long[]> visits = track.visits;

      for (int i = visits.size() - 1; i >= 1; i--) {
         long[] recent = visits.get(i);

         for (int j = i - 1; j >= 0; j--) {
            long[] older = visits.get(j);
            if (recent[2] - older[2] >= 30000L) {
               double dx = recent[0] - older[0];
               double dz = recent[1] - older[1];
               if (dx * dx + dz * dz < 4096.0) {
                  return new ChunkPos((int)recent[0] >> 4, (int)recent[1] >> 4);
               }
            }
         }
      }

      return null;
   }

   private void checkPhantomChunks() {
      if (mc.world != null && mc.player != null) {
         double viewRange = ((Integer)mc.options.getViewDistance().getValue()).intValue() * 16.0 + 32.0;
         double viewRangeSq = viewRange * viewRange;
         List<long[]> loads;
         synchronized (this.recentChunkLoads) {
            loads = new ArrayList<>(this.recentChunkLoads);
         }

         List<double[]> playerPositions = new ArrayList<>();
         playerPositions.add(new double[]{mc.player.getX(), mc.player.getZ()});

         for (AbstractClientPlayerEntity player : mc.world.getPlayers()) {
            if (player != mc.player) {
               playerPositions.add(new double[]{player.getX(), player.getZ()});
            }
         }

         for (long[] load : loads) {
            double centerX = (load[0] << 4) + 8.0;
            double centerZ = (load[1] << 4) + 8.0;
            boolean explained = false;

            for (double[] playerx : playerPositions) {
               double dx = centerX - playerx[0];
               double dz = centerZ - playerx[1];
               if (dx * dx + dz * dz < viewRangeSq) {
                  explained = true;
                  break;
               }
            }

            if (!explained) {
               ChunkPos chunk = new ChunkPos((int)load[0], (int)load[1]);
               this.flaggedChunks.compute(chunk, (key, old) -> {
                  if (old == null) {
                     return new FlagData(5);
                  } else {
                     old.confidence = Math.min(15, old.confidence + 1);
                     return old;
                  }
               });
            }
         }
      }
   }

   @Override
   public void onRender(MatrixStack matrices, float tickDelta) {
      if (mc.world != null && mc.player != null && !this.flaggedChunks.isEmpty()) {
         Camera camera = RenderUtils.getCamera();
         if (camera != null) {
            Vec3d cameraPos = RenderUtils.getCameraPos(camera);
            Vec3d tracerStart = RenderUtils.getCameraForward(camera).multiply(0.1);
            double yBottom = 63.0 - cameraPos.y;
            double yTop = yBottom + 0.02;
            double outlineBottom = yBottom - 0.3;
            double outlineTop = yTop + 0.3;
            RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);

            for (Entry<ChunkPos, ScanOverlay.FlagData> entry : this.flaggedChunks.entrySet()) {
               ChunkPos chunk = entry.getKey();
               int score = entry.getValue().confidence;
               double centerX = (chunk.x << 4) + 8.0 - cameraPos.x;
               double centerZ = (chunk.z << 4) + 8.0 - cameraPos.z;
               float confidence = Math.min(1.0F, (score - 3) / 12.0F);
               int red = (int)(180.0F + 75.0F * confidence);
               int green = (int)(255.0F - 220.0F * confidence);
               batch.renderFilledBox(centerX - 8.0, yBottom, centerZ - 8.0, centerX + 8.0, yTop, centerZ + 8.0, new Color(red, green, 20, 150));
               batch.renderOutlineBox(
                  centerX - 8.1, outlineBottom - 0.1, centerZ - 8.1, centerX + 8.1, outlineTop + 0.1, centerZ + 8.1, new Color(red, green, 20, 50)
               );
               batch.renderOutlineBox(centerX - 8.0, outlineBottom, centerZ - 8.0, centerX + 8.0, outlineTop, centerZ + 8.0, new Color(red, green, 20, 130));
               batch.renderOutlineBox(
                  centerX - 7.95, outlineBottom + 0.05, centerZ - 7.95, centerX + 7.95, outlineTop - 0.05, centerZ + 7.95, new Color(red, green, 20, 255)
               );
               if (this.tracers.getValue()) {
                  float width = this.tracerWidth.getValue().floatValue();
                  Vec3d target = new Vec3d(centerX, yBottom + 0.01, centerZ);
                  batch.renderLine(new Color(red, green, 20, 70), tracerStart, target, width + 1.9F);
                  batch.renderLine(new Color(red, green, 20, 140), tracerStart, target, width + 0.95F);
                  batch.renderLine(new Color(red, green, 20, 255), tracerStart, target, width);
               }
            }

            batch.flush();
         }
      }
   }

   final class FlagData {
      private int confidence;

      private FlagData(int confidence) {
         this.confidence = confidence;
      }
   }

   final class PlayerTrack {
      private String name;
      private double doubleVal;
      private double doubleVal2;
      private double doubleVal3;
      private int ticksSeen;
      private int ticksGone;
      private boolean wasUnderground;
      private boolean flagged;
      private final List<long[]> visits = new ArrayList<>();

      private PlayerTrack(String name) {
         this.name = name;
      }
   }
}


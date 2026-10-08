package com.chukl.addon.modules.water;

import com.chukl.addon.water.Category;
import com.chukl.addon.water.WModule;
import com.chukl.addon.water.Setting;
import com.chukl.addon.water.RenderUtils;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

public final class FutureDebug extends WModule {
   private static final int BASE_CHEST_THRESHOLD = 10;
   private static final double CHUNK_THICKNESS = 0.1;
   private static final double RENDER_Y = 63.0;
   private static final long SHAPE_FADE_MS = 1000L;
   private static final int MARKER_SPACING_CHUNKS = 7;
   private final Setting<Integer> scanRadius = new Setting<>("Scan Radius", 1, 1, 5);
   private final Setting<Boolean> smartCheck = new Setting<>("Smart Check", true);
   private final Setting<Integer> sensitivity = new Setting<>("Sensitivity", 5, -10, 10);
   private final Setting<Color> fillColor = new Setting<>("Fill Color", new Color(180, 60, 60, 40));
   private final Setting<Integer> fillAlpha = new Setting<>("Fill Alpha", 40, 0, 255);
   private static final int[][] SHAPES = new int[][]{
      {5, 3}, {3, 3}, {3, 3}, {4, 1}, {5, 5}, {3, 5}, {6, 2}, {1, 3}, {5, 2}, {5, 2}, {1, 2}, {1, 6}, {4, 4}, {1, 9}
   };
   private final Map<Long, Long> confirmedChunks = new ConcurrentHashMap<>();
   private final Set<ChunkPos> baseHits = ConcurrentHashMap.newKeySet();
   private final AtomicBoolean scanning = new AtomicBoolean(false);
   private ExecutorService scanExec;
   private int tickCount;

   public FutureDebug() {
      super("FutureDebug", Category.RENDER);
      this.addSetting(this.scanRadius);
      this.addSetting(this.smartCheck);
      this.addSetting(this.sensitivity);
      this.addSetting(this.fillColor);
      this.addSetting(this.fillAlpha);
   }

   @Override
   public void onEnable() {
      this.confirmedChunks.clear();
      this.baseHits.clear();
      this.tickCount = 0;
      this.scanning.set(false);
   }

   @Override
   public void onDisable() {
      this.confirmedChunks.clear();
      this.baseHits.clear();
      this.scanning.set(false);
      if (this.scanExec != null) {
         this.scanExec.shutdownNow();
      }
   }

   @Override
   public void onTick() {
      if (mc.world != null && mc.player != null) {
         ChunkPos playerChunk = mc.player.getChunkPos();
         int radius = this.scanRadius.getValue() * 5;
         this.baseHits.removeIf(cpx -> Math.abs(cpx.x - playerChunk.x) <= 1 && Math.abs(cpx.z - playerChunk.z) <= 1);
         if (++this.tickCount % 5 == 0) {
            if (this.scanning.compareAndSet(false, true)) {
               if (this.scanExec == null || this.scanExec.isShutdown()) {
                  this.scanExec = Executors.newSingleThreadExecutor(task -> {
                     Thread thread = new Thread(task, "futuredebug-scan");
                     thread.setDaemon(true);
                     return thread;
                  });
               }

               List<ChunkPos> positions = new ArrayList<>();
               List<WorldChunk> chunks = new ArrayList<>();

               for (int dx = -radius; dx <= radius; dx++) {
                  for (int dz = -radius; dz <= radius; dz++) {
                     ChunkPos cp = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                     WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(cp.x, cp.z, false);
                     if (chunk != null && !chunk.isEmpty()) {
                        positions.add(cp);
                        chunks.add(chunk);
                     }
                  }
               }

               long now = System.currentTimeMillis();
               this.scanExec.submit(() -> {
                  try {
                     for (int i = 0; i < positions.size(); i++) {
                        ChunkPos cpx = positions.get(i);
                        WorldChunk chunkx = chunks.get(i);
                        long key = cpx.toLong();
                        if (!this.confirmedChunks.containsKey(key) && this.canAddMarker(cpx) && this.isUnknownGeode(chunkx)) {
                           this.confirmedChunks.put(key, now);
                        }

                        if (this.hasChestsBelowZero(chunkx)) {
                           this.baseHits.add(cpx);
                        }
                     }
                  } catch (Exception var13) {
                  } finally {
                     this.scanning.set(false);
                  }
               });
            }
         }
      }
   }

   @Override
   public void onRender(MatrixStack matrices, float tickDelta) {
      if (mc.world != null && mc.player != null) {
         if (!this.confirmedChunks.isEmpty() || !this.baseHits.isEmpty()) {
            Camera camera = RenderUtils.getCamera();
            if (camera != null) {
               Vec3d camPos = camera.getCameraPos();
               Color base = this.fillColor.getValue();
               long now = System.currentTimeMillis();
               double y1 = 63.0 - camPos.y;
               double y2 = 63.1 - camPos.y;
               matrices.push();

               try {
                  RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);

                  for (Entry<Long, Long> entry : this.confirmedChunks.entrySet()) {
                     this.renderAnimatedShape(batch, entry.getKey(), entry.getValue(), now, camPos, y1, y2, base);
                  }

                  batch.flush();
               } finally {
                  matrices.pop();
               }
            }
         }
      }
   }

   private void renderAnimatedShape(RenderUtils.WorldBatch batch, long key, long createdAt, long now, Vec3d camPos, double y1, double y2, Color base) {
      int chunkX = ChunkPos.getPackedX(key);
      int chunkZ = ChunkPos.getPackedZ(key);
      long hash = key * -7046029254386353131L;
      hash ^= hash >>> 32;
      int[] shape = SHAPES[Math.floorMod(hash, SHAPES.length)];
      int width = shape[0];
      int length = shape[1];
      int offsetX = -((width - 1) / 2);
      int offsetZ = -((length - 1) / 2);
      float progress = Math.min(1.0F, Math.max(0.0F, (float)(now - createdAt) / 1000.0F));
      if (!(progress <= 0.0F)) {
         float eased = 1.0F - (float)Math.pow(1.0F - progress, 3.0);
         int alpha = Math.max(0, Math.min(255, Math.round(this.fillAlpha.getValue().intValue() * eased)));
         Color animatedFill = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
         double x1 = (chunkX + offsetX << 4) - camPos.x;
         double z1 = (chunkZ + offsetZ << 4) - camPos.z;
         batch.renderFilledBox(x1, y1, z1, x1 + width * 16.0, y2, z1 + length * 16.0, animatedFill);
      }
   }

   private boolean canAddMarker(ChunkPos candidate) {
      for (long existing : this.confirmedChunks.keySet()) {
         int existingX = ChunkPos.getPackedX(existing);
         int existingZ = ChunkPos.getPackedZ(existing);
         if (Math.abs(existingX - candidate.x) <= 7 && Math.abs(existingZ - candidate.z) <= 7) {
            return false;
         }
      }

      return true;
   }

   private boolean isUnknownGeode(WorldChunk chunk) {
      ChunkSection[] sections = chunk.getSectionArray();
      int minY = chunk.getBottomY();
      int amethyst = 0;
      int calcite = 0;
      int basalt = 0;
      int airNext = 0;
      int solidNext = 0;
      int sumX = 0;
      int sumY = 0;
      int sumZ = 0;
      List<int[]> positions = new ArrayList<>();
      byte[][][] map = new byte[16][384][16];

      for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
         int baseY = minY + sectionIndex * 16;
         if (baseY <= 64 && baseY + 16 >= -64) {
            ChunkSection section = sections[sectionIndex];
            if (section != null && !section.isEmpty()) {
               for (int lx = 0; lx < 16; lx++) {
                  for (int ly = 0; ly < 16; ly++) {
                     int worldY = baseY + ly;
                     if (worldY >= -64 && worldY <= 64) {
                        int mappedY = worldY + 64;
                        if (mappedY >= 0 && mappedY < 384) {
                           for (int lz = 0; lz < 16; lz++) {
                              BlockState state = section.getBlockState(lx, ly, lz);
                              if (state.isOf(Blocks.BUDDING_AMETHYST)) {
                                 return false;
                              }

                              if (this.isCountableAmethyst(state)) {
                                 map[lx][mappedY][lz] = 1;
                                 amethyst++;
                                 sumX += lx;
                                 sumY += worldY;
                                 sumZ += lz;
                                 positions.add(new int[]{lx, worldY, lz});
                              } else if (state.isOf(Blocks.CALCITE)) {
                                 map[lx][mappedY][lz] = 2;
                                 calcite++;
                              } else if (state.isOf(Blocks.SMOOTH_BASALT)) {
                                 map[lx][mappedY][lz] = 3;
                                 basalt++;
                              } else if (!state.isAir() && !state.isOf(Blocks.CAVE_AIR) && !state.isOf(Blocks.VOID_AIR)) {
                                 map[lx][mappedY][lz] = 5;
                              } else {
                                 map[lx][mappedY][lz] = 4;
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }

      if (amethyst == 0) {
         return false;
      } else if (!this.smartCheck.getValue()) {
         return amethyst >= this.getMinAmethyst();
      } else {
         int yMin = Integer.MAX_VALUE;
         int yMax = Integer.MIN_VALUE;

         for (int[] pos : positions) {
            yMin = Math.min(yMin, pos[1]);
            yMax = Math.max(yMax, pos[1]);
         }

         float density = amethyst / (256.0F * Math.max(1, yMax - yMin + 1));
         float centerX = (float)sumX / amethyst;
         float centerY = (float)sumY / amethyst;
         float centerZ = (float)sumZ / amethyst;
         float averageDistance = 0.0F;

         for (int[] pos : positions) {
            float dx = pos[0] - centerX;
            float dy = pos[1] - centerY;
            float dz = pos[2] - centerZ;
            averageDistance += (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
         }

         averageDistance /= amethyst;
         int[] ndx = new int[]{1, -1, 0, 0, 0, 0};
         int[] ndy = new int[]{0, 0, 1, -1, 0, 0};
         int[] ndz = new int[]{0, 0, 0, 0, 1, -1};

         for (int[] pos : positions) {
            int ax = pos[0];
            int ay = pos[1] + 64;
            int az = pos[2];

            for (int n = 0; n < 6; n++) {
               int nx = ax + ndx[n];
               int ny = ay + ndy[n];
               int nz = az + ndz[n];
               if (nx >= 0 && nx <= 15 && ny >= 0 && ny < 384 && nz >= 0 && nz <= 15) {
                  byte neighbor = map[nx][ny][nz];
                  if (neighbor == 4) {
                     airNext++;
                  } else if (neighbor >= 2) {
                     solidNext++;
                  }
               }
            }
         }

         int totalNeighbors = airNext + solidNext;
         float airRatio = totalNeighbors > 0 ? (float)airNext / totalNeighbors : 0.0F;
         return amethyst >= this.getMinAmethyst()
            && density >= this.getMinDensity()
            && calcite + basalt >= this.getMinCalciteBasalt()
            && averageDistance <= this.getMaxAvgDist()
            && airRatio <= this.getMaxAirRatio();
      }
   }

   private boolean hasChestsBelowZero(WorldChunk chunk) {
      int count = 0;
      ChunkSection[] sections = chunk.getSectionArray();
      int minY = chunk.getBottomY();

      for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
         int baseY = minY + sectionIndex * 16;
         if (baseY >= 0) {
            break;
         }

         ChunkSection section = sections[sectionIndex];
         if (section != null && !section.isEmpty() && section.hasAny(statex -> statex.isOf(Blocks.CHEST) || statex.isOf(Blocks.TRAPPED_CHEST))) {
            int maxLocalY = Math.min(15, -baseY - 1);

            for (int lx = 0; lx < 16; lx++) {
               for (int lz = 0; lz < 16; lz++) {
                  for (int ly = 0; ly <= maxLocalY; ly++) {
                     BlockState state = section.getBlockState(lx, ly, lz);
                     if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST)) {
                        if (++count >= 10) {
                           return true;
                        }
                     }
                  }
               }
            }
         }
      }

      return false;
   }

   private boolean isCountableAmethyst(BlockState state) {
      return state.isOf(Blocks.AMETHYST_CLUSTER)
         || state.isOf(Blocks.LARGE_AMETHYST_BUD)
         || state.isOf(Blocks.MEDIUM_AMETHYST_BUD)
         || state.isOf(Blocks.SMALL_AMETHYST_BUD)
         || state.isOf(Blocks.AMETHYST_BLOCK);
   }

   private int getMinAmethyst() {
      return (int)(40.0 - (this.sensitivity.getValue() - 1) * 3.5555555555555554);
   }

   private float getMinDensity() {
      return 0.01F - (this.sensitivity.getValue() - 1) * 8.8888896E-4F;
   }

   private int getMinCalciteBasalt() {
      return (int)(15.0 - (this.sensitivity.getValue() - 1) * 1.3333333333333333);
   }

   private float getMaxAirRatio() {
      return 0.35F + (this.sensitivity.getValue() - 1) * 0.05F;
   }

   private float getMaxAvgDist() {
      return 7.0F + (this.sensitivity.getValue() - 1) * 0.5555556F;
   }
}


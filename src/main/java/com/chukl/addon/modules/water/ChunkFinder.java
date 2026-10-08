package com.chukl.addon.modules.water;

import com.chukl.addon.water.Category;
import com.chukl.addon.water.WModule;
import com.chukl.addon.water.Setting;
import com.chukl.addon.water.RenderUtils;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
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

public final class ChunkFinder extends WModule {
   private static final int BASE_CHEST_THRESHOLD = 10;
   private static final double CHUNK_THICKNESS = 0.1;
   private static final double FLASH_THICKNESS = 0.3;
   private static final long FLASH_ON_MS = 150L;
   private static final long FLASH_CYCLE_MS = 400L;
   private final Setting<Integer> scanRadius = new Setting<>("Scan Radius", 1, 1, 5);
   private final Setting<Integer> clusterThreshold = new Setting<>("Sim Chunks", 10, 1, 10);
   private final Setting<Color> fillColor = new Setting<>("Fill Color", new Color(180, 60, 60, 40));
   private final Setting<Integer> fillAlpha = new Setting<>("Fill Alpha", 40, 0, 255);
   private final Set<ChunkPos> amethystHits = ConcurrentHashMap.newKeySet();
   private final Set<ChunkPos> baseHits = ConcurrentHashMap.newKeySet();
   private volatile Set<ChunkPos> amethystRenderCache = Collections.emptySet();
   private volatile long flashAnchorMs = 0L;
   private final Setting<Float> glassIntensity = new Setting<>("Glass Intensity", 1.5F, 0.0F, 2.0F);
   private ExecutorService scanExec;
   private final AtomicBoolean scanning = new AtomicBoolean(false);
   private int tickCount = 0;

   public ChunkFinder() {
      super("Chunk Finder", Category.RENDER);
      this.addSetting(this.scanRadius);
      this.addSetting(this.clusterThreshold);
      this.addSetting(this.fillColor);
      this.addSetting(this.fillAlpha);
      this.addSetting(this.glassIntensity);
   }

   @Override
   public void onEnable() {
      this.reset();
   }

   @Override
   public void onDisable() {
      this.reset();
      if (this.scanExec != null) {
         this.scanExec.shutdownNow();
      }
   }

   private void reset() {
      this.amethystHits.clear();
      this.baseHits.clear();
      this.amethystRenderCache = Collections.emptySet();
      this.tickCount = 0;
      this.scanning.set(false);
      this.flashAnchorMs = 0L;
   }

   @Override
   public void onTick() {
      if (mc.world != null && mc.player != null) {
         ChunkPos center = mc.player.getChunkPos();
         int r = this.scanRadius.getValue() * 5;
         this.amethystHits.removeIf(cpx -> Math.abs(cpx.x - center.x) > r + 2 || Math.abs(cpx.z - center.z) > r + 2);
         this.baseHits.removeIf(cpx -> Math.abs(cpx.x - center.x) > r + 2 || Math.abs(cpx.z - center.z) > r + 2);
         if (++this.tickCount % 5 == 0) {
            if (this.scanning.compareAndSet(false, true)) {
               if (this.scanExec == null || this.scanExec.isShutdown()) {
                  this.scanExec = Executors.newSingleThreadExecutor(t -> {
                     Thread th = new Thread(t, "chunkfinder-scan");
                     th.setDaemon(true);
                     return th;
                  });
               }

               List<ChunkPos> toScan = new ArrayList<>();
               List<WorldChunk> rawChunks = new ArrayList<>();
               int threshold = this.clusterThreshold.getValue();

               for (int dx = -r; dx <= r; dx++) {
                  for (int dz = -r; dz <= r; dz++) {
                     ChunkPos cp = new ChunkPos(center.x + dx, center.z + dz);
                     WorldChunk wc = mc.world.getChunkManager().getWorldChunk(cp.x, cp.z, false);
                     if (wc != null && !wc.isEmpty()) {
                        toScan.add(cp);
                        rawChunks.add(wc);
                     }
                  }
               }

               this.scanExec.submit(() -> {
                  try {
                     Map<ChunkPos, Integer> hitCounts = new HashMap<>();
                     Set<ChunkPos> newBaseHits = new HashSet<>();

                     for (int i = 0; i < toScan.size(); i++) {
                        ChunkPos cpx = toScan.get(i);
                        WorldChunk wcx = rawChunks.get(i);
                        int hits = this.countAmethystBlocks(wcx);
                        if (hits >= threshold) {
                           hitCounts.put(cpx, hits);
                        }

                        if (this.hasChestsBelowZero(wcx)) {
                           newBaseHits.add(cpx);
                        }
                     }

                     Set<ChunkPos> newAmethyst = this.limitSuspiciousChunks(hitCounts, center, threshold);
                     Set<ChunkPos> newRenderShape = this.buildShapeForms(newAmethyst, threshold);
                     boolean undergroundCheck = mc.player != null && mc.player.getY() <= -2.0;
                     if (undergroundCheck) {
                        newRenderShape.removeIf(cpxx -> !newBaseHits.contains(cpxx));
                     }

                     if (!newAmethyst.equals(this.amethystHits) || !newBaseHits.equals(this.baseHits) || !newRenderShape.equals(this.amethystRenderCache)) {
                        this.amethystRenderCache = Collections.unmodifiableSet(newRenderShape);
                     }

                     this.amethystHits.clear();
                     this.amethystHits.addAll(newAmethyst);
                     this.baseHits.clear();
                     this.baseHits.addAll(newBaseHits);
                  } catch (Exception var14) {
                  } finally {
                     this.scanning.set(false);
                  }
               });
            }
         }
      }
   }

   private Set<ChunkPos> limitSuspiciousChunks(Map<ChunkPos, Integer> hitCounts, ChunkPos center, int threshold) {
      List<Entry<ChunkPos, Integer>> sorted = new ArrayList<>(hitCounts.entrySet());
      sorted.sort((a, b) -> {
         int byHits = Integer.compare(b.getValue(), a.getValue());
         return byHits != 0 ? byHits : Integer.compare(this.distanceSq(center, a.getKey()), this.distanceSq(center, b.getKey()));
      });
      int maxChunks = Math.max(3, 33 - threshold * 3);
      Set<ChunkPos> result = new LinkedHashSet<>();

      for (Entry<ChunkPos, Integer> entry : sorted) {
         if (result.size() >= maxChunks) {
            break;
         }

         result.add(entry.getKey());
      }

      return result;
   }

   private int distanceSq(ChunkPos a, ChunkPos b) {
      int dx = a.x - b.x;
      int dz = a.z - b.z;
      return dx * dx + dz * dz;
   }

   private Set<ChunkPos> buildShapeForms(Set<ChunkPos> source, int threshold) {
      Set<ChunkPos> shape = new HashSet<>();
      if (threshold >= 10) {
         shape.addAll(source);
         return shape;
      } else {
         int[][] forms = new int[][]{{1, 4}, {2, 4}, {1, 2}, {3, 1}, {5, 4}, {5, 6}};

         for (ChunkPos c : source) {
            Random rng = new Random(Math.abs(c.hashCode()));
            int[] sel = forms[rng.nextInt(forms.length)];
            int w = sel[0];
            int h = sel[1];
            if (rng.nextBoolean()) {
               int tmp = w;
               w = h;
               h = tmp;
            }

            int sx = -(w / 2);
            int sz = -(h / 2);

            for (int dx = 0; dx < w; dx++) {
               for (int dz = 0; dz < h; dz++) {
                  shape.add(new ChunkPos(c.x + sx + dx, c.z + sz + dz));
               }
            }
         }

         return shape;
      }
   }

   private int countAmethystBlocks(WorldChunk chunk) {
      int count = 0;
      ChunkSection[] sections = chunk.getSectionArray();
      int minY = chunk.getBottomY();

      for (int si = 0; si < sections.length; si++) {
         int sectionBottomY = minY + si * 16;
         if (sectionBottomY > 32) {
            break;
         }

         ChunkSection section = sections[si];
         if (section != null && !section.isEmpty() && section.hasAny(this::isAmethystBlock)) {
            for (int lx = 0; lx < 16; lx++) {
               for (int ly = 0; ly < 16; ly++) {
                  for (int lz = 0; lz < 16; lz++) {
                     if (this.isAmethystBlock(section.getBlockState(lx, ly, lz))) {
                        count++;
                     }
                  }
               }
            }
         }
      }

      return count;
   }

   private boolean isAmethystBlock(BlockState state) {
      return state.isOf(Blocks.AMETHYST_CLUSTER) || state.isOf(Blocks.AMETHYST_BLOCK);
   }

   private boolean hasChestsBelowZero(WorldChunk chunk) {
      int chestCount = 0;
      ChunkSection[] sections = chunk.getSectionArray();
      int minY = chunk.getBottomY();

      for (int si = 0; si < sections.length; si++) {
         int sectionBottomY = minY + si * 16;
         if (sectionBottomY >= 0) {
            break;
         }

         ChunkSection section = sections[si];
         if (section != null && !section.isEmpty() && section.hasAny(bsx -> bsx.isOf(Blocks.CHEST) || bsx.isOf(Blocks.TRAPPED_CHEST))) {
            int lyMax = Math.min(15, -sectionBottomY - 1);

            for (int lx = 0; lx < 16; lx++) {
               for (int lz = 0; lz < 16; lz++) {
                  for (int ly = 0; ly <= lyMax; ly++) {
                     BlockState bs = section.getBlockState(lx, ly, lz);
                     if (bs.isOf(Blocks.CHEST) || bs.isOf(Blocks.TRAPPED_CHEST)) {
                        if (++chestCount >= 10) {
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

   @Override
   public void onRender(MatrixStack matrices, float tickDelta) {
      if (mc.world != null && mc.player != null) {
         if (!this.amethystRenderCache.isEmpty() || !this.baseHits.isEmpty()) {
            Camera cam = RenderUtils.getCamera();
            if (cam != null) {
               Vec3d cp = RenderUtils.getCameraPos(cam);
               double aBot = 47.0 - cp.y;
               double aTop = aBot + 0.1;
               Color base = this.fillColor.getValue();
               int alpha = this.fillAlpha.getValue();
               Color fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
               boolean flashOn = false;
               int flashAlpha = 0;
               if (!this.baseHits.isEmpty()) {
                  long now = System.currentTimeMillis();
                  if (this.flashAnchorMs == 0L) {
                     this.flashAnchorMs = now;
                  }

                  long posInCycle = (now - this.flashAnchorMs) % 400L;
                  if (posInCycle < 150L) {
                     flashOn = true;
                     float t = (float)posInCycle / 150.0F;
                     flashAlpha = (int)((1.0F - t) * 200.0F);
                  }
               } else {
                  this.flashAnchorMs = 0L;
               }

               matrices.push();
               RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);

               for (ChunkPos c : this.amethystRenderCache) {
                  double x1 = (c.x << 4) - cp.x;
                  double z1 = (c.z << 4) - cp.z;
                  batch.renderGlassPane(x1, aTop, z1, x1 + 16.0, z1 + 16.0, fill, this.glassIntensity.getValue(), System.nanoTime() / 1.E9);
               }

               if (flashOn && flashAlpha > 0) {
                  Color flashColor = new Color(255, 255, 255, flashAlpha);

                  for (ChunkPos c : this.baseHits) {
                     double x1 = (c.x << 4) - cp.x;
                     double z1 = (c.z << 4) - cp.z;
                     batch.renderGlassPane(x1, aTop + 0.3, z1, x1 + 16.0, z1 + 16.0, flashColor, this.glassIntensity.getValue(), System.nanoTime() / 1.E9);
                  }
               }

               batch.flush();
               matrices.pop();
            }
         }
      }
   }
}


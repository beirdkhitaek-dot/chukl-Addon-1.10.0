package com.chukl.addon.modules.water;

import com.chukl.addon.water.Category;
import com.chukl.addon.water.WModule;
import com.chukl.addon.water.Setting;
import com.chukl.addon.water.RenderUtils;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.WorldChunk;

public final class LightDebug extends WModule {
   private static final int SCAN_RADIUS = 3;
   private static final int SCAN_INTERVAL = 10;
   private static final int MAX_RENDER_BLOCKS = 50000;
   private final Setting<Integer> minLight = new Setting<>("Min Light", 0, 0, 15);
   private final Setting<Integer> maxLight = new Setting<>("Max Light", 7, 0, 15);
   private final Setting<Integer> minY = new Setting<>("Min Y", -51, -64, 20);
   private final Setting<Integer> maxY = new Setting<>("Max Y", 20, -64, 20);
   private final Setting<Color> colorLow = new Setting<>("Color Low", new Color(50, 200, 50, 60));
   private final Setting<Color> colorHigh = new Setting<>("Color High", new Color(255, 50, 50, 60));
   private final Map<ChunkPos, List<LightDebug.LightBlock>> lightBlocks = new ConcurrentHashMap<>();
   private ChunkPos lastPlayerChunk = null;
   private int scanTimer = 0;
   private ExecutorService scanExec;
   private final AtomicBoolean scanning = new AtomicBoolean(false);

   public LightDebug() {
      super("LightDebug", Category.RENDER);
      this.addSetting(this.minLight);
      this.addSetting(this.maxLight);
      this.addSetting(this.minY);
      this.addSetting(this.maxY);
      this.addSetting(this.colorLow);
      this.addSetting(this.colorHigh);
   }

   @Override
   public void onEnable() {
      this.lightBlocks.clear();
      this.lastPlayerChunk = null;
      this.scanTimer = 0;
      this.scanning.set(false);
   }

   @Override
   public void onDisable() {
      this.lightBlocks.clear();
      if (this.scanExec != null) {
         this.scanExec.shutdownNow();
      }
   }

   @Override
   public void onTick() {
      if (mc.world != null && mc.player != null) {
         ChunkPos currentChunk = mc.player.getChunkPos();
         boolean moved = !currentChunk.equals(this.lastPlayerChunk);
         this.lastPlayerChunk = currentChunk;
         if (++this.scanTimer % 10 == 0 || moved) {
            if (this.scanning.compareAndSet(false, true)) {
               if (this.scanExec == null || this.scanExec.isShutdown()) {
                  this.scanExec = Executors.newSingleThreadExecutor(t -> {
                     Thread th = new Thread(t, "lightdebug-scan");
                     th.setDaemon(true);
                     return th;
                  });
               }

               int minL = this.minLight.getValue();
               int maxL = this.maxLight.getValue();
               int yMin = Math.min(this.minY.getValue(), this.maxY.getValue());
               int yMax = Math.max(this.minY.getValue(), this.maxY.getValue());
               List<ChunkPos> toScan = new ArrayList<>();
               List<WorldChunk> rawChunks = new ArrayList<>();

               for (int dx = -3; dx <= 3; dx++) {
                  for (int dz = -3; dz <= 3; dz++) {
                     ChunkPos cp = new ChunkPos(currentChunk.x + dx, currentChunk.z + dz);
                     WorldChunk wc = mc.world.getChunkManager().getWorldChunk(cp.x, cp.z, false);
                     if (wc != null && !wc.isEmpty()) {
                        toScan.add(cp);
                        rawChunks.add(wc);
                     }
                  }
               }

               this.lightBlocks.keySet().removeIf(cpx -> Math.abs(cpx.x - currentChunk.x) > 3 + 1 || Math.abs(cpx.z - currentChunk.z) > 3 + 1);
               this.scanExec.submit(() -> {
                  try {
                     for (int i = 0; i < toScan.size(); i++) {
                        ChunkPos cpx = toScan.get(i);
                        List<LightDebug.LightBlock> blocks = this.scanChunk(rawChunks.get(i), cpx, minL, maxL, yMin, yMax);
                        if (blocks.isEmpty()) {
                           this.lightBlocks.remove(cpx);
                        } else {
                           this.lightBlocks.put(cpx, blocks);
                        }
                     }
                  } catch (Exception var13x) {
                  } finally {
                     this.scanning.set(false);
                  }
               });
            }
         }
      }
   }

   private List<LightDebug.LightBlock> scanChunk(WorldChunk chunk, ChunkPos cp, int minL, int maxL, int yMin, int yMax) {
      List<LightDebug.LightBlock> result = new ArrayList<>();
      int baseX = cp.x << 4;
      int baseZ = cp.z << 4;

      for (int y = yMin; y <= yMax; y++) {
         for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
               BlockPos pos = new BlockPos(baseX + lx, y, baseZ + lz);
               int level = mc.world.getLightLevel(LightType.BLOCK, pos);
               if (level >= minL && level <= maxL) {
                  result.add(new LightBlock(pos, level));
               }
            }
         }
      }

      return result;
   }

   @Override
   public void onRender(MatrixStack matrices, float tickDelta) {
      if (mc.world != null && mc.player != null) {
         if (!this.lightBlocks.isEmpty()) {
            Camera cam = RenderUtils.getCamera();
            if (cam != null) {
               Vec3d camPos = RenderUtils.getCameraPos(cam);
               int maxL = this.maxLight.getValue();
               int minL = this.minLight.getValue();
               matrices.push();

               try {
                  RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);
                  int rendered = 0;

                  label65:
                  for (List<LightDebug.LightBlock> blocks : this.lightBlocks.values()) {
                     for (LightDebug.LightBlock lb : blocks) {
                        if (rendered++ >= 50000) {
                           break label65;
                        }

                        Color fill = this.interpolateColor(lb.level, minL, maxL);
                        double x1 = lb.pos.getX() - camPos.x;
                        double y1 = lb.pos.getY() - camPos.y;
                        double z1 = lb.pos.getZ() - camPos.z;
                        batch.renderFilledBox(x1, y1, z1, x1 + 1.0, y1 + 1.0, z1 + 1.0, fill);
                     }
                  }

                  batch.flush();
               } finally {
                  matrices.pop();
               }
            }
         }
      }
   }

   private Color interpolateColor(int level, int minL, int maxL) {
      float range = Math.max(1, maxL - minL);
      float ratio = Math.max(0.0F, Math.min(1.0F, (level - minL) / range));
      Color low = this.colorLow.getValue();
      Color high = this.colorHigh.getValue();
      int r = (int)(low.getRed() + (high.getRed() - low.getRed()) * ratio);
      int g = (int)(low.getGreen() + (high.getGreen() - low.getGreen()) * ratio);
      int b = (int)(low.getBlue() + (high.getBlue() - low.getBlue()) * ratio);
      int a = (int)(low.getAlpha() + (high.getAlpha() - low.getAlpha()) * ratio);
      return new Color(r, g, b, a);
   }

   final class LightBlock {
      final BlockPos pos;
      final int level;

      LightBlock(BlockPos pos, int level) {
         this.pos = pos;
         this.level = level;
      }
   }
}


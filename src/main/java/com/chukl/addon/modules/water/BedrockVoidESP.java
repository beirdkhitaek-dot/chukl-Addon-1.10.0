package com.chukl.addon.modules.water;

import com.chukl.addon.water.Category;
import com.chukl.addon.water.WModule;
import com.chukl.addon.water.WaterPlus;
import com.chukl.addon.water.Setting;
import com.chukl.addon.water.RenderUtils;
import java.awt.Color;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

public final class BedrockVoidESP extends WModule {
   private static final List<Integer> OVERWORLD_Y = List.of(-64, -63, -62, -61, -60);
   private static final List<Integer> NETHER_FLOOR = List.of(0, 1, 2, 3, 4);
   private static final List<Integer> NETHER_ROOF = List.of(123, 124, 125, 126, 127);
   private final Setting<Integer> minVoidSize = new Setting<>("Min Void Size", 1, 1, 50);
   private final Setting<Boolean> showEsp = new Setting<>("Show ESP", true);
   private final Setting<Boolean> showTracers = new Setting<>("Tracers", true);
   private final Setting<Color> espColor = new Setting<>("ESP Color", new Color(255, 0, 0, 150));
   private final Setting<Color> tracerColor = new Setting<>("Tracer Color", new Color(255, 0, 0, 200));
   private final Set<BlockPos> voidBlocks = ConcurrentHashMap.newKeySet();
   private ExecutorService threadPool;

   public BedrockVoidESP() {
      super("Bedrock Void ESP", Category.RENDER);
      this.addSetting(this.minVoidSize);
      this.addSetting(this.showEsp);
      this.addSetting(this.showTracers);
      this.addSetting(this.espColor);
      this.addSetting(this.tracerColor);
   }

   @Override
   public void onEnable() {
      this.voidBlocks.clear();
      this.threadPool = Executors.newFixedThreadPool(2);
      this.scanAllChunks();
   }

   @Override
   public void onDisable() {
      this.voidBlocks.clear();
      if (this.threadPool != null) {
         this.threadPool.shutdownNow();
      }
   }

   @Override
   public void onPacketReceive(Packet<?> packet) {
      if (packet instanceof ChunkDataS2CPacket p) {
         if (mc.world != null) {
            int cx = p.getChunkX();
            int cz = p.getChunkZ();
            WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(cx, cz, false);
            if (chunk != null) {
               ChunkPos cp = new ChunkPos(cx, cz);
               this.voidBlocks.removeIf(pos -> new ChunkPos(pos).equals(cp));
               if (this.threadPool != null && !this.threadPool.isShutdown()) {
                  this.threadPool.submit(() -> this.scanChunk(chunk));
               }
            }
         }
      }
   }

   @Override
   public void onRender(MatrixStack matrices, float tickDelta) {
      if (mc.world != null && mc.player != null && !this.voidBlocks.isEmpty()) {
         if (this.showEsp.getValue() || this.showTracers.getValue()) {
            Camera cam = RenderUtils.getCamera();
            if (cam != null) {
               Vec3d camPos = RenderUtils.getCameraPos(cam);
               Vec3d camForward = RenderUtils.getCameraForward(cam);
               Vec3d camRight = RenderUtils.getCameraRight(cam);
               Vec3d camUp = RenderUtils.getCameraUp(camForward, camRight);
               Vec3d tracerStart = camForward.multiply(150.0);
               Color fill = this.espColor.getValue();
               Color outline = new Color(fill.getRed(), fill.getGreen(), fill.getBlue(), 255);
               Color tracer = this.tracerColor.getValue();
               matrices.push();

               try {
                  RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);
                  int rendered = 0;

                  for (BlockPos pos : this.voidBlocks) {
                     if (rendered++ > 500) {
                        break;
                     }

                     double bx = pos.getX() - camPos.x;
                     double by = pos.getY() - camPos.y;
                     double bz = pos.getZ() - camPos.z;
                     if (this.showEsp.getValue()) {
                        batch.renderFilledBox(bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0, fill);
                        batch.renderOutlineBox(bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0, outline);
                     }

                     if (this.showTracers.getValue() && rendered <= 50) {
                        Vec3d rel = new Vec3d(bx + 0.5, by + 0.5, bz + 0.5);
                        Vec3d end = RenderUtils.getSpreadTracerEnd(rel, camForward, camRight, camUp, 24.0, 2.75);
                        batch.renderLine(tracer, tracerStart, end, WaterPlus.tracerLineWidth());
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

   private void scanAllChunks() {
      if (mc.world != null && mc.player != null) {
         ChunkPos center = mc.player.getChunkPos();
         int radius = Math.min(mc.options.getClampedViewDistance(), 6);

         for (int x = center.x - radius; x <= center.x + radius; x++) {
            for (int z = center.z - radius; z <= center.z + radius; z++) {
               WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(x, z, false);
               if (chunk != null && this.threadPool != null && !this.threadPool.isShutdown()) {
                  this.threadPool.submit(() -> this.scanChunk(chunk));
               }
            }
         }
      }
   }

   private void scanChunk(WorldChunk chunk) {
      if (mc.world != null) {
         List<Integer> yLevels = this.getYLevels();
         if (!yLevels.isEmpty()) {
            Set<BlockPos> processed = new HashSet<>();
            int baseX = chunk.getPos().getStartX();
            int baseZ = chunk.getPos().getStartZ();

            for (int y : yLevels) {
               for (int lx = 0; lx < 16; lx++) {
                  for (int lz = 0; lz < 16; lz++) {
                     BlockPos pos = new BlockPos(baseX + lx, y, baseZ + lz);
                     if (!processed.contains(pos)) {
                        BlockState state = chunk.getBlockState(pos);
                        if (!state.isOf(Blocks.BEDROCK) && (state.isAir() || !state.isOf(Blocks.BEDROCK))) {
                           List<BlockPos> group = this.floodFill(pos, yLevels, processed, chunk);
                           if (group.size() >= this.minVoidSize.getValue()) {
                              this.voidBlocks.addAll(group);
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private List<BlockPos> floodFill(BlockPos start, List<Integer> yLevels, Set<BlockPos> processed, WorldChunk chunk) {
      List<BlockPos> group = new ArrayList<>();
      Queue<BlockPos> queue = new LinkedList<>();
      queue.offer(start);
      int baseX = chunk.getPos().getStartX();
      int baseZ = chunk.getPos().getStartZ();

      while (!queue.isEmpty() && group.size() < 256) {
         BlockPos current = queue.poll();
         if (!processed.contains(current)) {
            processed.add(current);
            BlockState state = chunk.getBlockState(current);
            if (!state.isOf(Blocks.BEDROCK)) {
               group.add(current);

               for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                  BlockPos nb = current.offset(dir);
                  if (!processed.contains(nb)) {
                     int nx = nb.getX() - baseX;
                     int nz = nb.getZ() - baseZ;
                     if (nx >= 0 && nx < 16 && nz >= 0 && nz < 16 && yLevels.contains(nb.getY())) {
                        queue.offer(nb);
                     }
                  }
               }
            }
         }
      }

      return group;
   }

   private List<Integer> getYLevels() {
      if (mc.world == null) {
         return Collections.emptyList();
      } else {
         String dim = mc.world.getRegistryKey().getValue().toString();
         if (dim.equals("minecraft:overworld")) {
            return OVERWORLD_Y;
         } else if (dim.equals("minecraft:the_nether")) {
            List<Integer> all = new ArrayList<>(NETHER_FLOOR);
            all.addAll(NETHER_ROOF);
            return all;
         } else {
            return Collections.emptyList();
         }
      }
   }
}


package com.chukl.addon.modules.water;

import com.chukl.addon.water.Category;
import com.chukl.addon.water.WModule;
import com.chukl.addon.water.Setting;
import com.chukl.addon.water.RenderUtils;
import java.awt.Color;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.VertexConsumerProvider.Immediate;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.util.math.MatrixStack.Entry;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.BlockPos.Mutable;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

public final class HoleESP extends WModule {
   private static final int MAX_CHUNKS_PER_TICK = 200;
   private static final int FIXED_MIN_DEPTH = 7;
   private final Setting<Double> alpha = new Setting<>("Fill Alpha", 60.0, 0.0, 255.0);
   private final Setting<Color> color = new Setting<>("Color", new Color(255, 100, 0));
   private final Setting<Double> range = new Setting<>("Range", 64.0, 16.0, 128.0);
   private final Setting<Boolean> gradientFill = new Setting<>("Gradient Fill", true);
   private final Map<Long, HoleESP.TrackedChunk> chunks = new ConcurrentHashMap<>();
   private final Queue<Long> chunkQueue = new ArrayDeque<>();
   private final Set<Long> queuedChunks = ConcurrentHashMap.newKeySet();
   private final Set<HoleESP.HoleData> holes = ConcurrentHashMap.newKeySet();
   private ExecutorService executor;
   private ClientWorld currentWorld;

   public HoleESP() {
      super("Hole ESP", Category.RENDER);
      this.addSetting(this.alpha);
      this.addSetting(this.color);
      this.addSetting(this.range);
      this.addSetting(this.gradientFill);
   }

   @Override
   public void onEnable() {
      this.currentWorld = mc.world;
      this.ensureExecutor();
      this.clear();
   }

   @Override
   public void onDisable() {
      this.shutdownExecutor();
      this.clear();
      this.currentWorld = null;
   }

   @Override
   public void onTick() {
      if (mc.world != null && mc.player != null) {
         if (mc.world != this.currentWorld) {
            this.currentWorld = mc.world;
            this.clear();
         }

         this.ensureExecutor();
         this.updateChunks();
      }
   }

   @Override
   public void onRender(MatrixStack matrices, float tickDelta) {
      if (mc.world != null && mc.player != null && !this.holes.isEmpty()) {
         Camera cam = RenderUtils.getCamera();
         if (cam != null) {
            Vec3d camPos = RenderUtils.getCameraPos(cam);
            int fillAlpha = this.clampAlpha(this.alpha.getValue());
            boolean renderGradient = this.gradientFill.getValue();
            BufferAllocator allocator = new BufferAllocator(2097152);
            Immediate immediate = VertexConsumerProvider.immediate(allocator);
            VertexConsumer fillConsumer = immediate.getBuffer(RenderLayers.debugFilledBox());
            Entry entry = matrices.peek();
            boolean rendered = false;

            for (HoleESP.HoleData hole : this.holes) {
               if (hole.isReadyToRender()) {
                  Box worldBox = hole.box;
                  if (RenderUtils.isWorldBoxVisible(worldBox.minX, worldBox.minY, worldBox.minZ, worldBox.maxX, worldBox.maxY, worldBox.maxZ)) {
                     Color baseColor = this.color.getValue();
                     Color fillColor = this.withAlpha(baseColor, fillAlpha);
                     Box relativeBox = new Box(
                        worldBox.minX - camPos.x,
                        worldBox.minY - camPos.y,
                        worldBox.minZ - camPos.z,
                        worldBox.maxX - camPos.x,
                        worldBox.maxY - camPos.y,
                        worldBox.maxZ - camPos.z
                     );
                     if (renderGradient) {
                        this.renderGradientBox(fillConsumer, entry, relativeBox, baseColor, fillAlpha);
                     } else {
                        this.renderFilledBox(fillConsumer, entry, relativeBox, this.toArgb(fillColor));
                     }

                     rendered = true;
                  }
               }
            }

            if (!rendered) {
               allocator.close();
            } else {

               try {
                  immediate.draw();
               } finally {
                  if (depthWasEnabled) {
                  }

                  allocator.close();
               }
            }
         }
      }
   }

   private void renderFilledBox(VertexConsumer consumer, Entry entry, Box box, int color) {
      float minX = (float)box.minX;
      float minY = (float)box.minY;
      float minZ = (float)box.minZ;
      float maxX = (float)box.maxX;
      float maxY = (float)box.maxY;
      float maxZ = (float)box.maxZ;
      this.emitQuad(consumer, entry, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ, color);
      this.emitQuad(consumer, entry, minX, maxY, minZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, color);
      this.emitQuad(consumer, entry, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, maxX, minY, minZ, color);
      this.emitQuad(consumer, entry, minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, color);
      this.emitQuad(consumer, entry, minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, color);
      this.emitQuad(consumer, entry, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, maxX, minY, maxZ, color);
   }

   private void renderGradientBox(VertexConsumer consumer, Entry entry, Box box, Color baseColor, int maxAlpha) {
      double height = Math.max(0.001, box.maxY - box.minY);
      int slices = Math.max(1, MathHelper.ceil(height));
      int baseAlpha = Math.max(6, Math.round(maxAlpha * 0.18F));
      float minX = (float)box.minX;
      float minZ = (float)box.minZ;
      float maxX = (float)box.maxX;
      float maxZ = (float)box.maxZ;
      int topCapColor = 0;
      int bottomCapColor = 0;

      for (int slice = 0; slice < slices; slice++) {
         double startProgress = (double)slice / slices;
         double endProgress = (double)(slice + 1) / slices;
         float sliceMinY = (float)MathHelper.lerp(startProgress, box.minY, box.maxY);
         float sliceMaxY = (float)MathHelper.lerp(endProgress, box.minY, box.maxY);
         float bottomFade = 1.0F - (float)slice / Math.max(1, slices - 1);
         float topFade = 1.0F - (float)(slice + 1) / Math.max(1, slices);
         int sliceBottomColor = this.toArgb(this.withAlpha(baseColor, Math.max(baseAlpha, Math.round(maxAlpha * bottomFade))));
         int sliceTopColor = this.toArgb(this.withAlpha(baseColor, Math.max(baseAlpha, Math.round(maxAlpha * topFade))));
         if (slice == 0) {
            bottomCapColor = sliceBottomColor;
         }

         if (slice == slices - 1) {
            topCapColor = sliceTopColor;
         }

         this.emitVerticalGradientQuad(
            consumer, entry, minX, sliceMinY, minZ, minX, sliceMaxY, minZ, maxX, sliceMaxY, minZ, maxX, sliceMinY, minZ, sliceBottomColor, sliceTopColor
         );
         this.emitVerticalGradientQuad(
            consumer, entry, minX, sliceMinY, maxZ, maxX, sliceMinY, maxZ, maxX, sliceMaxY, maxZ, minX, sliceMaxY, maxZ, sliceBottomColor, sliceTopColor
         );
         this.emitVerticalGradientQuad(
            consumer, entry, minX, sliceMinY, minZ, minX, sliceMinY, maxZ, minX, sliceMaxY, maxZ, minX, sliceMaxY, minZ, sliceBottomColor, sliceTopColor
         );
         this.emitVerticalGradientQuad(
            consumer, entry, maxX, sliceMinY, minZ, maxX, sliceMaxY, minZ, maxX, sliceMaxY, maxZ, maxX, sliceMinY, maxZ, sliceBottomColor, sliceTopColor
         );
      }

      this.emitQuad(
         consumer, entry, minX, (float)box.maxY, minZ, minX, (float)box.maxY, maxZ, maxX, (float)box.maxY, maxZ, maxX, (float)box.maxY, minZ, topCapColor
      );
      this.emitQuad(
         consumer, entry, minX, (float)box.minY, minZ, maxX, (float)box.minY, minZ, maxX, (float)box.minY, maxZ, minX, (float)box.minY, maxZ, bottomCapColor
      );
   }

   private void emitVerticalGradientQuad(
      VertexConsumer consumer,
      Entry entry,
      float x1,
      float y1,
      float z1,
      float x2,
      float y2,
      float z2,
      float x3,
      float y3,
      float z3,
      float x4,
      float y4,
      float z4,
      int bottomColor,
      int topColor
   ) {
      consumer.vertex(entry, x1, y1, z1).color(bottomColor);
      consumer.vertex(entry, x2, y2, z2).color(topColor);
      consumer.vertex(entry, x3, y3, z3).color(topColor);
      consumer.vertex(entry, x4, y4, z4).color(bottomColor);
   }

   private void emitQuad(
      VertexConsumer consumer,
      Entry entry,
      float x1,
      float y1,
      float z1,
      float x2,
      float y2,
      float z2,
      float x3,
      float y3,
      float z3,
      float x4,
      float y4,
      float z4,
      int color
   ) {
      consumer.vertex(entry, x1, y1, z1).color(color);
      consumer.vertex(entry, x2, y2, z2).color(color);
      consumer.vertex(entry, x3, y3, z3).color(color);
      consumer.vertex(entry, x4, y4, z4).color(color);
   }

   private void updateChunks() {
      if (mc.world != null && mc.player != null) {
         for (HoleESP.TrackedChunk trackedChunk : this.chunks.values()) {
            trackedChunk.marked = false;
         }

         int viewDist = Math.max(1, this.getRange() / 16);
         int playerChunkX = mc.player.getChunkPos().x;
         int playerChunkZ = mc.player.getChunkPos().z;

         for (int cx = playerChunkX - viewDist; cx <= playerChunkX + viewDist; cx++) {
            for (int cz = playerChunkZ - viewDist; cz <= playerChunkZ + viewDist; cz++) {
               WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(cx, cz, false);
               if (chunk != null) {
                  long key = ChunkPos.toLong(cx, cz);
                  HoleESP.TrackedChunk trackedChunk = this.chunks.get(key);
                  if (trackedChunk != null) {
                     trackedChunk.marked = true;
                  } else if (this.queuedChunks.add(key)) {
                     this.chunkQueue.add(key);
                  }
               }
            }
         }

         this.processChunkQueue();
         this.chunks.entrySet().removeIf(entry -> !entry.getValue().marked);
         Set<Long> activeKeys = this.chunks.keySet();
         this.holes.removeIf(hole -> !this.isBoxInActiveChunks(hole.box, activeKeys));
      }
   }

   private boolean isBoxInActiveChunks(Box box, Set<Long> activeKeys) {
      int chunkX = (int)Math.floor(box.getCenter().x) >> 4;
      int chunkZ = (int)Math.floor(box.getCenter().z) >> 4;
      return activeKeys.contains(ChunkPos.toLong(chunkX, chunkZ));
   }

   private void processChunkQueue() {
      if (this.executor != null && mc.world != null) {
         int processed = 0;

         while (!this.chunkQueue.isEmpty() && processed < 200) {
            Long chunkKey = this.chunkQueue.poll();
            if (chunkKey != null) {
               this.queuedChunks.remove(chunkKey);
               int chunkX = ChunkPos.getPackedX(chunkKey);
               int chunkZ = ChunkPos.getPackedZ(chunkKey);
               WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(chunkX, chunkZ, false);
               if (chunk != null) {
                  this.chunks.put(chunkKey, new TrackedChunk(chunkX, chunkZ));
                  this.executor.execute(() -> this.searchChunk(chunk));
                  processed++;
               }
            }
         }
      }
   }

   private void searchChunk(WorldChunk chunk) {
      ClientWorld world = mc.world;
      if (world != null && world == this.currentWorld && this.isEnabled()) {
         ChunkSection[] sections = chunk.getSectionArray();
         int minY = world.getBottomY();
         int maxY = world.getBottomY() + world.getHeight();
         int sectionY = minY;

         for (ChunkSection section : sections) {
            if (section != null && !section.isEmpty()) {
               for (int z = 0; z < 16; z++) {
                  for (int x = 0; x < 16; x++) {
                     for (int y = 0; y < 16; y++) {
                        int currentY = sectionY + y;
                        if (currentY > minY && currentY < maxY) {
                           BlockPos pos = new BlockPos(chunk.getPos().getStartX() + x, currentY, chunk.getPos().getStartZ() + z);
                           this.checkHole(pos);
                           this.check3x1Hole(pos);
                        }
                     }
                  }
               }
            }

            sectionY += 16;
         }
      }
   }

   private void checkHole(BlockPos pos) {
      if (this.isValidHoleSection(pos) && !this.isValidHoleSection(pos.up())) {
         Mutable currentPos = pos.mutableCopy();

         while (this.isValidHoleSection(currentPos)) {
            currentPos.move(Direction.DOWN);
         }

         int depth = pos.getY() - currentPos.getY();
         if (depth >= this.getMinDepth()) {
            Box box = new Box(pos.getX(), currentPos.getY() + 1, pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
            if (!this.containsIntersecting(box)) {
               this.holes.add(new HoleData(box, depth, true));
            }
         }
      }
   }

   private void check3x1Hole(BlockPos pos) {
      if (this.isValid3x1HoleSectionX(pos) && !this.isValid3x1HoleSectionX(pos.up())) {
         Mutable currentPos = pos.mutableCopy();

         while (this.isValid3x1HoleSectionX(currentPos)) {
            currentPos.move(Direction.DOWN);
         }

         int depth = pos.getY() - currentPos.getY();
         if (depth >= this.getMinDepth()) {
            Box box = new Box(pos.getX(), currentPos.getY() + 1, pos.getZ(), pos.getX() + 3, pos.getY() + 1, pos.getZ() + 1);
            if (!this.containsIntersecting(box)) {
               this.holes.add(new HoleData(box, depth, false));
            }
         }
      }

      if (this.isValid3x1HoleSectionZ(pos) && !this.isValid3x1HoleSectionZ(pos.up())) {
         Mutable currentPos = pos.mutableCopy();

         while (this.isValid3x1HoleSectionZ(currentPos)) {
            currentPos.move(Direction.DOWN);
         }

         int depth = pos.getY() - currentPos.getY();
         if (depth >= this.getMinDepth()) {
            Box box = new Box(pos.getX(), currentPos.getY() + 1, pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 3);
            if (!this.containsIntersecting(box)) {
               this.holes.add(new HoleData(box, depth, false));
            }
         }
      }
   }

   private boolean containsIntersecting(Box box) {
      for (HoleESP.HoleData data : this.holes) {
         if (data.box.equals(box) || data.box.intersects(box)) {
            return true;
         }
      }

      return false;
   }

   private boolean isTransparentBlock(BlockState state) {
      return state.getBlock() == Blocks.OAK_LEAVES
         || state.getBlock() == Blocks.SPRUCE_LEAVES
         || state.getBlock() == Blocks.BIRCH_LEAVES
         || state.getBlock() == Blocks.JUNGLE_LEAVES
         || state.getBlock() == Blocks.ACACIA_LEAVES
         || state.getBlock() == Blocks.DARK_OAK_LEAVES
         || state.getBlock() == Blocks.CHERRY_LEAVES
         || state.getBlock() == Blocks.MANGROVE_LEAVES
         || state.getBlock() == Blocks.AZALEA_LEAVES
         || state.getBlock() == Blocks.FLOWERING_AZALEA_LEAVES
         || state.getBlock() == Blocks.GLASS
         || state.getBlock() == Blocks.GLASS_PANE
         || state.getBlock() == Blocks.VINE
         || state.getBlock() == Blocks.CAVE_VINES
         || state.getBlock() == Blocks.CAVE_VINES_PLANT
         || state.getBlock() == Blocks.WEEPING_VINES
         || state.getBlock() == Blocks.WEEPING_VINES_PLANT
         || state.getBlock() == Blocks.TWISTING_VINES
         || state.getBlock() == Blocks.TWISTING_VINES_PLANT
         || state.getBlock() == Blocks.GLOW_LICHEN
         || state.getBlock() == Blocks.HANGING_ROOTS
         || state.getBlock() == Blocks.SPORE_BLOSSOM
         || state.getBlock() == Blocks.BAMBOO
         || state.getBlock() == Blocks.BAMBOO_SAPLING
         || state.getBlock() == Blocks.KELP
         || state.getBlock() == Blocks.KELP_PLANT
         || state.getBlock() == Blocks.SEAGRASS
         || state.getBlock() == Blocks.TALL_SEAGRASS
         || state.getBlock() == Blocks.SHORT_GRASS
         || state.getBlock() == Blocks.TALL_GRASS
         || state.getBlock() == Blocks.FERN
         || state.getBlock() == Blocks.LARGE_FERN
         || state.getBlock() == Blocks.SUGAR_CANE
         || state.getBlock() == Blocks.DEAD_BUSH
         || state.getBlock() == Blocks.SWEET_BERRY_BUSH;
   }

   private boolean isSolidWall(BlockPos pos) {
      if (mc.world == null) {
         return false;
      } else {
         BlockState state = mc.world.getBlockState(pos);
         return !state.isAir() && !this.isTransparentBlock(state);
      }
   }

   private boolean isValidHoleSection(BlockPos pos) {
      return this.isPassable(pos)
         && this.isSolidWall(pos.north())
         && this.isSolidWall(pos.south())
         && this.isSolidWall(pos.east())
         && this.isSolidWall(pos.west());
   }

   private boolean isValid3x1HoleSectionX(BlockPos pos) {
      return this.isPassable(pos)
         && this.isPassable(pos.east())
         && this.isPassable(pos.east(2))
         && this.isSolidWall(pos.north())
         && this.isSolidWall(pos.south())
         && this.isSolidWall(pos.west())
         && this.isSolidWall(pos.east(3));
   }

   private boolean isValid3x1HoleSectionZ(BlockPos pos) {
      return this.isPassable(pos)
         && this.isPassable(pos.south())
         && this.isPassable(pos.south(2))
         && this.isSolidWall(pos.east())
         && this.isSolidWall(pos.west())
         && this.isSolidWall(pos.north())
         && this.isSolidWall(pos.south(3));
   }

   private boolean isPassable(BlockPos pos) {
      if (mc.world == null) {
         return false;
      } else {
         BlockState state = mc.world.getBlockState(pos);
         if (!state.isAir()) {
            return false;
         } else {
            BlockState below = mc.world.getBlockState(pos.down());
            BlockState above = mc.world.getBlockState(pos.up());
            return !this.isPlantBlock(below) && !this.isPlantBlock(above) && !this.isMineshaftBlock(below) && !this.isMineshaftBlock(above);
         }
      }
   }

   private boolean isPlantBlock(BlockState state) {
      return state.getBlock() == Blocks.KELP
         || state.getBlock() == Blocks.KELP_PLANT
         || state.getBlock() == Blocks.SEAGRASS
         || state.getBlock() == Blocks.TALL_SEAGRASS
         || state.getBlock() == Blocks.VINE
         || state.getBlock() == Blocks.CAVE_VINES
         || state.getBlock() == Blocks.CAVE_VINES_PLANT
         || state.getBlock() == Blocks.WEEPING_VINES
         || state.getBlock() == Blocks.WEEPING_VINES_PLANT
         || state.getBlock() == Blocks.TWISTING_VINES
         || state.getBlock() == Blocks.TWISTING_VINES_PLANT
         || state.getBlock() == Blocks.GLOW_LICHEN
         || state.getBlock() == Blocks.HANGING_ROOTS
         || state.getBlock() == Blocks.SPORE_BLOSSOM;
   }

   private boolean isMineshaftBlock(BlockState state) {
      return state.getBlock() == Blocks.RAIL
         || state.getBlock() == Blocks.POWERED_RAIL
         || state.getBlock() == Blocks.DETECTOR_RAIL
         || state.getBlock() == Blocks.ACTIVATOR_RAIL
         || state.getBlock() == Blocks.OAK_FENCE
         || state.getBlock() == Blocks.DARK_OAK_FENCE
         || state.getBlock() == Blocks.SPRUCE_FENCE
         || state.getBlock() == Blocks.COBWEB;
   }

   private void clear() {
      this.chunks.clear();
      this.chunkQueue.clear();
      this.queuedChunks.clear();
      this.holes.clear();
   }

   private void ensureExecutor() {
      if (this.executor == null || this.executor.isShutdown()) {
         this.executor = Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "water-hole-esp");
            thread.setDaemon(true);
            return thread;
         });
      }
   }

   private void shutdownExecutor() {
      ExecutorService existing = this.executor;
      this.executor = null;
      if (existing != null) {
         existing.shutdown();

         try {
            if (!existing.awaitTermination(500L, TimeUnit.MILLISECONDS)) {
               existing.shutdownNow();
            }
         } catch (InterruptedException var3) {
            existing.shutdownNow();
            Thread.currentThread().interrupt();
         }
      }
   }

   private int getRange() {
      return MathHelper.clamp((int)Math.round(this.range.getValue()), 16, 128);
   }

   private int getMinDepth() {
      return 7;
   }

   private int clampAlpha(double value) {
      return MathHelper.clamp((int)Math.round(value), 0, 255);
   }

   private Color withAlpha(Color base, int alphaValue) {
      return new Color(base.getRed(), base.getGreen(), base.getBlue(), MathHelper.clamp(alphaValue, 0, 255));
   }

   private int toArgb(Color color) {
      return color.getAlpha() << 24 | color.getRed() << 16 | color.getGreen() << 8 | color.getBlue();
   }

   final class HoleData {
      private Box box;
      private int depth;
      private boolean is1x1;
      private long createdAt;

      private HoleData(Box box, int depth, boolean is1x1) {
         this.box = box;
         this.depth = depth;
         this.is1x1 = is1x1;
         this.createdAt = System.currentTimeMillis();
      }

      private boolean isReadyToRender() {
         return true;
      }

      @Override
      public boolean equals(Object obj) {
         if (this == obj) {
            return true;
         } else {
            return obj instanceof HoleESP.HoleData holeData ? Objects.equals(this.box, holeData.box) : false;
         }
      }

      @Override
      public int hashCode() {
         return Objects.hash(this.box);
      }
   }

   final class TrackedChunk {
      private int intVal;
      private int intVal2;
      private boolean marked;

      private TrackedChunk(int x, int z) {
         this.intVal = x;
         this.intVal2 = z;
         this.marked = true;
      }
   }
}


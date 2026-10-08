package com.chukl.addon.modules.water;

import com.chukl.addon.water.NotificationManager;
import com.chukl.addon.water.Category;
import com.chukl.addon.water.WModule;
import com.chukl.addon.water.BlocksSetting;
import com.chukl.addon.water.Setting;
import com.chukl.addon.water.RenderUtils;
import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

public final class BlockESP extends WModule {
   private static final int TRACER_ALPHA = 255;
   private static final int TRACER_GLOW_ALPHA = 70;
   private static final int TRACER_MID_ALPHA = 145;
   private static final int RESCAN_INTERVAL_TICKS = 200;
   private static final int CHUNKS_PER_TICK = 6;
   private static final long NOTIFY_COOLDOWN_MS = 750L;
   private static final double BOX_INSET = 0.0625;
   private final BlocksSetting blocks = new BlocksSetting("Blocks", Blocks.SPAWNER);
   private final Setting<Boolean> notify = new Setting<>("Notification", true);
   private final Setting<Boolean> tracers = new Setting<>("Tracers", true);
   private final Setting<Double> tracerWidth = new Setting<>("Tracer Weight", 1.0, 0.1, 5.0);
   private final Setting<Boolean> filled = new Setting<>("Filled", true);
   private final Setting<Double> alpha = new Setting<>("Opacity", 220.0, 0.0, 255.0);
   private final Setting<Double> fillAlpha = new Setting<>("Fill Alpha", 100.0, 0.0, 255.0);
   private final Setting<Integer> maxRender = new Setting<>("Max Render", 500, 10, 2000);
   private final Map<Long, Set<BlockPos>> cachedBlocks = new ConcurrentHashMap<>();
   private final Map<BlockPos, Block> posTypeMap = new ConcurrentHashMap<>();
   private final Map<Long, Long> lastNotifiedAt = new ConcurrentHashMap<>();
   private final ArrayDeque<Long> scanQueue = new ArrayDeque<>();
   private final Set<Long> queuedChunks = new HashSet<>();
   private final Object queueLock = new Object();
   private final ConcurrentHashMap<Block, Color> customBlockColors = new ConcurrentHashMap<>();
   private volatile Set<Block> targets = Collections.emptySet();
   private long lastBlocksVersion = -1L;
   private int tickCounter = 0;
   private boolean fullRescanRequested = true;
   private ChunkPos lastCenterChunk;
   private int lastChunkRadius = -1;
   private final List<BlockESP.RenderEntry> renderList = new ArrayList<>();

   public BlockESP() {
      super("Block ESP", Category.RENDER);
      this.addSetting(this.blocks);
      this.addSetting(this.notify);
      this.addSetting(this.tracers);
      this.addSetting(this.tracerWidth);
      this.addSetting(this.filled);
      this.addSetting(this.alpha);
      this.addSetting(this.fillAlpha);
      this.addSetting(this.maxRender);
   }

   @Override
   public void onEnable() {
      this.clearCaches();
      this.lastBlocksVersion = -1L;
      this.fullRescanRequested = true;
      this.tickCounter = 0;
      this.lastCenterChunk = null;
      this.lastChunkRadius = -1;
   }

   @Override
   public void onDisable() {
      this.clearCaches();
      this.lastCenterChunk = null;
      this.lastChunkRadius = -1;
   }

   @Override
   public void onTick() {
      if (mc.world != null && mc.player != null) {
         this.updateTargets();
         if (this.targets.isEmpty()) {
            this.clearCaches();
         } else {
            this.tickCounter++;
            ChunkPos currentChunk = mc.player.getChunkPos();
            int currentChunkRadius = this.getChunkRadius();
            boolean forceRescan = this.fullRescanRequested || this.tickCounter % 200 == 0;
            if (forceRescan || this.lastCenterChunk == null || !this.lastCenterChunk.equals(currentChunk) || this.lastChunkRadius != currentChunkRadius) {
               this.rebuildLoadedChunkQueue(forceRescan);
               this.fullRescanRequested = false;
               this.lastCenterChunk = currentChunk;
               this.lastChunkRadius = currentChunkRadius;
            }

            for (int i = 0; i < 6; i++) {
               Long chunkKey;
               synchronized (this.queueLock) {
                  chunkKey = this.scanQueue.poll();
                  if (chunkKey != null) {
                     this.queuedChunks.remove(chunkKey);
                  }
               }

               if (chunkKey == null) {
                  break;
               }

               int chunkX = ChunkPos.getPackedX(chunkKey);
               int chunkZ = ChunkPos.getPackedZ(chunkKey);
               WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(chunkX, chunkZ, false);
               if (chunk != null) {
                  this.scanChunk(chunk);
               }
            }
         }
      }
   }

   @Override
   public void onPacketReceive(Packet<?> packet) {
      if (mc.world != null) {
         if (packet instanceof ChunkDataS2CPacket chunkData) {
            this.queueChunk(ChunkPos.toLong(chunkData.getChunkX(), chunkData.getChunkZ()), true);
         } else if (packet instanceof ChunkDeltaUpdateS2CPacket deltaUpdate) {
            deltaUpdate.visitUpdates((pos, state) -> this.queueChunk(new ChunkPos(pos).toLong(), true));
         } else if (packet instanceof BlockUpdateS2CPacket blockUpdate) {
            this.queueChunk(new ChunkPos(blockUpdate.getPos()).toLong(), true);
         }
      }
   }

   @Override
   public void onRender(MatrixStack matrices, float tickDelta) {
      if (mc.world != null && mc.player != null && !this.cachedBlocks.isEmpty()) {
         Set<Block> localTargets = this.targets;
         if (!localTargets.isEmpty()) {
            Camera cam = RenderUtils.getCamera();
            if (cam != null) {
               Vec3d camPos = RenderUtils.getCameraPos(cam);
               Vec3d camFwd = RenderUtils.getCameraForward(cam);
               Vec3d tracerOrigin = Freecam.resolveTracerOrigin(camPos, tickDelta);
               Vec3d tracerStart = tracerOrigin.equals(camPos) ? camFwd.multiply(0.1) : tracerOrigin.subtract(camPos);
               int outlineA = clampAlpha((int)Math.round(this.alpha.getValue()));
               int fillA = clampAlpha((int)Math.round(this.fillAlpha.getValue()));
               int limit = this.maxRender.getValue();
               double maxDistanceSq = this.getMaxRenderDistanceSq();
               double px = mc.player.getX();
               double py = mc.player.getY();
               double pz = mc.player.getZ();
               this.renderList.clear();

               for (Set<BlockPos> positions : this.cachedBlocks.values()) {
                  for (BlockPos pos : positions) {
                     double distSq = pos.getSquaredDistance(px, py, pz);
                     if (!(distSq > maxDistanceSq)) {
                        Block block = this.posTypeMap.get(pos);
                        if (block != null && localTargets.contains(block)) {
                           this.renderList
                              .add(
                                 new RenderEntry(
                                    pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z, this.getBlockColor(block, 255), distSq
                                 )
                              );
                        }
                     }
                  }
               }

               if (!this.renderList.isEmpty()) {
                  this.renderList.sort(Comparator.comparingDouble(ex -> ex.distSq));
                  int count = Math.min(limit, this.renderList.size());
                  RenderUtils.WorldBatch lineBatch = RenderUtils.beginWorldBatch(matrices);

                  for (int i = count - 1; i >= 0; i--) {
                     BlockESP.RenderEntry e = this.renderList.get(i);
                     Color col = withAlpha(e.color, outlineA);
                     Color tracerCol = withAlpha(e.color, 255);
                     Color tracerGlowCol = withAlpha(e.color, 70);
                     Color tracerMidCol = withAlpha(e.color, 145);
                     lineBatch.renderOutlineBox(
                        e.doubleVal + 0.0625,
                        e.doubleVal2 + 0.0625,
                        e.doubleVal3 + 0.0625,
                        e.doubleVal + 1.0 - 0.0625,
                        e.doubleVal2 + 1.0 - 0.0625,
                        e.doubleVal3 + 1.0 - 0.0625,
                        col
                     );
                     if (this.tracers.getValue()) {
                        Vec3d end = new Vec3d(e.doubleVal + 0.5, e.doubleVal2 + 0.5, e.doubleVal3 + 0.5);
                        lineBatch.renderLine(tracerGlowCol, tracerStart, end, this.tracerWidth.getValue().floatValue() + 2.4F);
                        lineBatch.renderLine(tracerMidCol, tracerStart, end, this.tracerWidth.getValue().floatValue() + 1.4F);
                        lineBatch.renderLine(tracerCol, tracerStart, end, this.tracerWidth.getValue().floatValue());
                     }
                  }

                  lineBatch.flush();
                  if (this.filled.getValue() && fillA > 0) {
                     RenderUtils.WorldBatch fillBatch = RenderUtils.beginWorldBatch(matrices);

                     for (int ix = count - 1; ix >= 0; ix--) {
                        BlockESP.RenderEntry e = this.renderList.get(ix);
                        fillBatch.renderFilledBox(
                           e.doubleVal + 0.0625,
                           e.doubleVal2 + 0.0625,
                           e.doubleVal3 + 0.0625,
                           e.doubleVal + 1.0 - 0.0625,
                           e.doubleVal2 + 1.0 - 0.0625,
                           e.doubleVal3 + 1.0 - 0.0625,
                           withAlpha(e.color, fillA)
                        );
                     }

                     fillBatch.flush();
                  }
               }
            }
         }
      }
   }

   private static int clampAlpha(int v) {
      return Math.max(0, Math.min(255, v));
   }

   private void updateTargets() {
      long version = this.blocks.getVersion();
      if (version != this.lastBlocksVersion) {
         this.lastBlocksVersion = version;
         this.targets = Set.copyOf(this.blocks.getSelectedBlocks());
         this.clearCaches();
         this.fullRescanRequested = true;
      }
   }

   public boolean isSelected(Block block) {
      this.updateTargets();
      return this.blocks.contains(block);
   }

   public int getSelectedCount() {
      this.updateTargets();
      return this.blocks.size();
   }

   public Set<Block> getSelectedBlocks() {
      return new LinkedHashSet<>(this.blocks.getSelectedBlocks());
   }

   public boolean isNotifyEnabled() {
      return this.notify.getValue();
   }

   public void setNotifyEnabled(boolean v) {
      this.notify.setValue(v);
   }

   public boolean isTracersEnabled() {
      return this.tracers.getValue();
   }

   public void setTracersEnabled(boolean v) {
      this.tracers.setValue(v);
   }

   public void setSelectedBlocks(Set<Block> newBlocks) {
      this.blocks.clear();

      for (Block b : newBlocks) {
         this.blocks.toggle(b);
      }

      this.fullRescanRequested = true;
   }

   public void setBlockColors(Map<Block, Color> colors) {
      this.customBlockColors.clear();
      this.customBlockColors.putAll(colors);
   }

   public Map<Block, Color> getBlockColors() {
      return new LinkedHashMap<>(this.customBlockColors);
   }

   private void rebuildLoadedChunkQueue(boolean forceRescan) {
      if (mc.world != null && mc.player != null) {
         int viewDist = this.getChunkRadius();
         ChunkPos center = mc.player.getChunkPos();
         List<WorldChunk> loadedChunks = new ArrayList<>();
         Set<Long> loadedChunkKeys = new HashSet<>();

         for (int x = -viewDist; x <= viewDist; x++) {
            for (int z = -viewDist; z <= viewDist; z++) {
               WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(center.x + x, center.z + z, false);
               if (chunk != null) {
                  loadedChunks.add(chunk);
                  loadedChunkKeys.add(chunk.getPos().toLong());
               }
            }
         }

         loadedChunks.sort(Comparator.comparingInt(chunkx -> this.getChunkDistanceSq(center, chunkx.getPos())));
         synchronized (this.queueLock) {
            this.scanQueue.removeIf(k -> !loadedChunkKeys.contains(k));
            this.queuedChunks.retainAll(loadedChunkKeys);

            for (WorldChunk chunk : loadedChunks) {
               long key = chunk.getPos().toLong();
               if ((forceRescan || !this.cachedBlocks.containsKey(key)) && this.queuedChunks.add(key)) {
                  this.scanQueue.addLast(key);
               }
            }
         }

         this.pruneOutOfRange(center, viewDist);
      }
   }

   private void queueChunk(long chunkKey, boolean prioritized) {
      synchronized (this.queueLock) {
         if (prioritized && this.queuedChunks.contains(chunkKey)) {
            this.scanQueue.remove(chunkKey);
            this.scanQueue.addFirst(chunkKey);
         } else if (this.queuedChunks.add(chunkKey)) {
            if (prioritized) {
               this.scanQueue.addFirst(chunkKey);
            } else {
               this.scanQueue.add(chunkKey);
            }
         }
      }
   }

   private void scanChunk(WorldChunk chunk) {
      Set<Block> localTargets = this.targets;
      if (!localTargets.isEmpty()) {
         int worldBottom = mc.world.getBottomY();
         int worldTopExclusive = mc.world.getBottomY() + mc.world.getHeight();
         int minSection = mc.world.getBottomSectionCoord();
         ChunkPos chunkPos = chunk.getPos();
         long chunkKey = chunkPos.toLong();
         Set<BlockPos> oldSet = this.cachedBlocks.get(chunkKey);
         Set<BlockPos> newSet = new HashSet<>();
         Block firstNewBlock = null;
         BlockPos firstNewPos = null;
         ChunkSection[] sections = chunk.getSectionArray();

         for (int si = 0; si < sections.length; si++) {
            ChunkSection section = sections[si];
            if (section != null && !section.isEmpty()) {
               int sectionYBase = (minSection + si) * 16;
               if (sectionYBase + 16 > worldBottom
                  && sectionYBase < worldTopExclusive
                  && section.getBlockStateContainer().hasAny(state -> localTargets.contains(state.getBlock()))) {
                  for (int lx = 0; lx < 16; lx++) {
                     for (int lz = 0; lz < 16; lz++) {
                        for (int ly = 0; ly < 16; ly++) {
                           Block block = section.getBlockState(lx, ly, lz).getBlock();
                           if (localTargets.contains(block)) {
                              BlockPos pos = new BlockPos(chunkPos.getStartX() + lx, sectionYBase + ly, chunkPos.getStartZ() + lz);
                              newSet.add(pos);
                              this.posTypeMap.put(pos, block);
                              if (firstNewBlock == null && (oldSet == null || !oldSet.contains(pos))) {
                                 firstNewBlock = block;
                                 firstNewPos = pos;
                              }
                           }
                        }
                     }
                  }
               }
            }
         }

         if (oldSet != null) {
            for (BlockPos pos : oldSet) {
               if (!newSet.contains(pos)) {
                  this.posTypeMap.remove(pos);
               }
            }
         }

         if (newSet.isEmpty()) {
            this.removeChunkCache(chunkKey);
            this.lastNotifiedAt.remove(chunkKey);
         } else {
            this.cachedBlocks.put(chunkKey, newSet);
            if (firstNewBlock != null) {
               this.maybeNotify(chunkKey, firstNewBlock, firstNewPos, chunkPos);
            }
         }
      }
   }

   private void maybeNotify(long chunkKey, Block block, BlockPos pos, ChunkPos chunkPos) {
      if (this.notify.getValue() && mc.player != null) {
         long now = System.currentTimeMillis();
         long last = this.lastNotifiedAt.getOrDefault(chunkKey, 0L);
         if (now - last >= 750L) {
            this.lastNotifiedAt.put(chunkKey, now);
            NotificationManager.INSTANCE
               .push(
                  this.safeBlockName(block) + " found",
                  "X " + pos.getX() + "  Y " + pos.getY() + "  Z " + pos.getZ(),
                  this.createNotificationStack(block),
                  this.getBlockColor(block, 255).getRGB()
               );
            mc.world
               .playSound(
                  mc.player, mc.player.getX(), mc.player.getY(), mc.player.getZ(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.MASTER, 0.6F, 0.95F
               );
         }
      }
   }

   private ItemStack createNotificationStack(Block block) {
      ItemStack stack = new ItemStack(block.asItem());
      return stack.isEmpty() ? ItemStack.EMPTY : stack;
   }

   private int getChunkDistanceSq(ChunkPos o, ChunkPos t) {
      int dx = t.x - o.x;
      int dz = t.z - o.z;
      return dx * dx + dz * dz;
   }

   private int getChunkRadius() {
      return mc.options.getClampedViewDistance();
   }

   private double getMaxRenderDistanceSq() {
      double d = this.getChunkRadius() * 16.0 + 16.0;
      return d * d;
   }

   private void pruneOutOfRange(ChunkPos center, int chunkRadius) {
      List<Long> toRemove = new ArrayList<>();

      for (Long k : this.cachedBlocks.keySet()) {
         ChunkPos cp = new ChunkPos(ChunkPos.getPackedX(k), ChunkPos.getPackedZ(k));
         if (Math.abs(cp.x - center.x) > chunkRadius || Math.abs(cp.z - center.z) > chunkRadius) {
            toRemove.add(k);
         }
      }

      for (Long kx : toRemove) {
         this.removeChunkCache(kx);
         this.lastNotifiedAt.remove(kx);
      }
   }

   private void removeChunkCache(long chunkKey) {
      Set<BlockPos> removed = this.cachedBlocks.remove(chunkKey);
      if (removed != null) {
         removed.forEach(this.posTypeMap::remove);
      }
   }

   private Color getBlockColor(Block block, int alpha) {
      Color custom = this.customBlockColors.get(block);
      if (custom != null) {
         return new Color(custom.getRed(), custom.getGreen(), custom.getBlue(), alpha);
      } else {
         Identifier id = Registries.BLOCK.getId(block);
         String path = id == null ? "" : id.getPath();
         if (block == Blocks.SPAWNER) {
            return new Color(138, 126, 166, alpha);
         } else if (path.contains("diamond")) {
            return new Color(0, 255, 255, alpha);
         } else if (path.contains("ancient_debris")) {
            return new Color(196, 120, 72, alpha);
         } else if (path.contains("emerald")) {
            return new Color(0, 255, 127, alpha);
         } else if (path.contains("gold")) {
            return new Color(255, 215, 0, alpha);
         } else if (path.contains("iron")) {
            return new Color(213, 213, 213, alpha);
         } else if (path.contains("redstone")) {
            return new Color(255, 70, 70, alpha);
         } else {
            return path.contains("lapis") ? new Color(70, 110, 255, alpha) : new Color(255, 255, 0, alpha);
         }
      }
   }

   private static Color withAlpha(Color c, int a) {
      return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
   }

   private String safeBlockName(Block block) {
      try {
         return block.getName().getString();
      } catch (Exception var4) {
         Identifier id = Registries.BLOCK.getId(block);
         return id == null ? "Block" : id.toString();
      }
   }

   private void clearCaches() {
      this.cachedBlocks.clear();
      this.posTypeMap.clear();
      this.lastNotifiedAt.clear();
      synchronized (this.queueLock) {
         this.scanQueue.clear();
         this.queuedChunks.clear();
      }
   }

   final class RenderEntry {
      final double doubleVal;
      final double doubleVal2;
      final double doubleVal3;
      final Color color;
      final double distSq;

      RenderEntry(double x, double y, double z, Color color, double distSq) {
         this.doubleVal = x;
         this.doubleVal2 = y;
         this.doubleVal3 = z;
         this.color = color;
         this.distSq = distSq;
      }
   }
}


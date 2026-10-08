package com.chukl.addon.modules.water;

import com.chukl.addon.water.Category;
import com.chukl.addon.water.WModule;
import com.chukl.addon.water.ModuleManager;
import com.chukl.addon.water.BlocksSetting;
import com.chukl.addon.water.Setting;
import com.chukl.addon.water.RenderUtils;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

public final class ExtraESP extends WModule {
   private static final int RESCAN_INTERVAL_TICKS = 120;
   private static final int SCAN_INTERVAL_TICKS = 1;
   private static final int SCAN_RADIUS_CHUNKS = 20;
   private static final int RENDER_RADIUS_CHUNKS = 16;
   private static final int MAX_RENDER_ENTRIES = 5000;
   private static final int TRACER_ALPHA = 255;
   private static final float TRACER_GLOW_SIZE = 1.9F;
   private static final int TRACER_GLOW_ALPHA = 70;
   private static final int TRACER_MID_ALPHA = 140;
   private static final long NOTIFY_COOLDOWN_MS = 900L;
   private static final double BOX_INSET = 0.0;
   private static final int SCAN_MIN_Y = -63;
   private static final int SCAN_MAX_Y = -10;
   private final BlocksSetting blocks = new BlocksSetting("Blocks", Blocks.SPAWNER);
   private final Setting<Boolean> notify = new Setting<>("Notify", true);
   private final Setting<Boolean> tracers = new Setting<>("Tracers", true);
   private final Setting<Double> tracerWidth = new Setting<>("Tracer Width", 0.8, 0.1, 4.0);
   private final Setting<Double> alpha = new Setting<>("Opacity", 190.0, 0.0, 255.0);
   private final Setting<Double> fillAlpha = new Setting<>("Fill Alpha", 35.0, 0.0, 255.0);
   private final Map<Long, Set<BlockPos>> cachedBlocks = new ConcurrentHashMap<>();
   private final Map<BlockPos, Block> posTypeMap = new ConcurrentHashMap<>();
   private final Map<Long, Long> lastNotifiedAt = new ConcurrentHashMap<>();
   private final Map<Block, Color> customBlockColors = new ConcurrentHashMap<>();
   private volatile Set<Block> targets = Collections.emptySet();
   private long lastBlocksVersion = -1L;
   private int tickCounter;
   private boolean fullRescanRequested = true;
   private ChunkPos lastCenterChunk;
   private int lastChunkRadius = -1;
   private final List<ExtraESP.RenderEntry> renderList = new ArrayList<>();
   private final ExecutorService scanner = Executors.newFixedThreadPool(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())), r -> {
      Thread t = new Thread(r, "extraESP-scan");
      t.setDaemon(true);
      t.setPriority(5);
      return t;
   });
   private final AtomicBoolean isScanning = new AtomicBoolean(false);
   private final Set<Long> scannedChunks = Collections.synchronizedSet(new HashSet<>());
   private int scanTick;
   private int lastCenterCX = Integer.MIN_VALUE;
   private int lastCenterCZ = Integer.MIN_VALUE;

   public ExtraESP() {
      super("Extra ESP", Category.RENDER);
      this.addSetting(this.blocks);
      this.addSetting(this.notify);
      this.addSetting(this.tracers);
      this.addSetting(this.tracerWidth);
      this.addSetting(this.alpha);
      this.addSetting(this.fillAlpha);
   }

   @Override
   public void onEnable() {
      this.clearCaches();
      this.lastBlocksVersion = -1L;
      this.fullRescanRequested = true;
      this.tickCounter = 0;
      this.scanTick = 0;
      this.lastCenterChunk = null;
      this.lastChunkRadius = -1;
      this.lastCenterCX = Integer.MIN_VALUE;
      this.lastCenterCZ = Integer.MIN_VALUE;
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
            if (this.fullRescanRequested || this.tickCounter % 120 == 0) {
               this.scannedChunks.clear();
               this.fullRescanRequested = false;
            }

            if (++this.scanTick >= 1 && !this.isScanning.get()) {
               this.scanTick = 0;
               this.startScan();
            }
         }
      }
   }

   @Override
   public void onPacketReceive(Packet<?> packet) {
      if (mc.world != null) {
         if (packet instanceof ChunkDataS2CPacket chunkData) {
            this.invalidateChunk(ChunkPos.toLong(chunkData.getChunkX(), chunkData.getChunkZ()));
         } else if (packet instanceof ChunkDeltaUpdateS2CPacket deltaUpdate) {
            deltaUpdate.visitUpdates((pos, state) -> this.invalidateChunk(new ChunkPos(pos).toLong()));
         } else if (packet instanceof BlockUpdateS2CPacket blockUpdate) {
            this.invalidateChunk(new ChunkPos(blockUpdate.getPos()).toLong());
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
               double maxDistanceSq = 272.0 * 272.0;
               double px = mc.player.getX();
               double pz = mc.player.getZ();
               float tW = this.tracerWidth.getValue().floatValue();
               this.renderList.clear();

               for (Set<BlockPos> positions : this.cachedBlocks.values()) {
                  for (BlockPos pos : positions) {
                     double dx = pos.getX() - px;
                     double dz = pos.getZ() - pz;
                     double distSq = dx * dx + dz * dz;
                     if (!(distSq > maxDistanceSq)) {
                        Block block = this.posTypeMap.get(pos);
                        if (block != null && localTargets.contains(block)) {
                           this.renderList
                              .add(new RenderEntry(pos.getX() - camPos.x, pos.getY() - camPos.y, pos.getZ() - camPos.z, distSq, block));
                        }
                     }
                  }
               }

               if (!this.renderList.isEmpty()) {
                  this.renderList.sort(Comparator.comparingDouble(ex -> ex.distSq));
                  int count = Math.min(this.renderList.size(), 5000);
                  int outlineA = clampAlpha((int)Math.round(this.alpha.getValue()));
                  int fillA = clampAlpha((int)Math.round(this.fillAlpha.getValue()));
                  if (this.tracers.getValue() || outlineA > 0) {
                     RenderUtils.WorldBatch lineBatch = RenderUtils.beginWorldBatch(matrices);

                     for (int i = count - 1; i >= 0; i--) {
                        ExtraESP.RenderEntry e = this.renderList.get(i);
                        Color color = this.getBlockColor(e.block, outlineA);
                        lineBatch.renderOutlineBox(
                           e.doubleVal + 0.0,
                           e.doubleVal2 + 0.0,
                           e.doubleVal3 + 0.0,
                           e.doubleVal + 1.0 - 0.0,
                           e.doubleVal2 + 1.0 - 0.0,
                           e.doubleVal3 + 1.0 - 0.0,
                           color
                        );
                        if (this.tracers.getValue()) {
                           Color tracerColor = this.getBlockColor(e.block, 255);
                           Vec3d target = new Vec3d(e.doubleVal + 0.5, e.doubleVal2 + 0.5, e.doubleVal3 + 0.5);
                           lineBatch.renderLine(this.getBlockColor(e.block, 70), tracerStart, target, tW + 1.9F);
                           lineBatch.renderLine(this.getBlockColor(e.block, 140), tracerStart, target, tW + 0.95F);
                           lineBatch.renderLine(tracerColor, tracerStart, target, tW);
                        }
                     }

                     lineBatch.flush();
                  }

                  if (fillA > 0) {
                     RenderUtils.WorldBatch fillBatch = RenderUtils.beginWorldBatch(matrices);

                     for (int ix = count - 1; ix >= 0; ix--) {
                        ExtraESP.RenderEntry e = this.renderList.get(ix);
                        fillBatch.renderFilledBox(
                           e.doubleVal + 0.0,
                           e.doubleVal2 + 0.0,
                           e.doubleVal3 + 0.0,
                           e.doubleVal + 1.0 - 0.0,
                           e.doubleVal2 + 1.0 - 0.0,
                           e.doubleVal3 + 1.0 - 0.0,
                           this.getBlockColor(e.block, fillA)
                        );
                     }

                     fillBatch.flush();
                  }
               }
            }
         }
      }
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

   private void startScan() {
      if (this.isScanning.compareAndSet(false, true)) {
         if (mc.world != null && mc.player != null) {
            ChunkPos center = mc.player.getChunkPos();
            if (Math.abs(center.x - this.lastCenterCX) > 1 || Math.abs(center.z - this.lastCenterCZ) > 1) {
               this.scannedChunks.clear();
               this.lastCenterCX = center.x;
               this.lastCenterCZ = center.z;
            }

            this.lastCenterChunk = center;
            this.lastChunkRadius = 20;
            List<WorldChunk> chunks = new ArrayList<>();
            Set<Long> loadedChunkKeys = new HashSet<>();

            try {
               for (int cx = center.x - 20; cx <= center.x + 20; cx++) {
                  for (int cz = center.z - 20; cz <= center.z + 20; cz++) {
                     WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(cx, cz, false);
                     if (chunk != null) {
                        long key = chunk.getPos().toLong();
                        loadedChunkKeys.add(key);
                        if (!this.scannedChunks.contains(key)) {
                           chunks.add(chunk);
                        }
                     }
                  }
               }
            } catch (Throwable var10) {
               this.isScanning.set(false);
               return;
            }

            this.pruneMissingLoadedChunks(loadedChunkKeys);
            this.pruneOutOfRange(center, 20);
            if (chunks.isEmpty()) {
               this.isScanning.set(false);
            } else {
               chunks.sort(Comparator.comparingInt(chunkx -> this.getChunkDistanceSq(center, chunkx.getPos())));
               this.scanner.execute(() -> {
                  try {
                     for (WorldChunk chunkx : chunks) {
                        this.scanChunk(chunkx);
                        this.scannedChunks.add(chunkx.getPos().toLong());
                     }
                  } finally {
                     this.isScanning.set(false);
                  }
               });
            }
         } else {
            this.isScanning.set(false);
         }
      }
   }

   private void scanChunk(WorldChunk chunk) {
      Set<Block> localTargets = this.targets;
      if (!localTargets.isEmpty() && mc.world != null) {
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
               if (sectionYBase + 16 > -63 && sectionYBase <= -10 && section.getBlockStateContainer().hasAny(state -> localTargets.contains(state.getBlock()))) {
                  for (int lx = 0; lx < 16; lx++) {
                     for (int lz = 0; lz < 16; lz++) {
                        for (int ly = 0; ly < 16; ly++) {
                           int worldY = sectionYBase + ly;
                           if (worldY >= -63 && worldY <= -10) {
                              Block block = section.getBlockState(lx, ly, lz).getBlock();
                              if (localTargets.contains(block)) {
                                 BlockPos pos = new BlockPos(chunkPos.getStartX() + lx, worldY, chunkPos.getStartZ() + lz);
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
         }

         if (oldSet != null && !newSet.isEmpty()) {
            for (BlockPos pos : oldSet) {
               if (!newSet.contains(pos)) {
                  this.posTypeMap.remove(pos);
               }
            }
         }

         if (!newSet.isEmpty()) {
            this.cachedBlocks.put(chunkKey, newSet);
            if (firstNewBlock != null) {
               this.maybeNotify(chunkKey, firstNewBlock, firstNewPos);
            }
         }
      }
   }

   private void maybeNotify(long chunkKey, Block block, BlockPos pos) {
      if (this.notify.getValue() && mc.player != null && pos != null) {
         long now = System.currentTimeMillis();
         long last = this.lastNotifiedAt.getOrDefault(chunkKey, 0L);
         if (now - last >= 900L) {
            this.lastNotifiedAt.put(chunkKey, now);
            Identifier id = Registries.BLOCK.getId(block);
            String blockName = id != null ? id.toString() : "unknown";
            mc.execute(
               () -> {
                  if (mc.player != null) {
                     mc.player
                        .sendMessage(
                           Text.literal("[Chunk] ")
                              .formatted(Formatting.GRAY)
                              .append(Text.literal(blockName).formatted(Formatting.WHITE))
                              .append(Text.literal(" at (").formatted(Formatting.GRAY))
                              .append(Text.literal(pos.getX() + ", " + pos.getY() + ", " + pos.getZ()).formatted(Formatting.WHITE))
                              .append(Text.literal(")").formatted(Formatting.GRAY)),
                           false
                        );
                  }
               }
            );
         }
      }
   }

   private void clearCaches() {
      this.cachedBlocks.clear();
      this.posTypeMap.clear();
      this.lastNotifiedAt.clear();
      this.scannedChunks.clear();
   }

   private void invalidateChunk(long chunkKey) {
      this.scannedChunks.remove(chunkKey);
   }

   private void pruneMissingLoadedChunks(Set<Long> loadedChunkKeys) {
      List<Long> toRemove = new ArrayList<>();

      for (Long key : this.cachedBlocks.keySet()) {
         if (!loadedChunkKeys.contains(key)) {
            toRemove.add(key);
         }
      }

      for (Long keyx : toRemove) {
         this.removeChunkCache(keyx);
         this.lastNotifiedAt.remove(keyx);
         this.scannedChunks.remove(keyx);
      }
   }

   private void pruneOutOfRange(ChunkPos center, int chunkRadius) {
      List<Long> toRemove = new ArrayList<>();

      for (Long key : this.cachedBlocks.keySet()) {
         ChunkPos cp = new ChunkPos(ChunkPos.getPackedX(key), ChunkPos.getPackedZ(key));
         if (Math.abs(cp.x - center.x) > chunkRadius || Math.abs(cp.z - center.z) > chunkRadius) {
            toRemove.add(key);
         }
      }

      for (Long keyx : toRemove) {
         this.removeChunkCache(keyx);
         this.lastNotifiedAt.remove(keyx);
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
         } else if (path.contains("lapis")) {
            return new Color(70, 110, 255, alpha);
         } else if (path.contains("chest")) {
            return new Color(210, 140, 60, alpha);
         } else {
            return path.contains("barrel") ? new Color(200, 130, 100, alpha) : new Color(177, 92, 255, alpha);
         }
      }
   }

   public void setBlockColors(Map<Block, Color> colors) {
      this.customBlockColors.clear();
      if (colors != null) {
         for (Entry<Block, Color> entry : colors.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
               this.customBlockColors.put(entry.getKey(), entry.getValue());
            }
         }
      }

      ModuleManager.INSTANCE.onSettingChanged();
   }

   public Map<Block, Color> getBlockColors() {
      return new LinkedHashMap<>(this.customBlockColors);
   }

   private int getChunkDistanceSq(ChunkPos origin, ChunkPos target) {
      int dx = target.x - origin.x;
      int dz = target.z - origin.z;
      return dx * dx + dz * dz;
   }

   private static int clampAlpha(int v) {
      return Math.max(0, Math.min(255, v));
   }

   final class RenderEntry {
      final double doubleVal;
      final double doubleVal2;
      final double doubleVal3;
      final double distSq;
      final Block block;

      RenderEntry(double x, double y, double z, double distSq, Block block) {
         this.doubleVal = x;
         this.doubleVal2 = y;
         this.doubleVal3 = z;
         this.distSq = distSq;
         this.block = block;
      }
   }
}


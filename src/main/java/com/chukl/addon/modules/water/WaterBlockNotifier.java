package com.chukl.addon.modules.water;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

public final class WaterBlockNotifier extends WModule {
   private static final int CHUNKS_PER_TICK = 32;
   private static final int RESCAN_INTERVAL_TICKS = 5;
   private static final double BOX_INSET = 0.035;
   private final BlocksSetting blocks = new BlocksSetting("Blocks", Blocks.HOPPER);
   private final Setting<Boolean> chatNotify = new Setting<>("Chat Notify", true);
   private final Setting<Boolean> sound = new Setting<>("Sound", true);
   private final Setting<Boolean> esp = new Setting<>("ESP", true);
   private final Setting<Boolean> tracers = new Setting<>("Tracers", false);
   private final Setting<Integer> scanRadius = new Setting<>("Scan Radius", 8, 1, 16);
   private final Setting<Integer> fillAlpha = new Setting<>("Fill Alpha", 80, 0, 255);
   private final Map<Long, Set<BlockPos>> cached = new ConcurrentHashMap<>();
   private final Map<BlockPos, Block> blockTypes = new ConcurrentHashMap<>();
   private final Set<BlockPos> announced = ConcurrentHashMap.newKeySet();
   private final ArrayDeque<Long> queue = new ArrayDeque<>();
   private final Set<Long> queued = new HashSet<>();
   private final Object queueLock = new Object();
   private volatile Set<Block> targets = Collections.emptySet();
   private long lastBlocksVersion = -1L;
   private int ticks = 0;
   private ChunkPos lastCenter = null;
   private int lastRadius = -1;
   private int lastLoadedCount = -1;
   private int lastTargetHash = 0;

   public WaterBlockNotifier() {
      super("Block Notifier", Category.RENDER);
      this.addSetting(this.blocks);
      this.addSetting(this.chatNotify);
      this.addSetting(this.sound);
      this.addSetting(this.esp);
      this.addSetting(this.tracers);
      this.addSetting(this.scanRadius);
      this.addSetting(this.fillAlpha);
   }

   @Override
   public void onEnable() {
      this.clearAll();
      this.lastBlocksVersion = -1L;
      this.ticks = 0;
      this.lastCenter = null;
      this.lastRadius = -1;
      this.lastLoadedCount = -1;
      this.lastTargetHash = 0;
      this.forceRescanNow();
   }

   @Override
   public void onDisable() {
      this.clearAll();
      this.lastCenter = null;
      this.lastRadius = -1;
      this.lastLoadedCount = -1;
      this.lastTargetHash = 0;
   }

   @Override
   public void onTick() {
      if (mc.world != null && mc.player != null) {
         this.updateTargets();
         if (this.targets.isEmpty()) {
            this.clearAll();
         } else {
            ChunkPos center = mc.player.getChunkPos();
            int radius = Math.min(this.scanRadius.getValue(), mc.options.getClampedViewDistance());
            int loadedCount = this.countLoadedChunks(center, radius);
            int targetHash = this.targets.hashCode();
            boolean moved = this.lastCenter == null || !this.lastCenter.equals(center);
            boolean radiusChanged = this.lastRadius != radius;
            boolean loadedChanged = this.lastLoadedCount != loadedCount;
            boolean targetChanged = this.lastTargetHash != targetHash;
            boolean interval = ++this.ticks >= 5;
            if (moved || radiusChanged || loadedChanged || targetChanged || interval || this.queue.isEmpty()) {
               this.ticks = 0;
               this.rebuildQueue(center, radius);
               this.lastCenter = center;
               this.lastRadius = radius;
               this.lastLoadedCount = loadedCount;
               this.lastTargetHash = targetHash;
            }

            for (int i = 0; i < 32; i++) {
               Long key;
               synchronized (this.queueLock) {
                  key = this.queue.pollFirst();
                  if (key != null) {
                     this.queued.remove(key);
                  }
               }

               if (key == null) {
                  break;
               }

               int cx = ChunkPos.getPackedX(key);
               int cz = ChunkPos.getPackedZ(key);
               WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(cx, cz, false);
               if (chunk != null) {
                  this.scanChunk(chunk);
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
         this.clearAll();
         if (mc.world != null && mc.player != null) {
            this.forceRescanNow();
         }
      }
   }

   private void forceRescanNow() {
      if (mc.world != null && mc.player != null) {
         ChunkPos center = mc.player.getChunkPos();
         int radius = Math.min(this.scanRadius.getValue(), mc.options.getClampedViewDistance());
         this.rebuildQueue(center, radius);
         this.lastCenter = center;
         this.lastRadius = radius;
         this.lastLoadedCount = this.countLoadedChunks(center, radius);
      }
   }

   private int countLoadedChunks(ChunkPos center, int radius) {
      int count = 0;

      for (int dx = -radius; dx <= radius; dx++) {
         for (int dz = -radius; dz <= radius; dz++) {
            WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(center.x + dx, center.z + dz, false);
            if (chunk != null && !chunk.isEmpty()) {
               count++;
            }
         }
      }

      return count;
   }

   private void rebuildQueue(ChunkPos center, int radius) {
      List<WorldChunk> loaded = new ArrayList<>();
      Set<Long> loadedKeys = new HashSet<>();

      for (int dx = -radius; dx <= radius; dx++) {
         for (int dz = -radius; dz <= radius; dz++) {
            WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(center.x + dx, center.z + dz, false);
            if (chunk != null && !chunk.isEmpty()) {
               loaded.add(chunk);
               loadedKeys.add(chunk.getPos().toLong());
            }
         }
      }

      loaded.sort(Comparator.comparingInt(chunkx -> this.distanceSq(center, chunkx.getPos())));
      synchronized (this.queueLock) {
         this.queue.removeIf(keyx -> !loadedKeys.contains(keyx));
         this.queued.retainAll(loadedKeys);

         for (WorldChunk chunk : loaded) {
            long key = chunk.getPos().toLong();
            if (this.queued.add(key)) {
               this.queue.addLast(key);
            }
         }
      }

      this.prune(center, radius);
   }

   private int distanceSq(ChunkPos a, ChunkPos b) {
      int dx = a.x - b.x;
      int dz = a.z - b.z;
      return dx * dx + dz * dz;
   }

   private void scanChunk(WorldChunk chunk) {
      Set<Block> selected = this.targets;
      if (!selected.isEmpty() && mc.world != null) {
         ChunkPos cp = chunk.getPos();
         long key = cp.toLong();
         Set<BlockPos> old = this.cached.get(key);
         Set<BlockPos> found = new HashSet<>();
         int minSection = mc.world.getBottomSectionCoord();
         ChunkSection[] sections = chunk.getSectionArray();

         for (int si = 0; si < sections.length; si++) {
            int yBase = (minSection + si) * 16;
            int yTop = yBase + 15;
            if (yBase > -2) {
               break;
            }

            if (yTop >= mc.world.getBottomY()) {
               ChunkSection section = sections[si];
               if (section != null && !section.isEmpty() && section.hasAny(statex -> selected.contains(statex.getBlock()))) {
                  for (int ly = 0; ly < 16; ly++) {
                     int y = yBase + ly;
                     if (y <= -2) {
                        for (int lx = 0; lx < 16; lx++) {
                           for (int lz = 0; lz < 16; lz++) {
                              BlockState state = section.getBlockState(lx, ly, lz);
                              Block block = state.getBlock();
                              if (selected.contains(block)) {
                                 BlockPos pos = new BlockPos(cp.getStartX() + lx, y, cp.getStartZ() + lz);
                                 found.add(pos);
                                 this.blockTypes.put(pos, block);
                                 if (this.announced.add(pos)) {
                                    this.notifyFound(block, pos);
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         }

         if (old != null) {
            for (BlockPos pos : old) {
               if (!found.contains(pos)) {
                  this.blockTypes.remove(pos);
                  this.announced.remove(pos);
               }
            }
         }

         if (found.isEmpty()) {
            this.cached.remove(key);
         } else {
            this.cached.put(key, found);
         }
      }
   }

   private void notifyFound(Block block, BlockPos pos) {
      String message = this.safeName(block) + " found (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
      if (this.chatNotify.getValue() && mc.inGameHud != null) {
         mc.inGameHud.getChatHud().addMessage(Text.literal("§ §fnull"));
      }

      if (this.sound.getValue() && mc.world != null && mc.player != null) {
         mc.world
            .playSound(
               mc.player, mc.player.getX(), mc.player.getY(), mc.player.getZ(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.MASTER, 0.55F, 1.1F
            );
      }
   }

   @Override
   public void onRender(MatrixStack matrices, float tickDelta) {
      if (mc.world != null && mc.player != null && !this.cached.isEmpty()) {
         if (this.esp.getValue() || this.tracers.getValue()) {
            Camera cam = RenderUtils.getCamera();
            if (cam != null) {
               Vec3d camPos = RenderUtils.getCameraPos(cam);
               RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);
               boolean rendered = false;
               matrices.push();

               try {
                  for (Set<BlockPos> positions : this.cached.values()) {
                     for (BlockPos pos : positions) {
                        Block block = this.blockTypes.get(pos);
                        if (block != null && this.targets.contains(block) && mc.world.getBlockState(pos).isOf(block)) {
                           Color fill = this.colorFor(block, this.fillAlpha.getValue());
                           Color line = this.colorFor(block, 255);
                           double x = pos.getX() - camPos.x;
                           double y = pos.getY() - camPos.y;
                           double z = pos.getZ() - camPos.z;
                           if (this.esp.getValue()) {
                              batch.renderFilledBox(x + 0.035, y + 0.035, z + 0.035, x + 1.0 - 0.035, y + 1.0 - 0.035, z + 1.0 - 0.035, fill);
                              batch.renderOutlineBox(x + 0.035, y + 0.035, z + 0.035, x + 1.0 - 0.035, y + 1.0 - 0.035, z + 1.0 - 0.035, line);
                           }

                           if (this.tracers.getValue()) {
                              batch.renderLine(line, new Vec3d(0.0, 0.0, 0.0), new Vec3d(x + 0.5, y + 0.5, z + 0.5), 1.0F);
                           }

                           rendered = true;
                        }
                     }
                  }

                  if (rendered) {
                     batch.flush();
                  }
               } finally {
                  matrices.pop();
               }
            }
         }
      }
   }

   private void prune(ChunkPos center, int radius) {
      List<Long> remove = new ArrayList<>();

      for (Long key : this.cached.keySet()) {
         ChunkPos cp = new ChunkPos(ChunkPos.getPackedX(key), ChunkPos.getPackedZ(key));
         if (Math.abs(cp.x - center.x) > radius || Math.abs(cp.z - center.z) > radius) {
            remove.add(key);
         }
      }

      for (Long keyx : remove) {
         Set<BlockPos> removed = this.cached.remove(keyx);
         if (removed != null) {
            for (BlockPos pos : removed) {
               this.blockTypes.remove(pos);
               this.announced.remove(pos);
            }
         }
      }
   }

   private void clearAll() {
      this.cached.clear();
      this.blockTypes.clear();
      this.announced.clear();
      synchronized (this.queueLock) {
         this.queue.clear();
         this.queued.clear();
      }
   }

   private Color colorFor(Block block, int alpha) {
      return new Color(255, 255, 255, alpha);
   }

   private String safeName(Block block) {
      try {
         return block.getName().getString();
      } catch (Throwable var4) {
         Identifier id = Registries.BLOCK.getId(block);
         return id == null ? "Block" : id.toString();
      }
   }
}


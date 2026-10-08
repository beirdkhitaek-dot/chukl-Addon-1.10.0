package com.chukl.addon.modules.water;

import com.chukl.addon.water.Category;
import com.chukl.addon.water.WModule;
import com.chukl.addon.water.ModuleManager;
import com.chukl.addon.water.BlocksSetting;
import com.chukl.addon.water.Setting;
import com.chukl.addon.water.RenderUtils;
import com.water.utils.renderer.StorageTracerRenderer;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.EnchantingTableBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.FurnaceBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.block.entity.PistonBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.block.entity.SmokerBlockEntity;
import net.minecraft.block.entity.TrappedChestBlockEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

public final class StorageESP extends WModule {
   private static final int TRACER_ALPHA = 191;
   private static final float TRACER_GLOW_SIZE = 1.9F;
   private static final int TRACER_GLOW_ALPHA = 70;
   private static final int TRACER_MID_ALPHA = 140;
   private static final long PULSE_DURATION_MS = 300L;
   public static StorageESP INSTANCE;
   private final Setting<Boolean> newStyle = new Setting<>("New Style", true);
   private final Setting<Boolean> chests = new Setting<>("Chest", true);
   private final Setting<Boolean> enderChests = new Setting<>("Ender Chest", true);
   private final Setting<Boolean> spawners = new Setting<>("Spawner", true);
   private final Setting<Boolean> shulkerBoxes = new Setting<>("Shulker Box", true);
   private final Setting<Boolean> shulkerDye = new Setting<>("Use Shulker Dyes", true);
   private final Setting<Boolean> furnaces = new Setting<>("Furnace", true);
   private final Setting<Boolean> barrels = new Setting<>("Barrel", true);
   private final Setting<Boolean> enchant = new Setting<>("Enchanting Table", true);
   private final Setting<Boolean> pistons = new Setting<>("Moving Piston", true);
   private final Setting<Boolean> redstonePulse = new Setting<>("Redstone Pulse", true);
   private final Setting<Boolean> hoppers = new Setting<>("Hopper", true);
   private final Setting<Boolean> tracers = new Setting<>("Tracers", true);
   private final Setting<Double> tracerWidth = new Setting<>("Tracer Width", 0.6, 0.05, 4.0);
   private final Setting<Double> fillAlpha = new Setting<>("Fill Alpha", 15.0, 0.0, 255.0);
   private final BlocksSetting customBlocks = new BlocksSetting("Blocks");
   private final Map<Block, Color> customBlockColors = new ConcurrentHashMap<>();
   private final Map<BlockPos, Block> storageBlocks = new ConcurrentHashMap<>();
   private final Map<BlockPos, Color> blockColors = new ConcurrentHashMap<>();
   private final Map<BlockPos, Long> pulseExpiresAt = new ConcurrentHashMap<>();
   private final ExecutorService scanner = Executors.newFixedThreadPool(Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors())), r -> {
      Thread t = new Thread(r, "storageESP-scan");
      t.setDaemon(true);
      t.setPriority(5);
      return t;
   });
   private final AtomicBoolean isScanning = new AtomicBoolean(false);
   private int scanTick = 0;
   private static final int SCAN_INTERVAL_TICKS = 1;
   private static final int SCAN_RADIUS_CHUNKS = 20;
   private static final int CACHE_RADIUS_CHUNKS = 20;
   private static final int RENDER_RADIUS_CHUNKS = 16;
   private final Set<Long> scannedChunks = Collections.synchronizedSet(new HashSet<>());
   private int lastCenterCX = Integer.MIN_VALUE;
   private int lastCenterCZ = Integer.MIN_VALUE;
   private RenderUtils.PersistentBatch fillBatch;
   private StorageTracerRenderer tracerBatch;
   private boolean lastChests;
   private boolean lastEnderChests;
   private boolean lastSpawners;
   private boolean lastShulkers;
   private boolean lastFurnaces;
   private boolean lastBarrels;
   private boolean lastEnchant;
   private boolean lastPistons;
   private boolean lastRedstonePulse;
   private boolean lastHoppers;
   private boolean lastNewStyle = true;
   private int nearbyRescanTick = 0;
   private static final int NEARBY_RESCAN_INTERVAL = 5;

   public StorageESP() {
      super("Storage ESP", Category.RENDER);
      INSTANCE = this;
      this.addSetting(this.newStyle);
      this.addSetting(this.chests);
      this.addSetting(this.enderChests);
      this.addSetting(this.spawners);
      this.addSetting(this.shulkerBoxes);
      this.addSetting(this.shulkerDye);
      this.addSetting(this.furnaces);
      this.addSetting(this.barrels);
      this.addSetting(this.enchant);
      this.addSetting(this.pistons);
      this.addSetting(this.redstonePulse);
      this.addSetting(this.hoppers);
      this.addSetting(this.tracers);
      this.addSetting(this.tracerWidth);
      this.addSetting(this.fillAlpha);
      this.addSetting(this.customBlocks);
   }

   public Map<Block, Color> getBuiltinBlockColors() {
      Map<Block, Color> map = new LinkedHashMap<>();
      if (this.chests.getValue()) {
         map.put(Blocks.CHEST, this.customBlockColors.getOrDefault(Blocks.CHEST, new Color(210, 140, 60)));
         map.put(Blocks.TRAPPED_CHEST, this.customBlockColors.getOrDefault(Blocks.TRAPPED_CHEST, new Color(220, 120, 40)));
      }

      if (this.enderChests.getValue()) {
         map.put(Blocks.ENDER_CHEST, this.customBlockColors.getOrDefault(Blocks.ENDER_CHEST, new Color(140, 80, 220)));
      }

      if (this.spawners.getValue()) {
         map.put(Blocks.SPAWNER, this.customBlockColors.getOrDefault(Blocks.SPAWNER, new Color(160, 150, 180)));
      }

      if (this.shulkerBoxes.getValue()) {
         map.put(Blocks.PURPLE_SHULKER_BOX, this.customBlockColors.getOrDefault(Blocks.PURPLE_SHULKER_BOX, new Color(160, 60, 180)));
      }

      if (this.furnaces.getValue()) {
         map.put(Blocks.FURNACE, this.customBlockColors.getOrDefault(Blocks.FURNACE, new Color(150, 150, 150)));
      }

      if (this.barrels.getValue()) {
         map.put(Blocks.BARREL, this.customBlockColors.getOrDefault(Blocks.BARREL, new Color(200, 130, 100)));
      }

      if (this.enchant.getValue()) {
         map.put(Blocks.ENCHANTING_TABLE, this.customBlockColors.getOrDefault(Blocks.ENCHANTING_TABLE, new Color(100, 100, 240)));
      }

      if (this.pistons.getValue()) {
         map.put(Blocks.PISTON, this.customBlockColors.getOrDefault(Blocks.PISTON, new Color(80, 200, 80)));
      }

      if (this.redstonePulse.getValue()) {
         map.put(Blocks.REDSTONE_WIRE, this.customBlockColors.getOrDefault(Blocks.REDSTONE_WIRE, new Color(255, 55, 55)));
      }

      if (this.hoppers.getValue()) {
         map.put(Blocks.HOPPER, this.customBlockColors.getOrDefault(Blocks.HOPPER, new Color(120, 120, 120)));
      }

      return map;
   }

   public void applySelectedBlocks(Set<Block> blocks) {
      this.chests.setValue(false);
      this.enderChests.setValue(false);
      this.spawners.setValue(false);
      this.shulkerBoxes.setValue(false);
      this.furnaces.setValue(false);
      this.barrels.setValue(false);
      this.enchant.setValue(false);
      this.pistons.setValue(false);
      this.redstonePulse.setValue(false);
      this.hoppers.setValue(false);
      this.customBlocks.setValue(new LinkedHashSet<>(blocks));
      this.invalidateColorCache();
   }

   public void setBuiltinBlockColor(Block block, Color color) {
      if (block != null && color != null) {
         this.customBlockColors.put(block, color);
         this.invalidateColorCache();
         ModuleManager.INSTANCE.onSettingChanged();
      }
   }

   public Map<Block, Color> getCustomBlockColors() {
      return new LinkedHashMap<>(this.customBlockColors);
   }

   public void setCustomBlockColors(Map<Block, Color> colors) {
      this.customBlockColors.clear();
      if (colors != null) {
         for (Entry<Block, Color> entry : colors.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
               this.customBlockColors.put(entry.getKey(), entry.getValue());
            }
         }
      }

      this.invalidateColorCache();
      ModuleManager.INSTANCE.onSettingChanged();
   }

   private void invalidateColorCache() {
      this.blockColors.clear();
      this.pulseExpiresAt.clear();
      this.scannedChunks.clear();
   }

   @Override
   public void onEnable() {
      this.storageBlocks.clear();
      this.blockColors.clear();
      this.pulseExpiresAt.clear();
      this.scannedChunks.clear();
      this.scanTick = 0;
      this.fillBatch = RenderUtils.createStorageBoxBatch();
      this.tracerBatch = new StorageTracerRenderer();
      this.lastChests = this.chests.getValue();
      this.lastEnderChests = this.enderChests.getValue();
      this.lastSpawners = this.spawners.getValue();
      this.lastShulkers = this.shulkerBoxes.getValue();
      this.lastFurnaces = this.furnaces.getValue();
      this.lastBarrels = this.barrels.getValue();
      this.lastEnchant = this.enchant.getValue();
      this.lastPistons = this.pistons.getValue();
      this.lastRedstonePulse = this.redstonePulse.getValue();
      this.lastHoppers = this.hoppers.getValue();
      this.lastNewStyle = this.newStyle.getValue();
   }

   @Override
   public void onDisable() {
      this.storageBlocks.clear();
      this.blockColors.clear();
      this.pulseExpiresAt.clear();
      this.scannedChunks.clear();
      if (this.fillBatch != null) {
         this.fillBatch.close();
         this.fillBatch = null;
      }

      if (this.tracerBatch != null) {
         this.tracerBatch.close();
         this.tracerBatch = null;
      }
   }

   private void checkSettingsChanged() {
      boolean changed = this.chests.getValue() != this.lastChests
         || this.enderChests.getValue() != this.lastEnderChests
         || this.spawners.getValue() != this.lastSpawners
         || this.shulkerBoxes.getValue() != this.lastShulkers
         || this.furnaces.getValue() != this.lastFurnaces
         || this.barrels.getValue() != this.lastBarrels
         || this.enchant.getValue() != this.lastEnchant
         || this.pistons.getValue() != this.lastPistons
         || this.redstonePulse.getValue() != this.lastRedstonePulse
         || this.hoppers.getValue() != this.lastHoppers
         || this.newStyle.getValue() != this.lastNewStyle;
      if (changed) {
         this.storageBlocks.clear();
         this.blockColors.clear();
         this.pulseExpiresAt.clear();
         this.scannedChunks.clear();
         this.lastChests = this.chests.getValue();
         this.lastEnderChests = this.enderChests.getValue();
         this.lastSpawners = this.spawners.getValue();
         this.lastShulkers = this.shulkerBoxes.getValue();
         this.lastFurnaces = this.furnaces.getValue();
         this.lastBarrels = this.barrels.getValue();
         this.lastEnchant = this.enchant.getValue();
         this.lastPistons = this.pistons.getValue();
         this.lastRedstonePulse = this.redstonePulse.getValue();
         this.lastHoppers = this.hoppers.getValue();
         this.lastNewStyle = this.newStyle.getValue();
      }
   }

   @Override
   public void onTick() {
      if (mc.world != null && mc.player != null) {
         this.checkSettingsChanged();
         this.pruneExpiredPulses();
         this.pruneDistantCache();
         if (++this.nearbyRescanTick >= 5) {
            this.nearbyRescanTick = 0;
            if (mc.player != null) {
               int cx = mc.player.getChunkPos().x;
               int cz = mc.player.getChunkPos().z;

               for (int dx = -2; dx <= 2; dx++) {
                  for (int dz = -2; dz <= 2; dz++) {
                     this.invalidateChunk(ChunkPos.toLong(cx + dx, cz + dz));
                  }
               }
            }
         }

         if (++this.scanTick >= 1 && !this.isScanning.get()) {
            this.scanTick = 0;
            this.startScan();
         }
      } else {
         this.storageBlocks.clear();
         this.blockColors.clear();
         this.pulseExpiresAt.clear();
      }
   }

   @Override
   public void onPacketReceive(Packet<?> packet) {
      if (mc.world != null) {
         if (packet instanceof ChunkDataS2CPacket chunkData) {
            this.invalidateChunk(ChunkPos.toLong(chunkData.getChunkX(), chunkData.getChunkZ()));
         } else if (packet instanceof ChunkDeltaUpdateS2CPacket deltaUpdate) {
            deltaUpdate.visitUpdates((pos, state) -> {
               this.handlePulseUpdate(pos, state);
               this.invalidateChunk(new ChunkPos(pos).toLong());
            });
         } else if (packet instanceof BlockUpdateS2CPacket blockUpdate) {
            this.handlePulseUpdate(blockUpdate.getPos(), blockUpdate.getState());
            this.invalidateChunk(new ChunkPos(blockUpdate.getPos()).toLong());
         }
      }
   }

   private void startScan() {
      this.isScanning.set(true);
      if (mc.world != null && mc.player != null) {
         BlockPos center = mc.player.getBlockPos();
         int cx0 = center.getX() >> 4;
         int cz0 = center.getZ() >> 4;
         if (Math.abs(cx0 - this.lastCenterCX) > 1 || Math.abs(cz0 - this.lastCenterCZ) > 1) {
            this.scannedChunks.clear();
            this.lastCenterCX = cx0;
            this.lastCenterCZ = cz0;
         }

         boolean hasCustom = this.customBlocks.size() > 0;
         List<WorldChunk> chunks = new ArrayList<>();

         try {
            for (int cx = cx0 - 20; cx <= cx0 + 20; cx++) {
               for (int cz = cz0 - 20; cz <= cz0 + 20; cz++) {
                  WorldChunk c = mc.world.getChunkManager().getWorldChunk(cx, cz, false);
                  if (c != null) {
                     long key = (long)cx << 32 | cz & 4294967295L;
                     if (!this.scannedChunks.contains(key)) {
                        chunks.add(c);
                     }
                  }
               }
            }
         } catch (Exception var12) {
            this.isScanning.set(false);
            return;
         }

         if (chunks.isEmpty()) {
            this.isScanning.set(false);
         } else {
            this.scanner.execute(() -> {
               try {
                  Map<BlockPos, Block> allFound = new HashMap<>();
                  Map<BlockPos, Color> allColors = new HashMap<>();

                  for (WorldChunk chunk : chunks) {
                     try {
                        for (Entry<BlockPos, BlockEntity> ex : (Iterable<Entry<BlockPos, BlockEntity>>)(Object)(new HashMap(chunk.getBlockEntities()).entrySet())) {
                           BlockEntity be = ex.getValue();
                           if (be instanceof PistonBlockEntity && this.pistons.getValue()) {
                              this.addPulse(ex.getKey(), be.getCachedState().getBlock(), this.getColorForBlock(be));
                           } else if (be != null && this.isStorageBlock(be)) {
                              allFound.put(ex.getKey(), be.getCachedState().getBlock());
                              allColors.put(ex.getKey(), this.getColorForBlock(be));
                           }
                        }
                     } catch (Exception var25) {
                     }

                     if (hasCustom) {
                        try {
                           ChunkSection[] secs = chunk.getSectionArray();
                           int minY = chunk.getBottomY();
                           int chunkX = chunk.getPos().x << 4;
                           int chunkZ = chunk.getPos().z << 4;

                           for (int si = 0; si < secs.length; si++) {
                              ChunkSection sec = secs[si];
                              if (sec != null && !sec.isEmpty()) {
                                 int baseY = minY + si * 16;

                                 for (int lx = 0; lx < 16; lx++) {
                                    for (int lz = 0; lz < 16; lz++) {
                                       for (int ly = 0; ly < 16; ly++) {
                                          Block b = sec.getBlockState(lx, ly, lz).getBlock();
                                          if (this.customBlocks.contains(b)) {
                                             BlockPos bp = new BlockPos(chunkX + lx, baseY + ly, chunkZ + lz);
                                             if (!allFound.containsKey(bp)) {
                                                allFound.put(bp, b);
                                                allColors.put(bp, this.customBlockColors.getOrDefault(b, new Color(0, 200, 255)));
                                             }
                                          }
                                       }
                                    }
                                 }
                              }
                           }
                        } catch (Exception var24) {
                        }
                     }
                  }

                  this.storageBlocks.putAll(allFound);
                  this.blockColors.putAll(allColors);

                  for (WorldChunk chunk : chunks) {
                     this.scannedChunks.add(chunk.getPos().toLong());
                  }

                  this.pruneExpiredPulses();
                  this.pruneDistantCache();
               } catch (Exception var26) {
               } finally {
                  this.isScanning.set(false);
               }
            });
         }
      } else {
         this.isScanning.set(false);
      }
   }

   private void pruneDistantCache() {
      if (mc.player != null) {
         int playerChunkX = mc.player.getChunkPos().x;
         int playerChunkZ = mc.player.getChunkPos().z;
         this.storageBlocks.keySet().removeIf(pos -> Math.max(Math.abs((pos.getX() >> 4) - playerChunkX), Math.abs((pos.getZ() >> 4) - playerChunkZ)) > 20);
         this.blockColors.keySet().removeIf(pos -> Math.max(Math.abs((pos.getX() >> 4) - playerChunkX), Math.abs((pos.getZ() >> 4) - playerChunkZ)) > 20);
         this.pulseExpiresAt.keySet().removeIf(pos -> Math.max(Math.abs((pos.getX() >> 4) - playerChunkX), Math.abs((pos.getZ() >> 4) - playerChunkZ)) > 20);
         this.scannedChunks
            .removeIf(chunkKey -> Math.max(Math.abs(ChunkPos.getPackedX(chunkKey) - playerChunkX), Math.abs(ChunkPos.getPackedZ(chunkKey) - playerChunkZ)) > 20);
      }
   }

   private void invalidateChunk(long chunkKey) {
      this.scannedChunks.remove(chunkKey);
   }

   private void clearChunkBlocks(long chunkKey) {
      int chunkX = ChunkPos.getPackedX(chunkKey);
      int chunkZ = ChunkPos.getPackedZ(chunkKey);
      this.storageBlocks.keySet().removeIf(pos -> pos.getX() >> 4 == chunkX && pos.getZ() >> 4 == chunkZ);
      this.blockColors.keySet().removeIf(pos -> pos.getX() >> 4 == chunkX && pos.getZ() >> 4 == chunkZ);
      this.pulseExpiresAt.keySet().removeIf(pos -> pos.getX() >> 4 == chunkX && pos.getZ() >> 4 == chunkZ);
   }

   private void addPulse(BlockPos pos, Block block, Color color) {
      if (pos != null && block != null && color != null) {
         BlockPos key = pos.toImmutable();
         this.storageBlocks.put(key, block);
         this.blockColors.put(key, color);
         this.pulseExpiresAt.put(key, System.currentTimeMillis() + 300L);
      }
   }

   private void pruneExpiredPulses() {
      long now = System.currentTimeMillis();
      this.pulseExpiresAt.entrySet().removeIf(entry -> {
         if (entry.getValue() > now) {
            return false;
         } else {
            BlockPos pos = entry.getKey();
            this.storageBlocks.remove(pos);
            this.blockColors.remove(pos);
            return true;
         }
      });
   }

   private void handlePulseUpdate(BlockPos pos, BlockState state) {
      if (pos != null && state != null) {
         Block block = state.getBlock();
         if (this.pistons.getValue() && this.isPistonPulseState(state)) {
            this.addPulse(pos, block, this.pistonPulseColor());
         }

         if (this.redstonePulse.getValue() && this.isPoweredRedstoneState(state)) {
            this.addPulse(pos, block, this.redstonePulseColor(block));
         }
      }
   }

   private boolean isPistonPulseState(BlockState state) {
      Block block = state.getBlock();
      return block == Blocks.MOVING_PISTON || block == Blocks.PISTON_HEAD || block == Blocks.PISTON || block == Blocks.STICKY_PISTON;
   }

   private boolean isPoweredRedstoneState(BlockState state) {
      Block block = state.getBlock();
      if (block == Blocks.REDSTONE_BLOCK) {
         return true;
      } else if (state.contains(Properties.POWER) && (Integer)state.get(Properties.POWER) > 0) {
         return true;
      } else {
         return state.contains(Properties.POWERED) && Boolean.TRUE.equals(state.get(Properties.POWERED))
            ? true
            : (block == Blocks.REDSTONE_TORCH || block == Blocks.REDSTONE_WALL_TORCH)
               && state.contains(Properties.LIT)
               && Boolean.TRUE.equals(state.get(Properties.LIT));
      }
   }

   private Color pistonPulseColor() {
      Color color = this.customBlockColors.get(Blocks.PISTON);
      return color != null ? color : new Color(80, 200, 80);
   }

   private Color redstonePulseColor(Block block) {
      Color color = this.customBlockColors.get(block);
      if (color == null) {
         color = this.customBlockColors.get(Blocks.REDSTONE_WIRE);
      }

      return color != null ? color : new Color(255, 55, 55);
   }

   private boolean isStorageBlock(BlockEntity be) {
      if (be instanceof ChestBlockEntity && this.chests.getValue()) {
         return true;
      } else if (be instanceof TrappedChestBlockEntity && this.chests.getValue()) {
         return true;
      } else if (be instanceof EnderChestBlockEntity && this.enderChests.getValue()) {
         return true;
      } else if (be instanceof MobSpawnerBlockEntity && this.spawners.getValue()) {
         return true;
      } else if (be instanceof ShulkerBoxBlockEntity && this.shulkerBoxes.getValue()) {
         return true;
      } else if (be instanceof FurnaceBlockEntity && this.furnaces.getValue()) {
         return true;
      } else if (be instanceof BlastFurnaceBlockEntity && this.furnaces.getValue()) {
         return true;
      } else if (be instanceof SmokerBlockEntity && this.furnaces.getValue()) {
         return true;
      } else if (be instanceof BarrelBlockEntity && this.barrels.getValue()) {
         return true;
      } else {
         return be instanceof EnchantingTableBlockEntity && this.enchant.getValue() ? true : be instanceof HopperBlockEntity && this.hoppers.getValue();
      }
   }

   private Color getColorForBlock(BlockEntity be) {
      Block block = be.getCachedState().getBlock();
      if (this.customBlockColors.containsKey(block)) {
         return this.customBlockColors.get(block);
      } else if (be instanceof ShulkerBoxBlockEntity && this.shulkerBoxes.getValue()) {
         if (this.shulkerDye.getValue()) {
            Color d = this.getShulkerDyeColor(block);
            if (d != null) {
               return d;
            }
         }

         return new Color(160, 60, 180);
      } else if (be instanceof TrappedChestBlockEntity) {
         return new Color(220, 120, 40);
      } else if (be instanceof ChestBlockEntity) {
         return new Color(210, 140, 60);
      } else if (be instanceof EnderChestBlockEntity) {
         return new Color(140, 80, 220);
      } else if (be instanceof MobSpawnerBlockEntity) {
         return new Color(160, 150, 180);
      } else if (be instanceof FurnaceBlockEntity || be instanceof BlastFurnaceBlockEntity || be instanceof SmokerBlockEntity) {
         return new Color(150, 150, 150);
      } else if (be instanceof BarrelBlockEntity) {
         return new Color(200, 130, 100);
      } else if (be instanceof EnchantingTableBlockEntity) {
         return new Color(100, 100, 240);
      } else if (be instanceof PistonBlockEntity) {
         return new Color(80, 200, 80);
      } else {
         return be instanceof HopperBlockEntity ? new Color(120, 120, 120) : new Color(100, 200, 255);
      }
   }

   private Color getShulkerDyeColor(Block block) {
      if (block == Blocks.WHITE_SHULKER_BOX) {
         return new Color(15789802);
      } else if (block == Blocks.ORANGE_SHULKER_BOX) {
         return new Color(15242794);
      } else if (block == Blocks.MAGENTA_SHULKER_BOX) {
         return new Color(12274357);
      } else if (block == Blocks.LIGHT_BLUE_SHULKER_BOX) {
         return new Color(3845832);
      } else if (block == Blocks.YELLOW_SHULKER_BOX) {
         return new Color(15253016);
      } else if (block == Blocks.LIME_SHULKER_BOX) {
         return new Color(6991400);
      } else if (block == Blocks.PINK_SHULKER_BOX) {
         return new Color(14708912);
      } else if (block == Blocks.GRAY_SHULKER_BOX) {
         return new Color(4738128);
      } else if (block == Blocks.LIGHT_GRAY_SHULKER_BOX) {
         return new Color(10000544);
      } else if (block == Blocks.CYAN_SHULKER_BOX) {
         return new Color(2263176);
      } else if (block == Blocks.PURPLE_SHULKER_BOX) {
         return new Color(7878832);
      } else if (block == Blocks.BLUE_SHULKER_BOX) {
         return new Color(2767016);
      } else if (block == Blocks.BROWN_SHULKER_BOX) {
         return new Color(7883824);
      } else if (block == Blocks.GREEN_SHULKER_BOX) {
         return new Color(5005344);
      } else if (block == Blocks.RED_SHULKER_BOX) {
         return new Color(9969696);
      } else {
         return block == Blocks.BLACK_SHULKER_BOX ? new Color(1710626) : null;
      }
   }

   @Override
   public void onRender(MatrixStack matrices, float tickDelta) {
      this.pruneExpiredPulses();
      if (mc.world != null && mc.player != null && !this.storageBlocks.isEmpty()) {
         if (this.fillBatch == null) {
            this.fillBatch = RenderUtils.createStorageBoxBatch();
         }

         if (this.tracerBatch == null) {
            this.tracerBatch = new StorageTracerRenderer();
         }

         Camera cam = RenderUtils.getCamera();
         if (cam != null) {
            Vec3d camPos = RenderUtils.getCameraPos(cam);
            Vec3d camFwd = RenderUtils.getCameraForward(cam);
            Vec3d tracerOrigin = Freecam.resolveTracerOrigin(camPos, tickDelta);
            Vec3d tracerStart = tracerOrigin.equals(camPos) ? camFwd.multiply(0.1) : tracerOrigin.subtract(camPos);
            double px = mc.player.getX();
            double py = mc.player.getY();
            double pz = mc.player.getZ();
            double renderDistSq = 272.0 * 272.0;
            float tW = this.tracerWidth.getValue().floatValue();
            if (this.tracers.getValue()) {
               List<Entry<BlockPos, Color>> targets = new ArrayList<>();

               for (BlockPos pos : this.storageBlocks.keySet()) {
                  Color base = this.currentBlockColor(pos);
                  double dx = pos.getX() - px;
                  double dz = pos.getZ() - pz;
                  if (base != null && dx * dx + dz * dz <= renderDistSq) {
                     targets.add(Map.entry(pos, base));
                  }
               }

               targets.sort(Comparator.comparingLong(ex -> ((BlockPos)ex.getKey()).asLong()));
               this.tracerBatch.begin(matrices);

               for (Entry<BlockPos, Color> entry : targets) {
                  Vec3d target = Vec3d.ofCenter((Vec3i)entry.getKey()).subtract(camPos);
                  if (Double.isFinite(target.x) && Double.isFinite(target.y) && Double.isFinite(target.z) && !(target.squaredDistanceTo(tracerStart) < 1.0E-12)
                     )
                   {
                     this.tracerBatch.addLine(tracerStart, target, withAlpha(entry.getValue(), 191), tW);
                  }
               }

               this.tracerBatch.flush();
            }

            if (this.newStyle.getValue()) {
               int fillA = this.clamp((int)Math.round(this.fillAlpha.getValue()));
               this.fillBatch.begin(matrices);

               for (Entry<BlockPos, Block> e : this.storageBlocks.entrySet()) {
                  BlockPos posx = e.getKey();
                  Color base = this.currentBlockColor(posx);
                  if (base != null) {
                     double dx = posx.getX() - px;
                     double dz = posx.getZ() - pz;
                     if (!(dx * dx + dz * dz > renderDistSq)) {
                        double rx = posx.getX() - camPos.x;
                        double ry = posx.getY() - camPos.y;
                        double rz = posx.getZ() - camPos.z;
                        if (Double.isFinite(rx) && Double.isFinite(ry) && Double.isFinite(rz) && fillA > 0) {
                           this.fillBatch.addFilledBox(rx + 0.1, ry + 0.1, rz + 0.1, rx + 0.9, ry + 0.9, rz + 0.9, withAlpha(base, fillA));
                        }
                     }
                  }
               }

               this.fillBatch.flushFill();
            } else {
               int outlineA = 220;
               int fillA = this.clamp((int)Math.round(this.fillAlpha.getValue()));
               this.fillBatch.begin(matrices);

               for (Entry<BlockPos, Block> ex : this.storageBlocks.entrySet()) {
                  BlockPos posx = ex.getKey();
                  Color base = this.currentBlockColor(posx);
                  if (base != null) {
                     double dx = posx.getX() - px;
                     double dz = posx.getZ() - pz;
                     if (!(dx * dx + dz * dz > renderDistSq)) {
                        double rx = posx.getX() - camPos.x;
                        double ry = posx.getY() - camPos.y;
                        double rz = posx.getZ() - camPos.z;
                        if (Double.isFinite(rx) && Double.isFinite(ry) && Double.isFinite(rz)) {
                           this.fillBatch.addOutlineBox(rx + 0.0625, ry, rz + 0.0625, rx + 0.9375, ry + 1.0, rz + 0.9375, withAlpha(base, outlineA));
                           if (fillA > 0) {
                              this.fillBatch.addFilledBox(rx + 0.0625, ry, rz + 0.0625, rx + 0.9375, ry + 1.0, rz + 0.9375, withAlpha(base, fillA));
                           }
                        }
                     }
                  }
               }

               this.fillBatch.flushFill();
            }
         }
      }
   }

   private Color currentBlockColor(BlockPos pos) {
      Block block = this.storageBlocks.get(pos);
      Color chosen = block == null ? null : this.customBlockColors.get(block);
      return chosen != null ? chosen : this.blockColors.get(pos);
   }

   private static Color withAlpha(Color c, int a) {
      return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a)));
   }

   private static Color brighten(Color c, float t) {
      int r = Math.min(255, (int)(c.getRed() + (255 - c.getRed()) * t * 0.5F + c.getRed() * t * 0.3F));
      int g = Math.min(255, (int)(c.getGreen() + (255 - c.getGreen()) * t * 0.5F + c.getGreen() * t * 0.3F));
      int b = Math.min(255, (int)(c.getBlue() + (255 - c.getBlue()) * t * 0.5F + c.getBlue() * t * 0.3F));
      return new Color(r, g, b);
   }

   private int clamp(int v) {
      return Math.max(0, Math.min(255, v));
   }
}


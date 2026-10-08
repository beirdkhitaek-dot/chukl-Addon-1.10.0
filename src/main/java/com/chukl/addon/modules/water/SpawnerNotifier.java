package com.chukl.addon.modules.water;

import com.chukl.addon.water.Category;
import com.chukl.addon.water.WModule;
import com.chukl.addon.water.Setting;
import com.chukl.addon.water.RenderUtils;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

public final class SpawnerNotifier extends WModule {
   private final Setting<Color> color = new Setting<>("Color", new Color(255, 80, 80));
   private final CopyOnWriteArrayList<BlockPos> spawners = new CopyOnWriteArrayList<>();
   public static SpawnerNotifier INSTANCE;
   private final Set<BlockPos> notified = ConcurrentHashMap.newKeySet();
   private long lastSoundTime = 0L;
   private int tickCounter = 0;

   public SpawnerNotifier() {
      super("Spawner Notifier", Category.RENDER);
      this.addSetting(this.color);
      INSTANCE = this;
   }

   @Override
   public void onEnable() {
      this.spawners.clear();
      this.notified.clear();
      this.tickCounter = 0;
   }

   @Override
   public void onDisable() {
      this.spawners.clear();
      this.notified.clear();
   }

   @Override
   public void onTick() {
      if (mc.world != null && mc.player != null) {
         this.tickCounter++;
         if (this.tickCounter % 40 == 0) {
            this.tickCounter = 0;
            ChunkPos center = mc.player.getChunkPos();
            int viewDist = Math.min(mc.options.getClampedViewDistance(), 8);
            List<BlockPos> found = new ArrayList<>();

            for (int cx = -viewDist; cx <= viewDist; cx++) {
               for (int cz = -viewDist; cz <= viewDist; cz++) {
                  WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(center.x + cx, center.z + cz, false);
                  if (chunk != null) {
                     for (BlockEntity be : chunk.getBlockEntities().values()) {
                        if (be instanceof MobSpawnerBlockEntity spawnerBE) {
                           BlockPos bp = spawnerBE.getPos();
                           found.add(bp);
                           if (!this.notified.contains(bp)) {
                              this.notified.add(bp);
                              long now = System.currentTimeMillis();
                              if (now - this.lastSoundTime > 5000L) {
                                 this.lastSoundTime = now;
                                 mc.world
                                    .playSound(
                                       mc.player,
                                       mc.player.getX(),
                                       mc.player.getY(),
                                       mc.player.getZ(),
                                       SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                                       SoundCategory.MASTER,
                                       1.0F,
                                       1.2F
                                    );
                              }

                              String msg = "§a[SpawnerNotifier] §fSpawner found at §e" + bp.getX() + ", " + bp.getY() + ", " + bp.getZ();

                              for (int i = 0; i < 4; i++) {
                                 mc.inGameHud.getChatHud().addMessage(Text.literal(msg));
                              }
                           }
                        }
                     }
                  }
               }
            }

            this.notified.retainAll(new HashSet<>(found));
            this.spawners.clear();
            this.spawners.addAll(found);
         }
      }
   }

   @Override
   public void onRender(MatrixStack matrices, float tickDelta) {
      if (mc.world != null && mc.player != null && !this.spawners.isEmpty()) {
         Camera cam = RenderUtils.getCamera();
         if (cam != null) {
            Vec3d camPos = RenderUtils.getCameraPos(cam);
            List<BlockPos> snapshot = new ArrayList<>(this.spawners);
            matrices.push();

            try {
               for (BlockPos pos : snapshot) {
                  double dx = pos.getX() + 0.5 - camPos.x;
                  double dy = pos.getY() + 0.5 - camPos.y;
                  double dz = pos.getZ() + 0.5 - camPos.z;
                  this.renderBeam(matrices, dx, dy, dz);
               }
            } finally {
               matrices.pop();
            }
         }
      }
   }

   private void renderBeam(MatrixStack matrices, double dx, double dy, double dz) {
      Color c = this.color.getValue();
      Vec3d bottom = new Vec3d(dx, dy, dz);
      Vec3d top = new Vec3d(dx, dy + 500.0, dz);
      int alpha = c.getAlpha();
      Color outer = new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(18, alpha / 5));
      Color middle = new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(45, alpha / 2));
      RenderUtils.renderLine(matrices, outer, bottom, top, 22.0F);
      RenderUtils.renderLine(matrices, middle, bottom, top, 15.0F);
      RenderUtils.renderLine(matrices, c, bottom, top, 10.0F);
   }
}


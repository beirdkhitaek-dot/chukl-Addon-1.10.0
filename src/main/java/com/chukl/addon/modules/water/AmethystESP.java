package com.chukl.addon.modules.water;

import com.chukl.addon.water.Category;
import com.chukl.addon.water.WModule;
import com.chukl.addon.water.WaterPlus;
import com.chukl.addon.water.Setting;
import com.chukl.addon.water.RenderUtils;
import java.awt.Color;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.client.toast.Toast.Visibility;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.BlockPos.Mutable;
import net.minecraft.world.LightType;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

public final class AmethystESP extends WModule {
   private static final double TRACER_START_DISTANCE = 150.0;
   private static final double TRACER_END_DISTANCE = 24.0;
   private static final double TRACER_BEHIND_SPREAD = 2.75;
   private static final double CHUNK_THICKNESS = 0.05;
   private static final int MIN_Y = -58;
   private static final int MAX_Y = 30;
   private static final int LIGHT_TARGET = 5;
   private static final int SCAN_Y_STEP = 1;
   private static final int LIGHT_ZERO = 0;
   private static final int LIGHT_AIR_NBR = 4;
   private static final int MAX_HITS_PER_CHUNK = 9000;
   private static final int MAX_RENDER_BOXES_PER_CHUNK = 400;
   private static final int MAX_RENDER_TRACERS_PER_CHUNK = 80;
   private static final int[][] DIRECT_NEIGHBORS = new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
   private static final double FIXED_CHUNK_Y = 55.0;
   private static final boolean FIXED_BLOCK_ESP = true;
   private static final boolean FIXED_CHUNK_MARK = true;
   private static final boolean FIXED_TOAST_NOTIFY = true;
   private static final Color FIXED_ESP_COLOR = new Color(180, 100, 255);
   private static final Color FIXED_CHUNK_COLOR = new Color(180, 100, 255);
   private final Setting<Float> clusterThreshold = new Setting<>("Treshold", 14.0F, 1.0F, 40.0F);
   private final Setting<Boolean> tracers = new Setting<>("Show Tracers", true);
   private static AmethystESP INSTANCE;
   private final Map<ChunkPos, Set<BlockPos>> foundClusters = new ConcurrentHashMap<>();
   private final Set<ChunkPos> notifiedChunks = ConcurrentHashMap.newKeySet();
   private final LinkedBlockingQueue<long[]> scanQueue = new LinkedBlockingQueue<>();
   private final Set<Long> queuedChunks = ConcurrentHashMap.newKeySet();
   private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "water-amethyst-scan");
      t.setDaemon(true);
      t.setPriority(4);
      return t;
   });
   private final AtomicBoolean scannerRunning = new AtomicBoolean(false);
   private final AtomicInteger newClustersFoundInBatch = new AtomicInteger(0);
   private final LinkedBlockingQueue<Integer> pendingToasts = new LinkedBlockingQueue<>();
   private int tickCounter = 0;

   public AmethystESP() {
      super("Amethyst ESP", Category.RENDER);
      INSTANCE = this;
      this.addSetting(this.clusterThreshold);
      this.addSetting(this.tracers);
   }

   public static AmethystESP instance() {
      return INSTANCE;
   }

   @Override
   public void onEnable() {
      this.foundClusters.clear();
      this.notifiedChunks.clear();
      this.scanQueue.clear();
      this.queuedChunks.clear();
      this.pendingToasts.clear();
      this.newClustersFoundInBatch.set(0);
      this.enqueueFullScan();
   }

   @Override
   public void onDisable() {
      this.foundClusters.clear();
      this.notifiedChunks.clear();
      this.scanQueue.clear();
      this.queuedChunks.clear();
      this.pendingToasts.clear();
      this.newClustersFoundInBatch.set(0);
   }

   private void enqueueFullScan() {
      if (mc.world != null && mc.player != null) {
         ChunkPos center = mc.player.getChunkPos();
         int radius = this.getRenderChunkRadius();

         for (int x = center.x - radius; x <= center.x + radius; x++) {
            for (int z = center.z - radius; z <= center.z + radius; z++) {
               this.enqueueChunk(x, z);
            }
         }

         this.kickScanner();
      }
   }

   private void enqueueChunk(int cx, int cz) {
      long key = ChunkPos.toLong(cx, cz);
      if (this.queuedChunks.add(key)) {
         this.scanQueue.offer(new long[]{cx, cz});
      }
   }

   private void kickScanner() {
      if (!this.scannerRunning.get() && !this.scanQueue.isEmpty() && this.scannerRunning.compareAndSet(false, true)) {
         this.scanExecutor.submit(this::drainScanQueue);
      }
   }

   private void drainScanQueue() {
      try {
         long[] coords;
         try {
            while ((coords = this.scanQueue.poll()) != null && this.isEnabled()) {
               int cx = (int)coords[0];
               int cz = (int)coords[1];
               long key = ChunkPos.toLong(cx, cz);
               this.queuedChunks.remove(key);
               MinecraftClient client = MinecraftClient.getInstance();
               World world = client.world;
               if (world != null && this.isChunkWithinRenderDistance(cx, cz)) {
                  WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz, false);
                  if (chunk != null) {
                     this.scanChunk(world, chunk);
                  }
               }
            }
         } catch (Exception var12) {
            var12.printStackTrace();
         }
      } finally {
         this.scannerRunning.set(false);
         if (!this.scanQueue.isEmpty() && this.isEnabled()) {
            this.kickScanner();
         }
      }
   }

   private void scanChunk(World world, WorldChunk chunk) {
      ChunkPos cp = chunk.getPos();
      int threshold = this.clusterThreshold.getValue().intValue();
      int baseX = cp.getStartX();
      int baseZ = cp.getStartZ();
      Mutable pos = new Mutable();
      Mutable nb = new Mutable();
      Set<BlockPos> hits = new HashSet<>();
      int scanned = 0;
      boolean capped = false;
      ChunkSection[] sections = chunk.getSectionArray();
      int chunkMinY = chunk.getBottomY();

      for (int si = 0; si < sections.length; si++) {
         int sectionBottomY = chunkMinY + si * 16;
         if (sectionBottomY <= 30 && sectionBottomY + 16 >= -58) {
            ChunkSection section = sections[si];
            if (section != null && !section.isEmpty() && section.hasAny(bsx -> bsx.isOf(Blocks.BUDDING_AMETHYST) || bsx.isOf(Blocks.AMETHYST_CLUSTER))) {
               for (int lx = 0; lx < 16 && !capped; lx++) {
                  int wx = baseX + lx;

                  for (int lz = 0; lz < 16 && !capped; lz++) {
                     int wz = baseZ + lz;

                     for (int ly = 0; ly < 16 && !capped; ly++) {
                        int wy = sectionBottomY + ly;
                        if (wy >= -58 && wy <= 30) {
                           BlockState bs = section.getBlockState(lx, ly, lz);
                           if (bs.isOf(Blocks.BUDDING_AMETHYST) || bs.isOf(Blocks.AMETHYST_CLUSTER)) {
                              pos.set(wx, wy, wz);
                              if (world.getLightLevel(LightType.BLOCK, pos) == 0 && this.hasQualifyingNeighbor(world, nb, wx, wy, wz)) {
                                 hits.add(pos.toImmutable());
                                 if (++scanned >= 9000) {
                                    capped = true;
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         }
      }

      if (hits.size() >= threshold) {
         boolean isNew = this.notifiedChunks.add(cp);
         this.foundClusters.put(cp, hits);
         if (isNew) {
            this.pendingToasts.offer(hits.size());
         }
      } else {
         this.foundClusters.remove(cp);
         this.notifiedChunks.remove(cp);
      }
   }

   private boolean hasQualifyingNeighbor(World world, Mutable nb, int x, int y, int z) {
      for (int[] d : DIRECT_NEIGHBORS) {
         nb.set(x + d[0], y + d[1], z + d[2]);
         if (world.getLightLevel(LightType.BLOCK, nb) >= 4) {
            return true;
         }
      }

      return false;
   }

   @Override
   public void onTick() {
      Integer toastCount;
      while ((toastCount = this.pendingToasts.poll()) != null) {
         this.showToast(toastCount);
      }

      if (mc.world != null && mc.player != null) {
         this.pruneOutOfRenderDistance();
         if (++this.tickCounter % 60 == 0) {
            ChunkPos center = mc.player.getChunkPos();
            int radius = this.getRenderChunkRadius();

            for (int x = center.x - radius; x <= center.x + radius; x++) {
               for (int z = center.z - radius; z <= center.z + radius; z++) {
                  this.enqueueChunk(x, z);
               }
            }

            this.kickScanner();
         }
      }
   }

   @Override
   public void onRender(MatrixStack matrices, float tickDelta) {
      if (mc.world != null && mc.player != null && !this.foundClusters.isEmpty()) {
         this.pruneOutOfRenderDistance();
         Camera camera = RenderUtils.getCamera();
         if (camera != null) {
            Vec3d camPos = RenderUtils.getCameraPos(camera);
            Vec3d camForward = RenderUtils.getCameraForward(camera);
            Vec3d camRight = RenderUtils.getCameraRight(camera);
            Vec3d camUp = RenderUtils.getCameraUp(camForward, camRight);
            Vec3d tracerStart = camForward.multiply(150.0);
            ChunkPos playerChunk = mc.player.getChunkPos();
            int renderChunkRadius = this.getRenderChunkRadius();
            double maxRenderDistanceSq = this.getMaxRenderDistanceSq(renderChunkRadius);
            double playerX = mc.player.getX();
            double playerY = mc.player.getY();
            double playerZ = mc.player.getZ();
            boolean renderTracers = this.tracers.getValue();
            Color espFill = this.withAlpha(FIXED_ESP_COLOR, 180);
            Color chunkFill = this.withAlpha(FIXED_CHUNK_COLOR, 200);
            Color tracerColor = this.withAlpha(FIXED_ESP_COLOR, 220);
            matrices.push();
            RenderUtils.WorldBatch batch = RenderUtils.beginWorldBatch(matrices);

            for (Entry<ChunkPos, Set<BlockPos>> entry : this.foundClusters.entrySet()) {
               ChunkPos cp = entry.getKey();
               if (this.isChunkWithinRenderDistance(playerChunk, cp, renderChunkRadius)) {
                  Set<BlockPos> positions = entry.getValue();
                  if (!positions.isEmpty()) {
                     double x1 = cp.getStartX() - camPos.x;
                     double z1 = cp.getStartZ() - camPos.z;
                     double x2 = cp.getEndX() + 1 - camPos.x;
                     double z2 = cp.getEndZ() + 1 - camPos.z;
                     double y1 = 55.0 - camPos.y;
                     double y2 = y1 + 0.05;
                     batch.renderFilledBox(x1, y1, z1, x2, y2, z2, chunkFill);
                     int renderedBoxes = 0;
                     int renderedTracers = 0;
                     boolean allowBoxes = true;

                     for (BlockPos pos : positions) {
                        if (!(pos.getSquaredDistance(playerX, playerY, playerZ) > maxRenderDistanceSq)) {
                           if (allowBoxes) {
                              if (renderedBoxes++ < 400) {
                                 double bx = pos.getX() - camPos.x;
                                 double by = pos.getY() - camPos.y;
                                 double bz = pos.getZ() - camPos.z;
                                 batch.renderFilledBox(bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0, espFill);
                              } else {
                                 allowBoxes = false;
                              }
                           }

                           if (renderTracers && renderedTracers < 80) {
                              Vec3d relTarget = new Vec3d(pos.getX() + 0.5 - camPos.x, pos.getY() + 0.5 - camPos.y, pos.getZ() + 0.5 - camPos.z);
                              Vec3d tracerEnd = RenderUtils.getSpreadTracerEnd(relTarget, camForward, camRight, camUp, 24.0, 2.75);
                              batch.renderLine(tracerColor, tracerStart, tracerEnd, WaterPlus.tracerLineWidth());
                              renderedTracers++;
                           }

                           if (!allowBoxes && (!renderTracers || renderedTracers >= 80)) {
                              break;
                           }
                        }
                     }
                  }
               }
            }

            batch.flush();
            matrices.pop();
         }
      }
   }

   private Color withAlpha(Color base, int alpha) {
      return new Color(base.getRed(), base.getGreen(), base.getBlue(), Math.min(255, alpha));
   }

   private int getRenderChunkRadius() {
      return mc.options.getClampedViewDistance();
   }

   private double getMaxRenderDistanceSq(int chunkRadius) {
      double distance = chunkRadius * 16.0 + 16.0;
      return distance * distance;
   }

   private boolean isChunkWithinRenderDistance(int cx, int cz) {
      MinecraftClient client = MinecraftClient.getInstance();
      return client.player == null
         ? false
         : this.isChunkWithinRenderDistance(client.player.getChunkPos(), new ChunkPos(cx, cz), client.options.getClampedViewDistance());
   }

   private boolean isChunkWithinRenderDistance(ChunkPos center, ChunkPos target, int radius) {
      return Math.abs(target.x - center.x) <= radius && Math.abs(target.z - center.z) <= radius;
   }

   private void pruneOutOfRenderDistance() {
      if (mc.player != null) {
         ChunkPos center = mc.player.getChunkPos();
         int radius = this.getRenderChunkRadius();
         this.foundClusters.keySet().removeIf(cp -> !this.isChunkWithinRenderDistance(center, cp, radius));
         this.scanQueue.removeIf(coords -> !this.isChunkWithinRenderDistance(center, new ChunkPos((int)coords[0], (int)coords[1]), radius));
         this.queuedChunks.removeIf(key -> !this.isChunkWithinRenderDistance(center, new ChunkPos(ChunkPos.getPackedX(key), ChunkPos.getPackedZ(key)), radius));
      }
   }

   public static void onChunkData(int cx, int cz) {
      if (INSTANCE != null && INSTANCE.isEnabled()) {
         if (INSTANCE.isChunkWithinRenderDistance(cx, cz)) {
            INSTANCE.enqueueChunk(cx, cz);
            INSTANCE.kickScanner();
         }
      }
   }

   public static void onChunkUnload(int cx, int cz) {
      if (INSTANCE != null) {
         ChunkPos cp = new ChunkPos(cx, cz);
         INSTANCE.foundClusters.remove(cp);
         long key = cp.toLong();
         INSTANCE.scanQueue.removeIf(coords -> (int)coords[0] == cx && (int)coords[1] == cz);
         INSTANCE.queuedChunks.remove(key);
      }
   }

   public static void onBlockUpdate(BlockPos pos, BlockState state) {
      onChunkData(pos.getX() >> 4, pos.getZ() >> 4);
   }

   public static void renderHud(DrawContext context, float delta) {
      if (INSTANCE != null && INSTANCE.isEnabled()) {
         MinecraftClient client = MinecraftClient.getInstance();
         context.drawText(client.textRenderer, "§d[Amethyst ESP] §f" + INSTANCE.foundClusters.size() + " cluster", 10, 10, -1, true);
      }
   }

   private void showToast(int totalClusters) {
      MinecraftClient client = MinecraftClient.getInstance();
      if (client.getToastManager() != null) {
         if (client.player != null) {
            client.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6F, 0.85F);
         }

         client.getToastManager()
            .add(
               new AmethystToast(
                  Text.literal("Amethyst ESP"), Text.literal("Found " + totalClusters + " clusters"), new ItemStack(Items.AMETHYST_CLUSTER)
               )
            );
      }
   }

   final class AmethystToast implements Toast {
      private static final Identifier TEXTURE = Identifier.ofVanilla("toast/advancement");
      private Text title;
      private Text description;
      private ItemStack icon;
      private Visibility visibility = Visibility.HIDE;

      private AmethystToast(Text title, Text description, ItemStack icon) {
         this.title = title;
         this.description = description;
         this.icon = icon;
      }

      public Visibility getVisibility() {
         return this.visibility;
      }

      public void update(ToastManager manager, long time) {
         this.visibility = time >= 5000.0 * manager.getNotificationDisplayTimeMultiplier() ? Visibility.HIDE : Visibility.SHOW;
      }

      public void draw(DrawContext context, TextRenderer textRenderer, long startTime) {
         context.drawGuiTexture(RenderPipelines.GUI_TEXTURED, TEXTURE, 0, 0, this.getWidth(), this.getHeight());
         context.drawText(textRenderer, this.title, 30, 7, -5213953, false);
         List<OrderedText> lines = textRenderer.wrapLines(this.description, 125);
         if (!lines.isEmpty()) {
            context.drawText(textRenderer, lines.getFirst(), 30, 18, -1, false);
         }

         context.drawItemWithoutEntity(this.icon, 8, 8);
      }
   }
}


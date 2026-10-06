package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import com.chukl.addon.utils.LineOfSight;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Finds big rectangular excavated rooms ("dug out" areas) in loaded chunks and
 * draws a box around each one. Natural caves are irregular, so they rarely
 * contain a clean box with solid walls that meets the limits.
 *
 * Interior blocks: anything a player placed inside the room (farms, redstone,
 * chests, water, lava, pillars...) is treated as empty space when finding the
 * room, so a full base or farm no longer hides the room.
 *
 * Base detection: rooms with player-placed storage/utility blocks, or lots of
 * player-placed blocks inside, are marked as bases and drawn in another color.
 */
public class DugOutHighlighter extends Module {
    /** Blocks that generate naturally in solid terrain. Everything else counts as player-placed. */
    private static final Set<Block> NATURAL_BLOCKS = Set.of(
        Blocks.STONE, Blocks.DEEPSLATE, Blocks.TUFF, Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE,
        Blocks.DIRT, Blocks.GRAVEL, Blocks.SAND, Blocks.CLAY, Blocks.CALCITE, Blocks.BEDROCK,
        Blocks.SMOOTH_BASALT, Blocks.DRIPSTONE_BLOCK, Blocks.POINTED_DRIPSTONE,
        Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST, Blocks.MOSS_BLOCK,
        Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
        Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
        Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
        Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE
    );

    /** Blocks that are rarely found in natural caves and suggest a player lives or stores items here. */
    private static final Set<Block> BASE_BLOCKS = Set.of(
        Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.ENDER_CHEST,
        Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER,
        Blocks.HOPPER, Blocks.DISPENSER, Blocks.DROPPER,
        Blocks.BREWING_STAND, Blocks.CRAFTING_TABLE, Blocks.ENCHANTING_TABLE,
        Blocks.ANVIL, Blocks.BEACON, Blocks.LANTERN, Blocks.SOUL_LANTERN
    );

    private static boolean isBaseBlock(Block block) {
        return BASE_BLOCKS.contains(block)
            || block instanceof BedBlock
            || block instanceof ShulkerBoxBlock
            || block instanceof AbstractSignBlock;
    }

    private record Area(Box box, int baseBlocks, int interiorBlocks) {}

    private final SettingGroup sgScan = settings.createGroup("Scan");
    private final SettingGroup sgBase = settings.createGroup("Base Detection");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // --- Scan ---

    private final Setting<Integer> scanRadius = sgScan.add(new IntSetting.Builder()
        .name("scan-radius")
        .description("Chunks around you to scan.")
        .defaultValue(4)
        .range(1, 8)
        .sliderRange(1, 8)
        .build()
    );

    private final Setting<Integer> minY = sgScan.add(new IntSetting.Builder()
        .name("min-y")
        .description("Lowest Y level to scan.")
        .defaultValue(-64)
        .range(-64, 320)
        .sliderRange(-64, 64)
        .build()
    );

    private final Setting<Integer> maxY = sgScan.add(new IntSetting.Builder()
        .name("max-y")
        .description("Highest Y level to scan.")
        .defaultValue(50)
        .range(-64, 320)
        .sliderRange(-64, 128)
        .build()
    );

    private final Setting<Integer> minVolume = sgScan.add(new IntSetting.Builder()
        .name("min-volume")
        .description("Minimum size of the room in blocks (counting anything inside it).")
        .defaultValue(200)
        .range(20, 50000)
        .sliderRange(20, 5000)
        .build()
    );

    private final Setting<Integer> minSize = sgScan.add(new IntSetting.Builder()
        .name("min-width-length")
        .description("Minimum width and length of the room.")
        .defaultValue(4)
        .range(2, 32)
        .sliderRange(2, 16)
        .build()
    );

    private final Setting<Integer> minHeight = sgScan.add(new IntSetting.Builder()
        .name("min-height")
        .description("Minimum height of the room.")
        .defaultValue(3)
        .range(2, 16)
        .sliderRange(2, 10)
        .build()
    );

    private final Setting<Integer> maxRoomSize = sgScan.add(new IntSetting.Builder()
        .name("max-room-size")
        .description("Largest width, length or height a single room can have. Bigger rooms get split into several boxes.")
        .defaultValue(64)
        .range(16, 128)
        .sliderRange(16, 128)
        .build()
    );

    private final Setting<Boolean> ignoreInterior = sgScan.add(new BoolSetting.Builder()
        .name("ignore-interior-blocks")
        .description("Treat player-placed blocks inside the room (farms, redstone, chests, water, lava, pillars...) as empty space, so rooms with a base or farm inside are still found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minWallSolidity = sgScan.add(new IntSetting.Builder()
        .name("min-wall-solidity")
        .description("How solid the walls, floor and ceiling around a room must be (%). Higher = only clean player-made rooms. Natural caves have ragged walls.")
        .defaultValue(85)
        .range(0, 100)
        .sliderRange(50, 100)
        .build()
    );

    private final Setting<Integer> scanInterval = sgScan.add(new IntSetting.Builder()
        .name("scan-interval")
        .description("Seconds between scans.")
        .defaultValue(3)
        .range(1, 30)
        .sliderRange(1, 15)
        .build()
    );

    // --- Base detection ---

    private final Setting<Boolean> detectBases = sgBase.add(new BoolSetting.Builder()
        .name("detect-bases")
        .description("Mark rooms that contain player-placed blocks (chests, barrels, furnaces, beds, farms...) as bases.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minBaseBlocks = sgBase.add(new IntSetting.Builder()
        .name("min-base-blocks")
        .description("How many storage/utility blocks (chests, barrels, furnaces, beds...) a room needs before it counts as a base.")
        .defaultValue(2)
        .range(1, 50)
        .sliderRange(1, 20)
        .visible(detectBases::get)
        .build()
    );

    private final Setting<Integer> minInteriorBlocks = sgBase.add(new IntSetting.Builder()
        .name("min-interior-blocks")
        .description("A room with this many player-placed blocks inside (farm, redstone, walls...) also counts as a base. Water and lava are not counted.")
        .defaultValue(25)
        .range(1, 2000)
        .sliderRange(5, 300)
        .visible(detectBases::get)
        .build()
    );

    private final Setting<Boolean> onlyBases = sgBase.add(new BoolSetting.Builder()
        .name("only-bases")
        .description("Only draw rooms detected as bases. Empty dug out rooms are hidden.")
        .defaultValue(false)
        .visible(detectBases::get)
        .build()
    );

    private final Setting<SettingColor> baseSideColor = sgBase.add(new ColorSetting.Builder()
        .name("base-side-color")
        .description("Side color for rooms detected as bases.")
        .defaultValue(new SettingColor(255, 60, 60, 40))
        .visible(detectBases::get)
        .build()
    );

    private final Setting<SettingColor> baseLineColor = sgBase.add(new ColorSetting.Builder()
        .name("base-line-color")
        .description("Outline color for rooms detected as bases.")
        .defaultValue(new SettingColor(255, 60, 60, 255))
        .visible(detectBases::get)
        .build()
    );

    // --- Render ---

    private final Setting<Integer> renderDistance = sgRender.add(new IntSetting.Builder()
        .name("render-distance")
        .description("Max distance in blocks to draw boxes.")
        .defaultValue(160)
        .range(16, 512)
        .sliderRange(16, 320)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the boxes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Color of the box sides.")
        .defaultValue(new SettingColor(0, 200, 255, 30))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Color of the box outline.")
        .defaultValue(new SettingColor(0, 200, 255, 255))
        .build()
    );

    private final Setting<Boolean> seeThroughWalls = sgRender.add(new BoolSetting.Builder()
        .name("see-through-walls")
        .description("Draw dug out areas through walls. When off, only areas in your line of sight are drawn.")
        .defaultValue(true)
        .build()
    );

    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private volatile List<Area> areas = List.of();
    private ExecutorService executor;
    private int timer;

    public DugOutHighlighter() {
        super(ChuklAddon.CATEGORY, "dug-out-highlighter", "Highlights big dug out (excavated) areas underground, even with a farm or base inside, and marks the ones that look like bases.");
    }

    @Override
    public void onActivate() {
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "DugOutHighlighter-Scan");
            t.setDaemon(true);
            return t;
        });
        areas = List.of();
        scanning.set(false);
        timer = 0;
    }

    @Override
    public void onDeactivate() {
        if (executor != null) executor.shutdownNow();
        executor = null;
        areas = List.of();
        scanning.set(false);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null || executor == null) return;
        if (--timer > 0) return;
        timer = scanInterval.get() * 20;

        if (!scanning.compareAndSet(false, true)) return;

        ClientWorld world = mc.world;
        BlockPos playerPos = mc.player.getBlockPos();
        int chunkX = playerPos.getX() >> 4;
        int chunkZ = playerPos.getZ() >> 4;
        int radius = scanRadius.get();
        int lowY = Math.min(minY.get(), maxY.get());
        int highY = Math.max(minY.get(), maxY.get());
        int volume = minVolume.get();
        int size = minSize.get();
        int height = minHeight.get();
        int maxDim = maxRoomSize.get();
        boolean interior = ignoreInterior.get();
        double solidity = minWallSolidity.get() / 100.0;
        boolean bases = detectBases.get();

        executor.execute(() -> {
            try {
                Scanner scanner = new Scanner(world, lowY, highY, volume, size, height, maxDim, interior, solidity, bases);
                areas = scanner.run(chunkX, chunkZ, radius);
            } catch (Throwable t) {
                ChuklAddon.LOG.error("Dug Out Highlighter scan failed", t);
            } finally {
                scanning.set(false);
            }
        });
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        List<Area> list = areas;
        if (list.isEmpty() || mc.player == null) return;

        double maxDistSq = (double) renderDistance.get() * renderDistance.get();
        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();

        boolean bases = detectBases.get();
        int baseThreshold = minBaseBlocks.get();
        int interiorThreshold = minInteriorBlocks.get();

        for (Area area : list) {
            Box box = area.box();
            boolean isBase = bases && (area.baseBlocks() >= baseThreshold || area.interiorBlocks() >= interiorThreshold);

            if (bases && onlyBases.get() && !isBase) continue;

            double cx = (box.minX + box.maxX) / 2.0;
            double cy = (box.minY + box.maxY) / 2.0;
            double cz = (box.minZ + box.maxZ) / 2.0;

            double dx = cx - px, dy = cy - py, dz = cz - pz;
            if (dx * dx + dy * dy + dz * dz > maxDistSq) continue;

            if (!seeThroughWalls.get() && !LineOfSight.canSeeBox(box)) continue;

            event.renderer.box(
                box.minX, box.minY, box.minZ,
                box.maxX, box.maxY, box.maxZ,
                isBase ? baseSideColor.get() : sideColor.get(),
                isBase ? baseLineColor.get() : lineColor.get(),
                shapeMode.get(), 0
            );
        }
    }

    /** Runs on the background thread. Finds axis-aligned boxes of open space that sit on a solid floor. */
    private static final class Scanner {
        private final ClientWorld world;
        private final BlockPos.Mutable pos = new BlockPos.Mutable();
        private final int minY, maxY, minVolume, minSize, minHeight, maxDim;
        private final boolean ignoreInterior, detectBases;
        private final double minSolidity;

        Scanner(ClientWorld world, int minY, int maxY, int minVolume, int minSize, int minHeight, int maxDim,
                boolean ignoreInterior, double minSolidity, boolean detectBases) {
            this.world = world;
            this.minY = minY;
            this.maxY = maxY;
            this.minVolume = minVolume;
            this.minSize = minSize;
            this.minHeight = minHeight;
            this.maxDim = maxDim;
            this.ignoreInterior = ignoreInterior;
            this.minSolidity = minSolidity;
            this.detectBases = detectBases;
        }

        /**
         * "Open" = air, or (when ignoring interior blocks) any block that doesn't generate naturally
         * in solid terrain, like farms, chests, redstone, water and lava placed inside the room.
         * Unloaded chunks and anything outside the Y band count as solid.
         */
        private boolean open(int x, int y, int z) {
            if (y < minY || y > maxY) return false;
            if (!world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) return false;
            return openLoaded(x, y, z);
        }

        /** Same as open() but skips the chunk check, for blocks inside a chunk already known to be loaded. */
        private boolean openLoaded(int x, int y, int z) {
            BlockState state = world.getBlockState(pos.set(x, y, z));
            if (state.isAir()) return true;
            return ignoreInterior && !NATURAL_BLOCKS.contains(state.getBlock());
        }

        /** Fraction of blocks hugging the outside of the box (floor, ceiling, 4 walls) that are not open. */
        private double wallSolidity(int x, int y, int z, int w, int d, int h) {
            int solid = 0, total = 0;

            for (int i = 0; i < w; i++) {
                for (int j = 0; j < d; j++) {
                    total += 2;
                    if (!open(x + i, y - 1, z + j)) solid++;
                    if (!open(x + i, y + h, z + j)) solid++;
                }
            }
            for (int k = 0; k < h; k++) {
                for (int j = 0; j < d; j++) {
                    total += 2;
                    if (!open(x - 1, y + k, z + j)) solid++;
                    if (!open(x + w, y + k, z + j)) solid++;
                }
            }
            for (int i = 0; i < w; i++) {
                for (int k = 0; k < h; k++) {
                    total += 2;
                    if (!open(x + i, y + k, z - 1)) solid++;
                    if (!open(x + i, y + k, z + d)) solid++;
                }
            }

            return (double) solid / total;
        }

        /**
         * Returns {baseBlocks, interiorBlocks}.
         * baseBlocks = storage/utility blocks (chests, furnaces, beds...) inside the box and its 1-block shell.
         * interiorBlocks = player-placed solid blocks strictly inside the box (water and lava not counted).
         */
        private int[] countBase(int[] b) {
            int baseBlocks = 0, interiorBlocks = 0;

            for (int x = b[0] - 1; x <= b[3]; x++) {
                for (int z = b[2] - 1; z <= b[5]; z++) {
                    if (!world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) continue;

                    for (int y = b[1] - 1; y <= b[4]; y++) {
                        BlockState state = world.getBlockState(pos.set(x, y, z));
                        Block block = state.getBlock();

                        if (isBaseBlock(block)) baseBlocks++;

                        boolean inside = x >= b[0] && x < b[3] && y >= b[1] && y < b[4] && z >= b[2] && z < b[5];
                        if (inside && !state.isAir() && !NATURAL_BLOCKS.contains(block) && state.getFluidState().isEmpty()) {
                            interiorBlocks++;
                        }
                    }
                }
            }

            return new int[]{baseBlocks, interiorBlocks};
        }

        private static boolean covered(List<int[]> boxes, int x, int y, int z) {
            for (int[] b : boxes) {
                if (x >= b[0] && x < b[3] && y >= b[1] && y < b[4] && z >= b[2] && z < b[5]) return true;
            }
            return false;
        }

        List<Area> run(int centerChunkX, int centerChunkZ, int radius) {
            List<int[]> boxes = new ArrayList<>();

            for (int cx = centerChunkX - radius; cx <= centerChunkX + radius; cx++) {
                for (int cz = centerChunkZ - radius; cz <= centerChunkZ + radius; cz++) {
                    if (!world.getChunkManager().isChunkLoaded(cx, cz)) continue;

                    int bx = cx << 4, bz = cz << 4;

                    for (int x = bx; x < bx + 16; x++) {
                        for (int z = bz; z < bz + 16; z++) {
                            for (int y = minY; y <= maxY; y++) {
                                if (!openLoaded(x, y, z)) continue;
                                // Lower corner of a room: solid floor, solid wall at -x and -z
                                if (open(x, y - 1, z)) continue;
                                if (open(x - 1, y, z) || open(x, y, z - 1)) continue;
                                if (covered(boxes, x, y, z)) continue;

                                // Expand along +x
                                int w = 1;
                                while (w < maxDim && open(x + w, y, z)) w++;

                                // Expand along +z while the whole row is open
                                int d = 1;
                                expandZ:
                                while (d < maxDim) {
                                    for (int i = 0; i < w; i++) {
                                        if (!open(x + i, y, z + d)) break expandZ;
                                    }
                                    d++;
                                }

                                // Expand upward while the whole layer is open
                                int h = 1;
                                expandY:
                                while (h < maxDim && y + h <= maxY) {
                                    for (int i = 0; i < w; i++) {
                                        for (int j = 0; j < d; j++) {
                                            if (!open(x + i, y + h, z + j)) break expandY;
                                        }
                                    }
                                    h++;
                                }

                                if (w >= minSize && d >= minSize && h >= minHeight && w * d * h >= minVolume
                                    && wallSolidity(x, y, z, w, d, h) >= minSolidity) {
                                    boxes.add(new int[]{x, y, z, x + w, y + h, z + d});
                                }
                            }
                        }
                    }
                }
            }

            List<Area> result = new ArrayList<>(boxes.size());
            for (int[] b : boxes) {
                int[] counts = detectBases ? countBase(b) : new int[]{0, 0};
                result.add(new Area(new Box(b[0], b[1], b[2], b[3], b[4], b[5]), counts[0], counts[1]));
            }
            return result;
        }
    }
}

package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Finds probable base locations from plant/amethyst growth and buried rotated deepslate.
 *
 * Works the same way as the Krypton module: every chunk is scanned ONCE when its data arrives (and once
 * for the chunks already loaded when you turn the module on). Chunks that still contain things that
 * have not finished growing "heat up" all chunks around them. A chunk that is hot, is not covered by
 * fully grown plants, and has at least 3 loaded neighbors is marked as sus.
 *
 * Speed: chunks are scanned in parallel on several background threads, sections without anything
 * interesting are skipped, and the sus list refreshes every other tick.
 */
public class SusChunkFinder extends Module {
    private record ScanResult(int notGrown, boolean hasGrown, Set<BlockPos> deepslate) {}

    private record Opts(boolean kelp, boolean caveVines, boolean vines, boolean amethyst, boolean folia,
                        boolean bamboo, boolean cocoa, boolean beeNest, boolean deepslate) {}

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    // --- General (same list and order as Krypton) ---

    private final Setting<Integer> simulationDistance = sgGeneral.add(new IntSetting.Builder()
        .name("simulation-distance")
        .description("Chunk radius around growing plants that is considered 'currently loaded' and ignored. 0 = only the chunk the plants are in, 1 = 3x3 chunks, 2 = 5x5.")
        .defaultValue(4)
        .range(0, 10)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Integer> sensitivity = sgGeneral.add(new IntSetting.Builder()
        .name("sensitivity")
        .description("Minimum number of unfinished growing things needed to mark a chunk as sus.")
        .defaultValue(5)
        .range(1, 20)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Boolean> smartAdjustment = sgGeneral.add(new BoolSetting.Builder()
        .name("smart-adjustment")
        .description("Smart detection: only scans and draws chunks inside your render distance, and makes chunks with more heat draw stronger. Never changes your sensitivity or simulation distance.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> alpha = sgGeneral.add(new IntSetting.Builder()
        .name("alpha")
        .description("Opacity (10-255) of the chunk fill and the deepslate boxes.")
        .defaultValue(81)
        .range(10, 255)
        .sliderRange(10, 255)
        .build()
    );

    private final Setting<Boolean> kelp = sgGeneral.add(new BoolSetting.Builder()
        .name("kelp").description("Use kelp growth.").defaultValue(false).build());

    private final Setting<Boolean> caveVines = sgGeneral.add(new BoolSetting.Builder()
        .name("cave-vines").description("Use cave vine growth.").defaultValue(false).build());

    private final Setting<Boolean> vines = sgGeneral.add(new BoolSetting.Builder()
        .name("vines").description("Use vine growth.").defaultValue(false).build());

    private final Setting<Boolean> amethyst = sgGeneral.add(new BoolSetting.Builder()
        .name("amethyst").description("Use amethyst bud and cluster growth.").defaultValue(true).build());

    private final Setting<Boolean> bamboo = sgGeneral.add(new BoolSetting.Builder()
        .name("bamboo").description("Use bamboo growth.").defaultValue(false).build());

    private final Setting<Boolean> cocoa = sgGeneral.add(new BoolSetting.Builder()
        .name("cocoa").description("Use cocoa pod growth.").defaultValue(false).build());

    private final Setting<Boolean> beeNest = sgGeneral.add(new BoolSetting.Builder()
        .name("bee-nest")
        .description("Bee nests with honey mean someone loaded the chunk.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> rotatedDeepslate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotated-deepslate")
        .description("Detect rotated deepslate buried between Y 0 and 60.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> debugInfo = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-info")
        .description("Every 2 seconds, prints the numbers behind the chunk you are standing in (heat, tracked, loaded neighbors). Use it to compare with Krypton.")
        .defaultValue(false)
        .build()
    );

    // --- Render ---

    private final Setting<ShapeMode> chunkShapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("chunk-shape-mode")
        .description("How sus chunks are drawn. Sides = filled flat square, Lines = outline only, Both = filled with outline.")
        .defaultValue(ShapeMode.Sides)
        .build()
    );

    private final Setting<SettingColor> chunkSideColor = sgRender.add(new ColorSetting.Builder()
        .name("chunk-fill-color")
        .description("Fill color of sus chunks (opacity comes from the Alpha setting).")
        .defaultValue(new SettingColor(255, 0, 0, 110))
        .visible(() -> chunkShapeMode.get() != ShapeMode.Lines)
        .build()
    );

    private final Setting<SettingColor> chunkLineColor = sgRender.add(new ColorSetting.Builder()
        .name("chunk-outline-color")
        .description("Outline color of sus chunks.")
        .defaultValue(new SettingColor(255, 0, 0, 200))
        .visible(() -> chunkShapeMode.get() != ShapeMode.Sides)
        .build()
    );

    private final Setting<Integer> chunkY = sgRender.add(new IntSetting.Builder()
        .name("chunk-y")
        .description("Height the chunk square is drawn at.")
        .defaultValue(63)
        .range(-64, 320)
        .sliderRange(-64, 128)
        .build()
    );

    private final Setting<Double> chunkThickness = sgRender.add(new DoubleSetting.Builder()
        .name("chunk-thickness")
        .description("Thickness of the chunk box in blocks. Small = flat square, larger = tall column.")
        .defaultValue(0.1)
        .range(0.05, 128)
        .sliderRange(0.05, 16)
        .build()
    );

    private final Setting<Integer> chunkInset = sgRender.add(new IntSetting.Builder()
        .name("chunk-inset")
        .description("Shrinks each chunk square by this many blocks on every side, so the layer is smaller and neighbors don't merge into one big sheet.")
        .defaultValue(2)
        .range(0, 7)
        .sliderRange(0, 7)
        .build()
    );

    private final Setting<SettingColor> deepslateColor = sgRender.add(new ColorSetting.Builder()
        .name("deepslate-color")
        .description("Outline color of suspicious rotated deepslate blocks.")
        .defaultValue(new SettingColor(0, 255, 255, 160))
        .visible(rotatedDeepslate::get)
        .build()
    );

    private final Setting<Integer> deepslateDistance = sgRender.add(new IntSetting.Builder()
        .name("deepslate-render-distance")
        .description("Only draw suspicious deepslate within this many blocks.")
        .defaultValue(64)
        .range(16, 256)
        .sliderRange(16, 192)
        .visible(rotatedDeepslate::get)
        .build()
    );

    // --- Data (only touched by the single scan thread, read by the main thread) ---

    private final Map<ChunkPos, Integer> heatmap = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> tracked = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> growthCounts = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Boolean> fullyGrown = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> usedSimDist = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Set<BlockPos>> chunkDeepslate = new ConcurrentHashMap<>();
    private final Set<ChunkPos> loadedChunks = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> deepslateSet = ConcurrentHashMap.newKeySet();

    private volatile Map<ChunkPos, Integer> susChunks = Map.of();
    private volatile List<BlockPos> deepslateBlocks = List.of();
    private ExecutorService[] executors;
    private ClientWorld lastWorld;
    private int timer;
    private final Map<ChunkPos, WorldChunk> seenChunks = new java.util.HashMap<>();

    public SusChunkFinder() {
        super(ChuklAddon.CATEGORY, "sus-chunk-finder", "Finds probable base locations using plant/amethyst growth and rotated deepslate.");
    }

    @Override
    public void onActivate() {
        restart();
    }

    @Override
    public void onDeactivate() {
        stopExecutors();
        lastWorld = null;
        clearData();
    }

    private void stopExecutors() {
        if (executors != null) {
            for (ExecutorService e : executors) e.shutdownNow();
        }
        executors = null;
    }

    private void restart() {
        stopExecutors();

        // One single-thread executor per worker. A given chunk always goes to the same worker, so two
        // scans of the same chunk can never run at once, while different chunks scan in parallel.
        int count = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        executors = new ExecutorService[count];
        for (int i = 0; i < count; i++) {
            int id = i;
            executors[i] = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "SusChunkFinder-Scan-" + id);
                t.setDaemon(true);
                return t;
            });
        }

        clearData();
        lastWorld = mc.world;
        timer = 0;
    }

    private void clearData() {
        heatmap.clear();
        tracked.clear();
        growthCounts.clear();
        fullyGrown.clear();
        usedSimDist.clear();
        chunkDeepslate.clear();
        loadedChunks.clear();
        seenChunks.clear();
        deepslateSet.clear();
        susChunks = Map.of();
        deepslateBlocks = List.of();
    }

    // ---------- scanning ----------

    private boolean isDonutFolia() {
        if (mc.getNetworkHandler() == null) return false;
        String brand = mc.getNetworkHandler().getBrand();
        return brand != null && brand.contains("DonutFolia");
    }

    private Opts opts() {
        return new Opts(kelp.get(), caveVines.get(), vines.get(), amethyst.get(), amethyst.get() && isDonutFolia(),
            bamboo.get(), cocoa.get(), beeNest.get(), rotatedDeepslate.get());
    }

    private int effectiveSimDist() {
        return simulationDistance.get();
    }

    /** Your sensitivity is used exactly as set. Smart Adjustment does not change it. */
    private int effectiveSensitivity() {
        return sensitivity.get();
    }

    /**
     * Looks for chunks that just loaded (or were sent again by the server, which creates a new chunk
     * object) and scans each one once. Checked every tick, so a chunk is scanned the moment it arrives.
     */
    private void scanNewChunks() {
        if (mc.world == null || mc.player == null) return;

        var manager = mc.world.getChunkManager();
        ChunkPos center = mc.player.getChunkPos();
        int radius = mc.options.getViewDistance().getValue() + (smartAdjustment.get() ? 0 : 2);

        for (int cx = center.x - radius; cx <= center.x + radius; cx++) {
            for (int cz = center.z - radius; cz <= center.z + radius; cz++) {
                if (!manager.isChunkLoaded(cx, cz)) continue;

                WorldChunk chunk = manager.getWorldChunk(cx, cz);
                if (chunk == null) continue;

                ChunkPos pos = new ChunkPos(cx, cz);
                if (seenChunks.get(pos) != chunk) {
                    seenChunks.put(pos, chunk);
                    queue(chunk);
                }
            }
        }

        // Forget unloaded chunks so they get scanned again when they come back
        seenChunks.keySet().removeIf(pos -> !manager.isChunkLoaded(pos.x, pos.z));
    }

    private void queue(WorldChunk chunk) {
        ExecutorService[] pool = executors;
        if (pool == null || chunk == null || mc.world == null) return;

        ClientWorld world = mc.world;
        Opts o = opts();
        int simDist = effectiveSimDist();

        ChunkPos pos = chunk.getPos();
        ExecutorService worker = pool[Math.floorMod(pos.x * 31 + pos.z, pool.length)];

        worker.execute(() -> {
            try {
                updateChunk(world, chunk, o, simDist);
            } catch (Throwable t) {
                ChuklAddon.LOG.error("Sus Chunk Finder scan failed", t);
            }
        });
    }

    private static void forEachNeighbor(ChunkPos cp, int dist, Consumer<ChunkPos> action) {
        for (int dx = -dist; dx <= dist; dx++) {
            for (int dz = -dist; dz <= dist; dz++) {
                action.accept(new ChunkPos(cp.x + dx, cp.z + dz));
            }
        }
    }

    /** Background thread. Removes what this chunk contributed last time, scans it again, adds the new result. */
    private void updateChunk(ClientWorld world, WorldChunk chunk, Opts o, int simDist) {
        if (world != mc.world) return;

        ChunkPos cp = chunk.getPos();
        loadedChunks.add(cp);

        int prevSim = usedSimDist.getOrDefault(cp, simDist);
        int prevGrowth = growthCounts.getOrDefault(cp, 0);
        boolean prevFull = Boolean.TRUE.equals(fullyGrown.get(cp));
        Set<BlockPos> prevDeepslate = chunkDeepslate.remove(cp);

        if (prevGrowth > 0) {
            forEachNeighbor(cp, prevSim, n -> heatmap.compute(n, (k, v) -> {
                int value = (v == null ? 0 : v) - prevGrowth;
                return value <= 0 ? null : value;
            }));
        }

        if (prevFull) {
            forEachNeighbor(cp, prevSim, n -> tracked.compute(n, (k, v) -> {
                if (v == null) return null;
                return v - 1 <= 0 ? null : v - 1;
            }));
        }

        if (prevDeepslate != null && !prevDeepslate.isEmpty()) deepslateSet.removeAll(prevDeepslate);

        ScanResult result = scanChunk(world, chunk, cp, o);

        growthCounts.put(cp, result.notGrown());
        fullyGrown.put(cp, result.hasGrown());
        usedSimDist.put(cp, simDist);

        if (result.hasGrown()) forEachNeighbor(cp, simDist, n -> tracked.merge(n, 1, Integer::sum));
        if (result.notGrown() > 0) forEachNeighbor(cp, simDist, n -> heatmap.merge(n, result.notGrown(), Integer::sum));

        if (!result.deepslate().isEmpty()) {
            chunkDeepslate.put(cp, result.deepslate());
            deepslateSet.addAll(result.deepslate());
        }
    }

    private static boolean isBud(BlockState s) {
        Block b = s.getBlock();
        return b == Blocks.SMALL_AMETHYST_BUD || b == Blocks.MEDIUM_AMETHYST_BUD || b == Blocks.LARGE_AMETHYST_BUD;
    }

    private static boolean isCluster(BlockState s) {
        return s.getBlock() == Blocks.AMETHYST_CLUSTER;
    }

    private ScanResult scanChunk(ClientWorld world, WorldChunk chunk, ChunkPos cp, Opts o) {
        ChunkSection[] sections = chunk.getSectionArray();
        int bottomSection = chunk.getBottomSectionCoord();
        int startX = cp.getStartX();
        int startZ = cp.getStartZ();

        // Palette check: sections without any of these blocks are skipped entirely.
        boolean useAmethystBlocks = o.amethyst() && !o.folia();
        Predicate<BlockState> growthFilter = s -> {
            Block b = s.getBlock();
            return (o.kelp() && b == Blocks.KELP)
                || (o.caveVines() && b == Blocks.CAVE_VINES)
                || (o.vines() && b == Blocks.VINE)
                || (useAmethystBlocks && (b == Blocks.AMETHYST_CLUSTER || isBud(s)))
                || (o.bamboo() && b == Blocks.BAMBOO)
                || (o.cocoa() && b == Blocks.COCOA)
                || (o.beeNest() && b == Blocks.BEE_NEST);
        };
        Predicate<BlockState> deepslateFilter = s -> s.getBlock() == Blocks.DEEPSLATE;

        int notGrown = 0;
        int grown = 0;
        boolean foundBuds = false;
        boolean foundClusters = false;
        Set<BlockPos> deepslate = new HashSet<>();
        BlockPos.Mutable other = new BlockPos.Mutable();

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;

            int sectionMinY = (bottomSection + i) << 4;

            if (o.folia()) {
                if (!foundBuds && section.hasAny(SusChunkFinder::isBud)) foundBuds = true;
                if (!foundClusters && section.hasAny(SusChunkFinder::isCluster)) foundClusters = true;
            }

            boolean scanGrowth = section.hasAny(growthFilter);
            boolean scanDeepslate = o.deepslate()
                && sectionMinY + 15 >= 0 && sectionMinY <= 60
                && section.hasAny(deepslateFilter);

            if (!scanGrowth && !scanDeepslate) continue;

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        Block block = state.getBlock();

                        int bx = startX + x;
                        int by = sectionMinY + y;
                        int bz = startZ + z;

                        if (scanGrowth) {
                            if (o.kelp() && block == Blocks.KELP && state.contains(Properties.AGE_25)) {
                                boolean waterAbove = world.getBlockState(other.set(bx, by + 1, bz)).isOf(Blocks.WATER);
                                if (state.get(Properties.AGE_25) != 25 && waterAbove) grown++;
                                else notGrown++;

                            } else if (o.caveVines() && block == Blocks.CAVE_VINES && state.contains(Properties.AGE_25)) {
                                boolean airBelow = world.getBlockState(other.set(bx, by - 1, bz)).isAir();
                                if (state.get(Properties.AGE_25) != 25 && airBelow) grown++;
                                else notGrown++;

                            } else if (o.vines() && block == Blocks.VINE) {
                                BlockState below = world.getBlockState(other.set(bx, by - 1, bz));
                                if (!below.isOf(Blocks.VINE)) {
                                    if (!below.isAir()) {
                                        notGrown++;
                                    } else if (state.get(Properties.NORTH) || state.get(Properties.EAST)
                                        || state.get(Properties.SOUTH) || state.get(Properties.WEST)) {
                                        grown++;
                                    } else {
                                        notGrown++;
                                    }
                                }

                            } else if (useAmethystBlocks && block == Blocks.AMETHYST_CLUSTER) {
                                notGrown++;

                            } else if (useAmethystBlocks && isBud(state) && state.contains(Properties.FACING)) {
                                Direction facing = state.get(Properties.FACING);
                                BlockPos parent = other.set(bx, by, bz).offset(facing.getOpposite());
                                if (world.getBlockState(parent).isOf(Blocks.BUDDING_AMETHYST)) grown++;
                                else notGrown++;

                            } else if (o.bamboo() && block == Blocks.BAMBOO && state.contains(Properties.STAGE)) {
                                BlockState above = world.getBlockState(other.set(bx, by + 1, bz));
                                if (!above.isOf(Blocks.BAMBOO)) {
                                    if (state.get(Properties.STAGE) == 1) notGrown++;
                                    else if (above.isAir()) grown++;
                                }

                            } else if (o.cocoa() && block == Blocks.COCOA && state.contains(Properties.AGE_2)) {
                                if (state.get(Properties.AGE_2) == 2) notGrown++;
                                else grown++;

                            } else if (o.beeNest() && block == Blocks.BEE_NEST && state.contains(Properties.HONEY_LEVEL)) {
                                if (state.get(Properties.HONEY_LEVEL) == 5) notGrown++;
                                else grown++;
                            }
                        }

                        if (scanDeepslate && block == Blocks.DEEPSLATE && state.contains(Properties.AXIS)
                            && state.get(Properties.AXIS) != Direction.Axis.Y && by >= 0 && by <= 60) {

                            boolean buried = true;
                            for (Direction dir : Direction.values()) {
                                if (world.getBlockState(other.set(bx, by, bz).offset(dir)).isAir()) {
                                    buried = false;
                                    break;
                                }
                            }
                            if (buried) deepslate.add(new BlockPos(bx, by, bz));
                        }
                    }
                }
            }
        }

        if (o.folia()) {
            if (foundBuds) grown++;
            else if (foundClusters) notGrown++;
        }

        return new ScanResult(notGrown, grown > 0, deepslate);
    }

    // ---------- main thread ----------

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null || executors == null) return;

        // New world / dimension: start over, like turning the module off and on
        if (mc.world != lastWorld) {
            restart();
            return;
        }

        scanNewChunks();

        if (debugInfo.get() && mc.player.age % 40 == 0) printDebug();

        if (--timer > 0) return;
        timer = 2; // refresh the sus list every other tick

        ClientWorld world = mc.world;
        int minSensitivity = effectiveSensitivity();
        Map<ChunkPos, Integer> sus = new java.util.HashMap<>();

        for (Map.Entry<ChunkPos, Integer> entry : heatmap.entrySet()) {
            ChunkPos pos = entry.getKey();
            if (entry.getValue() < minSensitivity) continue;
            if (tracked.containsKey(pos)) continue;
            if (!world.getChunkManager().isChunkLoaded(pos.x, pos.z)) continue;

            int neighbors = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    if (loadedChunks.contains(new ChunkPos(pos.x + dx, pos.z + dz))) neighbors++;
                }
            }

            if (neighbors >= 3) sus.put(pos, entry.getValue());
        }
        susChunks = sus;

        if (rotatedDeepslate.get()) {
            deepslateSet.removeIf(p -> !world.getChunkManager().isChunkLoaded(p.getX() >> 4, p.getZ() >> 4));
            deepslateBlocks = new ArrayList<>(deepslateSet);
        } else {
            deepslateBlocks = List.of();
        }
    }

    private void printDebug() {
        ChunkPos cp = mc.player.getChunkPos();
        int heat = heatmap.getOrDefault(cp, 0);
        int cover = tracked.getOrDefault(cp, 0);
        int neighbors = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (loadedChunks.contains(new ChunkPos(cp.x + dx, cp.z + dz))) neighbors++;
            }
        }

        String brand = mc.getNetworkHandler() == null ? "?" : String.valueOf(mc.getNetworkHandler().getBrand());
        info("Server " + brand + ", folia mode " + isDonutFolia() + ", render distance " + mc.options.getViewDistance().getValue()
            + ", sim distance " + effectiveSimDist() + ", chunks tracked " + loadedChunks.size());
        info("Chunk " + cp.x + ", " + cp.z + ": heat " + heat + " (needs " + effectiveSensitivity() + "), covered by grown plants "
            + cover + ", loaded neighbors " + neighbors + " (needs 3), own growing count " + growthCounts.getOrDefault(cp, 0)
            + ", flagged " + susChunks.containsKey(cp));
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;

        Map<ChunkPos, Integer> chunks = susChunks;
        if (!chunks.isEmpty()) {
            double y = chunkY.get();
            double thickness = chunkThickness.get();
            int inset = chunkInset.get();
            SettingColor fill = chunkSideColor.get();
            SettingColor line = chunkLineColor.get();
            ShapeMode mode = chunkShapeMode.get();
            int baseAlpha = alpha.get();
            boolean smart = smartAdjustment.get();
            int view = mc.options.getViewDistance().getValue();
            ChunkPos here = mc.player.getChunkPos();
            int sens = Math.max(1, sensitivity.get());

            for (Map.Entry<ChunkPos, Integer> entry : chunks.entrySet()) {
                ChunkPos cp = entry.getKey();

                int a = baseAlpha;
                if (smart) {
                    if (Math.max(Math.abs(cp.x - here.x), Math.abs(cp.z - here.z)) > view) continue;

                    // Hotter chunks draw stronger: 40% opacity at the threshold up to 100% at 4x the threshold
                    double strength = Math.min(1.0, 0.4 + 0.6 * (entry.getValue() / (double) (sens * 4)));
                    a = Math.max(10, (int) (baseAlpha * strength));
                }

                Color side = new Color(fill.r, fill.g, fill.b, a);

                event.renderer.box(
                    cp.getStartX() + inset, y, cp.getStartZ() + inset,
                    cp.getStartX() + 16 - inset, y + thickness, cp.getStartZ() + 16 - inset,
                    side, line, mode, 0
                );
            }
        }

        if (rotatedDeepslate.get()) {
            List<BlockPos> blocks = deepslateBlocks;
            if (blocks.isEmpty()) return;

            SettingColor base = deepslateColor.get();
            Color color = new Color(base.r, base.g, base.b, alpha.get());
            double maxDistSq = (double) deepslateDistance.get() * deepslateDistance.get();
            double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
            int drawn = 0;

            for (BlockPos pos : blocks) {
                double dx = pos.getX() + 0.5 - px, dy = pos.getY() + 0.5 - py, dz = pos.getZ() + 0.5 - pz;
                if (dx * dx + dy * dy + dz * dz > maxDistSq) continue;

                event.renderer.box(pos, color, color, ShapeMode.Lines, 0);

                if (++drawn >= 500) break; // keeps weak devices smooth
            }
        }
    }
}

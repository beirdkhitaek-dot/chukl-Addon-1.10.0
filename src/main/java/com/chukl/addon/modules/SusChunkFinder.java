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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Finds probable base locations from plant/amethyst growth states and buried rotated deepslate.
 *
 * Plants only finish growing while their chunk is loaded and ticking, so chunks full of fully grown
 * plants that no one is currently near often mean a player spent a long time there.
 *
 * Performance: chunks are scanned on a low priority background thread, a few per second, nearest
 * first, using palette checks to skip sections that contain nothing interesting.
 */
public class SusChunkFinder extends Module {
    private record ChunkResult(int notGrown, boolean hasGrown, List<BlockPos> deepslate) {}

    private record Params(int simDist, int sensitivity, int radius, int perScan, long rescanMs,
                          boolean kelp, boolean caveVines, boolean vines, boolean amethyst, boolean folia,
                          boolean bamboo, boolean cocoa, boolean beeNest, boolean deepslate, boolean smart) {}

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgIndicators = sgGeneral; // same list as the original: distance, sensitivity, smart adjustment, alpha, then indicators
    private final SettingGroup sgPerf = settings.createGroup("Performance");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // --- General ---

    private final Setting<Integer> simulationDistance = sgGeneral.add(new IntSetting.Builder()
        .name("simulation-distance")
        .description("Chunk radius around growing plants that is considered 'currently loaded' and ignored.")
        .defaultValue(4)
        .range(2, 16)
        .sliderRange(2, 16)
        .build()
    );

    private final Setting<Integer> sensitivity = sgGeneral.add(new IntSetting.Builder()
        .name("sensitivity")
        .description("Minimum number of fully grown things needed to mark a chunk as sus.")
        .defaultValue(5)
        .range(1, 20)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Boolean> smartAdjustment = sgGeneral.add(new BoolSetting.Builder()
        .name("smart-adjustment")
        .description("Never uses a simulation distance larger than the area actually loaded around you. Approximation of the original option.")
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

    // --- Indicators ---

    private final Setting<Boolean> kelp = sgIndicators.add(new BoolSetting.Builder()
        .name("kelp").description("Use kelp growth.").defaultValue(false).build());

    private final Setting<Boolean> caveVines = sgIndicators.add(new BoolSetting.Builder()
        .name("cave-vines").description("Use cave vine growth.").defaultValue(false).build());

    private final Setting<Boolean> vines = sgIndicators.add(new BoolSetting.Builder()
        .name("vines").description("Use vine growth.").defaultValue(false).build());

    private final Setting<Boolean> amethyst = sgIndicators.add(new BoolSetting.Builder()
        .name("amethyst").description("Use amethyst bud and cluster growth.").defaultValue(true).build());

    private final Setting<Boolean> bamboo = sgIndicators.add(new BoolSetting.Builder()
        .name("bamboo").description("Use bamboo growth.").defaultValue(false).build());

    private final Setting<Boolean> cocoa = sgIndicators.add(new BoolSetting.Builder()
        .name("cocoa").description("Use cocoa pod growth.").defaultValue(false).build());

    private final Setting<Boolean> beeNest = sgIndicators.add(new BoolSetting.Builder()
        .name("bee-nest")
        .description("Bee nests with honey mean someone loaded the chunk.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> rotatedDeepslate = sgIndicators.add(new BoolSetting.Builder()
        .name("rotated-deepslate")
        .description("Detect rotated deepslate buried between Y 0 and 60.")
        .defaultValue(false)
        .build()
    );

    // --- Performance ---

    private final Setting<Integer> scanRadius = sgPerf.add(new IntSetting.Builder()
        .name("scan-radius")
        .description("Only scan chunks within this many chunks of you.")
        .defaultValue(10)
        .range(2, 32)
        .sliderRange(2, 24)
        .build()
    );

    private final Setting<Integer> chunksPerScan = sgPerf.add(new IntSetting.Builder()
        .name("chunks-per-scan")
        .description("Chunks scanned per second (nearest first). Lower = lighter on weak devices.")
        .defaultValue(4)
        .range(1, 30)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Integer> rescanSeconds = sgPerf.add(new IntSetting.Builder()
        .name("rescan-seconds")
        .description("Seconds before an already scanned chunk is scanned again.")
        .defaultValue(120)
        .range(10, 900)
        .sliderRange(10, 600)
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

    private final Map<ChunkPos, ChunkResult> results = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Long> lastScan = new ConcurrentHashMap<>();
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private volatile Set<ChunkPos> susChunks = Set.of();
    private volatile List<BlockPos> deepslateBlocks = List.of();
    private ExecutorService executor;
    private int timer;

    public SusChunkFinder() {
        super(ChuklAddon.CATEGORY, "sus-chunk-finder", "Finds probable base locations using plant/amethyst growth and rotated deepslate.");
    }

    @Override
    public void onActivate() {
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "SusChunkFinder-Scan");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        clearData();
        timer = 0;
    }

    @Override
    public void onDeactivate() {
        if (executor != null) executor.shutdownNow();
        executor = null;
        clearData();
    }

    private void clearData() {
        results.clear();
        lastScan.clear();
        susChunks = Set.of();
        deepslateBlocks = List.of();
        scanning.set(false);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null || executor == null) return;
        if (--timer > 0) return;
        timer = 20; // once per second

        if (!scanning.compareAndSet(false, true)) return;

        ClientWorld world = mc.world;
        ChunkPos center = mc.player.getChunkPos();

        Params params = new Params(
            simulationDistance.get(), sensitivity.get(), scanRadius.get(), chunksPerScan.get(),
            rescanSeconds.get() * 1000L,
            kelp.get(), caveVines.get(), vines.get(), amethyst.get(), amethyst.get() && isDonutFolia(),
            bamboo.get(), cocoa.get(), beeNest.get(), rotatedDeepslate.get(), smartAdjustment.get()
        );

        executor.execute(() -> {
            try {
                runScan(world, center, params);
            } catch (Throwable t) {
                ChuklAddon.LOG.error("Sus Chunk Finder scan failed", t);
            } finally {
                scanning.set(false);
            }
        });
    }

    private boolean isDonutFolia() {
        if (mc.getNetworkHandler() == null) return false;
        String brand = mc.getNetworkHandler().getBrand();
        return brand != null && brand.contains("DonutFolia");
    }

    /** Background thread: scan a few new chunks, drop unloaded ones, rebuild the result sets. */
    private void runScan(ClientWorld world, ChunkPos center, Params p) {
        long now = System.currentTimeMillis();

        // 1) Chunks that need (re)scanning, nearest first
        List<ChunkPos> candidates = new ArrayList<>();
        int maxLoaded = 0;
        for (int cx = center.x - p.radius(); cx <= center.x + p.radius(); cx++) {
            for (int cz = center.z - p.radius(); cz <= center.z + p.radius(); cz++) {
                if (!world.getChunkManager().isChunkLoaded(cx, cz)) continue;

                maxLoaded = Math.max(maxLoaded, Math.max(Math.abs(cx - center.x), Math.abs(cz - center.z)));

                ChunkPos cp = new ChunkPos(cx, cz);
                Long last = lastScan.get(cp);
                if (last == null || now - last > p.rescanMs()) candidates.add(cp);
            }
        }
        candidates.sort((a, b) -> Integer.compare(distSq(a, center), distSq(b, center)));

        int budget = p.perScan();
        for (ChunkPos cp : candidates) {
            if (budget-- <= 0) break;

            WorldChunk chunk = world.getChunkManager().getWorldChunk(cp.x, cp.z);
            if (chunk == null) continue;

            results.put(cp, scanChunk(world, chunk, cp, p));
            lastScan.put(cp, now);
        }

        // 2) Forget chunks that are no longer loaded
        results.keySet().removeIf(cp -> !world.getChunkManager().isChunkLoaded(cp.x, cp.z));
        lastScan.keySet().removeIf(cp -> !results.containsKey(cp));

        // 3) Rebuild what gets drawn
        int simDist = p.smart() ? Math.max(2, Math.min(p.simDist(), maxLoaded)) : p.simDist();
        rebuild(simDist, p.sensitivity());
    }

    private static int distSq(ChunkPos a, ChunkPos b) {
        int dx = a.x - b.x, dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private void rebuild(int simDist, int sensitivity) {
        Map<ChunkPos, Integer> heat = new HashMap<>();
        Set<ChunkPos> tracked = new HashSet<>();
        List<BlockPos> deepslate = new ArrayList<>();

        for (Map.Entry<ChunkPos, ChunkResult> entry : results.entrySet()) {
            ChunkPos cp = entry.getKey();
            ChunkResult r = entry.getValue();

            if (r.notGrown() > 0 || r.hasGrown()) {
                for (int dx = -simDist; dx <= simDist; dx++) {
                    for (int dz = -simDist; dz <= simDist; dz++) {
                        ChunkPos n = new ChunkPos(cp.x + dx, cp.z + dz);
                        if (r.notGrown() > 0) heat.merge(n, r.notGrown(), Integer::sum);
                        if (r.hasGrown()) tracked.add(n);
                    }
                }
            }

            deepslate.addAll(r.deepslate());
        }

        Set<ChunkPos> sus = new HashSet<>();
        for (Map.Entry<ChunkPos, Integer> entry : heat.entrySet()) {
            ChunkPos cp = entry.getKey();
            if (entry.getValue() < sensitivity) continue;
            if (tracked.contains(cp) || !results.containsKey(cp)) continue;

            int neighbors = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    if (results.containsKey(new ChunkPos(cp.x + dx, cp.z + dz))) neighbors++;
                }
            }

            if (neighbors >= 3) sus.add(cp);
        }

        susChunks = sus;
        deepslateBlocks = deepslate;
    }

    private static boolean isBud(BlockState s) {
        Block b = s.getBlock();
        return b == Blocks.SMALL_AMETHYST_BUD || b == Blocks.MEDIUM_AMETHYST_BUD || b == Blocks.LARGE_AMETHYST_BUD;
    }

    private static boolean isCluster(BlockState s) {
        return s.getBlock() == Blocks.AMETHYST_CLUSTER;
    }

    private ChunkResult scanChunk(ClientWorld world, WorldChunk chunk, ChunkPos cp, Params p) {
        ChunkSection[] sections = chunk.getSectionArray();
        int bottomSection = chunk.getBottomSectionCoord();
        int startX = cp.getStartX();
        int startZ = cp.getStartZ();

        // Palette check: sections without any of these blocks are skipped entirely.
        boolean useAmethystBlocks = p.amethyst() && !p.folia();
        Predicate<BlockState> growthFilter = s -> {
            Block b = s.getBlock();
            return (p.kelp() && b == Blocks.KELP)
                || (p.caveVines() && b == Blocks.CAVE_VINES)
                || (p.vines() && b == Blocks.VINE)
                || (useAmethystBlocks && (b == Blocks.AMETHYST_CLUSTER || isBud(s)))
                || (p.bamboo() && b == Blocks.BAMBOO)
                || (p.cocoa() && b == Blocks.COCOA)
                || (p.beeNest() && b == Blocks.BEE_NEST);
        };
        Predicate<BlockState> deepslateFilter = s -> s.getBlock() == Blocks.DEEPSLATE;

        int notGrown = 0;
        int grown = 0;
        boolean foundBuds = false;
        boolean foundClusters = false;
        List<BlockPos> deepslate = new ArrayList<>();
        BlockPos.Mutable other = new BlockPos.Mutable();

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;

            int sectionMinY = (bottomSection + i) << 4;

            if (p.folia()) {
                if (!foundBuds && section.hasAny(SusChunkFinder::isBud)) foundBuds = true;
                if (!foundClusters && section.hasAny(SusChunkFinder::isCluster)) foundClusters = true;
            }

            boolean scanGrowth = section.hasAny(growthFilter);
            boolean scanDeepslate = p.deepslate()
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
                            if (p.kelp() && block == Blocks.KELP && state.contains(Properties.AGE_25)) {
                                boolean waterAbove = world.getBlockState(other.set(bx, by + 1, bz)).isOf(Blocks.WATER);
                                if (state.get(Properties.AGE_25) != 25 && waterAbove) grown++;
                                else notGrown++;

                            } else if (p.caveVines() && block == Blocks.CAVE_VINES && state.contains(Properties.AGE_25)) {
                                boolean airBelow = world.getBlockState(other.set(bx, by - 1, bz)).isAir();
                                if (state.get(Properties.AGE_25) != 25 && airBelow) grown++;
                                else notGrown++;

                            } else if (p.vines() && block == Blocks.VINE) {
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

                            } else if (p.bamboo() && block == Blocks.BAMBOO && state.contains(Properties.STAGE)) {
                                BlockState above = world.getBlockState(other.set(bx, by + 1, bz));
                                if (!above.isOf(Blocks.BAMBOO)) {
                                    if (state.get(Properties.STAGE) == 1) notGrown++;
                                    else if (above.isAir()) grown++;
                                }

                            } else if (p.cocoa() && block == Blocks.COCOA && state.contains(Properties.AGE_2)) {
                                if (state.get(Properties.AGE_2) == 2) notGrown++;
                                else grown++;

                            } else if (p.beeNest() && block == Blocks.BEE_NEST && state.contains(Properties.HONEY_LEVEL)) {
                                if (state.get(Properties.HONEY_LEVEL) == 5) notGrown++;
                                else grown++;
                            }
                        }

                        if (scanDeepslate && block == Blocks.DEEPSLATE && state.contains(Properties.AXIS)
                            && state.get(Properties.AXIS) != Direction.Axis.Y && by >= 0 && by <= 60) {

                            BlockPos pos = new BlockPos(bx, by, bz);
                            boolean buried = true;
                            for (Direction dir : Direction.values()) {
                                if (world.getBlockState(other.set(bx, by, bz).offset(dir)).isAir()) {
                                    buried = false;
                                    break;
                                }
                            }
                            if (buried) deepslate.add(pos);
                        }
                    }
                }
            }
        }

        if (p.folia()) {
            if (foundBuds) grown++;
            else if (foundClusters) notGrown++;
        }

        return new ChunkResult(notGrown, grown > 0, deepslate);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;

        Set<ChunkPos> chunks = susChunks;
        if (!chunks.isEmpty()) {
            double y = chunkY.get();
            double thickness = chunkThickness.get();
            SettingColor fill = chunkSideColor.get();
            Color side = new Color(fill.r, fill.g, fill.b, alpha.get());
            SettingColor line = chunkLineColor.get();
            ShapeMode mode = chunkShapeMode.get();

            for (ChunkPos cp : chunks) {
                event.renderer.box(
                    cp.getStartX(), y, cp.getStartZ(),
                    cp.getStartX() + 16, y + thickness, cp.getStartZ() + 16,
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

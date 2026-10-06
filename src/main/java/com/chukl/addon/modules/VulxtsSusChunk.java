package com.chukl.addon.modules;

import com.chukl.addon.ChuklAddon;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

/**
 * Vulxts' SusChunkFinder, ported to Meteor.
 *
 * Every chunk is scored once from "unnatural growth" signals (amethyst/geode, very tall kelp, max-height
 * bamboo, ripe sweet berries, long vines, long dripstone). Chunks above the sensitivity threshold are
 * flagged, and with Smart Mode nearby flagged chunks are merged into one zone with a center marker.
 *
 * Performance: scanning runs on one background thread (you only queue a few chunks per tick), sections
 * without any enabled block are skipped using the section palette, and scores for chunks that are far away
 * are dropped.
 */
public class VulxtsSusChunk extends Module {
    private static final int[] THRESHOLDS = {25, 32, 39, 46, 54, 61, 68, 75, 82, 89, 96, 104, 111, 118, 125};
    private static final int MAX_COLUMN = 32;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> sensitivity = sgGeneral.add(new IntSetting.Builder()
        .name("sensitivity").description("Higher = stricter.")
        .defaultValue(3).range(1, 15).sliderRange(1, 15).build());

    private final Setting<Boolean> amethyst = sgGeneral.add(new BoolSetting.Builder()
        .name("amethyst").description("Score amethyst/geode evidence.")
        .defaultValue(true).build());

    private final Setting<Boolean> kelp = sgGeneral.add(new BoolSetting.Builder()
        .name("kelp").description("Score unusually tall kelp.")
        .defaultValue(true).build());

    private final Setting<Boolean> bamboo = sgGeneral.add(new BoolSetting.Builder()
        .name("bamboo").description("Score max-height bamboo.")
        .defaultValue(true).build());

    private final Setting<Boolean> berries = sgGeneral.add(new BoolSetting.Builder()
        .name("berries").description("Score max-growth sweet berries.")
        .defaultValue(true).build());

    private final Setting<Boolean> vines = sgGeneral.add(new BoolSetting.Builder()
        .name("vines").description("Score long vines.")
        .defaultValue(true).build());

    private final Setting<Boolean> dripstone = sgGeneral.add(new BoolSetting.Builder()
        .name("dripstone").description("Score long pointed dripstone.")
        .defaultValue(true).build());

    private final Setting<Integer> scanSpeed = sgGeneral.add(new IntSetting.Builder()
        .name("scan-speed").description("Chunks queued for scanning per update. Lower = lighter on weak devices.")
        .defaultValue(6).range(1, 24).sliderRange(1, 24).build());

    private final Setting<Boolean> smartMode = sgGeneral.add(new BoolSetting.Builder()
        .name("smart-mode").description("Merge nearby suspicious chunks into one zone.")
        .defaultValue(true).build());

    private final Setting<Integer> mergeRadius = sgGeneral.add(new IntSetting.Builder()
        .name("merge-radius").description("Chunk distance used for merging.")
        .defaultValue(3).range(1, 8).sliderRange(1, 8).visible(smartMode::get).build());

    private final Setting<Boolean> centroidMarker = sgGeneral.add(new BoolSetting.Builder()
        .name("centroid-marker").description("Draw a marker at zone centers.")
        .defaultValue(true).visible(smartMode::get).build());

    private final Setting<Integer> renderY = sgRender.add(new IntSetting.Builder()
        .name("render-y").description("Y level of the flat chunk overlay.")
        .defaultValue(100).range(-64, 320).sliderRange(-64, 320).build());

    private final Setting<SettingColor> fillColor = sgRender.add(new ColorSetting.Builder()
        .name("fill-color").description("Overlay color (opacity comes from Fill Opacity).")
        .defaultValue(new SettingColor(180, 70, 230, 90)).build());

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("outline-color").description("Outline color (opacity comes from Outline Opacity).")
        .defaultValue(new SettingColor(255, 120, 255, 200)).build());

    private final Setting<Integer> fillOpacity = sgRender.add(new IntSetting.Builder()
        .name("fill-opacity").description("Overlay alpha.")
        .defaultValue(90).range(0, 255).sliderRange(0, 255).build());

    private final Setting<Integer> outlineOpacity = sgRender.add(new IntSetting.Builder()
        .name("outline-opacity").description("Outline alpha.")
        .defaultValue(200).range(0, 255).sliderRange(0, 255).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("How the overlay is drawn.")
        .defaultValue(ShapeMode.Both).build());

    /** Finished scores. Only the scan thread writes, the main thread reads. */
    private final Map<Long, Score> scores = new ConcurrentHashMap<>();
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();

    // Main thread only
    private volatile List<Long> flagged = List.of();
    private volatile List<Zone> zones = List.of();
    private int[][] offsets = new int[0][];
    private int offsetRadius = -1;
    private ClientWorld lastWorld;
    private ExecutorService executor;
    private int timer;

    public VulxtsSusChunk() {
        super(ChuklAddon.CATEGORY, "vulxts-suschunk", "Vulxts' Sus Chunk Finder (weighted growth signals + zone merging).");
    }

    @Override
    public void onActivate() {
        stopExecutor();
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "VulxtsSusChunk-Scan");
            t.setDaemon(true);
            return t;
        });
        clear();
    }

    @Override
    public void onDeactivate() {
        stopExecutor();
        clear();
    }

    private void stopExecutor() {
        if (executor != null) executor.shutdownNow();
        executor = null;
    }

    private void clear() {
        scores.clear();
        pending.clear();
        flagged = List.of();
        zones = List.of();
        lastWorld = mc.world;
        timer = 0;
    }

    // ---------- main thread ----------

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null || executor == null) return;

        // New world / dimension: start over
        if (mc.world != lastWorld) {
            clear();
            return;
        }

        if (++timer % 4 != 0) return;

        ChunkPos center = mc.player.getChunkPos();
        int radius = Math.min(mc.options.getViewDistance().getValue() + 1, 16);
        ensureOffsets(radius);

        queueChunks(center);

        if (timer % 20 == 0) rebuild(center, radius);
    }

    /** Chunk offsets within the radius, nearest first, so close chunks are scanned before far ones. */
    private void ensureOffsets(int radius) {
        if (radius == offsetRadius) return;
        offsetRadius = radius;

        List<int[]> list = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) list.add(new int[]{dx, dz});
        }
        list.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        offsets = list.toArray(new int[0][]);
    }

    private void queueChunks(ChunkPos center) {
        ClientWorld world = mc.world;
        Opts opts = new Opts(amethyst.get(), kelp.get(), bamboo.get(), berries.get(), vines.get(), dripstone.get());
        if (!opts.any()) return;

        int budget = scanSpeed.get();
        for (int[] o : offsets) {
            if (budget <= 0) break;

            int cx = center.x + o[0], cz = center.z + o[1];
            long key = ChunkPos.toLong(cx, cz);
            if (scores.containsKey(key) || pending.contains(key)) continue;

            WorldChunk chunk = world.getChunkManager().getWorldChunk(cx, cz, false);
            if (chunk == null || chunk.isEmpty()) continue;

            pending.add(key);
            budget--;

            try {
                executor.execute(() -> {
                    try {
                        scores.put(key, scanChunk(world, chunk, opts));
                    } catch (Throwable t) {
                        ChuklAddon.LOG.error("Vulxts Sus Chunk scan failed", t);
                        scores.put(key, new Score()); // don't retry a chunk that keeps failing
                    } finally {
                        pending.remove(key);
                    }
                });
            } catch (RuntimeException e) {
                pending.remove(key); // executor shut down
                return;
            }
        }
    }

    private void rebuild(ChunkPos center, int radius) {
        // Forget scores for chunks that are far away (frees memory and lets them rescan on return)
        int keep = radius + 3;
        scores.keySet().removeIf(k ->
            Math.abs(ChunkPos.getPackedX(k) - center.x) > keep || Math.abs(ChunkPos.getPackedZ(k) - center.z) > keep);

        int threshold = THRESHOLDS[Math.max(1, Math.min(15, sensitivity.get())) - 1];

        List<Map.Entry<Long, Score>> candidates = new ArrayList<>();
        for (Map.Entry<Long, Score> e : scores.entrySet()) {
            if (e.getValue().score >= threshold) candidates.add(e);
        }
        candidates.sort((a, b) -> Double.compare(b.getValue().score, a.getValue().score));

        int max = Math.max(8, 64 - (threshold / 2));
        List<Long> newFlagged = new ArrayList<>();
        for (int i = 0; i < candidates.size() && i < max; i++) newFlagged.add(candidates.get(i).getKey());
        flagged = newFlagged;

        List<Zone> newZones = new ArrayList<>();
        if (!smartMode.get()) {
            for (long k : newFlagged) newZones.add(Zone.single(k, scores.get(k)));
            zones = newZones;
            return;
        }

        Set<Long> flaggedSet = new HashSet<>(newFlagged);
        Set<Long> seen = new HashSet<>();
        int merge = mergeRadius.get();

        for (long start : newFlagged) {
            if (!seen.add(start)) continue;

            Set<Long> members = new LinkedHashSet<>();
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(start);

            while (!queue.isEmpty()) {
                long k = queue.poll();
                members.add(k);
                int cx = ChunkPos.getPackedX(k), cz = ChunkPos.getPackedZ(k);
                for (int dx = -merge; dx <= merge; dx++) {
                    for (int dz = -merge; dz <= merge; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        long n = ChunkPos.toLong(cx + dx, cz + dz);
                        if (flaggedSet.contains(n) && seen.add(n)) queue.add(n);
                    }
                }
            }
            newZones.add(Zone.from(members, scores));
        }

        newZones.sort((a, b) -> Double.compare(b.maxScore, a.maxScore));
        zones = newZones;
    }

    // ---------- background thread ----------

    private Score scanChunk(ClientWorld world, WorldChunk chunk, Opts o) {
        Score score = new Score();
        ChunkSection[] sections = chunk.getSectionArray();
        int bottomSection = chunk.getBottomSectionCoord();

        // Palette check: sections that contain none of the enabled blocks are skipped completely
        Predicate<BlockState> filter = s -> {
            Block b = s.getBlock();
            return (o.amethyst && isAmethystSignal(b))
                || (o.kelp && (b == Blocks.KELP || b == Blocks.KELP_PLANT))
                || (o.bamboo && b == Blocks.BAMBOO)
                || (o.berries && b == Blocks.SWEET_BERRY_BUSH)
                || (o.vines && b == Blocks.VINE)
                || (o.dripstone && b == Blocks.POINTED_DRIPSTONE);
        };

        BlockPos.Mutable pos = new BlockPos.Mutable();
        int startX = chunk.getPos().getStartX();
        int startZ = chunk.getPos().getStartZ();

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty() || !section.hasAny(filter)) continue;

            int baseY = (bottomSection + i) << 4;

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!filter.test(state)) continue;

                        Block block = state.getBlock();
                        pos.set(startX + x, baseY + y, startZ + z);

                        if (isAmethystSignal(block)) {
                            score.add(Signal.AMETHYST);

                        } else if (block == Blocks.KELP || block == Blocks.KELP_PLANT) {
                            // Only judge a column from its top block
                            if (isKelp(world.getBlockState(pos.up()).getBlock())) continue;
                            int height = columnLength(world, pos, Direction.DOWN, Blocks.KELP, Blocks.KELP_PLANT);
                            boolean maxAge = block == Blocks.KELP && state.contains(Properties.AGE_25)
                                && state.get(Properties.AGE_25) == 25;
                            if ((maxAge && height >= 8) || height >= 14) score.add(Signal.KELP);

                        } else if (block == Blocks.BAMBOO) {
                            // Only judge a column from its bottom block
                            if (world.getBlockState(pos.down()).isOf(Blocks.BAMBOO)) continue;
                            if (columnLength(world, pos, Direction.UP, Blocks.BAMBOO) >= 12) score.add(Signal.BAMBOO);

                        } else if (block == Blocks.SWEET_BERRY_BUSH) {
                            if (state.contains(Properties.AGE_3) && state.get(Properties.AGE_3) == 3) score.add(Signal.BERRIES);

                        } else if (block == Blocks.VINE) {
                            // Only judge a vine from its top block
                            if (world.getBlockState(pos.up()).isOf(Blocks.VINE)) continue;
                            if (columnLength(world, pos, Direction.DOWN, Blocks.VINE) >= 7) score.add(Signal.VINES);

                        } else if (block == Blocks.POINTED_DRIPSTONE) {
                            // Only judge a stalactite from its top block (needs a dripstone block below it)
                            if (world.getBlockState(pos.up()).isOf(Blocks.POINTED_DRIPSTONE)
                                || !world.getBlockState(pos.down()).isOf(Blocks.POINTED_DRIPSTONE)) continue;
                            if (columnLength(world, pos, Direction.DOWN, Blocks.POINTED_DRIPSTONE) >= 5) score.add(Signal.DRIPSTONE);
                        }
                    }
                }
            }
        }

        score.compute();
        return score;
    }

    private static boolean isAmethystSignal(Block b) {
        return b == Blocks.AMETHYST_CLUSTER || b == Blocks.LARGE_AMETHYST_BUD
            || b == Blocks.MEDIUM_AMETHYST_BUD || b == Blocks.SMALL_AMETHYST_BUD
            || b == Blocks.BUDDING_AMETHYST || b == Blocks.AMETHYST_BLOCK;
    }

    private static boolean isKelp(Block b) {
        return b == Blocks.KELP || b == Blocks.KELP_PLANT;
    }

    /** Counts blocks of the given types in a straight line starting at (and including) start. */
    private static int columnLength(ClientWorld world, BlockPos start, Direction dir, Block... types) {
        BlockPos.Mutable p = new BlockPos.Mutable().set(start);
        int n = 0;
        while (n < MAX_COLUMN) {
            Block b = world.getBlockState(p).getBlock();
            boolean match = false;
            for (Block t : types) {
                if (b == t) {
                    match = true;
                    break;
                }
            }
            if (!match) break;
            n++;
            p.move(dir);
        }
        return n;
    }

    // ---------- render ----------

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;

        SettingColor f = fillColor.get(), l = lineColor.get();
        Color fill = new Color(f.r, f.g, f.b, fillOpacity.get());
        Color line = new Color(l.r, l.g, l.b, outlineOpacity.get());
        ShapeMode mode = shapeMode.get();
        double y = renderY.get();

        if (smartMode.get()) {
            boolean marker = centroidMarker.get();
            for (Zone z : zones) {
                double half = Math.min(24.0, 8.0 + Math.max(0, z.members.size() - 1) * 4.0);
                double x = z.centerX - half, zz = z.centerZ - half;
                event.renderer.box(x, y, zz, x + half * 2, y + 0.1, zz + half * 2, fill, line, mode, 0);

                if (marker) {
                    double r = 2.0;
                    event.renderer.box(z.centerX - r, y + 0.1, z.centerZ - r,
                        z.centerX + r, y + 0.25, z.centerZ + r, line, line, ShapeMode.Lines, 0);
                }
            }
        } else {
            for (long k : flagged) {
                double x = ChunkPos.getPackedX(k) * 16.0, z = ChunkPos.getPackedZ(k) * 16.0;
                event.renderer.box(x, y, z, x + 16, y + 0.1, z + 16, fill, line, mode, 0);
            }
        }
    }

    // ---------- data ----------

    private record Opts(boolean amethyst, boolean kelp, boolean bamboo, boolean berries, boolean vines, boolean dripstone) {
        boolean any() {
            return amethyst || kelp || bamboo || berries || vines || dripstone;
        }
    }

    private enum Signal {
        AMETHYST(2.0, 64), KELP(2.0, 6), BAMBOO(2.0, 6), BERRIES(2.0, 5), VINES(2.0, 6), DRIPSTONE(2.0, 5);

        final double weight;
        final int cap;

        Signal(double weight, int cap) {
            this.weight = weight;
            this.cap = cap;
        }
    }

    private static final class Score {
        final EnumMap<Signal, Integer> hits = new EnumMap<>(Signal.class);
        double score;

        void add(Signal s) {
            hits.merge(s, 1, Integer::sum);
        }

        void compute() {
            score = 0;
            for (Map.Entry<Signal, Integer> e : hits.entrySet()) {
                score += e.getKey().weight * Math.min(e.getValue(), e.getKey().cap);
            }
        }
    }

    private static final class Zone {
        final Set<Long> members;
        final double centerX, centerZ, maxScore;

        Zone(Set<Long> members, double centerX, double centerZ, double maxScore) {
            this.members = members;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.maxScore = maxScore;
        }

        static Zone single(long k, Score s) {
            return new Zone(Set.of(k), ChunkPos.getPackedX(k) * 16 + 8, ChunkPos.getPackedZ(k) * 16 + 8, s == null ? 0 : s.score);
        }

        static Zone from(Set<Long> members, Map<Long, Score> scores) {
            double wx = 0, wz = 0, total = 0, max = 0;
            for (long k : members) {
                Score s = scores.get(k);
                if (s == null) continue;
                double w = Math.max(1, s.score);
                total += w;
                max = Math.max(max, s.score);
                wx += (ChunkPos.getPackedX(k) * 16 + 8) * w;
                wz += (ChunkPos.getPackedZ(k) * 16 + 8) * w;
            }
            return new Zone(Set.copyOf(members), total == 0 ? 0 : wx / total, total == 0 ? 0 : wz / total, max);
        }
    }
}

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
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Water Client's ChunkFinder / SusChunk logic, ported to Meteor.
 *
 * - scans a configurable radius around you on a background thread
 * - counts amethyst per chunk and keeps only the strongest suspicious chunks
 * - widens each hit into Water's irregular chunk "forms"
 * - flags chunks with lots of underground chests and flashes them
 *
 * Scanning is throttled (once per second) and sections without amethyst or chests are skipped using the
 * section palette, so it stays light on weak devices.
 */
public class WaterSusChunk extends Module {
    private static final Predicate<BlockState> IS_AMETHYST =
        s -> s.isOf(Blocks.AMETHYST_CLUSTER) || s.isOf(Blocks.AMETHYST_BLOCK);
    private static final Predicate<BlockState> IS_CHEST =
        s -> s.isOf(Blocks.CHEST) || s.isOf(Blocks.TRAPPED_CHEST);

    private static final double RENDER_Y = 47.0;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Integer> scanRadius = sgGeneral.add(new IntSetting.Builder()
        .name("scan-radius")
        .description("Scan radius multiplier (radius in chunks = this x 5).")
        .defaultValue(1).range(1, 5).sliderRange(1, 5).build());

    private final Setting<Integer> clusterThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("sim-chunks")
        .description("Minimum amethyst blocks for a suspicious chunk.")
        .defaultValue(10).range(1, 10).sliderRange(1, 10).build());

    private final Setting<Integer> chestThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("chest-threshold")
        .description("Underground chest count needed to flag a base chunk.")
        .defaultValue(10).range(1, 64).sliderRange(1, 32).build());

    private final Setting<SettingColor> fillColor = sgRender.add(new ColorSetting.Builder()
        .name("fill-color")
        .description("Chunk highlight color.")
        .defaultValue(new SettingColor(180, 60, 60, 40)).build());

    private final Setting<Integer> fillAlpha = sgRender.add(new IntSetting.Builder()
        .name("fill-alpha")
        .description("Chunk highlight opacity.")
        .defaultValue(40).range(0, 255).sliderRange(0, 255).build());

    private final Setting<Boolean> outline = sgRender.add(new BoolSetting.Builder()
        .name("outline")
        .description("Draw chunk outlines.")
        .defaultValue(true).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How chunks are drawn.")
        .defaultValue(ShapeMode.Sides).build());

    private volatile Set<ChunkPos> renderCache = Collections.emptySet();
    private volatile Set<ChunkPos> baseHits = Collections.emptySet();
    private long flashAnchor;
    private ExecutorService executor;
    private final AtomicBoolean scanning = new AtomicBoolean();
    private int tickCount;

    public WaterSusChunk() {
        super(ChuklAddon.CATEGORY, "water-suschunk", "Water Client's Sus Chunk Finder (amethyst + underground chests).");
    }

    @Override
    public void onActivate() {
        reset();
        executor = newExecutor();
    }

    @Override
    public void onDeactivate() {
        if (executor != null) executor.shutdownNow();
        executor = null;
        reset();
    }

    private static ExecutorService newExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "WaterSusChunk-Scan");
            t.setDaemon(true);
            return t;
        });
    }

    private void reset() {
        renderCache = Collections.emptySet();
        baseHits = Collections.emptySet();
        scanning.set(false);
        tickCount = 0;
        flashAnchor = 0;
    }

    // ---------- main thread: gather chunks, hand them to the worker ----------

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null || executor == null) return;
        if (++tickCount % 20 != 0 || !scanning.compareAndSet(false, true)) return;

        ChunkPos center = mc.player.getChunkPos();
        int r = scanRadius.get() * 5;
        int threshold = clusterThreshold.get();
        int chestsNeeded = chestThreshold.get();
        boolean underground = mc.player.getY() <= -2;

        List<ChunkPos> positions = new ArrayList<>();
        List<WorldChunk> chunks = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(center.x + dx, center.z + dz, false);
                if (chunk != null && !chunk.isEmpty()) {
                    positions.add(new ChunkPos(center.x + dx, center.z + dz));
                    chunks.add(chunk);
                }
            }
        }

        try {
            executor.execute(() -> scan(positions, chunks, center, threshold, chestsNeeded, underground));
        } catch (RuntimeException e) {
            scanning.set(false); // executor was shut down
        }
    }

    // ---------- background thread ----------

    private void scan(List<ChunkPos> positions, List<WorldChunk> chunks, ChunkPos center,
                      int threshold, int chestsNeeded, boolean underground) {
        try {
            Map<ChunkPos, Integer> counts = new HashMap<>();
            Set<ChunkPos> chests = new HashSet<>();

            for (int i = 0; i < chunks.size(); i++) {
                WorldChunk c = chunks.get(i);
                int amethyst = countAmethyst(c);
                if (amethyst >= threshold) counts.put(positions.get(i), amethyst);
                if (hasChestsBelowZero(c, chestsNeeded)) chests.add(positions.get(i));
            }

            Set<ChunkPos> suspicious = limitSuspicious(counts, center, threshold);
            Set<ChunkPos> shape = buildShapeForms(suspicious, threshold);

            // Water's underground behavior: below ground only show chunks that also have chests.
            if (underground) shape.removeIf(p -> !chests.contains(p));

            renderCache = Collections.unmodifiableSet(shape);
            baseHits = Collections.unmodifiableSet(chests);
        } catch (Throwable t) {
            ChuklAddon.LOG.error("Water Sus Chunk scan failed", t);
        } finally {
            scanning.set(false);
        }
    }

    private static Set<ChunkPos> limitSuspicious(Map<ChunkPos, Integer> counts, ChunkPos center, int threshold) {
        List<Map.Entry<ChunkPos, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> {
            int hit = Integer.compare(b.getValue(), a.getValue());
            if (hit != 0) return hit;
            return Integer.compare(distanceSq(center, a.getKey()), distanceSq(center, b.getKey()));
        });

        int max = Math.max(3, 33 - threshold * 3);
        Set<ChunkPos> result = new LinkedHashSet<>();
        for (Map.Entry<ChunkPos, Integer> e : sorted) {
            if (result.size() >= max) break;
            result.add(e.getKey());
        }
        return result;
    }

    private static int distanceSq(ChunkPos a, ChunkPos b) {
        int dx = a.x - b.x, dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    /** Water's irregular cluster expansion: each hit becomes one of a few rectangular chunk forms. */
    private static Set<ChunkPos> buildShapeForms(Set<ChunkPos> source, int threshold) {
        if (threshold >= 10) return new HashSet<>(source);

        int[][] forms = {{1, 4}, {2, 4}, {1, 2}, {3, 1}, {5, 4}, {5, 6}};
        Set<ChunkPos> out = new HashSet<>();
        for (ChunkPos c : source) {
            Random rng = new Random(Math.abs(c.hashCode()));
            int[] s = forms[rng.nextInt(forms.length)];
            int w = s[0], h = s[1];
            if (rng.nextBoolean()) {
                int t = w;
                w = h;
                h = t;
            }
            int sx = -(w / 2), sz = -(h / 2);
            for (int dx = 0; dx < w; dx++) {
                for (int dz = 0; dz < h; dz++) {
                    out.add(new ChunkPos(c.x + sx + dx, c.z + sz + dz));
                }
            }
        }
        return out;
    }

    private static int countAmethyst(WorldChunk chunk) {
        int count = 0;
        ChunkSection[] sections = chunk.getSectionArray();
        int bottomY = chunk.getBottomY();

        for (int i = 0; i < sections.length; i++) {
            if (bottomY + i * 16 > 32) break;

            ChunkSection s = sections[i];
            if (s == null || s.isEmpty() || !s.hasAny(IS_AMETHYST)) continue;

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        if (IS_AMETHYST.test(s.getBlockState(x, y, z))) count++;
                    }
                }
            }
        }
        return count;
    }

    private static boolean hasChestsBelowZero(WorldChunk chunk, int needed) {
        int count = 0;
        ChunkSection[] sections = chunk.getSectionArray();
        int bottomY = chunk.getBottomY();

        for (int i = 0; i < sections.length; i++) {
            if (bottomY + i * 16 >= 0) break; // whole section is at or above Y0

            ChunkSection s = sections[i];
            if (s == null || s.isEmpty() || !s.hasAny(IS_CHEST)) continue;

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        if (IS_CHEST.test(s.getBlockState(x, y, z)) && ++count >= needed) return true;
                    }
                }
            }
        }
        return false;
    }

    // ---------- render ----------

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;

        SettingColor base = fillColor.get();
        Color side = new Color(base.r, base.g, base.b, fillAlpha.get());
        Color line = outline.get() ? new Color(base.r, base.g, base.b, 220) : side;
        ShapeMode mode = shapeMode.get();

        for (ChunkPos c : renderCache) {
            double x1 = c.getStartX();
            double z1 = c.getStartZ();
            event.renderer.box(x1, RENDER_Y, z1, x1 + 16, RENDER_Y + 0.1, z1 + 16, side, line, mode, 0);
        }

        Set<ChunkPos> bases = baseHits;
        if (bases.isEmpty()) {
            flashAnchor = 0;
            return;
        }

        long now = System.currentTimeMillis();
        if (flashAnchor == 0) flashAnchor = now;
        long phase = (now - flashAnchor) % 400;
        if (phase < 150) {
            int a = (int) ((1.0 - phase / 150.0) * 200.0);
            Color flash = new Color(255, 255, 255, a);
            for (ChunkPos c : bases) {
                double x1 = c.getStartX(), z1 = c.getStartZ();
                event.renderer.box(x1, RENDER_Y + 0.3, z1, x1 + 16, RENDER_Y + 0.4, z1 + 16,
                    flash, flash, ShapeMode.Sides, 0);
            }
        }
    }
}

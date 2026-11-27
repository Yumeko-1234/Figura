package org.figuramc.figura.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.Avatar;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;

import java.util.*;

/**
 * /figura perf: measures for a few seconds what other avatars send to the GPU (vertices, render types = draw batches under
 * Iris), the frame rate, and the CPU and GPU time of the world render (GL timestamp queries, read a few frames later).
 * In "nodraw" mode every avatar still runs its scripts and model walk, but no avatar vertex reaches the GPU, so the frame
 * rate difference to a normal run is what drawing the avatars costs. Render thread only.
 */
public final class PerfProbe {

    private PerfProbe() {}

    private static boolean active;
    private static boolean noDraw;
    private static long endNanos, startNanos;
    private static int frames;

    private static final class Stats {
        String name;
        long vertices, batches, draws;
    }
    private static final Map<UUID, Stats> AVATARS = new HashMap<>();
    private static final Set<RenderType> FRAME_TYPES = new HashSet<>();
    private static long distinctTypesSum;

    // world render CPU time and GPU timestamps
    private static long levelStartCpu, levelCpuSum;
    private static final int RING = 8;
    private static int[] queries;
    private static final boolean[] pending = new boolean[RING];
    private static int ringIndex;
    private static long gpuSum;
    private static int gpuSamples;
    private static boolean gpuBroken;

    public static void start(int seconds, boolean noDrawMode) {
        active = true;
        noDraw = noDrawMode;
        startNanos = System.nanoTime();
        endNanos = startNanos + seconds * 1_000_000_000L;
        frames = 0;
        AVATARS.clear();
        FRAME_TYPES.clear();
        distinctTypesSum = 0;
        levelCpuSum = 0;
        gpuSum = 0;
        gpuSamples = 0;
        Arrays.fill(pending, false);
    }

    public static boolean isActive() {
        return active;
    }

    /** True while avatar vertices must not be sent (nodraw run). */
    public static boolean skipDraw() {
        return active && noDraw;
    }

    /** One render type of one avatar pass handed to the buffer source. */
    public static void onRenderType(Avatar avatar, RenderType type, int vertices, int batches) {
        if (!active || avatar == null)
            return;
        Stats s = AVATARS.computeIfAbsent(avatar.owner, k -> new Stats());
        s.name = avatar.entityName != null && !avatar.entityName.isBlank() ? avatar.entityName : avatar.owner.toString().substring(0, 8);
        s.vertices += vertices;
        s.batches += batches;
        s.draws++;
        FRAME_TYPES.add(type);
    }

    public static void onLevelStart() {
        if (!active)
            return;
        levelStartCpu = System.nanoTime();
        if (gpuBroken)
            return;
        try {
            if (queries == null) {
                queries = new int[RING * 2];
                GL15C.glGenQueries(queries);
            }
            int slot = ringIndex;
            if (pending[slot]) {
                // result of RING frames ago
                int a = queries[slot * 2], b = queries[slot * 2 + 1];
                if (GL15C.glGetQueryObjecti(b, GL15C.GL_QUERY_RESULT_AVAILABLE) != 0) {
                    long t0 = GL33C.glGetQueryObjecti64(a, GL15C.GL_QUERY_RESULT);
                    long t1 = GL33C.glGetQueryObjecti64(b, GL15C.GL_QUERY_RESULT);
                    if (t1 > t0) {
                        gpuSum += t1 - t0;
                        gpuSamples++;
                    }
                }
                pending[slot] = false;
            }
            GL33C.glQueryCounter(queries[slot * 2], GL33C.GL_TIMESTAMP);
        } catch (Throwable t) {
            gpuBroken = true;
            FiguraMod.LOGGER.warn("perf: GPU timestamps unavailable", t);
        }
    }

    public static void onLevelEnd() {
        if (!active)
            return;
        levelCpuSum += System.nanoTime() - levelStartCpu;
        frames++;
        distinctTypesSum += FRAME_TYPES.size();
        FRAME_TYPES.clear();
        if (!gpuBroken && queries != null) {
            try {
                GL33C.glQueryCounter(queries[ringIndex * 2 + 1], GL33C.GL_TIMESTAMP);
                pending[ringIndex] = true;
                ringIndex = (ringIndex + 1) % RING;
            } catch (Throwable t) {
                gpuBroken = true;
            }
        }
        if (System.nanoTime() >= endNanos)
            finish();
    }

    private static void finish() {
        active = false;
        double seconds = (System.nanoTime() - startNanos) / 1e9;
        int f = Math.max(frames, 1);
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "[perf%s] %.1f FPS over %.1f s, world render CPU %.2f ms, GPU %s ms per frame",
                noDraw ? " nodraw" : "", frames / seconds, seconds, levelCpuSum / 1e6 / f,
                gpuSamples > 0 ? String.format(Locale.ROOT, "%.2f", gpuSum / 1e6 / gpuSamples) : "n/a"));

        long totalVerts = 0, totalDraws = 0;
        for (Stats s : AVATARS.values()) {
            totalVerts += s.vertices;
            totalDraws += s.draws;
        }
        lines.add(String.format(Locale.ROOT, "[perf] %d avatars: %.0f vertices, %.1f render types per frame (%.1f distinct across avatars)%s",
                AVATARS.size(), totalVerts / (double) f, totalDraws / (double) f, distinctTypesSum / (double) f,
                noDraw ? " - counted, not sent" : ""));
        AVATARS.values().stream().sorted((a, b) -> Long.compare(b.vertices, a.vertices)).forEach(s ->
                lines.add(String.format(Locale.ROOT, "[perf]   %s: %.0f vertices, %.1f render types per frame",
                        s.name, s.vertices / (double) f, s.draws / (double) f)));

        Minecraft mc = Minecraft.getInstance();
        for (String line : lines) {
            FiguraMod.LOGGER.info(line);
            if (mc.gui != null)
                mc.gui.getChat().addMessage(Component.literal(line));
        }
        AVATARS.clear();
    }
}

package org.figuramc.figura.benchmark;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.config.Configs;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;

/**
 * Frame rate benchmark, only active with -Dfigura.benchmark.label=NAME (dev runs, see forge/build.gradle -Pbenchmark).
 * <p>
 * Creates (or opens) a flat creative world, loads an avatar from a built avatar file (the Figura cache .nbt) on the player,
 * shows it in third person with a fixed camera, waits for the warmup, records every frame for the duration, writes the results
 * and a screenshot, then closes the game.
 * <p>
 * Properties (all -Dfigura.benchmark.*): label (required), avatar (path to the .nbt, none = no avatar), otherPlayer (default true:
 * the avatar is treated like another player's, so the render script limits and smoothing apply to it), warmup and duration
 * (seconds, default 20 and 60), rate and budget (Render Script Rate / Budget for this run), results (file to append to).
 */
public final class FiguraBenchmark {

    public static final String LABEL = System.getProperty("figura.benchmark.label");
    public static final boolean ENABLED = LABEL != null && !LABEL.isBlank();
    // the player's own avatar is limited like another player's
    public static final boolean HOST_AS_OTHER = ENABLED && !"false".equalsIgnoreCase(System.getProperty("figura.benchmark.otherPlayer"));

    private static final String WORLD = "figura_benchmark";
    private static final String AVATAR = System.getProperty("figura.benchmark.avatar", "");
    private static final double WARMUP = Double.parseDouble(System.getProperty("figura.benchmark.warmup", "20"));
    private static final double DURATION = Double.parseDouble(System.getProperty("figura.benchmark.duration", "60"));
    private static final String RESULTS = System.getProperty("figura.benchmark.results", "benchmark-results.txt");

    private enum State { MENU, LOADING, WARMUP, MEASURE, DONE }
    private static State state = State.MENU;
    private static long phaseStart;

    // frame times of the measured part, in nanoseconds
    private static long[] frames = new long[1 << 16];
    private static int frameCount;
    private static long lastFrame;
    // Lua time (every script run) during the measured part
    private static long luaNanos;
    public static boolean measuring;

    private FiguraBenchmark() {}

    // every client tick
    public static void tick() {
        if (!ENABLED)
            return;
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        switch (state) {
            case MENU -> {
                if (mc.level == null && mc.screen instanceof TitleScreen) {
                    state = State.LOADING;
                    openWorld(mc);
                }
            }
            case LOADING -> {
                if (mc.player != null && mc.level != null && mc.screen == null) {
                    setUp(mc);
                    state = State.WARMUP;
                    phaseStart = now;
                    log("warming up for " + WARMUP + " s");
                }
            }
            case WARMUP -> {
                keepCamera(mc);
                if (now - phaseStart >= WARMUP * 1e9) {
                    frameCount = 0;
                    lastFrame = 0;
                    luaNanos = 0;
                    luaRenderNanos = 0;
                    avatarDrawNanos = 0;
                    measuring = true;
                    state = State.MEASURE;
                    phaseStart = now;
                    log("measuring for " + DURATION + " s");
                }
            }
            case MEASURE -> {
                keepCamera(mc);
                if (now - phaseStart >= DURATION * 1e9) {
                    measuring = false;
                    double seconds = (now - phaseStart) / 1e9;
                    writeResults(mc, seconds);
                    state = State.DONE;
                    mc.stop();
                }
            }
            default -> {}
        }
    }

    // every level frame (Avatar.beginLevelFrame)
    public static void onFrame(long nanos) {
        if (!measuring)
            return;
        if (lastFrame != 0) {
            if (frameCount == frames.length)
                frames = Arrays.copyOf(frames, frames.length * 2);
            frames[frameCount++] = nanos - lastFrame;
        }
        lastFrame = nanos;
    }

    // every Lua run (Avatar.run), render: render scripts (render events and part functions), else ticks and other events
    public static void onLua(long nanos, boolean render, Object toRun) {
        if (!measuring)
            return;
        luaNanos += nanos;
        if (render)
            luaRenderNanos += nanos;
        // per kind: an event by name, or a part render function
        String kind = toRun instanceof String s ? s
                : toRun instanceof org.figuramc.figura.lua.api.event.LuaEvent ? "event"
                : toRun instanceof org.luaj.vm2.LuaFunction ? "function" : toRun == null ? "null" : toRun.getClass().getSimpleName();
        long[] entry = LUA_BY_KIND.computeIfAbsent(kind + (render ? "(render)" : ""), k -> new long[2]);
        entry[0] += nanos;
        entry[1]++;
    }

    private static final java.util.Map<String, long[]> LUA_BY_KIND = new java.util.TreeMap<>();

    // every avatar model pass (ImmediateAvatarRenderer.render / renderSpecialParts), part render functions included
    public static void onAvatarDraw(long nanos) {
        if (measuring)
            avatarDrawNanos += nanos;
    }

    private static long luaRenderNanos, avatarDrawNanos;

    // what the render script limit decided, counted on every render event of the benchmark avatar
    private static final java.util.Map<String, Integer> RENDER_EVENTS = new java.util.TreeMap<>();

    public static void onRenderEvent(org.figuramc.figura.avatar.Avatar avatar, boolean ran) {
        if (!measuring)
            return;
        String key = "mode=" + avatar.renderMode + " limited=" + (avatar.renderScriptInterval > 0) + " ran=" + ran;
        RENDER_EVENTS.merge(key, 1, Integer::sum);
    }

    private static void openWorld(Minecraft mc) {
        if (mc.getLevelSource().levelExists(WORLD)) {
            log("opening the benchmark world");
            mc.createWorldOpenFlows().loadLevel(mc.screen, WORLD);
            return;
        }
        log("creating the benchmark world");
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(1L, false, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
    }

    private static void setUp(Minecraft mc) {
        // uncapped, unpaused, the avatar facing the camera
        mc.options.enableVsync().set(false);
        mc.options.framerateLimit().set(260);
        mc.options.pauseOnLostFocus = false;
        mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
        mc.options.hideGui = true;
        mc.player.connection.sendCommand("time set 6000");
        mc.player.connection.sendCommand("weather clear");
        keepCamera(mc);

        String rate = System.getProperty("figura.benchmark.rate");
        if (rate != null)
            Configs.AVATAR_RENDER_SCRIPT_RATE.value = Integer.parseInt(rate);
        String budget = System.getProperty("figura.benchmark.budget");
        if (budget != null)
            Configs.AVATAR_RENDER_SCRIPT_BUDGET.value = Float.parseFloat(budget);

        if (!AVATAR.isBlank()) {
            try {
                CompoundTag nbt = NbtIo.readCompressed(new File(AVATAR));
                AvatarManager.setAvatar(FiguraMod.getLocalPlayerUUID(), nbt);
                log("loaded avatar " + AVATAR);
            } catch (IOException e) {
                FiguraMod.LOGGER.error("[benchmark] could not read the avatar " + AVATAR, e);
            }
        } else {
            log("no avatar");
        }
    }

    // the same view every frame of every run
    private static void keepCamera(Minecraft mc) {
        if (mc.player == null)
            return;
        mc.player.setYRot(180);
        mc.player.setXRot(10);
        mc.player.setYHeadRot(180);
        mc.player.setYBodyRot(180);
        mc.player.yRotO = 180;
        mc.player.xRotO = 10;
    }

    private static void writeResults(Minecraft mc, double seconds) {
        long[] sorted = Arrays.copyOf(frames, frameCount);
        Arrays.sort(sorted);
        int n = sorted.length;
        double avgFps = n / seconds;
        double p50 = n == 0 ? 0 : sorted[n / 2] / 1e6;
        double p99 = n == 0 ? 0 : sorted[Math.min(n - 1, (int) (n * 0.99))] / 1e6;
        double max = n == 0 ? 0 : sorted[n - 1] / 1e6;
        // 1% low: the frame rate of the slowest 1% of frames
        int worst = Math.max(1, n / 100);
        long worstSum = 0;
        for (int i = n - worst; i < n; i++)
            worstSum += sorted[i];
        double low1 = n == 0 ? 0 : 1e9 / (worstSum / (double) worst);
        double luaMsPerSecond = luaNanos / 1e6 / seconds;
        double luaRenderMsPerSecond = luaRenderNanos / 1e6 / seconds;
        // avatar model passes per frame, with the part render functions they run
        double drawMsPerFrame = n == 0 ? 0 : avatarDrawNanos / 1e6 / n;

        String line = String.format(Locale.ROOT,
                "%s | avatar=%s otherPlayer=%s rate=%d budget=%.2f | frames=%d avgFps=%.1f p50=%.2fms p99=%.2fms max=%.2fms low1%%=%.1ffps"
                        + " | lua=%.1fms/s (render %.1f, other %.1f) avatarDraw=%.3fms/frame",
                LABEL, AVATAR.isBlank() ? "none" : Path.of(AVATAR).getFileName(), HOST_AS_OTHER,
                Configs.AVATAR_RENDER_SCRIPT_RATE.value, Configs.AVATAR_RENDER_SCRIPT_BUDGET.value,
                n, avgFps, p50, p99, max, low1, luaMsPerSecond, luaRenderMsPerSecond, luaMsPerSecond - luaRenderMsPerSecond, drawMsPerFrame);
        log(line);
        log("render events: " + RENDER_EVENTS);
        StringBuilder kinds = new StringBuilder();
        LUA_BY_KIND.forEach((k, v) -> kinds.append(String.format(Locale.ROOT, " %s=%.1fms/s x%d/s", k, v[0] / 1e6 / seconds, Math.round(v[1] / seconds))));
        log("lua by kind:" + kinds);
        try {
            Files.writeString(Path.of(RESULTS), line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            FiguraMod.LOGGER.error("[benchmark] could not write " + RESULTS, e);
        }
        Screenshot.grab(mc.gameDirectory, "benchmark-" + LABEL + ".png", mc.getMainRenderTarget(), message -> {});
    }

    private static void log(String message) {
        FiguraMod.LOGGER.info("[benchmark] " + message);
    }
}

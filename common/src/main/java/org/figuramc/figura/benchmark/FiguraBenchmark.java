package org.figuramc.figura.benchmark;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.RemotePlayer;
import com.mojang.authlib.GameProfile;
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
 * (seconds, default 20 and 60), rate and budget (Render Script Rate / Budget for this run), results (file to append to),
 * crowd (a folder of .nbt avatars, one fake player each, see spawnCrowd).
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
    // a folder of .nbt avatars: one client-side player per avatar, in rows in front of the camera (first person, own avatar off)
    private static final String CROWD = System.getProperty("figura.benchmark.crowd", "");
    // -Dfigura.benchmark.noLua=true: other avatars run no scripts once loaded (what drawing alone costs)
    public static final boolean NO_LUA = ENABLED && Boolean.getBoolean("figura.benchmark.noLua");
    private static final boolean CROWD_MODE = !CROWD.isBlank();
    // false: the same players without avatars (the cost of the players alone)
    private static final boolean CROWD_AVATARS = !"false".equalsIgnoreCase(System.getProperty("figura.benchmark.crowdAvatars"));
    private static int crowdSize;

    // profile the render thread with spark while measuring, saved to a file (spark must be installed)
    private static final boolean SPARK = Boolean.getBoolean("figura.benchmark.spark");

    private enum State { MENU, LOADING, WARMUP, MEASURE, SAVING, DONE }
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
                tickCrowd();
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
                    if (SPARK)
                        mc.player.connection.sendCommand("spark profiler start --thread * --interval 1");
                    log("measuring for " + DURATION + " s");
                }
            }
            case MEASURE -> {
                tickCrowd();
                keepCamera(mc);
                if (now - phaseStart >= DURATION * 1e9) {
                    measuring = false;
                    double seconds = (now - phaseStart) / 1e9;
                    writeResults(mc, seconds);
                    if (SPARK) {
                        mc.player.connection.sendCommand("spark profiler stop --save-to-file");
                        state = State.SAVING;
                        phaseStart = now;
                    } else {
                        state = State.DONE;
                        mc.stop();
                    }
                }
            }
            case SAVING -> {
                // a few seconds for spark to write the profile
                if (now - phaseStart >= 8e9) {
                    state = State.DONE;
                    mc.stop();
                }
            }
            default -> {}
        }
    }


    // -- draw batches: Figura's render types, and the entity draw calls of Oculus' batched entity rendering -- //

    // render types Figura handed to the buffer source this frame (one getBuffer each), and the distinct ones
    private static final java.util.Set<Object> frameTypes = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static int frameTypeRequests;
    private static long sumFiguraTypes, sumFiguraRequests, sumIrisDraws, sumIrisTypes, countedFrames;
    private static java.lang.reflect.Method irisDraws, irisTypes, irisReset;
    private static boolean irisLookedUp;

    public static void onRenderType(Object renderType) {
        frameTypes.add(renderType);
        frameTypeRequests++;
    }

    // at the end of every level render (the entity batches are drawn by then)
    public static void onLevelRendered() {
        measureHeads(Minecraft.getInstance());
        countBatches();
    }

    private static void countBatches() {
        // Oculus adds the counters to RenderBuffers (DrawCallTrackingRenderBuffers), the batched buffer source only exists during the level render
        Object source = Minecraft.getInstance().renderBuffers();
        if (!irisLookedUp) {
            irisLookedUp = true;
            try {
                irisDraws = source.getClass().getMethod("getDrawCalls");
                irisTypes = source.getClass().getMethod("getRenderTypes");
                irisReset = source.getClass().getMethod("resetDrawCounts");
            } catch (ReflectiveOperationException e) {
                log("no Oculus draw counters on " + source.getClass().getName() + ": " + e);
            }
            log("draw counters: " + irisDraws);
        }
        if (measuring) {
            countedFrames++;
            if (countedFrames == 100)
                for (Object type : frameTypes)
                    log("render type: " + type);
            sumFiguraTypes += frameTypes.size();
            sumFiguraRequests += frameTypeRequests;
            if (irisDraws != null) {
                try {
                    sumIrisDraws += (int) irisDraws.invoke(source);
                    sumIrisTypes += (int) irisTypes.invoke(source);
                } catch (ReflectiveOperationException ignored) {}
            }
        }
        frameTypes.clear();
        frameTypeRequests = 0;
        if (irisReset != null) {
            try {
                irisReset.invoke(source);
            } catch (ReflectiveOperationException ignored) {}
        }
    }

    // avatar batches drawn from their own texture instead of the avatar atlas, by reason: batches and vertices
    private static final java.util.Map<String, long[]> STANDALONE = new java.util.TreeMap<>();
    // parallel model walks: started, not started (why), finished early (where) or at the end of the entities, with the render thread wait
    private static final java.util.Map<String, long[]> WALKS = new java.util.TreeMap<>();

    public static synchronized void onWalk(String what, long waitNanos) {
        if (!measuring)
            return;
        long[] c = WALKS.computeIfAbsent(what, k -> new long[2]);
        c[0]++;
        c[1] += waitNanos;
    }

    private static final java.util.Set<String> UVS_OUTSIDE_LOGGED = new java.util.HashSet<>();

    public static synchronized void onUvsOutside(int w, int h, float[] bounds, double tu, double tv, boolean uvMatrix, String part) {
        if (!measuring || !UVS_OUTSIDE_LOGGED.add(part) || UVS_OUTSIDE_LOGGED.size() > 40)
            return;
        log(String.format(Locale.ROOT, "uvs outside: part %s texture %dx%d uv px [%.2f %.2f .. %.2f %.2f] corner %.4f %.4f uvMatrix=%s",
                part, w, h, bounds[0], bounds[1], bounds[2], bounds[3], tu, tv, uvMatrix));
    }

    // vertices not sent because their quads draw nothing (ImmediateAvatarRenderer.pushToBuffer)
    private static long strippedVertices;

    private static long sentVertices;

    private static long consumes, parallelConsumes;

    public static void onConsume(boolean parallel) {
        if (!measuring)
            return;
        consumes++;
        if (parallel)
            parallelConsumes++;
    }

    public static void onSent(int vertices) {
        if (measuring)
            sentVertices += vertices;
    }

    public static synchronized void onStripped(int vertices) {
        if (measuring)
            strippedVertices += vertices;
    }

    public static synchronized void onStandalone(String reason, int vertices) {
        if (!measuring)
            return;
        long[] c = STANDALONE.computeIfAbsent(reason, k -> new long[2]);
        c[0]++;
        c[1] += vertices;
    }

    private static String batchStats() {
        double n = Math.max(countedFrames, 1);
        return String.format(Locale.ROOT, " | figuraTypes=%.1f (getBuffer %.1f) irisDraws=%.1f irisTypes=%.1f per frame",
                sumFiguraTypes / n, sumFiguraRequests / n, sumIrisDraws / n, sumIrisTypes / n);
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
    public static synchronized void onLua(long nanos, boolean render, Object toRun) {
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
        // part render functions (and other functions run directly), by where they are defined
        if (toRun instanceof org.luaj.vm2.LuaClosure closure) {
            long[] f = LUA_BY_FUNCTION.computeIfAbsent("part " + where(closure), k -> new long[2]);
            f[0] += nanos;
            f[1]++;
        }
    }

    private static final java.util.Map<String, long[]> LUA_BY_KIND = new java.util.TreeMap<>();

    // time per Lua function, keyed by where it is defined: event handlers (LuaEvent.call) and part render functions (Avatar.run)
    private static final java.util.Map<String, long[]> LUA_BY_FUNCTION = new java.util.HashMap<>();

    public static synchronized void onHandler(org.luaj.vm2.LuaFunction function, long nanos) {
        if (!measuring)
            return;
        long[] entry = LUA_BY_FUNCTION.computeIfAbsent("event " + where(function), k -> new long[2]);
        entry[0] += nanos;
        entry[1]++;
    }

    private static String where(Object function) {
        if (function instanceof org.luaj.vm2.LuaClosure closure)
            return closure.p.shortsource() + ":" + closure.p.linedefined;
        return function == null ? "?" : function.getClass().getSimpleName();
    }

    // every avatar model pass (ImmediateAvatarRenderer.render / renderSpecialParts), part render functions included
    public static synchronized void onAvatarDraw(long nanos) {
        if (measuring)
            avatarDrawNanos += nanos;
    }

    private static long luaRenderNanos, avatarDrawNanos;

    // what the render script limit decided, counted on every render event of the benchmark avatar
    private static final java.util.Map<String, Integer> RENDER_EVENTS = new java.util.TreeMap<>();

    private static int tracedEvents;

    // the traced crowd player's vanilla head rotation as the render event sees it, against the one of this frame
    private static void traceRenderEvent(org.figuramc.figura.avatar.Avatar avatar, float delta) {
        if (TRACE_HEAD < 0 || !measuring || tracedEvents >= 120 || TRACE_HEAD >= CROWD_PLAYERS.size())
            return;
        RemotePlayer p = CROWD_PLAYERS.get(TRACE_HEAD);
        if (!avatar.owner.equals(p.getUUID()) || avatar.luaRuntime == null)
            return;
        tracedEvents++;
        float pt = Minecraft.getInstance().getFrameTime();
        org.figuramc.figura.math.vector.FiguraVec3 seen = avatar.luaRuntime.vanilla_model.HEAD.getOriginRot();
        float headYaw = net.minecraft.util.Mth.rotLerp(pt, p.yHeadRotO, p.yHeadRot), bodyYaw = net.minecraft.util.Mth.rotLerp(pt, p.yBodyRotO, p.yBodyRot);
        log(String.format(Locale.ROOT, "event %d delta=%.3f pt=%.3f script sees head yaw %.2f pitch %.2f, this frame %.2f %.2f",
                tracedEvents, delta, pt, seen.y, seen.x, -net.minecraft.util.Mth.wrapDegrees(headYaw - bodyYaw), -p.getViewXRot(pt)));
    }

    public static void onRenderEvent(org.figuramc.figura.avatar.Avatar avatar, boolean ran, float delta) {
        traceRenderEvent(avatar, delta);
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
        // one avatar: third person, the player facing the camera; a crowd: first person, the crowd in front
        mc.options.setCameraType(CROWD_MODE ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_FRONT);
        mc.options.hideGui = true;
        mc.player.connection.sendCommand("time set 6000");
        mc.player.connection.sendCommand("weather clear");
        if (CROWD_MODE) {
            // on the ground at the crowd's origin, see spawnCrowd
            mc.player.getAbilities().flying = false;
            mc.player.setPos(0.5, -60, 0.5);
            mc.player.connection.sendCommand("tp @s 0.5 -60 0.5");
        }
        keepCamera(mc);

        String rate = System.getProperty("figura.benchmark.rate");
        if (rate != null)
            Configs.AVATAR_RENDER_SCRIPT_RATE.value = Integer.parseInt(rate);
        String otherScripts = System.getProperty("figura.benchmark.otherScripts");
        if (otherScripts != null)
            Configs.OTHER_AVATAR_SCRIPTS.value = Integer.parseInt(otherScripts);
        // off unless asked for, so a crowd run still measures the scripts
        Configs.CROWD_PINGS_ONLY.value = Integer.getInteger("figura.benchmark.crowdPingsOnly", 0);
        String parallelTicks = System.getProperty("figura.benchmark.parallelTicks");
        if (parallelTicks != null)
            Configs.PARALLEL_TICK_SCRIPTS.value = Boolean.parseBoolean(parallelTicks);
        String tickBudget = System.getProperty("figura.benchmark.tickBudget");
        if (tickBudget != null)
            Configs.AVATAR_TICK_SCRIPT_BUDGET.value = Float.parseFloat(tickBudget);
        String spread = System.getProperty("figura.benchmark.spreadTicks");
        if (spread != null)
            Configs.SPREAD_TICK_SCRIPTS.value = Boolean.parseBoolean(spread);
        String budget = System.getProperty("figura.benchmark.budget");
        if (budget != null)
            Configs.AVATAR_RENDER_SCRIPT_BUDGET.value = Float.parseFloat(budget);

        if (CROWD_MODE)
            spawnCrowd(mc);

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

    // one client-side player per avatar in the crowd folder, standing in rows of five in front of the camera, facing it.
    // Like players from a server for the renderers (Epic Fight, Figura, Oculus), but only on this client: nobody moves them.
    // Their UUIDs are name based (version 3), so Figura never asks its backend for their avatars, and they get the default
    // permission level like any other player's.

    private static void spawnCrowd(Minecraft mc) {
        // built avatars (.nbt) or avatar folders (with an avatar.json, built on load like a local avatar)
        File[] files = new File(CROWD).listFiles((dir, name) -> name.endsWith(".nbt") || new File(dir, name + "/avatar.json").isFile());
        if (files == null || files.length == 0) {
            log("no avatars in the crowd folder " + CROWD);
            return;
        }
        Arrays.sort(files);
        int n = files.length;
        // up to 10 players: rows of five close up, more: rows of ten, further away
        int perRow = n <= 10 ? 5 : 10;
        double spacing = n <= 10 ? 1.6 : 1.3, firstRow = n <= 10 ? 4 : 6, rowGap = n <= 10 ? 2.5 : 2.2;
        // the same place in every run (the saved world keeps wherever the player ended up): the flat world's ground at 0, 0
        double x0 = 0.5, y0 = -60, z0 = 0.5;
        for (int i = 0; i < n; i++) {
            java.util.UUID id = java.util.UUID.nameUUIDFromBytes(("figura-benchmark-crowd-" + i).getBytes(StandardCharsets.UTF_8));
            RemotePlayer player = new RemotePlayer(mc.level, new GameProfile(id, "Crowd" + i));
            int row = i / perRow, col = i % perRow;
            double x = x0 + (col - (perRow - 1) / 2.0) * spacing + (row % 2) * spacing / 2;
            double z = z0 + firstRow + row * rowGap;
            player.setId(2_000_000 + i);
            player.moveTo(x, y0, z, 180, 0);
            player.setYHeadRot(180);
            player.setYBodyRot(180);
            player.setOldPosAndRot();
            mc.level.addPlayer(player.getId(), player);
            CROWD_PLAYERS.add(player);
            HEAD_STATS.add(new HeadStats());
            if (CROWD_AVATARS) try {
                CompoundTag nbt = files[i].isDirectory() ? org.figuramc.figura.avatar.local.LocalAvatarLoader.buildAvatar(files[i].toPath(), id) : NbtIo.readCompressed(files[i]);
                AvatarManager.setAvatar(id, nbt);
                HEAD_STATS.get(i).name = files[i].getName() + (usesSmoothHead(nbt) ? " (smooth head script)" : "");
                log("crowd " + i + ": " + HEAD_STATS.get(i).name);
            } catch (Exception e) {
                FiguraMod.LOGGER.error("[benchmark] could not read the avatar " + files[i], e);
            }
        }
        // -Dfigura.benchmark.fullDetail=3,8: those crowd players set to full detail (like the popup menu's Toggle Full Detail)
        for (String index : System.getProperty("figura.benchmark.fullDetail", "").split(",")) {
            if (index.isBlank())
                continue;
            int i = Integer.parseInt(index.trim());
            if (i >= 0 && i < CROWD_PLAYERS.size()) {
                org.figuramc.figura.avatar.AvatarCulling.toggleFullDetail(CROWD_PLAYERS.get(i).getUUID());
                log("crowd " + i + " set to full detail");
            }
        }
        crowdSize = n;
    }

    // -- crowd head motion: the players look around like players on a server, and how smoothly their avatars' heads follow -- //

    // -Dfigura.benchmark.crowdLook=false: the crowd stands still
    private static final boolean CROWD_LOOK = !"false".equalsIgnoreCase(System.getProperty("figura.benchmark.crowdLook"));
    private static final java.util.List<RemotePlayer> CROWD_PLAYERS = new java.util.ArrayList<>();
    private static final java.util.List<HeadStats> HEAD_STATS = new java.util.ArrayList<>();
    private static int crowdTicks;
    // -Dfigura.benchmark.traceHead=<index>: log the head of that crowd player frame by frame
    private static final int TRACE_HEAD = Integer.getInteger("figura.benchmark.traceHead", -1);
    private static int traced;

    private static boolean usesSmoothHead(CompoundTag nbt) {
        CompoundTag scripts = nbt.getCompound("scripts");
        for (String key : scripts.getAllKeys()) {
            String src = new String(scripts.getByteArray(key), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            if (src.contains("smoothhead") || src.contains("smooth_head") || src.contains("smoothead"))
                return true;
        }
        return false;
    }

    // every client tick: the rotation a server would send, rounded to the 1/256 turn steps of the protocol, interpolated
    // over three ticks like a remote player's head (ClientboundRotateHeadPacket / ClientboundMoveEntityPacket.Rot)
    private static void tickCrowd() {
        if (!CROWD_LOOK || CROWD_PLAYERS.isEmpty())
            return;
        crowdTicks++;
        if (crowdTicks == 20 && EPIC_FIGHT_MODE)
            toEpicFightMode();
        double t = crowdTicks / 20.0;
        for (int i = 0; i < CROWD_PLAYERS.size(); i++) {
            RemotePlayer p = CROWD_PLAYERS.get(i);
            float yaw = quantize(180 + 55 * (float) Math.sin(2 * Math.PI * t / 4 + i * 0.7));
            float pitch = quantize(20 * (float) Math.sin(2 * Math.PI * t / 3 + i * 1.3));
            p.lerpHeadTo(yaw, 3);
            p.lerpTo(p.getX(), p.getY(), p.getZ(), yaw, pitch, 3, false);
        }
    }

    // -Dfigura.benchmark.epicFightMode=true: the crowd in Epic Fight's battle mode (PlayerMode.EPICFIGHT) instead of vanilla mode
    private static final boolean EPIC_FIGHT_MODE = Boolean.getBoolean("figura.benchmark.epicFightMode");

    private static void toEpicFightMode() {
        try {
            Class<?> caps = Class.forName("yesman.epicfight.world.capabilities.EpicFightCapabilities");
            java.lang.reflect.Method getPatch = caps.getMethod("getPlayerPatch", net.minecraft.world.entity.player.Player.class);
            int n = 0;
            for (RemotePlayer p : CROWD_PLAYERS) {
                Object patch = getPatch.invoke(null, p);
                if (patch == null)
                    continue;
                patch.getClass().getMethod("toEpicFightMode", boolean.class).invoke(patch, false);
                if ((boolean) patch.getClass().getMethod("isEpicFightMode").invoke(patch))
                    n++;
            }
            log("crowd in Epic Fight mode: " + n + "/" + CROWD_PLAYERS.size());
        } catch (ReflectiveOperationException e) {
            log("could not switch the crowd to Epic Fight mode: " + e);
        }
    }

    private static float quantize(float degrees) {
        return net.minecraft.util.Mth.floor(degrees * 256 / 360) * 360 / 256f;
    }

    private static final class HeadStats {
        String name = "?";
        org.figuramc.figura.model.FiguraModelPart head;
        boolean searched;
        double lastX, lastY, lastZ, lastTX, lastTY, lastTZ;
        boolean hasLast;
        long frames, stalls, jumps;
        double drawnSum, targetSum, errorSum;
    }

    // after every level render: the head part's drawn direction against the direction the player looks
    private static void measureHeads(Minecraft mc) {
        if (!CROWD_LOOK || CROWD_PLAYERS.isEmpty())
            return;
        float pt = mc.getFrameTime();
        for (int i = 0; i < CROWD_PLAYERS.size(); i++) {
            HeadStats s = HEAD_STATS.get(i);
            RemotePlayer p = CROWD_PLAYERS.get(i);
            if (s.head == null) {
                if (s.searched)
                    continue;
                org.figuramc.figura.avatar.Avatar avatar = AvatarManager.getLoadedAvatar(p.getUUID());
                if (avatar == null || avatar.renderer == null || avatar.renderer.root == null)
                    continue;
                s.head = findHead(avatar.renderer.root);
                if (s.head != null) {
                    StringBuilder path = new StringBuilder(s.head.name);
                    for (org.figuramc.figura.model.FiguraModelPart q = s.head.parent; q != null; q = q.parent)
                        path.insert(0, q.name + ".");
                    log("crowd " + i + " head part: " + path);
                }
                s.searched = true;
                if (s.head == null)
                    continue;
            }
            org.figuramc.figura.math.matrix.FiguraMat4 m = s.head.savedPartToWorldMat;
            // the part's forward axis in the world
            double x = m.v13, y = m.v23, z = m.v33, len = Math.sqrt(x * x + y * y + z * z);
            if (len == 0)
                continue;
            x /= len; y /= len; z /= len;
            double yaw = Math.toRadians(p.getViewYRot(pt)), pitch = Math.toRadians(p.getViewXRot(pt));
            double tx = -Math.sin(yaw) * Math.cos(pitch), ty = -Math.sin(pitch), tz = Math.cos(yaw) * Math.cos(pitch);
            if (i == TRACE_HEAD && measuring && traced < 240) {
                traced++;
                org.figuramc.figura.math.vector.FiguraVec3 sr = s.head.customization.getRot();
                log(String.format(Locale.ROOT, "trace %d pt=%.3f script rot %.1f %.1f %.1f target yaw=%.2f pitch=%.2f drawn yaw=%.2f pitch=%.2f", traced, pt, sr.x, sr.y, sr.z,
                        Math.toDegrees(Math.atan2(-tx, tz)), Math.toDegrees(Math.asin(-ty)), Math.toDegrees(Math.atan2(-x, z)), Math.toDegrees(Math.asin(-y))));
            }
            if (s.hasLast && measuring) {
                double drawn = angle(x, y, z, s.lastX, s.lastY, s.lastZ), target = angle(tx, ty, tz, s.lastTX, s.lastTY, s.lastTZ);
                s.frames++;
                s.drawnSum += drawn;
                s.targetSum += target;
                s.errorSum += Math.abs(drawn - target);
                if (target > 0.3 && drawn < 0.03)
                    s.stalls++;
                if (drawn > 2 * target + 1)
                    s.jumps++;
            }
            s.lastX = x; s.lastY = y; s.lastZ = z;
            s.lastTX = tx; s.lastTY = ty; s.lastTZ = tz;
            s.hasLast = true;
        }
    }

    private static double angle(double ax, double ay, double az, double bx, double by, double bz) {
        double dot = Math.max(-1, Math.min(1, ax * bx + ay * by + az * bz));
        return Math.toDegrees(Math.acos(dot));
    }

    // the visible part following the head with the most faces under it (avatars often keep an empty or hidden Head group)
    private static org.figuramc.figura.model.FiguraModelPart findHead(org.figuramc.figura.model.FiguraModelPart root) {
        java.util.ArrayDeque<org.figuramc.figura.model.FiguraModelPart> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        org.figuramc.figura.model.FiguraModelPart best = null;
        int bestFaces = 0;
        while (!queue.isEmpty()) {
            org.figuramc.figura.model.FiguraModelPart part = queue.poll();
            if (!part.customization.visible)
                continue;
            if (part.parentType == org.figuramc.figura.model.ParentType.Head) {
                int faces = faces(part);
                if (faces > bestFaces) {
                    best = part;
                    bestFaces = faces;
                }
            }
            queue.addAll(part.children);
        }
        return best;
    }

    private static int faces(org.figuramc.figura.model.FiguraModelPart part) {
        int n = 0;
        for (int f : part.facesByTexture)
            n += f;
        for (org.figuramc.figura.model.FiguraModelPart child : part.children)
            if (child.customization.visible)
                n += faces(child);
        return n;
    }

    // per avatar: stalls (the player turns, the head stays), jumps (the head moves more than twice as far as the player turns),
    // error: how far the head's turn in a frame is from the player's, on average
    private static void logHeads() {
        if (!CROWD_LOOK || HEAD_STATS.isEmpty())
            return;
        long frames = 0, stalls = 0, jumps = 0;
        double error = 0;
        StringBuilder sb = new StringBuilder();
        for (HeadStats s : HEAD_STATS) {
            if (s.frames == 0) {
                sb.append(String.format(Locale.ROOT, "%n    %-48s no head part", s.name));
                continue;
            }
            sb.append(String.format(Locale.ROOT, "%n    %-48s stalls %5.1f%% jumps %5.1f%% error %.3f deg/frame (turn %.3f, head %.3f)",
                    s.name, 100.0 * s.stalls / s.frames, 100.0 * s.jumps / s.frames, s.errorSum / s.frames,
                    s.targetSum / s.frames, s.drawnSum / s.frames));
            frames += s.frames;
            stalls += s.stalls;
            jumps += s.jumps;
            error += s.errorSum;
        }
        // what each avatar did to the vanilla head (most hide it in ENTITY_INIT), null = left as it is (shown)
        StringBuilder vanilla = new StringBuilder("vanilla head visible (HEAD / PLAYER):");
        for (int i = 0; i < CROWD_PLAYERS.size(); i++) {
            org.figuramc.figura.avatar.Avatar avatar = AvatarManager.getLoadedAvatar(CROWD_PLAYERS.get(i).getUUID());
            if (avatar == null || avatar.luaRuntime == null)
                continue;
            vanilla.append(String.format(Locale.ROOT, "%n    %-48s %s / %s", HEAD_STATS.get(i).name,
                    avatar.luaRuntime.vanilla_model.HEAD.getVisible(), avatar.luaRuntime.vanilla_model.PLAYER.getVisible()));
        }
        log(vanilla.toString());
        log(String.format(Locale.ROOT, "heads: stalls %.2f%% jumps %.2f%% error %.3f deg/frame over %d avatar frames%s",
                100.0 * stalls / Math.max(frames, 1), 100.0 * jumps / Math.max(frames, 1), error / Math.max(frames, 1), frames, sb));
    }

    // the same view every frame of every run
    private static void keepCamera(Minecraft mc) {
        if (mc.player == null)
            return;
        float yaw = CROWD_MODE ? 0 : 180, pitch = CROWD_MODE ? (crowdSize > 10 ? 15 : 8) : 10;
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.setYHeadRot(yaw);
        mc.player.setYBodyRot(yaw);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
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
                LABEL, CROWD_MODE ? "crowd:" + Path.of(CROWD).getFileName() + "x" + crowdSize + (CROWD_AVATARS ? "" : "-noavatars") : AVATAR.isBlank() ? "none" : Path.of(AVATAR).getFileName(), HOST_AS_OTHER,
                Configs.AVATAR_RENDER_SCRIPT_RATE.value, Configs.AVATAR_RENDER_SCRIPT_BUDGET.value,
                n, avgFps, p50, p99, max, low1, luaMsPerSecond, luaRenderMsPerSecond, luaMsPerSecond - luaRenderMsPerSecond, drawMsPerFrame)
                + batchStats();
        log(line);
        log("render events: " + RENDER_EVENTS);
        logHeads();
        StringBuilder standalone = new StringBuilder();
        STANDALONE.forEach((k, v) -> standalone.append(String.format(Locale.ROOT, " %s=%.1f batches %.0f vertices", k, v[0] / (double) Math.max(countedFrames, 1), v[1] / (double) Math.max(countedFrames, 1))));
        log("not from the atlas, per frame:" + standalone);
        StringBuilder walks = new StringBuilder();
        WALKS.forEach((k, v) -> walks.append(String.format(Locale.ROOT, " %s=%.1f/frame wait %.3fms/frame", k, v[0] / (double) Math.max(countedFrames, 1), v[1] / 1e6 / Math.max(countedFrames, 1))));
        log("parallel walks:" + walks);
        log(String.format(Locale.ROOT, "vertices sent: %.0f per frame, empty quads not sent: %.0f per frame, passes written by workers: %d of %d", sentVertices / (double) Math.max(countedFrames, 1), strippedVertices / (double) Math.max(countedFrames, 1), parallelConsumes, consumes));
        StringBuilder kinds = new StringBuilder();
        LUA_BY_KIND.forEach((k, v) -> kinds.append(String.format(Locale.ROOT, " %s=%.1fms/s x%d/s", k, v[0] / 1e6 / seconds, Math.round(v[1] / seconds))));
        log("lua by kind:" + kinds);
        // the most expensive functions, with time per second, calls per second and time per call
        StringBuilder functions = new StringBuilder();
        LUA_BY_FUNCTION.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(25)
                .forEach(e -> functions.append(String.format(Locale.ROOT, "%n    %7.2f ms/s %6d calls/s %8.3f ms/call  %s",
                        e.getValue()[0] / 1e6 / seconds, Math.round(e.getValue()[1] / seconds),
                        e.getValue()[0] / 1e6 / Math.max(e.getValue()[1], 1), e.getKey())));
        log("lua by function:" + functions);
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

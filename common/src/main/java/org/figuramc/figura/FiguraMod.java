package org.figuramc.figura;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.avatar.local.CacheAvatarLoader;
import org.figuramc.figura.avatar.local.LocalAvatarFetcher;
import org.figuramc.figura.avatar.local.LocalAvatarLoader;
import org.figuramc.figura.backend2.NetworkStuff;
import org.figuramc.figura.compat.GeckoLibCompat;
import org.figuramc.figura.compat.SimpleVCCompat;
import org.figuramc.figura.config.ConfigManager;
import org.figuramc.figura.config.Configs;
import org.figuramc.figura.entries.EntryPointManager;
import org.figuramc.figura.font.Emojis;
import org.figuramc.figura.lua.FiguraLuaPrinter;
import org.figuramc.figura.lua.docs.FiguraDocsManager;
import org.figuramc.figura.mixin.SkullBlockEntityAccessor;
import org.figuramc.figura.permissions.PermissionManager;
import org.figuramc.figura.resources.FiguraRuntimeResources;
import org.figuramc.figura.utils.*;
import org.figuramc.figura.wizards.AvatarWizard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

public class FiguraMod {

    public static final String MOD_ID = "figura";
    public static final String MOD_NAME = "Figura";
    public static final FiguraModMetadata METADATA = FiguraModMetadata.getMetadataForMod(MOD_ID);
    public static final Version VERSION = new Version(PlatformUtils.getFiguraModVersionString());
    public static final Calendar CALENDAR = Calendar.getInstance();
    public static final Path GAME_DIR = PlatformUtils.getGameDir().normalize();
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);
    public static final float VERTEX_OFFSET = -0.0005f;

    public static int ticks;
    public static Entity extendedPickEntity;
    public static Component splashText;
    public static boolean parseMessages = true;
    public static boolean processingKeybind;

    /* For some reason, the mod menu entrypoint (or something) is able to call this before the Config
    class can initialize, meaning Configs.DEBUG_MODE can be null when this is called.... Weird */
    @SuppressWarnings("all")
    public static boolean debugModeEnabled() {
        return Configs.DEBUG_MODE != null && Configs.DEBUG_MODE.value;
    }

    // -Dfigura.verifyOptimizations=true: the performance optimizations also do the work the old way and log the first difference
    // separate from debug mode, since checking makes every optimized path slower than the original
    private static final boolean VERIFY_OPTIMIZATIONS = Boolean.getBoolean("figura.verifyOptimizations");

    public static boolean verifyOptimizations() {
        return VERIFY_OPTIMIZATIONS;
    }

    public static void onClientInit() {
        // init managers
        EntryPointManager.init();
        PermissionManager.init();
        LocalAvatarFetcher.init();
        CacheAvatarLoader.init();
        FiguraDocsManager.init();
        FiguraRuntimeResources.init();

        GeckoLibCompat.init();
        SimpleVCCompat.init();
    }

    public static List<FiguraResourceListener> getResourceListeners() {
        List<FiguraResourceListener> listeners = new ArrayList<>();
        listeners.add(LocalAvatarLoader.AVATAR_LISTENER);
        listeners.add(Emojis.RESOURCE_LISTENER);
        listeners.add(AvatarWizard.RESOURCE_LISTENER);
        listeners.add(AvatarManager.RESOURCE_RELOAD_EVENT);
        return listeners;
    }


    public static void tick() {
        pushProfiler("network");
        NetworkStuff.tick();
        popPushProfiler("files");
        LocalAvatarLoader.tick();
        LocalAvatarFetcher.tick();
        if (Minecraft.getInstance().player != null) {
            popPushProfiler("avatars");
            AvatarManager.tickLoadedAvatars();
        }
        popPushProfiler("chatPrint");
        FiguraLuaPrinter.printChatFromQueue();
        popPushProfiler("emojiAnim");
        Emojis.tickAnimations();
        popProfiler();
        org.figuramc.figura.benchmark.FiguraBenchmark.tick();
        ticks++;
    }

    // -- Helper Functions -- //

    // debug print
    public static void debug(String str, Object... args) {
        if (FiguraMod.debugModeEnabled()) LOGGER.info("[DEBUG] " + str, args);
        else LOGGER.debug(str, args);
    }

    // mod root directory
    public static Path getFiguraDirectory() {
        String config = Configs.MAIN_DIR.value;
        Path p = config.isBlank() ? GAME_DIR.resolve(MOD_ID) : Path.of(config);
        return IOUtils.createDirIfNeeded(p);
    }

    // mod cache directory
    public static Path getCacheDirectory() {
        return IOUtils.getOrCreateDir(getFiguraDirectory(), Configs.LOCAL_ASSETS.value ? "local_cache" : "cache");
    }

    // get local player uuid
    public static UUID getLocalPlayerUUID() {
        Entity player = Minecraft.getInstance().player;
        return player != null ? player.getUUID() : Minecraft.getInstance().getUser().getGameProfile().getId();
    }

    public static boolean isLocal(UUID other) {
        return getLocalPlayerUUID().equals(other);
    }

    /**
     * Sends a chat message right away. Use when you know your message is safe.
     * If your message is unsafe, (generated by a user), use luaSendChatMessage instead.
     *
     * @param message - text to send
     */
    public static void sendChatMessage(Component message) {
        if (Minecraft.getInstance().gui != null) {
            parseMessages = false;
            Minecraft.getInstance().gui.getChat().addMessage(TextUtils.replaceTabs(message));
            parseMessages = true;
        } else {
            LOGGER.info(message.getString());
        }
    }

    /**
     * Converts a player name to UUID using minecraft internal functions.
     *
     * @param playerName - the player name
     * @return - the player's uuid or null
     */
    public static UUID playerNameToUUID(String playerName) {
        GameProfileCache cache = SkullBlockEntityAccessor.getProfileCache();
        if (cache == null) return null;

        var profile = cache.get(playerName);
        return profile.isEmpty() ? null : profile.get().getId();
    }

    public static Style getAccentColor() {
        Avatar avatar = AvatarManager.getAvatarForPlayer(getLocalPlayerUUID());
        int color = avatar != null ? ColorUtils.rgbToInt(ColorUtils.userInputHex(avatar.color, ColorUtils.Colors.AWESOME_BLUE.vec)) : ColorUtils.Colors.AWESOME_BLUE.hex;
        return Style.EMPTY.withColor(color);
    }

    // -- profiler -- //

    // the vanilla profiler, or the inactive one on Figura's worker threads (the profiler is not thread safe)
    private static ProfilerFiller profiler() {
        return org.figuramc.figura.utils.FiguraWorkers.isWorker() ? InactiveProfiler.INSTANCE : Minecraft.getInstance().getProfiler();
    }

    public static void pushProfiler(String name) {
        profiler().push(name);
    }

    // whether the vanilla profiler records anything (debug pie chart or a metrics recording), so labels are only built when they are used
    public static boolean isProfilerActive() {
        return profiler() != InactiveProfiler.INSTANCE;
    }

    public static void pushProfiler(Avatar avatar) {
        // pushing to the inactive profiler does nothing, and the pops are no-ops too, so skipping it keeps them balanced
        ProfilerFiller profiler = profiler();
        if (profiler != InactiveProfiler.INSTANCE)
            profiler.push(avatar.entityName.isBlank() ? avatar.owner.toString() : avatar.entityName);
    }

    public static void popPushProfiler(String name) {
        profiler().popPush(name);
    }

    public static void popProfiler() {
        profiler().pop();
    }

    public static <T> T popReturnProfiler(T var) {
        profiler().pop();
        return var;
    }

    public static void popProfiler(int times) {
        var profiler = profiler();
        for (int i = 0; i < times; i++)
            profiler.pop();
    }

    public enum Links {
        Wiki("https://wiki.figuramc.org/", ColorUtils.Colors.AWESOME_BLUE.style),
        Kofi("https://ko-fi.com/skyrina", ColorUtils.Colors.KOFI.style),
        OpenCollective("https://opencollective.com/figura", ColorUtils.Colors.KOFI.style),
        Discord("https://discord.figuramc.org/", ColorUtils.Colors.DISCORD.style),
        Github("https://github.com/FiguraMC/Figura", ColorUtils.Colors.GITHUB.style),
        Modrinth("https://modrinth.com/mod/figura", ColorUtils.Colors.MODRINTH.style),
        Curseforge("https://www.curseforge.com/minecraft/mc-mods/figura", ColorUtils.Colors.CURSEFORGE.style),
        LuaManual("https://www.lua.org/manual/5.2/manual.html", ColorUtils.Colors.LUA_LOG.style);

        public final String url;
        public final Style style;

        Links(String url, Style style) {
            this.url = url;
            this.style = style;
        }
    }
}

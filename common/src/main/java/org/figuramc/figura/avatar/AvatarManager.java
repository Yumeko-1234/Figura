package org.figuramc.figura.avatar;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.local.LocalAvatarLoader;
import org.figuramc.figura.backend2.NetworkStuff;
import org.figuramc.figura.config.Configs;
import org.figuramc.figura.gui.FiguraToast;
import org.figuramc.figura.gui.widgets.lists.AvatarList;
import org.figuramc.figura.lua.api.particle.ParticleAPI;
import org.figuramc.figura.lua.api.sound.SoundAPI;
import org.figuramc.figura.utils.EntityUtils;
import org.figuramc.figura.utils.FiguraClientCommandSource;
import org.figuramc.figura.utils.FiguraResourceListener;
import org.figuramc.figura.utils.FiguraText;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.ArrayDeque;
import java.util.UUID;

/**
 * Manages all the avatars that are currently loaded in memory, and also
 * handles getting the avatars of entities. If an entity does not have a loaded avatar,
 * the AvatarManager will fetch the avatar and cache it.
 */
public class AvatarManager {

    private static final Map<UUID, UserData> LOADED_USERS = new ConcurrentHashMap<>();
    private static final Set<UUID> FETCHED_USERS = new HashSet<>();
    // users whose backend fetch failed (expired token, backend hiccup), fetched again once the tick is reached
    private static final Map<UUID, Integer> FETCH_RETRY = new ConcurrentHashMap<>();

    private static final Map<Entity, Avatar> LOADED_CEM = new ConcurrentHashMap<>();
    // reused list of the avatars to tick this tick
    private static final List<Avatar> TICKING = new ArrayList<>();

    public static final FiguraResourceListener RESOURCE_RELOAD_EVENT = FiguraResourceListener.createResourceListener("resource_reload_event", manager -> executeAll("resourceReloadEvent", Avatar::resourceReloadEvent));

    public static boolean localUploaded = true; // init as true :3
    public static boolean panic = false;

    // -- panic mode -- // 

    public static void togglePanic() {
        AvatarManager.panic = !AvatarManager.panic;
        FiguraToast.sendToast(FiguraText.of(AvatarManager.panic ? "toast.panic_enabled" : "toast.panic_disabled"), FiguraToast.ToastType.WARNING);
        SoundAPI.getSoundEngine().figura$stopAllSounds();
        ParticleAPI.getParticleEngine().figura$clearParticles(null);
    }

    // -- avatar events -- // 

    // tick scripts waiting for a frame of this tick, in tick order
    private static final ArrayDeque<Avatar> DEFERRED_TICKS = new ArrayDeque<>();

    // tick scripts run on the workers together (see runTicks), collected here first
    private static final List<Avatar> TICK_NOW = new ArrayList<>();

    // runs the tick scripts of the avatars: other players' side by side on the workers (Parallel Tick Scripts), the own one here
    private static void runTicks(List<Avatar> avatars) {
        if (avatars.isEmpty())
            return;
        // model walks are only out during the level render; if one is still out (a level render that ended early), it is finished
        // first, so tick scripts never run while the render thread holds the workers' lock for it
        org.figuramc.figura.model.rendering.ImmediateAvatarRenderer.finishParallelWalks();
        if (avatars.size() == 1 || !Configs.PARALLEL_TICK_SCRIPTS.value || !org.figuramc.figura.utils.FiguraWorkers.available()) {
            for (Avatar avatar : avatars) {
                FiguraMod.pushProfiler(avatar);
                avatar.tickScripts();
                FiguraMod.popProfiler();
            }
            return;
        }
        FiguraMod.pushProfiler("parallelTicks");
        org.figuramc.figura.utils.FiguraWorkers.run(avatars.size(), i -> avatars.get(i).tickScripts());
        FiguraMod.popProfiler();
        // side by side, a tick costs the frame about its time divided by how many ran at once: that is what the tick budget counts
        int together = Math.min(avatars.size(), org.figuramc.figura.utils.FiguraWorkers.THREADS + 1);
        for (Avatar avatar : avatars)
            if (avatar.tickScriptNanos > 0)
                avatar.tickScriptNanos /= together;
    }
    // frames counted since the last tick, and how many there were between the last two ticks
    private static int framesSinceTick, framesPerTick;

    /**
     * Runs a share of the tick scripts deferred by tickLoadedAvatars, called at the start of every frame.
     * The share is sized so the queue empties by the frame the next tick is expected on.
     */
    public static void runDeferredTicks() {
        framesSinceTick++;
        if (DEFERRED_TICKS.isEmpty())
            return;
        if (Minecraft.getInstance().player == null) {
            DEFERRED_TICKS.clear();
            return;
        }

        // frames left in this tick, counting this one; the tick came sooner than expected -> run everything
        int framesLeft = Math.max(1, framesPerTick - framesSinceTick + 1);
        int count = (DEFERRED_TICKS.size() + framesLeft - 1) / framesLeft;
        runDeferred(count);
    }

    /**
     * Runs all tick scripts still deferred, called right before the next client tick changes the world.
     */
    public static void flushDeferredTicks() {
        if (!DEFERRED_TICKS.isEmpty())
            runDeferred(DEFERRED_TICKS.size());
    }

    private static void runDeferred(int count) {
        FiguraMod.pushProfiler(FiguraMod.MOD_ID);
        FiguraMod.pushProfiler("deferredTicks");
        TICK_NOW.clear();
        for (int i = 0; i < count; i++) {
            Avatar avatar = DEFERRED_TICKS.poll();
            if (avatar == null)
                break;
            TICK_NOW.add(avatar);
        }
        runTicks(TICK_NOW);
        TICK_NOW.clear();
        FiguraMod.popProfiler(2);
    }

    public static void tickLoadedAvatars() {
        if (panic)
            return;

        // retry the failed fetches once the backend is back
        if (!FETCH_RETRY.isEmpty() && NetworkStuff.isConnected()) {
            FETCH_RETRY.entrySet().removeIf(entry -> {
                if (FiguraMod.ticks < entry.getValue())
                    return false;
                FETCHED_USERS.remove(entry.getKey());
                return true;
            });
        }

        loadNearbyAvatars();
        updateAvatarOverrides();

        // tick the avatars
        TICKING.clear();
        for (UserData user : LOADED_USERS.values()) {
            Avatar avatar = user.getMainAvatar();
            if (avatar != null)
                TICKING.add(avatar);
        }
        AvatarCulling.throttleTickScripts(TICKING);

        // the scripts of this tick are spread over the frames until the next tick: the world does not change between them
        // (this runs after the client tick), so they see the same state no matter which frame they run on
        // a tick that follows another one in the same frame has no frames to spread over
        framesPerTick = framesSinceTick;
        framesSinceTick = 0;
        boolean spread = Configs.SPREAD_TICK_SCRIPTS.value && framesPerTick > 0;
        Entity cameraEntity = Minecraft.getInstance().getCameraEntity();
        UUID cameraId = cameraEntity == null ? null : cameraEntity.getUUID();

        int interval = Configs.CULLED_AVATAR_TICK_INTERVAL.value;
        for (Avatar avatar : TICKING) {
            // blocked by focus mode: tick scripts are paused
            // too far or out of view: paused (interval 0) or run less often, staggered per player so they don't all run on the same tick
            // over the tick script budget: run less often
            // full detail avatars are never throttled, only paused by focus mode
            if (avatar.focusBlocked || avatar.tickScriptsSkipped || (avatar.culled && !AvatarCulling.isFullDetail(avatar.owner) && (interval <= 0 || Math.floorMod(FiguraMod.ticks + avatar.owner.hashCode(), interval) != 0))) {
                avatar.tickSkipped();
                continue;
            }

            FiguraMod.pushProfiler(avatar);
            avatar.tickPrepare();
            // the own avatar and the ones being watched keep running on the tick, so their reaction is never delayed
            if (spread && !avatar.isHost && !avatar.owner.equals(cameraId) && !AvatarCulling.isFocused(avatar.owner) && !AvatarCulling.isFullDetail(avatar.owner))
                DEFERRED_TICKS.add(avatar);
            else if (!avatar.isHost && !avatar.owner.equals(cameraId))
                TICK_NOW.add(avatar);
            else
                avatar.tickScripts();
            FiguraMod.popProfiler();
        }
        // other players' avatars that tick now: side by side on the workers
        runTicks(TICK_NOW);
        TICK_NOW.clear();

        // CEM
        if (LOADED_CEM.isEmpty())
            return;

        // unload entities
        Set<Entity> toBeRemoved = new HashSet<>();

        for (Entity entity : LOADED_CEM.keySet())
            if (entity.isRemoved())
                toBeRemoved.add(entity);

        for (Entity entity : toBeRemoved) {
            Avatar avatar = LOADED_CEM.remove(entity);
            if (avatar != null)
                avatar.clean();
        }

        // tick entities
        for (Avatar avatar : LOADED_CEM.values()) {
            if (avatar != null) {
                FiguraMod.pushProfiler(avatar);
                avatar.tick();
                FiguraMod.popProfiler();
            }
        }
    }

    public static void executeAll(String src, Consumer<Avatar> consumer) {
        if (panic) return;

        FiguraMod.pushProfiler(FiguraMod.MOD_ID);
        FiguraMod.pushProfiler(src);

        for (UserData user : LOADED_USERS.values()) {
            Avatar avatar = user.getMainAvatar();
            if (avatar != null) {
                FiguraMod.pushProfiler(avatar);
                consumer.accept(avatar);
                FiguraMod.popProfiler();
            }
        }

        for (Avatar avatar : LOADED_CEM.values()) {
            if (avatar != null) {
                FiguraMod.pushProfiler(avatar);
                consumer.accept(avatar);
                FiguraMod.popProfiler();
            }
        }

        FiguraMod.popProfiler(2);
    }

    // -- avatar getters -- // 

    // player will also attempt to load from network, if possible
    // the avatar itself is only loaded once something that renders it asks (getAvatarToRender), this one gets the user data
    // (badges, avatar listing) and the avatar when it is loaded, enough for the tab list, chat and menus
    public static Avatar getAvatarForPlayer(UUID player) {
        if (panic || Minecraft.getInstance().level == null)
            return null;

        fetchBackend(player);

        UserData user = LOADED_USERS.get(player);
        return user == null ? null : user.getMainAvatar();
    }

    // players this many blocks beyond the avatar render distance already load their avatar, so it is ready when they come closer
    private static final int LOAD_DISTANCE_MARGIN = 16;

    // if the player is close enough to the camera for its avatar to be loaded (any distance when the render distance is unlimited)
    private static boolean withinLoadDistance(Entity player) {
        int max = Configs.AVATAR_RENDER_DISTANCE.value;
        Entity camera = Minecraft.getInstance().getCameraEntity();
        if (max <= 0 || camera == null)
            return true;
        double distance = max + LOAD_DISTANCE_MARGIN;
        return player.distanceToSqr(camera) <= distance * distance;
    }

    // once a second, every player within the avatar render distance (plus the margin) gets its avatar loaded, even before anything
    // draws it (behind the camera, behind a wall), so it shows as soon as it comes into view; avatars are never unloaded
    private static void loadNearbyAvatars() {
        Minecraft minecraft = Minecraft.getInstance();
        if (panic || minecraft.level == null || FiguraMod.ticks % 20 != 0)
            return;
        for (Player player : minecraft.level.players())
            if (withinLoadDistance(player))
                getAvatarToRender(player.getUUID());
    }

    // same, for the paths that draw the avatar (player entity, skull, first person hand): loads it and keeps it loaded
    public static Avatar getAvatarToRender(UUID player) {
        if (panic || Minecraft.getInstance().level == null)
            return null;

        fetchBackend(player);

        UserData user = LOADED_USERS.get(player);
        if (user == null)
            return null;
        user.want();
        return user.getMainAvatar();
    }

    private static Avatar getAvatarForEntity(Entity entity) {
        // get loaded
        Avatar loaded = LOADED_CEM.get(entity);
        if (loaded != null)
            return loaded;

        // new avatar
        ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        CompoundTag nbt = LocalAvatarLoader.CEM_AVATARS.get(type);
        return nbt == null ? null : loadEntityAvatar(entity, nbt);
    }

    // tries to get data from an entity
    public static Avatar getAvatar(Entity entity) {
        if (panic || Minecraft.getInstance().level == null || entity == null)
            return null;

        UUID uuid = entity.getUUID();

        // load from player (fetch backend) if is a player
        // only the players close enough to render keep their avatar loaded, the others are listed but not loaded until they come closer
        if (entity instanceof Player){
            Avatar avatar = withinLoadDistance(entity) ? getAvatarToRender(uuid) : getAvatarForPlayer(uuid);
            if (avatar != null)
                return avatar;
        }

        // otherwise check for CEM
        return getAvatarForEntity(entity);
    }

    // same as getAvatar, but returns null when the avatar should not be rendered this frame
    // (too far, out of view or blocked by focus mode), so the entity falls back to its vanilla rendering
    public static Avatar getAvatarForRender(Entity entity) {
        Avatar avatar = getAvatar(entity);
        return avatar == null || avatar.isRenderSkipped() ? null : avatar;
    }

    public static Avatar getAvatarForPlayerRender(UUID player) {
        Avatar avatar = getAvatarForPlayer(player);
        return avatar == null || avatar.isRenderSkipped() ? null : avatar;
    }

    // get a loaded avatar without fetching backend or creating a new one
    public static Avatar getLoadedAvatar(UUID owner) {
        if (panic || Minecraft.getInstance().level == null)
            return null;

        UserData user = LOADED_USERS.get(owner);
        return user == null ? null : user.getMainAvatar();
    }

    // get all main loaded avatars
    public static List<Avatar> getLoadedAvatars() {
        List<Avatar> list = new ArrayList<>();
        for (UserData user : LOADED_USERS.values()) {
            Avatar avatar = user.getMainAvatar();
            if (avatar != null && avatar.nbt != null)
                list.add(avatar);
        }
        return list;
    }

    // -- avatar management -- // 

    // removes an loaded avatar
    public static void clearAvatars(UUID id) {
        FETCHED_USERS.remove(id);

        UserData user = LOADED_USERS.get(id);
        if (user != null) user.clear();

        NetworkStuff.clear(id);
        FiguraMod.debug("Cleared avatars of " + id);
    }

    public static void clearCEMAvatars() {
        for (Avatar avatar : LOADED_CEM.values())
            avatar.clean();
        LOADED_CEM.clear();
    }

    // clears ALL loaded avatars, including local
    // active overrides are kept and their copies rebuilt on the next tick (call clearAvatarOverrides to drop them too)
    public static void clearAllAvatars() {
        for (UserData user : LOADED_USERS.values())
            user.cleanOverrideAvatar();
        for (UUID id : LOADED_USERS.keySet())
            clearAvatars(id);

        LOADED_USERS.clear();
        FETCHED_USERS.clear();
        FETCH_RETRY.clear();
        clearCEMAvatars();

        localUploaded = true;
        AvatarCulling.toggleFocus(null);
        AvatarList.selectedEntry = null;
        LocalAvatarLoader.loadAvatar(null, null);
        FiguraMod.LOGGER.info("Cleared all avatars");
    }

    // reloads an avatar
    public static void reloadAvatar(UUID id) {
        if (!localUploaded && FiguraMod.isLocal(id))
            loadLocalAvatar(LocalAvatarLoader.getLastLoadedPath());
        else
            clearAvatars(id);
    }

    // load the local player avatar
    public static void loadLocalAvatar(Path path) {
        UUID id = FiguraMod.getLocalPlayerUUID();

        // clear
        clearAvatars(id);
        FETCHED_USERS.add(id);

        // load
        UserData user = LOADED_USERS.computeIfAbsent(id, UserData::new);
        LocalAvatarLoader.loadAvatar(path, user);

        // mark as not uploaded
        localUploaded = false;
    }

    // load CEM avatar
    public static Avatar loadEntityAvatar(Entity entity, CompoundTag nbt) {
        Avatar targetAvatar = new Avatar(entity);
        targetAvatar.load(nbt);
        LOADED_CEM.put(entity, targetAvatar);
        return targetAvatar;
    }

    // set an user's avatar
    public static void setAvatar(UUID id, CompoundTag nbt) {
        try {
            UserData user = LOADED_USERS.computeIfAbsent(id, UserData::new);
            clearAvatars(id);
            user.loadAvatar(nbt);
        } catch (Exception e) {
            FiguraMod.LOGGER.error("Failed to set avatar for " + id, e);
        }
    }

    // get avatar from the backend
    private static void fetchBackend(UUID id) {
        if (FETCHED_USERS.contains(id))
            return;

        FETCHED_USERS.add(id);

        if (EntityUtils.checkInvalidPlayer(id)) {
            FiguraMod.debug("Voiding userdata for " + id);
            return;
        }

        UserData user = LOADED_USERS.computeIfAbsent(id, UserData::new);

        FiguraMod.debug("Getting userdata for " + id);
        NetworkStuff.getUser(user);
    }

    // -- avatar overrides -- //
    // public API for other mods (e.g. disguises): a player shows a copy of another player's avatar instead of their own
    // the copy belongs to the player (its scripts see the player's entity), never runs as host, uses the permissions
    // given to the source, and is rebuilt when the source changes avatar; until the source avatar is loaded (or if the
    // source has none) the player shows no avatar at all, so the own one never gives the disguise away

    // player -> source
    private static final Map<UUID, UUID> OVERRIDES = new ConcurrentHashMap<>();

    /**
     * Makes {@code player} show {@code source}'s avatar. Fetches the source's avatar from the backend if needed.
     * Setting a player as their own source clears the override.
     */
    public static void setAvatarOverride(UUID player, UUID source) {
        if (player.equals(source)) {
            clearAvatarOverride(player);
            return;
        }
        OVERRIDES.put(player, source);
        updateAvatarOverride(player, source);
    }

    /** Ends the override of {@code player}: their own avatar is shown again, without fetching it again. */
    public static void clearAvatarOverride(UUID player) {
        if (OVERRIDES.remove(player) == null)
            return;
        UserData user = LOADED_USERS.get(player);
        if (user != null)
            user.setOverride(null);
    }

    /** Ends every override, e.g. when leaving the world. */
    public static void clearAvatarOverrides() {
        for (UUID player : new ArrayList<>(OVERRIDES.keySet()))
            clearAvatarOverride(player);
    }

    /** The player whose avatar {@code player} currently shows instead of their own, or null. */
    public static UUID getAvatarOverride(UUID player) {
        return OVERRIDES.get(player);
    }

    private static void updateAvatarOverrides() {
        for (Map.Entry<UUID, UUID> entry : OVERRIDES.entrySet())
            updateAvatarOverride(entry.getKey(), entry.getValue());
    }

    // runs on the (unguarded) client tick: a failure is logged and leaves the player without a copy, it never crashes the game
    private static void updateAvatarOverride(UUID player, UUID source) {
        try {
            updateAvatarOverrideUnsafe(player, source);
        } catch (Exception e) {
            FiguraMod.LOGGER.error("Failed to update the avatar override of " + player + " (source " + source + ")", e);
        }
    }

    private static void updateAvatarOverrideUnsafe(UUID player, UUID source) {
        if (panic || Minecraft.getInstance().level == null)
            return;

        UserData user = LOADED_USERS.computeIfAbsent(player, UserData::new);
        user.setOverride(source);

        // load the source avatar like a render path would, but take its own avatar, not an override it may have
        fetchBackend(source);
        UserData sourceUser = LOADED_USERS.get(source);
        if (sourceUser == null)
            return;
        sourceUser.want();
        user.updateOverride(sourceUser.getOwnAvatar());
    }

    // the source's pings also run on the copies of its avatar, so ping driven animations show on the disguise too
    public static void forwardPingToOverrides(UUID source, int id, byte[] data) {
        for (Map.Entry<UUID, UUID> entry : OVERRIDES.entrySet()) {
            if (!entry.getValue().equals(source))
                continue;
            UserData user = LOADED_USERS.get(entry.getKey());
            Avatar copy = user == null ? null : user.getMainAvatar();
            if (copy == null)
                continue;
            try {
                copy.runPing(id, data);
            } catch (Exception e) {
                FiguraMod.LOGGER.error("Failed to forward a ping of " + source + " to the avatar override of " + entry.getKey(), e);
            }
        }
    }

    // the backend fetch of this user failed, try again in 5 seconds
    public static void fetchFailed(UUID id) {
        FETCH_RETRY.put(id, FiguraMod.ticks + 100);
    }

    // -- badges -- // 

    public static Pair<BitSet, BitSet> getBadges(UUID id) {
        UserData user = LOADED_USERS.get(id);
        if (user == null)
            return null;

        Pair<BitSet, BitSet> badges = user.getBadges();
        if (badges != null)
            return badges;

        badges = Badges.emptyBadges();
        user.loadBadges(badges);
        return badges;
    }

    // -- command -- // 

    public static LiteralArgumentBuilder<FiguraClientCommandSource> getCommand() {
        // root
        LiteralArgumentBuilder<FiguraClientCommandSource> root = LiteralArgumentBuilder.literal("set_avatar");

        // source
        RequiredArgumentBuilder<FiguraClientCommandSource, String> target = RequiredArgumentBuilder.argument("target", StringArgumentType.word());

        // target
        RequiredArgumentBuilder<FiguraClientCommandSource, String> source = RequiredArgumentBuilder.argument("source", StringArgumentType.word());
        source.executes(context -> {
            String s = StringArgumentType.getString(context, "source");
            String t = StringArgumentType.getString(context, "target");

            UUID sourceUUID, targetUUID;
            try {
                sourceUUID = UUID.fromString(s);
                targetUUID = UUID.fromString(t);
            } catch (Exception e) {
                context.getSource().figura$sendError(Component.literal("Failed to parse uuids"));
                return 0;
            }

            UserData user = LOADED_USERS.get(sourceUUID);
            Avatar avatar = user == null ? null : user.getMainAvatar();
            if (avatar == null || avatar.nbt == null) {
                context.getSource().figura$sendError(Component.literal("No source Avatar found"));
                return 0;
            }

            if (LOADED_USERS.get(targetUUID) != null) {
                if (FiguraMod.isLocal(targetUUID)) {
                    context.getSource().figura$sendError(Component.literal("Cannot set your own avatar this way; use '/figura load' instead"));
                    return 0;
                }
                setAvatar(targetUUID, avatar.nbt);
                context.getSource().figura$sendFeedback(Component.literal("Set avatar for " + t));
                return 1;
            }

            Entity targetEntity = EntityUtils.getEntityByUUID(targetUUID);
            if (targetEntity == null) {
                context.getSource().figura$sendError(Component.literal("Target entity not found"));
                return 0;
            }

            loadEntityAvatar(targetEntity, avatar.nbt);
            return 1;
        });
        target.then(source);

        // build root
        root.then(target);
        return root;
    }
}

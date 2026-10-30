package org.figuramc.figura.avatar;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.compat.EpicFightPoseThrottle;
import org.figuramc.figura.config.Configs;
import org.figuramc.figura.model.FiguraModelPartReader;
import org.figuramc.figura.model.rendering.AvatarRenderer;
import org.figuramc.figura.model.rendering.ImmediateAvatarRenderer;
import org.figuramc.figura.permissions.Permissions;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

/**
 * Decides, once per frame, which avatars of other players are skipped.
 * A skipped avatar is not rendered (the player falls back to the vanilla skin) and its scripts are throttled.
 * <p>
 * An avatar is skipped when:
 * - focus mode is on and it is not one of the focused players
 * - its player is further away than the avatar render distance
 * - its player is outside the camera view, or fully hidden behind a closer player that renders the vanilla skin
 *   (for avatars allowed to render off-screen, their world parts must be out of view or hidden too)
 * <p>
 * The host avatar and the avatar of the camera entity are never skipped.
 */
public class AvatarCulling {

    // only the players closer than this are considered as occluders
    private static final double OCCLUDER_RANGE = 48;
    // the vanilla player model always fills a column this wide (in blocks) around its center, when seen from any side
    private static final double OCCLUDER_RADIUS = 0.2;
    // margin added to a hidden player bounding box, since avatars often go a bit outside of it
    private static final double OCCLUDEE_MARGIN = 0.25;
    // margin added for the camera view test, for avatars with big models
    private static final double FRUSTUM_MARGIN = 2;
    // a bit bigger margin for the camera view test of avatars allowed to render off-screen, since they are often bigger than their hitbox
    // it must stay small: the box is inflated on every side, so a big margin reaches the camera and keeps every nearby avatar in view,
    // even the ones right behind it (an 8 blocks margin meant no avatar within ~8 blocks was ever culled)
    private static final double OFFSCREEN_FRUSTUM_MARGIN = 3;
    // bigger margin for the occlusion test of avatars allowed to render off-screen, this one only makes occlusion more careful
    private static final double OFFSCREEN_OCCLUDEE_MARGIN = 8;
    // how long the last known position of the world parts of a culled avatar is trusted, see worldPartsBox
    private static final int WORLD_BOUNDS_TICKS = 20;

    private static final Set<UUID> FOCUSED = new HashSet<>();
    private static final Set<UUID> FULL_DETAIL = new HashSet<>();

    private static final List<Avatar> AVATARS = new ArrayList<>();
    private static final List<double[]> OCCLUDERS = new ArrayList<>();
    private static final List<CrowdEntry> CROWD = new ArrayList<>();
    private static final Matrix4f VIEW_PROJ = new Matrix4f();
    private static final Vector4f TEMP = new Vector4f();
    private static float projScaleX;
    private static double camX, camY, camZ;

    // -- focus mode -- //

    public static boolean isFocused(UUID id) {
        return FOCUSED.contains(id);
    }

    public static boolean isFocusOn() {
        return !FOCUSED.isEmpty();
    }

    /**
     * Add the given player to the focused players, or remove it if it was already focused.
     * Focus mode is on while at least one player is focused, a null id clears every focused player.
     * Focus mode never touches permissions, so turning it off brings every avatar back as it was.
     * @return if the player is now focused
     */
    public static boolean toggleFocus(UUID id) {
        if (id == null) {
            FOCUSED.clear();
            return false;
        }
        if (FOCUSED.remove(id))
            return false;
        FOCUSED.add(id);
        return true;
    }

    // -- full detail -- //

    /**
     * Full detail players are never simplified: no Body Detail Reduction, Simplify Avatar Bodies, distance or crowd detail loss.
     * Their scripts are never throttled either: no render script interval or budget, tick script budget or culled tick interval.
     * Unlike focus, they are still culled.
     */
    public static boolean isFullDetail(UUID id) {
        return FULL_DETAIL.contains(id);
    }

    /**
     * Add the given player to the full detail players, or remove it if it already was one.
     * @return if the player is now drawn at full detail
     */
    public static boolean toggleFullDetail(UUID id) {
        if (FULL_DETAIL.remove(id))
            return false;
        FULL_DETAIL.add(id);
        return true;
    }

    // -- per frame update -- //

    public static void update(PoseStack poseStack, Matrix4f projection, Camera camera) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null)
            return;

        cull(level, poseStack, projection, camera);
        collectCrowd(level);
        limitCrowdDetail();
        throttleRenderScripts();
        throttleRedraws();
        EpicFightPoseThrottle.update(level, camX, camY, camZ);
    }

    // weight of the newest measure in the render script cost estimate
    private static final double COST_SMOOTHING = 0.2;
    // how fast the estimate decays when the scripts ran but took no measurable time
    private static final double COST_DECAY = 0.9;
    // the budget never makes an avatar run its render scripts less than once every this many frames
    // kept low so script driven physics still looks fluid, even if that means going over the budget with many heavy avatars
    private static final int MAX_BUDGET_INTERVAL = 3;

    // the highest interval the budgets may give an avatar: the ones drawn with reduced detail (far away, or not among the closest
    // when a crowd is around) accept a slower script rate too, so the budgets can hold with many heavy avatars
    private static int maxBudgetInterval(Avatar avatar, int closest) {
        int far = Configs.FAR_SCRIPT_INTERVAL.value;
        return avatar.lodLevel > 0 && far > closest ? far : closest;
    }

    private static final List<Avatar> THROTTLED = new ArrayList<>();

    // other players' render scripts only run once every few frames
    // the interval is the fixed one from the config, raised for the expensive avatars so all of them together stay within the time budget
    // the host, camera, focused and full detail avatars always run every frame
    private static void throttleRenderScripts() {
        int fixedInterval = Math.max(Configs.AVATAR_RENDER_SCRIPT_INTERVAL.value, 1);
        double budget = Configs.AVATAR_RENDER_SCRIPT_BUDGET.value * 1_000_000d;
        Entity cameraEntity = Minecraft.getInstance().getCameraEntity();
        UUID cameraId = cameraEntity == null ? null : cameraEntity.getUUID();

        THROTTLED.clear();
        for (Avatar avatar : AVATARS) {
            measureRenderScripts(avatar);
            avatar.renderScriptsSkipped = false;
            if (!avatar.isHost && !avatar.owner.equals(cameraId) && !FOCUSED.contains(avatar.owner) && !FULL_DETAIL.contains(avatar.owner) && !avatar.isRenderSkipped())
                THROTTLED.add(avatar);
        }

        // the cost each avatar may use per frame, the most expensive ones are capped first
        double cap = budget > 0 ? budgetCap(THROTTLED, budget, avatar -> avatar.renderScriptCost) : Double.MAX_VALUE;

        for (Avatar avatar : THROTTLED) {
            int interval = fixedInterval;
            if (avatar.renderScriptCost > cap)
                interval = Math.max(interval, (int) Math.min(Math.ceil(avatar.renderScriptCost / cap), maxBudgetInterval(avatar, MAX_BUDGET_INTERVAL)));

            // runs once every interval frames on average, and keeps its own phase when the interval changes
            avatar.renderScriptCredit += 1d / interval;
            if (avatar.renderScriptCredit >= 1)
                avatar.renderScriptCredit -= Math.floor(avatar.renderScriptCredit);
            else
                avatar.renderScriptsSkipped = true;
        }

        // remembered for the next frame measure
        for (Avatar avatar : AVATARS)
            avatar.renderScriptsRan = !avatar.renderScriptsSkipped && !avatar.isRenderSkipped();
    }

    // updates the estimated cost of one frame of render scripts, from the time measured since the last frame
    // only frames where the scripts were allowed to run are measured
    private static void measureRenderScripts(Avatar avatar) {
        long measured = avatar.renderScriptNanos;
        avatar.renderScriptNanos = 0;
        if (!avatar.renderScriptsRan)
            return;

        if (measured <= 0)
            avatar.renderScriptCost *= COST_DECAY;
        else if (avatar.renderScriptCost <= 0)
            avatar.renderScriptCost = measured;
        else
            avatar.renderScriptCost += (measured - avatar.renderScriptCost) * COST_SMOOTHING;
    }

    // the highest cost each throttled avatar may use so all of them together fit the budget
    // cheaper avatars keep their full cost, the leftover is split evenly between the expensive ones
    private static double budgetCap(List<Avatar> avatars, double budget, ToDoubleFunction<Avatar> costOf) {
        avatars.sort(Comparator.comparingDouble(costOf));
        double remaining = budget;
        int count = avatars.size();
        for (int i = 0; i < count; i++) {
            double share = remaining / (count - i);
            double cost = costOf.applyAsDouble(avatars.get(i));
            if (cost > share)
                return share;
            remaining -= cost;
        }
        return Double.MAX_VALUE;
    }

    // weight of the newest measure in the tick script cost estimate, lower than for render scripts since ticks are rarer and noisier
    private static final double TICK_COST_SMOOTHING = 0.1;
    // the budget never makes an avatar run its tick scripts less than once every this many ticks
    private static final int MAX_TICK_BUDGET_INTERVAL = 4;

    private static final List<Avatar> TICK_THROTTLED = new ArrayList<>();

    // called once per tick, before the avatars tick
    // the tick scripts of other players' visible avatars run every tick while all of them together fit the tick script budget,
    // otherwise the expensive ones run less often, the same way as the render script budget
    // culled and focus blocked avatars are left to the culled tick interval, the host, camera, focused and full detail avatars always tick
    public static void throttleTickScripts(Collection<Avatar> avatars) {
        double budget = Configs.AVATAR_TICK_SCRIPT_BUDGET.value * 1_000_000d;
        Entity cameraEntity = Minecraft.getInstance().getCameraEntity();
        UUID cameraId = cameraEntity == null ? null : cameraEntity.getUUID();

        TICK_THROTTLED.clear();
        for (Avatar avatar : avatars) {
            measureTickScripts(avatar);
            avatar.tickScriptsSkipped = false;
            if (!avatar.isHost && !avatar.owner.equals(cameraId) && !FOCUSED.contains(avatar.owner) && !FULL_DETAIL.contains(avatar.owner) && !avatar.culled && !avatar.focusBlocked)
                TICK_THROTTLED.add(avatar);
        }

        if (budget <= 0)
            return;

        double cap = budgetCap(TICK_THROTTLED, budget, avatar -> avatar.tickScriptCost);
        if (cap == Double.MAX_VALUE)
            return;

        for (Avatar avatar : TICK_THROTTLED) {
            if (avatar.tickScriptCost <= cap)
                continue;
            int interval = (int) Math.min(Math.ceil(avatar.tickScriptCost / cap), maxBudgetInterval(avatar, MAX_TICK_BUDGET_INTERVAL));

            // runs once every interval ticks on average, and keeps its own phase when the interval changes
            avatar.tickScriptCredit += 1d / interval;
            if (avatar.tickScriptCredit >= 1)
                avatar.tickScriptCredit -= Math.floor(avatar.tickScriptCredit);
            else
                avatar.tickScriptsSkipped = true;
        }
    }

    // updates the estimated cost of one tick of scripts, from the last tick they ran
    private static void measureTickScripts(Avatar avatar) {
        long measured = avatar.tickScriptNanos;
        avatar.tickScriptNanos = -1;
        if (measured < 0)
            return;

        if (avatar.tickScriptCost <= 0)
            avatar.tickScriptCost = measured;
        else
            avatar.tickScriptCost += (measured - avatar.tickScriptCost) * TICK_COST_SMOOTHING;
    }

    // the rendered avatars of other players, closest first
    // the host, camera, focused and full detail avatars are never part of it (the focused ones are the only ones not blocked while focus is on)
    private static void collectCrowd(ClientLevel level) {
        CROWD.clear();
        for (Avatar avatar : AVATARS) {
            if (avatar.isRenderSkipped() || avatar.isHost || FOCUSED.contains(avatar.owner) || FULL_DETAIL.contains(avatar.owner))
                continue;
            Player player = level.getPlayerByUUID(avatar.owner);
            if (player == null || player == Minecraft.getInstance().getCameraEntity())
                continue;
            CROWD.add(new CrowdEntry(avatar, player, player.distanceToSqr(camX, camY, camZ)));
        }
        CROWD.sort(Comparator.comparingDouble(CrowdEntry::distanceSqr));
    }

    // only the closest rendered avatars keep full detail, every next group of the same size loses one detail step
    // so a crowd costs about the same no matter how close it is
    private static void limitCrowdDetail() {
        int full = Configs.FULL_DETAIL_AVATARS.value;
        if (full <= 0)
            return;

        for (int i = full; i < CROWD.size(); i++) {
            Avatar avatar = CROWD.get(i).avatar();
            avatar.lodLevel = Math.max(avatar.lodLevel, Math.min(i / full, FiguraModelPartReader.LOD_LEVELS));
        }
    }

    // only the closest players have their avatars drawn from the model every frame: the others alternate between a frame
    // drawn from the model, which is kept, and a frame that draws the kept one again, moved along with the player
    // (ImmediateAvatarRenderer frame replay), so a crowd costs about half as much to draw
    // the same frames as the Epic Fight pose throttle, so a kept pose and a repeated avatar always go together
    private static void throttleRedraws() {
        for (Avatar avatar : AVATARS)
            avatar.redrawThrottled = avatar.redrawSkipped = false;

        int full = Configs.FULL_RATE_ANIMATIONS.value;
        if (!Configs.REPLAY_CROWD_AVATARS.value || full <= 0)
            return;

        long frame = Avatar.getLevelFrame();
        for (int i = full; i < CROWD.size(); i++) {
            CrowdEntry entry = CROWD.get(i);
            Avatar avatar = entry.avatar();
            avatar.redrawThrottled = true;

            // only right after a frame drawn from the model, so the avatar is never more than one frame behind
            if (((frame + entry.player().getId()) & 1) != 0
                    && avatar.renderer instanceof ImmediateAvatarRenderer renderer && renderer.canReplay(frame)) {
                avatar.redrawSkipped = true;
                // nothing is drawn from the model this frame, so the render scripts would have nothing to change
                avatar.renderScriptsSkipped = true;
                avatar.renderScriptsRan = false;
            }
        }
    }

    private record CrowdEntry(Avatar avatar, Player player, double distanceSqr) {}

    private static void cull(ClientLevel level, PoseStack poseStack, Matrix4f projection, Camera camera) {
        Minecraft minecraft = Minecraft.getInstance();

        AVATARS.clear();
        AvatarManager.executeAll("culling", avatar -> {
            if (avatar.entityType == EntityType.PLAYER)
                AVATARS.add(avatar);
        });

        Vec3 cam = camera.getPosition();
        camX = cam.x; camY = cam.y; camZ = cam.z;

        Matrix4f pose = poseStack.last().pose();
        Frustum frustum = new Frustum(pose, projection);
        frustum.prepare(camX, camY, camZ);
        VIEW_PROJ.set(projection).mul(pose);
        projScaleX = projection.m00();

        Entity cameraEntity = minecraft.getCameraEntity();
        UUID cameraId = cameraEntity == null ? null : cameraEntity.getUUID();

        int maxDistance = Configs.AVATAR_RENDER_DISTANCE.value;
        double maxDistanceSqr = (double) maxDistance * maxDistance;
        boolean viewCulling = Configs.AVATAR_VIEW_CULLING.value;
        int lodDistance = Configs.AVATAR_LOD_DISTANCE.value;

        // first pass: focus, distance and camera view
        for (Avatar avatar : AVATARS) {
            boolean exempt = avatar.isHost || avatar.owner.equals(cameraId);
            boolean focused = FOCUSED.contains(avatar.owner);

            avatar.focusBlocked = !FOCUSED.isEmpty() && !exempt && !focused;
            avatar.culled = false;
            avatar.lodLevel = 0;
            if (exempt || avatar.focusBlocked)
                continue;

            Player player = level.getPlayerByUUID(avatar.owner);

            // one detail step lost every lodDistance blocks
            if (lodDistance > 0 && player != null && !FULL_DETAIL.contains(avatar.owner))
                avatar.lodLevel = (int) Math.min(Math.sqrt(player.distanceToSqr(camX, camY, camZ)) / lodDistance, FiguraModelPartReader.LOD_LEVELS);

            if (focused)
                continue;
            if (player == null) {
                // not in the world, nothing to render, so only throttle its scripts
                avatar.culled = true;
            } else if (maxDistance > 0 && player.distanceToSqr(camX, camY, camZ) > maxDistanceSqr) {
                avatar.culled = true;
            } else if (viewCulling && !inView(frustum, avatar, player)) {
                avatar.culled = true;
            }
        }

        if (!viewCulling)
            return;

        // collect the occluders: visible players that render the opaque vanilla model
        OCCLUDERS.clear();
        double rangeSqr = OCCLUDER_RANGE * OCCLUDER_RANGE;
        for (Player player : level.players()) {
            if (player.getUUID().equals(cameraId) || player.isSpectator() || player.isInvisible() || player.isSleeping())
                continue;
            if (player.distanceToSqr(camX, camY, camZ) > rangeSqr)
                continue;

            Avatar avatar = AvatarManager.getLoadedAvatar(player.getUUID());
            if (avatar != null && !avatar.isRenderSkipped())
                continue; // the avatar can look like anything (or hide the vanilla model), so it is not a safe occluder

            if (!frustum.isVisible(player.getBoundingBox()))
                continue;

            double[] rect = occluderRect(player);
            if (rect != null)
                OCCLUDERS.add(rect);
        }

        if (OCCLUDERS.isEmpty())
            return;

        // second pass: hidden behind a closer occluder
        for (Avatar avatar : AVATARS) {
            if (avatar.culled || avatar.focusBlocked || avatar.isHost || avatar.owner.equals(cameraId) || FOCUSED.contains(avatar.owner))
                continue;

            Player player = level.getPlayerByUUID(avatar.owner);
            if (player == null)
                continue;

            AABB box = player.getBoundingBox().inflate(offscreen(avatar) ? OFFSCREEN_OCCLUDEE_MARGIN : OCCLUDEE_MARGIN);
            if (offscreen(avatar) && avatar.renderer != null && avatar.renderer.hasWorldParts()) {
                AABB world = worldPartsBox(avatar);
                if (world == UNKNOWN)
                    continue;
                if (world != null)
                    box = box.minmax(world);
            }

            double[] rect = boxRect(box);
            if (rect == null)
                continue;

            for (double[] occluder : OCCLUDERS) {
                // occluder depth is its furthest point, occludee depth is its closest point
                if (occluder[4] < rect[4]
                        && occluder[0] <= rect[0] && occluder[1] >= rect[1]
                        && occluder[2] <= rect[2] && occluder[3] >= rect[3]) {
                    avatar.culled = true;
                    break;
                }
            }
        }
    }

    private static boolean offscreen(Avatar avatar) {
        return avatar.permissions.get(Permissions.OFFSCREEN_RENDERING) == 1;
    }

    // an avatar allowed to render off-screen is still culled, just with a bigger margin around its player
    // its world parts can be drawn anywhere in the world, so they are tested on their own, from where they were last drawn
    private static boolean inView(Frustum frustum, Avatar avatar, Player player) {
        boolean offscreen = offscreen(avatar);
        if (frustum.isVisible(player.getBoundingBoxForCulling().inflate(offscreen ? OFFSCREEN_FRUSTUM_MARGIN : FRUSTUM_MARGIN)))
            return true;
        // without off-screen rendering the world parts are only drawn along with the player (vanilla culling)
        if (!offscreen || avatar.renderer == null || !avatar.renderer.hasWorldParts())
            return false;

        AABB world = worldPartsBox(avatar);
        return world == UNKNOWN || (world != null && frustum.isVisible(world));
    }

    // returned when the world parts were not drawn recently, so where they are now is unknown
    private static final AABB UNKNOWN = new AABB(0, 0, 0, 0, 0, 0);

    // box around what the world parts drew last time, with a margin, null when they drew nothing
    // world parts are only drawn while the avatar is not culled, and while culled its scripts barely run, so they rarely move
    // still, the box is trusted for WORLD_BOUNDS_TICKS only, then the avatar renders for a frame to update it
    private static AABB worldPartsBox(Avatar avatar) {
        AvatarRenderer renderer = avatar.renderer;
        int tick = renderer.worldPartBoundsTick;
        if (tick < 0 || FiguraMod.ticks - tick > WORLD_BOUNDS_TICKS)
            return UNKNOWN;

        double[] b = renderer.worldPartBounds;
        if (b == null)
            return null;
        return new AABB(b[0], b[1], b[2], b[3], b[4], b[5]).inflate(FRUSTUM_MARGIN);
    }

    // -- screen space helpers -- //
    // rects are {minX, maxX, minY, maxY, depth} in normalized device coordinates

    // projects a world point, returns false when it is too close or behind the camera
    private static boolean project(double x, double y, double z) {
        TEMP.set((float) (x - camX), (float) (y - camY), (float) (z - camZ), 1f);
        VIEW_PROJ.transform(TEMP);
        return TEMP.w > 0.05f;
    }

    // screen rect enclosing the whole box, with the depth of its closest corner
    private static double[] boxRect(AABB box) {
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE, depth = Double.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            double x = (i & 1) == 0 ? box.minX : box.maxX;
            double y = (i & 2) == 0 ? box.minY : box.maxY;
            double z = (i & 4) == 0 ? box.minZ : box.maxZ;
            if (!project(x, y, z))
                return null;
            double sx = TEMP.x / TEMP.w, sy = TEMP.y / TEMP.w;
            minX = Math.min(minX, sx); maxX = Math.max(maxX, sx);
            minY = Math.min(minY, sy); maxY = Math.max(maxY, sy);
            depth = Math.min(depth, TEMP.w);
        }
        return new double[] {minX, maxX, minY, maxY, depth};
    }

    // screen rect that is surely covered by the vanilla player model: a thin column around the center axis
    // uses the depth of its furthest point
    private static double[] occluderRect(Player player) {
        AABB box = player.getBoundingBox();
        double cx = (box.minX + box.maxX) / 2, cz = (box.minZ + box.maxZ) / 2;

        if (!project(cx, box.minY, cz))
            return null;
        double bx = TEMP.x / TEMP.w, by = TEMP.y / TEMP.w, bw = TEMP.w;

        if (!project(cx, box.maxY, cz))
            return null;
        double tx = TEMP.x / TEMP.w, ty = TEMP.y / TEMP.w, tw = TEMP.w;

        // use the widest depth so the column is never wider than the real model
        double far = Math.max(bw, tw) + OCCLUDER_RADIUS;
        double halfWidth = OCCLUDER_RADIUS * projScaleX / far;

        double minX = Math.max(bx, tx) - halfWidth;
        double maxX = Math.min(bx, tx) + halfWidth;
        if (minX >= maxX)
            return null;

        return new double[] {minX, maxX, Math.min(by, ty), Math.max(by, ty), far};
    }
}

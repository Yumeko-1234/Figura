package org.figuramc.figura.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.avatar.AvatarCulling;
import org.figuramc.figura.config.Configs;
import org.figuramc.figura.ducks.EpicFightArmatureAccessor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lowers how often Epic Fight samples the animations of the players in a crowd.
 * <p>
 * An Epic Fight entity is posed every time it renders, so twice a frame with a shader pack (shadow pass and normal pass).
 * The pose only depends on the frame and delta, so an armature already posed for them is not posed again.
 * <p>
 * Only the closest players animate every frame, the others every second frame, on alternating frames so the cost is spread evenly.
 * Their position and rotation still update every frame, only their limbs move at half rate.
 * The host, camera, focused and full detail players always animate every frame.
 */
public class EpicFightPoseThrottle {

    private static final Set<Integer> FULL_RATE = new HashSet<>();
    private static final List<Player> PLAYERS = new ArrayList<>();
    private static boolean throttling;

    // called once per frame, before the entities render
    public static void update(ClientLevel level, double camX, double camY, double camZ) {
        FULL_RATE.clear();
        int full = Configs.FULL_RATE_ANIMATIONS.value;
        throttling = full > 0;
        if (!throttling)
            return;

        Minecraft minecraft = Minecraft.getInstance();
        PLAYERS.clear();
        for (Player player : level.players()) {
            if (player == minecraft.player || player == minecraft.getCameraEntity() || AvatarCulling.isFocused(player.getUUID()) || AvatarCulling.isFullDetail(player.getUUID()))
                FULL_RATE.add(player.getId());
            else
                PLAYERS.add(player);
        }

        if (PLAYERS.size() > full) {
            PLAYERS.sort(Comparator.comparingDouble(player -> player.distanceToSqr(camX, camY, camZ)));
            PLAYERS.subList(full, PLAYERS.size()).clear();
        }
        for (Player player : PLAYERS)
            FULL_RATE.add(player.getId());
        PLAYERS.clear();
    }

    /**
     * @return if the armature keeps its last pose instead of being posed again
     */
    public static boolean skipPose(Entity entity, EpicFightArmatureAccessor armature, float delta) {
        long frame = Avatar.getLevelFrame();
        long posedFrame = armature.figura$getPosedFrame();

        // already posed for this exact frame, like in the shadow pass
        if (posedFrame == frame && armature.figura$getPosedDelta() == delta)
            return true;

        // only skipped right after a frame it was posed in, so it never goes more than one frame without a pose
        boolean skip = throttling && entity instanceof Player && !FULL_RATE.contains(entity.getId())
                && posedFrame >= 0 && frame - posedFrame == 1 && ((frame + entity.getId()) & 1) != 0;

        if (!skip)
            armature.figura$setPosed(frame, delta);
        holderPoseKept = skip;
        return skip;
    }

    // -- held items -- //

    // if the entity rendering now kept its last pose because of the crowd limit, so its held GeckoLib items may keep theirs too
    private static boolean holderPoseKept;

    // called before each entity renders, and after the level, so the flag never leaks to other entities or the hands
    public static void endEntity() {
        holderPoseKept = false;
    }

    public static boolean isHolderPoseKept() {
        return holderPoseKept;
    }
}

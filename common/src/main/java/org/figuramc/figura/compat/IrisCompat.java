package org.figuramc.figura.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import org.figuramc.figura.FiguraMod;

// Iris / Oculus batched entity rendering orders the translucent render types of a frame with a graph:
// the types drawn one after another inside the same group (one group per entity) are linked, and every cycle in that graph must be broken each frame
// avatar render types sit between the vanilla ones of their player (skin, armor, held items, glint), shared by every player,
// so with many players drawn in different orders they tie the shared types into big cycles, and breaking them gets expensive
// drawing the avatar vertices in their own group keeps their order (primary before secondary) without linking them to the vanilla types around them
public class IrisCompat {

    private static final boolean AVAILABLE;

    static {
        boolean available;
        try {
            Class.forName("net.irisshaders.batchedentityrendering.impl.Groupable");
            available = true;
        } catch (Throwable ignored) {
            available = false;
        }
        AVAILABLE = available;
    }

    private static boolean failed;

    // states returned by beginAvatarGroup, to give back to endAvatarGroup
    public static final int NONE = 0, OWN_GROUP = 1, OWN_GROUP_IN_ENTITY = 2;

    // starts a group for the avatar vertices drawn to this buffer source, closing the entity group it is in
    public static int beginAvatarGroup(MultiBufferSource bufferSource) {
        if (!AVAILABLE || failed)
            return NONE;
        try {
            return Groups.begin(bufferSource);
        } catch (Throwable t) {
            fail(t);
            return NONE;
        }
    }

    // ends the avatar group, and opens a new entity group if there was one, so the rest of the entity still renders in a group
    public static void endAvatarGroup(MultiBufferSource bufferSource, int state) {
        if (state == NONE || failed)
            return;
        try {
            Groups.end(bufferSource, state);
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static final boolean API_AVAILABLE;

    static {
        boolean available;
        try {
            Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            available = true;
        } catch (Throwable ignored) {
            available = false;
        }
        API_AVAILABLE = available;
    }

    private static boolean apiFailed;

    // if Iris is drawing the shadow map right now (it draws every entity there first, then again in the normal pass)
    public static boolean isShadowPass() {
        if (!API_AVAILABLE || apiFailed)
            return false;
        try {
            return net.irisshaders.iris.api.v0.IrisApi.getInstance().isRenderingShadowPass();
        } catch (Throwable t) {
            apiFailed = true;
            FiguraMod.LOGGER.error("Failed to check for the Iris shadow pass, render scripts will run in every pass", t);
            return false;
        }
    }

    private static void fail(Throwable t) {
        // never try again if the api is not what we expected
        failed = true;
        FiguraMod.LOGGER.error("Failed to separate the avatar render types for Iris batched entity rendering, falling back to the default ordering", t);
    }

    // separate class so the Iris classes are only loaded when they are present
    private static class Groups {
        // the buffer source given to entities is a wrapper made by Iris, the groups live in the batched buffer source behind it
        private static net.irisshaders.batchedentityrendering.impl.Groupable find(MultiBufferSource bufferSource) {
            if (bufferSource instanceof net.irisshaders.batchedentityrendering.impl.Groupable groupable)
                return groupable;
            if (Minecraft.getInstance().renderBuffers().bufferSource() instanceof net.irisshaders.batchedentityrendering.impl.Groupable groupable)
                return groupable;
            return null;
        }

        static int begin(MultiBufferSource bufferSource) {
            net.irisshaders.batchedentityrendering.impl.Groupable groupable = find(bufferSource);
            if (groupable == null)
                return NONE;

            // false when a group (the entity one) was already open
            if (groupable.maybeStartGroup())
                return OWN_GROUP;

            groupable.endGroup();
            groupable.startGroup();
            return OWN_GROUP_IN_ENTITY;
        }

        static void end(MultiBufferSource bufferSource, int state) {
            net.irisshaders.batchedentityrendering.impl.Groupable groupable = find(bufferSource);
            if (groupable == null)
                return;

            groupable.endGroup();
            if (state == OWN_GROUP_IN_ENTITY)
                groupable.startGroup();
        }
    }
}

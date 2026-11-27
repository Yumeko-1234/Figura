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

    // -- what the batched entity buffers are drawn with -- //

    private static final boolean STATE_AVAILABLE;

    static {
        boolean available;
        try {
            Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
            Class.forName("net.irisshaders.batchedentityrendering.impl.FullyBufferedMultiBufferSource");
            available = true;
        } catch (Throwable ignored) {
            available = false;
        }
        STATE_AVAILABLE = available;
    }

    private static boolean stateFailed;
    public static final int NO_ENTITY_STATE = Integer.MIN_VALUE;

    // the entity id Iris writes into entity vertices right now (the entity being rendered), NO_ENTITY_STATE without Iris
    public static int currentEntity() {
        if (!STATE_AVAILABLE || stateFailed)
            return NO_ENTITY_STATE;
        try {
            return State.get();
        } catch (Throwable t) {
            stateFail(t);
            return NO_ENTITY_STATE;
        }
    }

    // makes Iris write this entity id into the vertices written from now on, returns the one to give back to restoreCurrentEntity
    public static int swapCurrentEntity(int entity) {
        if (entity == NO_ENTITY_STATE || !STATE_AVAILABLE || stateFailed)
            return NO_ENTITY_STATE;
        try {
            int prev = State.get();
            State.set(entity);
            return prev;
        } catch (Throwable t) {
            stateFail(t);
            return NO_ENTITY_STATE;
        }
    }

    public static void restoreCurrentEntity(int prev) {
        if (prev == NO_ENTITY_STATE || stateFailed)
            return;
        try {
            State.set(prev);
        } catch (Throwable t) {
            stateFail(t);
        }
    }

    // if entities draw into this buffer source during the level render: the level one, or the wrapper Iris gives them in its place
    public static boolean isLevelBufferSource(MultiBufferSource bufferSource) {
        MultiBufferSource level = Minecraft.getInstance().renderBuffers().bufferSource();
        if (bufferSource == level)
            return true;
        if (!STATE_AVAILABLE || stateFailed)
            return false;
        try {
            if (State.isWrapperOf(bufferSource, level))
                return true;
            // Iris gives each entity a lambda that only marks the render types as entity ones and passes them on, the same for
            // every entity, so it can be drawn into later as well
            // Accelerated Rendering wraps that again, per entity, in a record that hands getBuffer to it
            MultiBufferSource accelerated = acceleratedWrapped(bufferSource);
            if (accelerated != null)
                bufferSource = accelerated;
            if (bufferSource == level || State.isWrapperOf(bufferSource, level))
                return true;
            MultiBufferSource inner = entityTagWrapped(bufferSource);
            return inner != null && (inner == level || State.isWrapperOf(inner, level));
        } catch (Throwable t) {
            stateFail(t);
            return false;
        }
    }

    // the buffer source behind Iris' per entity lambda (made in EntityRenderDispatcher by its entity render context mixin), or null
    private static Class<?> entityTagClass;
    private static java.lang.reflect.Field entityTagField;

    private static boolean entityTagFailed;

    private static MultiBufferSource entityTagWrapped(MultiBufferSource bufferSource) {
        if (entityTagFailed)
            return null;
        try {
            return readEntityTagWrapped(bufferSource);
        } catch (Throwable t) {
            entityTagFailed = true;
            FiguraMod.LOGGER.warn("Failed to look behind the Iris entity buffer source, avatars of other players are walked on the render thread", t);
            return null;
        }
    }

    // Accelerated Rendering's IrisEntityAcceleratableBufferSource(wrapped, original): getBuffer only calls wrapped.getBuffer
    private static final String ACCELERATED_WRAPPER = "com.github.argon4w.acceleratedrendering.compat.iris.buffers.IrisEntityAcceleratableBufferSource";
    private static java.lang.reflect.Method acceleratedWrappedAccessor;
    private static boolean acceleratedFailed;

    private static MultiBufferSource acceleratedWrapped(MultiBufferSource bufferSource) {
        if (acceleratedFailed || !bufferSource.getClass().getName().equals(ACCELERATED_WRAPPER))
            return null;
        try {
            if (acceleratedWrappedAccessor == null) {
                java.lang.reflect.Method m = bufferSource.getClass().getMethod("wrapped");
                m.setAccessible(true);
                acceleratedWrappedAccessor = m;
            }
            return (MultiBufferSource) acceleratedWrappedAccessor.invoke(bufferSource);
        } catch (Throwable t) {
            acceleratedFailed = true;
            FiguraMod.LOGGER.warn("Failed to look behind the Accelerated Rendering entity buffer source, avatars of other players are walked on the render thread", t);
            return null;
        }
    }

    private static MultiBufferSource readEntityTagWrapped(MultiBufferSource bufferSource) throws IllegalAccessException {
        Class<?> c = bufferSource.getClass();
        if (c != entityTagClass) {
            if (!c.getName().startsWith("net.minecraft.client.renderer.entity.EntityRenderDispatcher$$Lambda"))
                return null;
            java.lang.reflect.Field[] fields = c.getDeclaredFields();
            if (fields.length != 1 || fields[0].getType() != MultiBufferSource.class)
                return null;
            fields[0].setAccessible(true);
            entityTagField = fields[0];
            entityTagClass = c;
        }
        return (MultiBufferSource) entityTagField.get(bufferSource);
    }

    private static void stateFail(Throwable t) {
        stateFailed = true;
        FiguraMod.LOGGER.error("Failed to read the Iris entity rendering state, avatars of other players are walked on the render thread", t);
    }

    // separate class so the Iris classes are only loaded when they are present
    private static class State {
        static int get() {
            return net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.getCurrentRenderedEntity();
        }

        static void set(int entity) {
            net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.setCurrentEntity(entity);
        }

        static boolean isWrapperOf(MultiBufferSource bufferSource, MultiBufferSource level) {
            return level instanceof net.irisshaders.batchedentityrendering.impl.FullyBufferedMultiBufferSource buffered
                    && buffered.getUnflushableWrapper() == bufferSource;
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

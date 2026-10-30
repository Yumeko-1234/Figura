package org.figuramc.figura.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.config.Configs;
import org.figuramc.figura.mixin.compat.GeoItemRendererAccessor;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.renderer.GeoItemRenderer;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the vertices GeckoLib items were drawn with, so they are not built again from their cubes every frame.
 * <p>
 * GeckoLib builds an item from its bones and cubes every time it renders, which is most of its cost.
 * The built vertices only depend on the state of the bones (rotation, position, scale, pivot, hidden),
 * so they are kept relative to the item, and drawn again with the current item matrix, color, light and overlay
 * while the bones did not change. That draws exactly what GeckoLib would.
 * <p>
 * When the holder is a crowd player whose Epic Fight pose was kept this frame (see EpicFightPoseThrottle),
 * the item keeps its last vertices even if its bones moved, so its moving parts animate at the same half rate as the body.
 * <p>
 * Only plain item renderers are cached: ones that draw extra things from their bones
 * (overridden bone render methods, or render layers drawing for bones) always render normally.
 */
public class GeoItemMeshCache {

    // entries not drawn for this many frames are dropped
    private static final int MAX_UNUSED_FRAMES = 600;
    private static final int MAX_ENTRIES = 512;
    // x, y, z, r, g, b, a, u, v, nx, ny, nz
    private static final int FLOATS = 12;
    // overlay, light, packed color
    private static final int INTS = 3;

    private static final Map<Key, Entry> CACHE = new HashMap<>();
    private static final Map<Class<?>, Boolean> SAFE_CLASSES = new ConcurrentHashMap<>();
    private static final Recorder RECORDER = new Recorder();
    private static long lastCleanFrame;

    private record Key(GeoRenderer<?> renderer, BakedGeoModel model, RenderType renderType, long instanceId, ItemDisplayContext perspective, boolean reRender) {}

    private static class Entry {
        float[] bones = new float[0];
        float[] floats = new float[0];
        int[] ints = new int[0];
        int vertices;
        boolean valid;
        long usedFrame;
        // what the vertices were built with, values equal to them are swapped for the current ones when drawn again
        float red, green, blue, alpha;
        int light, overlay;
    }

    // -- render -- //

    /**
     * Draws the cached vertices, or renders the bones of the model into the buffer while keeping their vertices.
     * @return false when the item can't be cached, so the caller renders it normally
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static boolean render(GeoRenderer renderer, PoseStack poseStack, GeoAnimatable animatable, BakedGeoModel model, RenderType renderType, MultiBufferSource bufferSource,
                                 VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
        if (!Configs.GECKOLIB_ITEM_CACHE.value || !(renderer instanceof GeoItemRenderer<?> itemRenderer) || RECORDER.recording || !isSafe(renderer))
            return false;

        long frame = Avatar.getLevelFrame();
        cleanUp(frame);

        Key key = new Key(renderer, model, renderType, renderer.getInstanceId(animatable), ((GeoItemRendererAccessor) itemRenderer).figura$getRenderPerspective(), isReRender);
        Entry entry = CACHE.get(key);
        if (entry == null) {
            if (CACHE.size() >= MAX_ENTRIES)
                return false;
            entry = new Entry();
            CACHE.put(key, entry);
        }
        entry.usedFrame = frame;

        float[] bones = snapshotBones(model, entry.bones);
        boolean same = bones == entry.bones;
        if (entry.valid && (same || EpicFightPoseThrottle.isHolderPoseKept())) {
            draw(entry, poseStack, buffer, renderType, packedLight, packedOverlay, red, green, blue, alpha);
            return true;
        }

        // build normally, keeping the vertices relative to the item
        entry.bones = bones;
        entry.valid = false;
        entry.red = red; entry.green = green; entry.blue = blue; entry.alpha = alpha;
        entry.light = packedLight; entry.overlay = packedOverlay;

        RECORDER.begin(entry, buffer, poseStack.last().pose(), poseStack.last().normal());
        try {
            for (GeoBone group : model.topLevelBones()) {
                renderer.renderRecursively(poseStack, animatable, group, renderType, bufferSource, RECORDER, isReRender, partialTick, packedLight,
                        packedOverlay, red, green, blue, alpha);
            }
        } finally {
            entry.valid = RECORDER.end();
        }
        return true;
    }

    // -- bone state -- //

    private static float[] TEMP_BONES = new float[64];

    // returns the given array when the bones are unchanged, otherwise a new array with their current state
    private static float[] snapshotBones(BakedGeoModel model, float[] previous) {
        int size = 0;
        for (GeoBone bone : model.topLevelBones())
            size = writeBone(bone, size);

        if (size == previous.length && Arrays.equals(TEMP_BONES, 0, size, previous, 0, size))
            return previous;
        return Arrays.copyOf(TEMP_BONES, size);
    }

    private static int writeBone(GeoBone bone, int i) {
        if (TEMP_BONES.length < i + 14)
            TEMP_BONES = Arrays.copyOf(TEMP_BONES, TEMP_BONES.length * 2);
        float[] b = TEMP_BONES;
        b[i++] = bone.getRotX(); b[i++] = bone.getRotY(); b[i++] = bone.getRotZ();
        b[i++] = bone.getPosX(); b[i++] = bone.getPosY(); b[i++] = bone.getPosZ();
        b[i++] = bone.getScaleX(); b[i++] = bone.getScaleY(); b[i++] = bone.getScaleZ();
        b[i++] = bone.getPivotX(); b[i++] = bone.getPivotY(); b[i++] = bone.getPivotZ();
        b[i++] = bone.isHidden() ? 1 : 0;
        b[i++] = bone.isHidingChildren() ? 1 : 0;
        if (!bone.isHidingChildren()) {
            for (GeoBone child : bone.getChildBones())
                i = writeBone(child, i);
        }
        return i;
    }

    // -- drawing -- //

    private static void draw(Entry entry, PoseStack poseStack, VertexConsumer buffer, RenderType renderType, int light, int overlay, float red, float green, float blue, float alpha) {
        // with shader packs, pushing the vertices in bulk is much faster than one vertex call at a time
        SodiumCompat.BulkWriter bulk = SodiumCompat.writerFor(buffer, renderType);
        if (bulk != null) {
            drawBulk(entry, poseStack, bulk, light, overlay, red, green, blue, alpha);
            bulk.flush();
            return;
        }

        Matrix4f m = poseStack.last().pose();
        Matrix3f n = poseStack.last().normal();
        float[] f = entry.floats;
        int[] in = entry.ints;

        for (int v = 0; v < entry.vertices; v++) {
            int i = v * FLOATS, j = v * INTS;
            float x = f[i], y = f[i + 1], z = f[i + 2];
            float nx = f[i + 9], ny = f[i + 10], nz = f[i + 11];

            float r = f[i + 3], g = f[i + 4], b = f[i + 5], a = f[i + 6];
            if (r == entry.red && g == entry.green && b == entry.blue && a == entry.alpha) {
                r = red; g = green; b = blue; a = alpha;
            }
            int vOverlay = in[j] == entry.overlay ? overlay : in[j];
            int vLight = in[j + 1] == entry.light ? light : in[j + 1];

            buffer.vertex(
                    m.m00() * x + m.m10() * y + m.m20() * z + m.m30(),
                    m.m01() * x + m.m11() * y + m.m21() * z + m.m31(),
                    m.m02() * x + m.m12() * y + m.m22() * z + m.m32(),
                    r, g, b, a, f[i + 7], f[i + 8], vOverlay, vLight,
                    n.m00() * nx + n.m10() * ny + n.m20() * nz,
                    n.m01() * nx + n.m11() * ny + n.m21() * nz,
                    n.m02() * nx + n.m12() * ny + n.m22() * nz);
        }
    }

    private static void drawBulk(Entry entry, PoseStack poseStack, SodiumCompat.BulkWriter bulk, int light, int overlay, float red, float green, float blue, float alpha) {
        Matrix4f m = poseStack.last().pose();
        Matrix3f n = poseStack.last().normal();
        float p00 = m.m00(), p01 = m.m01(), p02 = m.m02(), p10 = m.m10(), p11 = m.m11(), p12 = m.m12(), p20 = m.m20(), p21 = m.m21(), p22 = m.m22(), p30 = m.m30(), p31 = m.m31(), p32 = m.m32();
        float n00 = n.m00(), n01 = n.m01(), n02 = n.m02(), n10 = n.m10(), n11 = n.m11(), n12 = n.m12(), n20 = n.m20(), n21 = n.m21(), n22 = n.m22();
        int color = SodiumCompat.packColor(red, green, blue, alpha);
        float[] f = entry.floats;
        int[] in = entry.ints;

        for (int v = 0; v < entry.vertices; v++) {
            int i = v * FLOATS, j = v * INTS;
            float x = f[i], y = f[i + 1], z = f[i + 2];
            float nx = f[i + 9], ny = f[i + 10], nz = f[i + 11];

            int vColor = f[i + 3] == entry.red && f[i + 4] == entry.green && f[i + 5] == entry.blue && f[i + 6] == entry.alpha ? color : in[j + 2];
            int vOverlay = in[j] == entry.overlay ? overlay : in[j];
            int vLight = in[j + 1] == entry.light ? light : in[j + 1];

            bulk.vertex(
                    p00 * x + p10 * y + p20 * z + p30,
                    p01 * x + p11 * y + p21 * z + p31,
                    p02 * x + p12 * y + p22 * z + p32,
                    vColor, f[i + 7], f[i + 8], vOverlay, vLight,
                    SodiumCompat.packNormal(n00 * nx + n10 * ny + n20 * nz, n01 * nx + n11 * ny + n21 * nz, n02 * nx + n12 * ny + n22 * nz));
        }
    }

    // -- recording -- //

    // passes everything to the real buffer, and keeps each vertex relative to the item matrix
    private static class Recorder implements VertexConsumer {
        private final Matrix4f inversePose = new Matrix4f();
        private final Matrix3f inverseNormal = new Matrix3f();
        private VertexConsumer delegate;
        private Entry entry;
        private boolean recording, broken;
        private int count;
        private float[] floats = new float[FLOATS * 256];
        private int[] ints = new int[INTS * 256];

        // the vertex being built by the separate calls, when the full vertex call is not used
        private float px, py, pz, pr = 1, pg = 1, pb = 1, pa = 1, pu, pv, pnx, pny, pnz;
        private int pOverlay, pLight;

        void begin(Entry entry, VertexConsumer delegate, Matrix4f pose, Matrix3f normal) {
            this.entry = entry;
            this.delegate = delegate;
            // an item matrix that can't be inverted (like one scaled to 0) can't have vertices relative to it
            float poseDet = pose.determinant(), normalDet = normal.determinant();
            broken = poseDet == 0 || normalDet == 0 || !Float.isFinite(poseDet) || !Float.isFinite(normalDet);
            if (!broken) {
                inversePose.set(pose).invert();
                inverseNormal.set(normal).invert();
            }
            count = 0;
            recording = true;
        }

        // @return if the vertices were kept
        boolean end() {
            recording = false;
            delegate = null;
            if (!broken) {
                entry.floats = Arrays.copyOf(floats, count * FLOATS);
                entry.ints = Arrays.copyOf(ints, count * INTS);
                entry.vertices = count;
            }
            entry = null;
            return !broken;
        }

        private void record(float x, float y, float z, float r, float g, float b, float a, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            if (broken)
                return;
            if ((count + 1) * FLOATS > floats.length) {
                floats = Arrays.copyOf(floats, floats.length * 2);
                ints = Arrays.copyOf(ints, ints.length * 2);
            }
            Matrix4f m = inversePose;
            Matrix3f n = inverseNormal;
            int i = count * FLOATS, j = count * INTS;
            floats[i] = m.m00() * x + m.m10() * y + m.m20() * z + m.m30();
            floats[i + 1] = m.m01() * x + m.m11() * y + m.m21() * z + m.m31();
            floats[i + 2] = m.m02() * x + m.m12() * y + m.m22() * z + m.m32();
            floats[i + 3] = r; floats[i + 4] = g; floats[i + 5] = b; floats[i + 6] = a;
            floats[i + 7] = u; floats[i + 8] = v;
            floats[i + 9] = n.m00() * nx + n.m10() * ny + n.m20() * nz;
            floats[i + 10] = n.m01() * nx + n.m11() * ny + n.m21() * nz;
            floats[i + 11] = n.m02() * nx + n.m12() * ny + n.m22() * nz;
            ints[j] = overlay;
            ints[j + 1] = light;
            ints[j + 2] = SodiumCompat.packColor(r, g, b, a);
            count++;
        }

        @Override
        public void vertex(float x, float y, float z, float red, float green, float blue, float alpha, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            record(x, y, z, red, green, blue, alpha, u, v, overlay, light, nx, ny, nz);
            delegate.vertex(x, y, z, red, green, blue, alpha, u, v, overlay, light, nx, ny, nz);
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            px = (float) x; py = (float) y; pz = (float) z;
            delegate.vertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            pr = r / 255f; pg = g / 255f; pb = b / 255f; pa = a / 255f;
            delegate.color(r, g, b, a);
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            pu = u; pv = v;
            delegate.uv(u, v);
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            pOverlay = (u & 0xFFFF) | (v << 16);
            delegate.overlayCoords(u, v);
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            pLight = (u & 0xFFFF) | (v << 16);
            delegate.uv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            pnx = x; pny = y; pnz = z;
            delegate.normal(x, y, z);
            return this;
        }

        @Override
        public void endVertex() {
            record(px, py, pz, pr, pg, pb, pa, pu, pv, pOverlay, pLight, pnx, pny, pnz);
            delegate.endVertex();
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
            // a default color can't be told apart from the given ones, so these vertices are not kept
            broken = true;
            delegate.defaultColor(r, g, b, a);
        }

        @Override
        public void unsetDefaultColor() {
            delegate.unsetDefaultColor();
        }
    }

    // -- safety -- //

    private static final String[] BONE_METHODS = {"renderRecursively", "renderChildBones", "renderCubesOfBone"};

    // a renderer is cached only if drawing its bones does nothing but write vertices to the given buffer
    private static boolean isSafe(GeoRenderer<?> renderer) {
        if (!SAFE_CLASSES.computeIfAbsent(renderer.getClass(), GeoItemMeshCache::classIsSafe))
            return false;
        for (GeoRenderLayer<?> layer : renderer.getRenderLayers()) {
            if (!SAFE_CLASSES.computeIfAbsent(layer.getClass(), GeoItemMeshCache::layerIsSafe))
                return false;
        }
        return true;
    }

    private static boolean classIsSafe(Class<?> clazz) {
        for (Class<?> c = clazz; c != null && c != GeoItemRenderer.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                for (String name : BONE_METHODS) {
                    if (method.getName().equals(name))
                        return false;
                }
            }
        }
        return true;
    }

    // layers only draw for bones when they override renderForBone
    private static boolean layerIsSafe(Class<?> clazz) {
        for (Class<?> c = clazz; c != null && c != GeoRenderLayer.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getName().equals("renderForBone"))
                    return false;
            }
        }
        return true;
    }

    // -- clean up -- //

    private static void cleanUp(long frame) {
        if (frame - lastCleanFrame < MAX_UNUSED_FRAMES && frame >= lastCleanFrame)
            return;
        lastCleanFrame = frame;
        for (Iterator<Entry> it = CACHE.values().iterator(); it.hasNext(); ) {
            Entry entry = it.next();
            // models baked again on resource reload are new keys, so the old ones end up here too
            if (frame - entry.usedFrame > MAX_UNUSED_FRAMES || frame < entry.usedFrame)
                it.remove();
        }
    }
}

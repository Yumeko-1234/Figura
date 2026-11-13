package org.figuramc.figura.compat;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderType;
import org.figuramc.figura.FiguraMod;

// bulk vertex writing through the Sodium vertex api (Sodium, Embeddium), the same path Sodium uses for vanilla entities
// with shader packs, the per element vertex path is slow (no fast format, extended attributes computed per vertex),
// while pushed vertices are converted in one go
public class SodiumCompat {

    private static final boolean AVAILABLE;

    static {
        boolean available;
        try {
            Class.forName("net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter");
            Class.forName("net.caffeinemc.mods.sodium.api.vertex.format.common.ModelVertex");
            available = true;
        } catch (Throwable ignored) {
            available = false;
        }
        AVAILABLE = available;
    }

    private static boolean failed;

    // the Iris / Oculus extended entity format, written directly (turned off with -Dfigura.irisDirectWrite=false)
    private static final boolean IRIS_DIRECT;
    private static boolean irisFailed;

    static {
        boolean iris = false;
        if (AVAILABLE && !"false".equalsIgnoreCase(System.getProperty("figura.irisDirectWrite"))) {
            try {
                Class.forName("net.irisshaders.iris.vertices.IrisVertexFormats");
                Class.forName("net.irisshaders.iris.vertices.NormalHelper");
                Class.forName("net.irisshaders.iris.uniforms.CapturedRenderingState");
                iris = true;
            } catch (Throwable ignored) {
            }
        }
        IRIS_DIRECT = iris;
    }

    static void irisFailed(Throwable t) {
        irisFailed = true;
        FiguraMod.LOGGER.error("Failed to write the shader pack vertex format directly, falling back to the converted path", t);
    }

    // a writer that sends its vertices to the consumer in bulk, or null if the vertices must go through the consumer
    // the writer is shared, so it must be flushed before the next call
    public static BulkWriter writerFor(VertexConsumer consumer, RenderType renderType) {
        if (!AVAILABLE || failed || renderType.format() != DefaultVertexFormat.NEW_ENTITY)
            return null;
        try {
            // with a shader pack the buffer holds the extended entity format: written directly, instead of in the entity format
            // that the buffer then converts vertex by vertex
            if (IRIS_DIRECT && !irisFailed && consumer instanceof com.mojang.blaze3d.vertex.BufferBuilder builder
                    && IrisEntityWriter.isExtended(((org.figuramc.figura.mixin.render.BufferBuilderAccessor) builder).figura$getFormat())) {
                BulkWriter writer = IrisEntityWriter.of(consumer);
                if (writer != null)
                    return writer;
            }
            return SodiumWriter.of(consumer);
        } catch (Throwable t) {
            // never try again if the api is not what we expected
            failed = true;
            FiguraMod.LOGGER.error("Failed to use the Sodium vertex writer, falling back to the vanilla vertex path", t);
            return null;
        }
    }

    // same byte conversions as the vanilla buffer builder, packed as in memory (r, g, b, a)
    public static int packColor(float r, float g, float b, float a) {
        return ((int) (r * 255f) & 0xFF) | ((int) (g * 255f) & 0xFF) << 8 | ((int) (b * 255f) & 0xFF) << 16 | ((int) (a * 255f) & 0xFF) << 24;
    }

    public static int packNormal(float x, float y, float z) {
        return packNormal(x) | packNormal(y) << 8 | packNormal(z) << 16;
    }

    private static int packNormal(float f) {
        return (int) (Math.max(-1f, Math.min(1f, f)) * 127f) & 0xFF;
    }

    public interface BulkWriter {
        void vertex(float x, float y, float z, int color, float u, float v, int overlay, int light, int normal);
        void flush();
    }

    // a bulk writer whose vertices can also be written straight to memory by worker threads (ImmediateAvatarRenderer.VertexBuffer):
    // each worker writes whole quads from its own address on, then the render thread hands all of them to the buffer at once
    public interface ParallelTarget {
        // bytes per vertex
        int stride();

        // writes vertices one after the other from address on, from any thread
        BulkWriter rawWriter(long address);

        // hands the vertices written from address on to the buffer, on the render thread
        void pushRaw(long address, int vertices);
    }

    // writes the Iris extended entity format (IrisVertexFormats.ENTITY) the way Iris' ModelToEntityVertexSerializer converts to it:
    // the entity format first (the extended format starts with the same 36 bytes), then per quad the rendered entity ids,
    // the mid texture coordinate (the average of the 4 uvs) and the tangent (NormalHelper.computeTangent of the first normal and 3 corners)
    // The buffer then takes the vertices as they are, instead of reading back and converting every one.
    // separate class so the Iris classes are only loaded when Iris is present
    private static class IrisEntityWriter implements BulkWriter, ParallelTarget {
        private static final int BATCH = 256;
        private static final int MODEL_STRIDE = net.caffeinemc.mods.sodium.api.vertex.format.common.ModelVertex.STRIDE;

        // the layout, read from the format itself; LAYOUT_OK is false if it is not what this writer expects
        private static final com.mojang.blaze3d.vertex.VertexFormat FORMAT = net.irisshaders.iris.vertices.IrisVertexFormats.ENTITY;
        private static final int STRIDE, OFFSET_IDS, OFFSET_MID_TEXTURE, OFFSET_TANGENT;
        private static final boolean LAYOUT_OK;
        private static final net.caffeinemc.mods.sodium.api.vertex.format.VertexFormatDescription DESCRIPTION;

        static {
            int stride = 0, ids = -1, mid = -1, tangent = -1;
            boolean ok;
            net.caffeinemc.mods.sodium.api.vertex.format.VertexFormatDescription description = null;
            try {
                java.util.List<com.mojang.blaze3d.vertex.VertexFormatElement> elements = FORMAT.getElements();
                com.mojang.blaze3d.vertex.VertexFormatElement[] start = {
                        DefaultVertexFormat.ELEMENT_POSITION, DefaultVertexFormat.ELEMENT_COLOR, DefaultVertexFormat.ELEMENT_UV0,
                        DefaultVertexFormat.ELEMENT_UV1, DefaultVertexFormat.ELEMENT_UV2, DefaultVertexFormat.ELEMENT_NORMAL,
                        DefaultVertexFormat.ELEMENT_PADDING
                };
                ok = elements.size() > start.length;
                int offset = 0;
                for (int i = 0; i < elements.size(); i++) {
                    com.mojang.blaze3d.vertex.VertexFormatElement e = elements.get(i);
                    if (i < start.length && e != start[i])
                        ok = false;
                    if (e == net.irisshaders.iris.vertices.IrisVertexFormats.ENTITY_ID_ELEMENT) ids = offset;
                    else if (e == net.irisshaders.iris.vertices.IrisVertexFormats.MID_TEXTURE_ELEMENT) mid = offset;
                    else if (e == net.irisshaders.iris.vertices.IrisVertexFormats.TANGENT_ELEMENT) tangent = offset;
                    offset += e.getByteSize();
                }
                stride = FORMAT.getVertexSize();
                ok &= offset == stride && ids == MODEL_STRIDE && mid >= 0 && tangent >= 0;
                description = net.caffeinemc.mods.sodium.api.vertex.format.VertexFormatRegistry.instance().get(FORMAT);
            } catch (Throwable t) {
                ok = false;
            }
            STRIDE = stride;
            OFFSET_IDS = ids;
            OFFSET_MID_TEXTURE = mid;
            OFFSET_TANGENT = tangent;
            DESCRIPTION = description;
            LAYOUT_OK = ok && description != null;
            if (!LAYOUT_OK)
                FiguraMod.LOGGER.warn("Unexpected shader pack entity vertex format, Figura avatars use the converted path");
        }

        private static IrisEntityWriter instance;

        private final long buffer = org.lwjgl.system.MemoryUtil.nmemAlloc((long) BATCH * STRIDE);
        private net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter writer;
        private int count;
        private short entity, blockEntity, item;
        // the current quad: positions and uvs of its corners
        private final float[] qx = new float[4], qy = new float[4], qz = new float[4], qu = new float[4], qv = new float[4];
        private int firstNormal;

        static boolean isExtended(com.mojang.blaze3d.vertex.VertexFormat format) {
            return format == FORMAT && LAYOUT_OK;
        }

        static IrisEntityWriter of(VertexConsumer consumer) {
            try {
                net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter writer = net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter.tryOf(consumer);
                if (writer == null)
                    return null;
                if (instance == null)
                    instance = new IrisEntityWriter();
                IrisEntityWriter w = instance;
                w.writer = writer;
                w.count = 0;
                // what the converter reads at push time, the same for the whole consume of one avatar
                net.irisshaders.iris.uniforms.CapturedRenderingState state = net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE;
                w.entity = (short) state.getCurrentRenderedEntity();
                w.blockEntity = (short) state.getCurrentRenderedBlockEntity();
                w.item = (short) state.getCurrentRenderedItem();
                return w;
            } catch (Throwable t) {
                irisFailed(t);
                return null;
            }
        }

        @Override
        public void vertex(float x, float y, float z, int color, float u, float v, int overlay, int light, int normal) {
            long ptr = buffer + (long) count * STRIDE;
            net.caffeinemc.mods.sodium.api.vertex.format.common.ModelVertex.write(ptr, x, y, z, color, u, v, overlay, light, normal);
            int corner = count & 3;
            qx[corner] = x; qy[corner] = y; qz[corner] = z; qu[corner] = u; qv[corner] = v;
            if (corner == 0)
                firstNormal = normal;
            else if (corner == 3)
                endQuad(ptr - 3L * STRIDE, firstNormal, qx, qy, qz, qu, qv, entity, blockEntity, item);
            if (++count == BATCH)
                flush();
        }

        // the per quad attributes of the 4 vertices written from quad on
        static void endQuad(long quad, int firstNormal, float[] qx, float[] qy, float[] qz, float[] qu, float[] qv, short entity, short blockEntity, short item) {
            int tangent = net.irisshaders.iris.vertices.NormalHelper.computeTangent(
                    net.caffeinemc.mods.sodium.api.util.NormI8.unpackX(firstNormal),
                    net.caffeinemc.mods.sodium.api.util.NormI8.unpackY(firstNormal),
                    net.caffeinemc.mods.sodium.api.util.NormI8.unpackZ(firstNormal),
                    qx[0], qy[0], qz[0], qu[0], qv[0],
                    qx[1], qy[1], qz[1], qu[1], qv[1],
                    qx[2], qy[2], qz[2], qu[2], qv[2]);
            float midU = (qu[0] + qu[1] + qu[2] + qu[3]) / 4f;
            float midV = (qv[0] + qv[1] + qv[2] + qv[3]) / 4f;
            for (int i = 0; i < 4; i++) {
                long p = quad + (long) i * STRIDE;
                org.lwjgl.system.MemoryUtil.memPutShort(p + OFFSET_IDS, entity);
                org.lwjgl.system.MemoryUtil.memPutShort(p + OFFSET_IDS + 2, blockEntity);
                org.lwjgl.system.MemoryUtil.memPutShort(p + OFFSET_IDS + 4, item);
                org.lwjgl.system.MemoryUtil.memPutFloat(p + OFFSET_MID_TEXTURE, midU);
                org.lwjgl.system.MemoryUtil.memPutFloat(p + OFFSET_MID_TEXTURE + 4, midV);
                org.lwjgl.system.MemoryUtil.memPutInt(p + OFFSET_TANGENT, tangent);
            }
        }

        @Override
        public void flush() {
            // whole quads only (BATCH is a multiple of 4 and avatars only push quads)
            int quads = count & ~3;
            if (quads == 0)
                return;
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                writer.push(stack, buffer, quads, DESCRIPTION);
            }
            count = 0;
        }

        @Override
        public int stride() {
            return STRIDE;
        }

        @Override
        public BulkWriter rawWriter(long address) {
            return new Raw(address, entity, blockEntity, item);
        }

        @Override
        public void pushRaw(long address, int vertices) {
            // in chunks: a consumer that feeds two buffers copies what it gets onto the 64 KiB MemoryStack
            for (int done = 0; done < vertices; done += BATCH) {
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    writer.push(stack, address + (long) done * STRIDE, Math.min(BATCH, vertices - done), DESCRIPTION);
                }
            }
        }

        // the same vertices written to memory one after the other, from any thread
        private static final class Raw implements BulkWriter {
            private final long base;
            private final short entity, blockEntity, item;
            private final float[] qx = new float[4], qy = new float[4], qz = new float[4], qu = new float[4], qv = new float[4];
            private int count, firstNormal;

            Raw(long base, short entity, short blockEntity, short item) {
                this.base = base;
                this.entity = entity;
                this.blockEntity = blockEntity;
                this.item = item;
            }

            @Override
            public void vertex(float x, float y, float z, int color, float u, float v, int overlay, int light, int normal) {
                long ptr = base + (long) count * STRIDE;
                net.caffeinemc.mods.sodium.api.vertex.format.common.ModelVertex.write(ptr, x, y, z, color, u, v, overlay, light, normal);
                int corner = count & 3;
                qx[corner] = x; qy[corner] = y; qz[corner] = z; qu[corner] = u; qv[corner] = v;
                if (corner == 0)
                    firstNormal = normal;
                else if (corner == 3)
                    endQuad(ptr - 3L * STRIDE, firstNormal, qx, qy, qz, qu, qv, entity, blockEntity, item);
                count++;
            }

            @Override
            public void flush() {}
        }
    }

    // separate class so the Sodium classes are only loaded when the api is present
    private static class SodiumWriter implements BulkWriter, ParallelTarget {
        // whole quads only: shader packs compute their extra vertex attributes per quad
        private static final int BATCH = 256;
        private static final SodiumWriter INSTANCE = new SodiumWriter();

        private final long buffer = org.lwjgl.system.MemoryUtil.nmemAlloc((long) BATCH * net.caffeinemc.mods.sodium.api.vertex.format.common.ModelVertex.STRIDE);
        private net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter writer;
        private int count;

        static SodiumWriter of(VertexConsumer consumer) {
            net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter writer = net.caffeinemc.mods.sodium.api.vertex.buffer.VertexBufferWriter.tryOf(consumer);
            if (writer == null)
                return null;
            INSTANCE.writer = writer;
            INSTANCE.count = 0;
            return INSTANCE;
        }

        @Override
        public void vertex(float x, float y, float z, int color, float u, float v, int overlay, int light, int normal) {
            net.caffeinemc.mods.sodium.api.vertex.format.common.ModelVertex.write(
                    buffer + (long) count * net.caffeinemc.mods.sodium.api.vertex.format.common.ModelVertex.STRIDE,
                    x, y, z, color, u, v, overlay, light, normal);
            if (++count == BATCH)
                flush();
        }

        @Override
        public void flush() {
            if (count == 0)
                return;
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                writer.push(stack, buffer, count, net.caffeinemc.mods.sodium.api.vertex.format.common.ModelVertex.FORMAT);
            }
            count = 0;
        }

        @Override
        public int stride() {
            return net.caffeinemc.mods.sodium.api.vertex.format.common.ModelVertex.STRIDE;
        }

        @Override
        public BulkWriter rawWriter(long address) {
            return new BulkWriter() {
                private long ptr = address;

                @Override
                public void vertex(float x, float y, float z, int color, float u, float v, int overlay, int light, int normal) {
                    net.caffeinemc.mods.sodium.api.vertex.format.common.ModelVertex.write(ptr, x, y, z, color, u, v, overlay, light, normal);
                    ptr += net.caffeinemc.mods.sodium.api.vertex.format.common.ModelVertex.STRIDE;
                }

                @Override
                public void flush() {}
            };
        }

        @Override
        public void pushRaw(long address, int vertices) {
            // in chunks: a consumer that feeds two buffers (glowing outline) copies what it gets onto the 64 KiB MemoryStack
            for (int done = 0; done < vertices; done += BATCH) {
                try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    writer.push(stack, address + (long) done * net.caffeinemc.mods.sodium.api.vertex.format.common.ModelVertex.STRIDE,
                            Math.min(BATCH, vertices - done), net.caffeinemc.mods.sodium.api.vertex.format.common.ModelVertex.FORMAT);
                }
            }
        }
    }
}

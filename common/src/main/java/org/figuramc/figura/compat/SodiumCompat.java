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

    // a writer that sends its vertices to the consumer in bulk, or null if the vertices must go through the consumer
    // the writer is shared, so it must be flushed before the next call
    public static BulkWriter writerFor(VertexConsumer consumer, RenderType renderType) {
        if (!AVAILABLE || failed || renderType.format() != DefaultVertexFormat.NEW_ENTITY)
            return null;
        try {
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

    // separate class so the Sodium classes are only loaded when the api is present
    private static class SodiumWriter implements BulkWriter {
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
    }
}

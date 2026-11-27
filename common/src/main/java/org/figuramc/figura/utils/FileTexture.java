package org.figuramc.figura.utils;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

public class FileTexture extends DynamicTexture {

    private final ResourceLocation id;

    private FileTexture(NativeImage image, ResourceLocation id) {
        super(image);
        this.id = id;

        Minecraft.getInstance().getTextureManager().register(id, this);
    }

    public static FileTexture of(Path path) throws IOException {
        String s = path.toString();
        ResourceLocation resourceLocation = new FiguraIdentifier("file/" + FiguraIdentifier.formatPath(s));
        return new FileTexture(readImage(path), resourceLocation);
    }

    public static NativeImage readImage(Path path) throws IOException {
        return decode(IOUtils.readFileBytes(path));
    }

    /**
     * Decodes an image file (png) held in a byte array.
     * The bytes are copied to memory freed by hand after the decode, like vanilla's TextureUtil.readResource. A BufferUtils
     * buffer was used before: its memory is freed by the garbage collector once the buffer object is unreachable, and nothing
     * keeps it reachable while stb_image reads from its address, so a GC during the decode freed the bytes under it
     * (EXCEPTION_ACCESS_VIOLATION in lwjgl_stb.dll, stbi_load_from_memory, while an avatar loaded).
     */
    public static NativeImage decode(byte[] bytes) throws IOException {
        ByteBuffer buffer = MemoryUtil.memAlloc(Math.max(bytes.length, 1));
        try {
            buffer.put(bytes);
            buffer.flip();
            return NativeImage.read(buffer);
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    public ResourceLocation getLocation() {
        return id;
    }
}

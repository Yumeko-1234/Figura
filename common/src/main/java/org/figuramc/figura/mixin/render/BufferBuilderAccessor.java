package org.figuramc.figura.mixin.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// the format the buffer is building, shader packs swap the entity format for an extended one (see SodiumCompat)
@Mixin(BufferBuilder.class)
public interface BufferBuilderAccessor {
    @Accessor("format")
    VertexFormat figura$getFormat();
}

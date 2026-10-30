package org.figuramc.figura.mixin.compat;

import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import software.bernie.geckolib.renderer.GeoItemRenderer;

@Pseudo
@Mixin(value = GeoItemRenderer.class, remap = false)
public interface GeoItemRendererAccessor {
    @Accessor(value = "renderPerspective", remap = false)
    ItemDisplayContext figura$getRenderPerspective();
}

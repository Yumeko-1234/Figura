package org.figuramc.figura.mixin.compat;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(targets = "yesman.epicfight.world.capabilities.entitypatch.EntityPatch", remap = false)
public interface EpicFightEntityPatchInvoker {
    @Invoker(value = "getOriginal", remap = false)
    Entity figura$getOriginal();
}

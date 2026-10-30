package org.figuramc.figura.mixin.compat;

import net.minecraft.world.entity.Entity;
import org.figuramc.figura.avatar.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Figura Extra Fight renders avatars on Epic Fight players by itself, looking them up with AvatarManager.getAvatar
// so it would render (and run the render scripts of) avatars skipped by AvatarCulling, ignoring the avatar render distance
// hiding the skipped ones makes it fall back to the Epic Fight player model, like the vanilla renderer does
@Pseudo
@Mixin(targets = "com.mikihina.figuraextrafight.compat.FiguraCompat$FiguraBridge", remap = false)
public class FiguraExtraFightBridgeMixin {
    @Inject(method = "getAvatar", at = @At("RETURN"), cancellable = true, require = 0)
    private void figura$skipCulledAvatar(Entity entity, CallbackInfoReturnable<Object> cir) {
        if (cir.getReturnValue() instanceof Avatar avatar && avatar.isRenderSkipped())
            cir.setReturnValue(null);
    }
}

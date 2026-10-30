package org.figuramc.figura.mixin.compat;

import net.minecraft.world.entity.Entity;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.avatar.AvatarManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// the Epic Fight model visibility of Figura Extra Fight follows the vanilla model edits of the avatar
// a skipped avatar is not rendered, so its edits must not hide the Epic Fight model either, see FiguraExtraFightBridgeMixin
@Pseudo
@Mixin(targets = "com.mikihina.figuraextrafight.compat.FiguraCompat", remap = false)
public class FiguraExtraFightCompatMixin {

    @Redirect(
            method = {"onRenderEpicFightPlayer", "onPrepareModel", "applyArmorVisibility"},
            at = @At(value = "INVOKE", target = "Lorg/figuramc/figura/avatar/AvatarManager;getAvatar(Lnet/minecraft/world/entity/Entity;)Lorg/figuramc/figura/avatar/Avatar;"),
            require = 0
    )
    private static Avatar figura$getAvatarForRender(Entity entity) {
        return AvatarManager.getAvatarForRender(entity);
    }
}

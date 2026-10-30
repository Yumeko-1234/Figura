package org.figuramc.figura.mixin.compat;

import org.figuramc.figura.compat.EpicFightPoseThrottle;
import org.figuramc.figura.ducks.EpicFightArmatureAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// setArmaturePose samples the animations and sets the armature pose, once per render of the entity
// skipping it keeps the armature on its last pose, see EpicFightPoseThrottle
@Pseudo
@Mixin(targets = "yesman.epicfight.client.renderer.patched.entity.PatchedEntityRenderer", remap = false)
public class EpicFightPatchedEntityRendererMixin {
    @Inject(method = "setArmaturePose", at = @At("HEAD"), cancellable = true, require = 0)
    private void figura$throttlePose(@Coerce Object entityPatch, @Coerce Object armature, float partialTicks, CallbackInfo ci) {
        if (!(armature instanceof EpicFightArmatureAccessor posed) || !(entityPatch instanceof EpicFightEntityPatchInvoker patch))
            return;

        if (EpicFightPoseThrottle.skipPose(patch.figura$getOriginal(), posed, partialTicks))
            ci.cancel();
    }
}

package org.figuramc.figura.mixin.compat;

import org.figuramc.figura.ducks.EpicFightArmatureAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

// every Epic Fight entity has its own armature copy, so it keeps the last pose set on it until the next one
// see EpicFightPoseThrottle
@Pseudo
@Mixin(targets = "yesman.epicfight.api.model.Armature", remap = false)
public class EpicFightArmatureMixin implements EpicFightArmatureAccessor {

    @Unique private long figura$posedFrame = -1;
    @Unique private float figura$posedDelta;

    @Override
    public long figura$getPosedFrame() {
        return figura$posedFrame;
    }

    @Override
    public float figura$getPosedDelta() {
        return figura$posedDelta;
    }

    @Override
    public void figura$setPosed(long frame, float delta) {
        figura$posedFrame = frame;
        figura$posedDelta = delta;
    }
}

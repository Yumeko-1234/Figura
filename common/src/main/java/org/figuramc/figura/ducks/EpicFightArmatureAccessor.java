package org.figuramc.figura.ducks;

public interface EpicFightArmatureAccessor {
    // the level frame and delta this armature was last posed for, -1 when never posed
    long figura$getPosedFrame();
    float figura$getPosedDelta();
    void figura$setPosed(long frame, float delta);
}

package org.figuramc.figura.mixin.render.renderers;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.SkullModelBase;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.avatar.AvatarManager;
import org.figuramc.figura.ducks.SkullBlockRendererAccessor;
import org.figuramc.figura.lua.api.entity.EntityAPI;
import org.figuramc.figura.lua.api.world.BlockStateAPI;
import org.figuramc.figura.lua.api.world.ItemStackAPI;
import org.figuramc.figura.permissions.Permissions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SkullBlockRenderer.class)
public abstract class SkullBlockRendererMixin implements BlockEntityRenderer<SkullBlockEntity> {

    @Unique
    private static Avatar avatar;
    @Unique
    private static SkullBlockEntity block;

    @Inject(at = @At("HEAD"), method = "renderSkull", cancellable = true)
    private static void renderSkull(Direction direction, float yaw, float animationProgress, PoseStack stack, MultiBufferSource bufferSource, int light, SkullModelBase model, RenderType renderLayer, CallbackInfo ci) {
        // parse block and items first, so we can yeet them in case of a missed event
        SkullBlockEntity localBlock = block;
        block = null;

        ItemStack localItem = SkullBlockRendererAccessor.getItem();
        SkullBlockRendererAccessor.setItem(null);

        Entity localEntity = SkullBlockRendererAccessor.getEntity();
        SkullBlockRendererAccessor.setEntity(null);

        SkullBlockRendererAccessor.SkullRenderMode localMode = SkullBlockRendererAccessor.getRenderMode();
        SkullBlockRendererAccessor.setRenderMode(SkullBlockRendererAccessor.SkullRenderMode.OTHER);

        // avatar pointer incase avatar variable is set during render. (unlikely)
        Avatar localAvatar = avatar;
        avatar = null;

        if (localAvatar == null || localAvatar.permissions.get(Permissions.CUSTOM_SKULL) == 0)
            return;

        FiguraMod.pushProfiler(FiguraMod.MOD_ID);
        FiguraMod.pushProfiler(localAvatar);
        FiguraMod.pushProfiler("skullRender");

        // event, its args are only built for avatars listening to it
        boolean listened = localAvatar.hasListeners("SKULL_RENDER");
        BlockStateAPI b = !listened || localBlock == null ? null : new BlockStateAPI(localBlock.getBlockState(), localBlock.getBlockPos());
        ItemStackAPI i = listened && localItem != null ? ItemStackAPI.verify(localItem) : null;
        EntityAPI<?> e = listened && localEntity != null ? EntityAPI.wrap(localEntity) : null;
        String m = localMode.name();

        FiguraMod.pushProfiler(!FiguraMod.isProfilerActive() ? "skull" : localBlock != null ? localBlock.getBlockPos().toString() : String.valueOf(localItem));

        FiguraMod.pushProfiler("event");
        boolean bool = listened && localAvatar.skullRenderEvent(Minecraft.getInstance().getFrameTime(), b, i, e, m);

        // render skull :3
        FiguraMod.popPushProfiler("render");
        if (bool || localAvatar.skullRender(stack, bufferSource, light, direction, yaw))
            ci.cancel();

        FiguraMod.popProfiler(5);
    }

    @Inject(at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/blockentity/SkullBlockRenderer;renderSkull(Lnet/minecraft/core/Direction;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/model/SkullModelBase;Lnet/minecraft/client/renderer/RenderType;)V"), method = "render(Lnet/minecraft/world/level/block/entity/SkullBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V")
    public void render(SkullBlockEntity skullBlockEntity, float f, PoseStack poseStack, MultiBufferSource multiBufferSource, int i, int j, CallbackInfo ci) {
        block = skullBlockEntity;
        SkullBlockRendererAccessor.setRenderMode(SkullBlockRendererAccessor.SkullRenderMode.BLOCK);
    }

    @Override
    public boolean shouldRenderOffScreen(SkullBlockEntity blockEntity) {
    	Avatar localAvatar = avatar; // avatar pointer incase avatar variable is set during render.
    	return localAvatar == null || localAvatar.permissions == null ? BlockEntityRenderer.super.shouldRenderOffScreen(blockEntity) : localAvatar.permissions.get(Permissions.OFFSCREEN_RENDERING) == 1;
    }

    @Inject(at = @At("HEAD"), method = "getRenderType")
    private static void getRenderType(SkullBlock.Type type, GameProfile profile, CallbackInfoReturnable<RenderType> cir) {
        avatar = (profile != null && profile.getId() != null) ? AvatarManager.getAvatarToRender(profile.getId()) : null;
        // blocked by focus mode: the skull falls back to its vanilla rendering
        // (not culled ones, a skull is often far from its owner, or its owner is not even in the world)
        if (avatar != null && avatar.focusBlocked)
            avatar = null;
    }
}

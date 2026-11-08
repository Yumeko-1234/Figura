package org.figuramc.figura.model.rendering;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.datafixers.util.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.avatar.Avatar;
import org.figuramc.figura.avatar.AvatarCulling;
import org.figuramc.figura.compat.IrisCompat;
import org.figuramc.figura.compat.SodiumCompat;
import org.figuramc.figura.config.Configs;
import org.figuramc.figura.lua.api.ClientAPI;
import org.figuramc.figura.math.matrix.FiguraMat3;
import org.figuramc.figura.math.matrix.FiguraMat4;
import org.figuramc.figura.math.vector.FiguraVec3;
import org.figuramc.figura.model.*;
import org.figuramc.figura.model.rendering.texture.AvatarAtlas;
import org.figuramc.figura.model.rendering.texture.FiguraTexture;
import org.figuramc.figura.model.rendering.texture.FiguraTextureSet;
import org.figuramc.figura.model.rendering.texture.RenderTypes;
import org.figuramc.figura.model.rendertasks.RenderTask;
import org.figuramc.figura.utils.ColorUtils;
import org.figuramc.figura.utils.ui.UIHelper;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ImmediateAvatarRenderer extends AvatarRenderer {

    protected final PartCustomization.PartCustomizationStack customizationStack = new PartCustomization.PartCustomizationStack();

    public static final FiguraMat4 VIEW_TO_WORLD_MATRIX = FiguraMat4.of();
    private static final PartCustomization pivotOffsetter = new PartCustomization();
    protected static final VertexBuffer VERTEX_BUFFER = new VertexBuffer();

    // detail level used by the current render pass, 0 is full detail
    public int lodLevel;
    // if the current render pass skips small parts and uses less detail outside of the head (Simplify Avatar Bodies)
    public boolean simplifyBody;

    // if the world pass and the pivots pass have anything to do, checked again when a script changes the model structure
    // these passes walk every part of the model, but most avatars have no world parts and are rarely invisible
    private boolean needsWorldPass = true, needsPivotsPass = true;
    private int passCheckVersion = -1;

    // if the profiler is active this pass, the per-part labels are skipped otherwise
    private boolean profiling;

    // if the part render functions are skipped this pass (Other Avatars' Render Script Rate)
    private boolean skipPartScripts;
    // nanoseconds between two runs of this avatar's render scripts when they are time limited, its parts are then smoothed
    // in between (ScriptTransformInterpolator); 0 when they run every frame
    private long smoothInterval;
    // if the part render functions run in cycles this pass (time limited avatars in the world)
    private boolean cycledPartScripts;

    // if this part runs its render functions now: always for avatars that are not time limited, otherwise once per cycle of the avatar,
    // while this frame's render script budget lasts (the first part of an avatar each frame always may, so every cycle finishes);
    // a part runs its pre, mid and post render functions together
    private boolean partScriptsDue(FiguraModelPart part) {
        if (skipPartScripts)
            return false;
        if (!cycledPartScripts)
            return true;
        if (part.scriptCycle == avatar.renderCycle)
            return false;
        if (AvatarCulling.renderScriptPool <= 0 && avatar.partScriptsRanThisFrame)
            return false;
        part.scriptCycle = avatar.renderCycle;
        avatar.partScriptsRanThisFrame = true;
        return true;
    }

    // -- frame replay -- //
    // in a crowd, only the closest players are drawn from their model every frame (AvatarCulling.throttleRedraws): the others alternate
    // between a frame that walks the model and keeps everything it drew, and a frame that draws the same again (Repeat Crowd Avatar Frames)
    // what is kept stays relative to the entity root of the frame it was made in, the replay only swaps that root for the current one,
    // so the player still moves and turns every frame while its avatar animates at half rate, like the Epic Fight pose throttle
    private final FrameCache frameCache = new FrameCache();
    // if the current pass keeps what it draws for the next frame
    private boolean capturing;
    // scratch for the replay: the current root and how it differs from the kept one
    private final FiguraMat4 replayRoot = FiguraMat4.of(), replayDelta = FiguraMat4.of();
    private final FiguraMat3 replayRootNormal = FiguraMat3.of(), replayDeltaNormal = FiguraMat3.of();

    // scratch objects reused for every part, instead of allocating new ones each time
    private final FiguraMat4 voidedPosition = FiguraMat4.of(), worldMatrix = FiguraMat4.of();
    private final FiguraMat3 voidedNormal = FiguraMat3.of();
    private final VertexData primaryData = new VertexData(), secondaryData = new VertexData();
    private final FiguraVec3 pivotScratch = FiguraVec3.of();
    private final BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();

    // light of the last block sampled this pass, most parts of an avatar sit in the same one or two blocks
    private int lastLightX, lastLightY, lastLightZ, lastLight;
    private boolean hasLastLight;

    // world space box around what the current world pass draws, published to worldPartBounds when the pass ends
    // only tracked when the world matrices are up to date this pass
    private boolean trackWorldBounds;
    private final double[] passBounds = new double[6];
    private final FiguraMat4 boundsMatrix = FiguraMat4.of();

    public ImmediateAvatarRenderer(Avatar avatar) {
        super(avatar);

        // Vertex data, read model parts
        root = FiguraModelPartReader.read(avatar, avatar.nbt.getCompound("models"), textureSets, false);
        FiguraModelPartReader.buildLods(avatar, root);

        sortParts();
    }

    public void checkEmpty() {
        if (!customizationStack.isEmpty())
            throw new IllegalStateException("Customization stack not empty!");
    }

    @Override
    public int render() {
        if (!org.figuramc.figura.benchmark.FiguraBenchmark.measuring)
            return commonRender(1.5d);
        long start = System.nanoTime();
        int used = commonRender(1.5d);
        org.figuramc.figura.benchmark.FiguraBenchmark.onAvatarDraw(System.nanoTime() - start);
        return used;
    }

    @Override
    public int renderSpecialParts() {
        if (!org.figuramc.figura.benchmark.FiguraBenchmark.measuring)
            return commonRender(0);
        long start = System.nanoTime();
        int used = commonRender(0);
        org.figuramc.figura.benchmark.FiguraBenchmark.onAvatarDraw(System.nanoTime() - start);
        return used;
    }

    @Override
    public void updateMatrices() {
        // flag rendering state
        this.isRendering = true;
        profiling = FiguraMod.isProfilerActive();

        // setup root customizations
        PartCustomization customization = setupRootCustomization(1.5d);

        // Push transform
        customizationStack.push(customization);

        // world matrices
        VIEW_TO_WORLD_MATRIX.set(AvatarRenderer.worldToViewMatrix().invert());

        // calculate each part matrices
        calculatePartMatrices(root);

        // finish rendering
        customizationStack.pop();
        checkEmpty();

        this.isRendering = false;
    }

    protected int commonRender(double vertOffset) {
        // flag rendering state
        this.isRendering = true;
        profiling = FiguraMod.isProfilerActive();

        // iris fix
        int irisConfig = UIHelper.paperdoll || !ClientAPI.hasShaderPackMod() ? 0 : Configs.IRIS_COMPATIBILITY_FIX.value;
        doIrisEmissiveFix = (irisConfig >= 2 && ClientAPI.hasShaderPack()) || (avatar.renderMode != EntityRenderMode.RENDER && avatar.renderMode != EntityRenderMode.WORLD);
        offsetRenderLayers = irisConfig >= 1;

        // reduced detail only for the avatar on its player in the world, never in the gui, first person or world parts
        lodLevel = avatar.renderMode == EntityRenderMode.RENDER ? avatar.lodLevel : 0;

        // the render scripts of this avatar are skipped this frame, only for the player in the world and its world pass
        // or already ran in this frame's shadow pass, whose results this pass draws
        // smoothing only where the scripts are limited: the player in the world and its world pass (gui and first person run every frame)
        smoothInterval = (avatar.isHost && !org.figuramc.figura.benchmark.FiguraBenchmark.HOST_AS_OTHER) || !(avatar.renderMode == EntityRenderMode.RENDER || avatar.renderMode == EntityRenderMode.WORLD
                || avatar.renderMode == EntityRenderMode.OTHER) ? 0 : avatar.renderScriptInterval;

        // the part render functions already ran in this frame's shadow pass
        skipPartScripts = avatar.reusesShadowScripts();
        // time limited avatars: each part runs its render functions once per cycle, spread over frames (partScriptsDue)
        cycledPartScripts = avatar.renderScriptInterval > 0
                && (avatar.renderMode == EntityRenderMode.RENDER || avatar.renderMode == EntityRenderMode.WORLD);

        // simplified body: the player in the world and the world pass (which walks every part looking for world parts)
        // never the gui, first person, the host (unless enabled) or the focused players
        simplifyBody = Configs.SIMPLIFY_AVATAR_BODIES.value && avatar.entityType == EntityType.PLAYER
                && (avatar.renderMode == EntityRenderMode.RENDER || avatar.renderMode == EntityRenderMode.WORLD)
                && (!avatar.isHost || Configs.BODY_LOD_SELF.value)
                && !AvatarCulling.isFocused(avatar.owner) && !AvatarCulling.isFullDetail(avatar.owner);

        // custom textures (the atlas first, so the first upload of each texture already goes there)
        prepareAtlas();
        for (FiguraTextureSet set : textureSets)
            set.uploadIfNeeded();
        for (FiguraTexture texture : customTextures.values())
            texture.uploadIfDirty();

        // Set shouldRenderPivots
        int config = Configs.RENDER_DEBUG_PARTS_PIVOT.value;
        if (!Minecraft.getInstance().getEntityRenderDispatcher().shouldRenderHitBoxes() || (!avatar.isHost && config < 2))
            shouldRenderPivots = 0;
        else
            shouldRenderPivots = config;

        // frame replay, only for the model of the player in the world (the pivot indicators are debug drawing, never kept)
        boolean replayPass = avatar.renderMode == EntityRenderMode.RENDER && currentFilterScheme == PartFilterScheme.MODEL
                && shouldRenderPivots == 0 && Configs.REPLAY_CROWD_AVATARS.value;
        if (replayPass && avatar.redrawSkipped && frameCache.matches(this)) {
            int used = replayFrame(vertOffset);
            this.isRendering = false;
            if (this.dirty)
                clean();
            return used;
        }
        // (only set by the passes that may keep a frame, a nested pass must not end the keeping of the pass around it)
        if (replayPass) {
            capturing = avatar.redrawThrottled;
            if (capturing)
                frameCache.begin(this, vertOffset);
        }

        // world matrices
        if (allowMatrixUpdate)
            VIEW_TO_WORLD_MATRIX.set(AvatarRenderer.worldToViewMatrix().invert());

        // complexity
        int prev = avatar.complexity.remaining;
        int[] remainingComplexity = new int[] {prev};
        hasLastLight = false;
        primaryData.cacheValid = secondaryData.cacheValid = false;

        trackWorldBounds = currentFilterScheme == PartFilterScheme.WORLD && allowMatrixUpdate;
        if (trackWorldBounds) {
            Arrays.fill(passBounds, 0, 3, Double.MAX_VALUE);
            Arrays.fill(passBounds, 3, 6, -Double.MAX_VALUE);
        }

        // render all model parts
        if (root.customization.visible && !canSkipPass()) {
            if (currentFilterScheme.parentType.isSeparate) {
                List<FiguraModelPart> parts = separatedParts.get(currentFilterScheme.parentType);
                if (parts != null) {
                    boolean renderLayer = !currentFilterScheme.parentType.isRenderLayer;
                    if (renderLayer) {
                        PartCustomization customization = setupRootCustomization(vertOffset);
                        customizationStack.push(customization); // push root
                        customizationStack.push(root.customization); // push "models"
                    }

                    for (FiguraModelPart part : parts) {
                        if (currentFilterScheme.parentType == ParentType.Item && part != itemToRender)
                            continue;

                        boolean saved = part.savedCustomization != null;
                        if (saved) {
                            customizationStack.push(part.savedCustomization);
                            part.savedCustomization = null;
                        }

                        renderPart(part, remainingComplexity, currentFilterScheme.initialValue);

                        if (saved) customizationStack.pop();
                    }

                    if (renderLayer) {
                        customizationStack.pop(); // pop "models"
                        customizationStack.pop(); // pop root
                    }
                }
            } else {
                PartCustomization customization = setupRootCustomization(vertOffset);
                customizationStack.push(customization);
                renderPart(root, remainingComplexity, currentFilterScheme.initialValue);
                customizationStack.pop();
            }

            // push vertices to vertex consumer
            FiguraMod.pushProfiler("draw");
            FiguraMod.pushProfiler("primary");
            // own Iris group, so the avatar render types don't tie the vanilla ones into ordering cycles
            int irisGroup = IrisCompat.beginAvatarGroup(bufferSource);
            try {
                VERTEX_BUFFER.consume(true, bufferSource, capturing ? frameCache : null);
                FiguraMod.popPushProfiler("secondary");
                VERTEX_BUFFER.consume(false, bufferSource, capturing ? frameCache : null);
            } finally {
                IrisCompat.endAvatarGroup(bufferSource, irisGroup);
            }
            FiguraMod.popProfiler(2);

            // finish rendering
            checkEmpty();
        }

        if (capturing) {
            capturing = false;
            frameCache.finish(prev - Math.max(remainingComplexity[0], 0));
        }

        if (trackWorldBounds) {
            trackWorldBounds = false;
            if (passBounds[0] > passBounds[3]) {
                worldPartBounds = null;
            } else {
                if (worldPartBounds == null)
                    worldPartBounds = new double[6];
                System.arraycopy(passBounds, 0, worldPartBounds, 0, 6);
            }
            worldPartBoundsTick = FiguraMod.ticks;
        }

        this.isRendering = false;
        if (this.dirty)
            clean();

        return prev - Math.max(remainingComplexity[0], 0);
    }

    // the world and pivots passes only draw world or pivot parts, but they still run the part render functions
    // so they are only skipped when the model has neither of them and no part render functions
    private boolean canSkipPass() {
        boolean world = currentFilterScheme == PartFilterScheme.WORLD;
        if (!world && currentFilterScheme != PartFilterScheme.PIVOTS)
            return false;

        if (passCheckVersion != structureVersion) {
            passCheckVersion = structureVersion;
            needsWorldPass = needsPivotsPass = false;
            checkPasses(root);
        }

        return world ? !needsWorldPass : !needsPivotsPass;
    }

    private void checkPasses(FiguraModelPart part) {
        if (part.preRender != null || part.midRender != null || part.postRender != null) {
            needsWorldPass = needsPivotsPass = true;
            return;
        }
        if (part.parentType == ParentType.World)
            needsWorldPass = true;
        else if (part.parentType.isPivot)
            needsPivotsPass = true;
        if (needsWorldPass && needsPivotsPass)
            return;

        for (FiguraModelPart child : part.children) {
            checkPasses(child);
            if (needsWorldPass && needsPivotsPass)
                return;
        }
    }

    protected PartCustomization setupRootCustomization(double vertOffset) {
        PartCustomization customization = new PartCustomization();

        customization.setPrimaryRenderType(RenderTypes.TRANSLUCENT);
        customization.setSecondaryRenderType(RenderTypes.EMISSIVE);

        rootMatrices(customization.positionMatrix, customization.normalMatrix, vertOffset);

        customization.light = light;
        customization.alpha = alpha;
        customization.overlay = overlay;

        customization.primaryTexture = new TextureCustomization(FiguraTextureSet.OverrideType.PRIMARY, null);
        customization.secondaryTexture = new TextureCustomization(FiguraTextureSet.OverrideType.SECONDARY, null);

        return customization;
    }

    // the transform the whole model is drawn with: model units to blocks, flipped, raised by vertOffset, then the entity pose
    protected void rootMatrices(FiguraMat4 position, FiguraMat3 normal, double vertOffset) {
        position.reset();
        normal.reset();

        double s = 1.0 / 16;
        position.scale(s, s, s);
        position.rotateZ(180);
        position.translate(0, vertOffset, 0);
        normal.rotateZ(180);

        position.multiply(posMat);
        normal.multiply(normalMat);
    }

    // draws the frame kept by the last model pass again, with the entity root of this frame
    // the kept vertices, pivots, render tasks and render layer parents are all relative to the kept root, so one change of root moves them all
    private int replayFrame(double vertOffset) {
        FrameCache cache = frameCache;
        FiguraMod.pushProfiler("replay");

        // custom textures, a tick script may have changed one since
        for (FiguraTextureSet set : textureSets)
            set.uploadIfNeeded();
        for (FiguraTexture texture : customTextures.values())
            texture.uploadIfDirty();

        // current root = delta * kept root
        rootMatrices(replayRoot, replayRootNormal, vertOffset);
        replayDelta.set(cache.root);
        replayDelta.invert();
        replayDelta.multiply(replayRoot);
        replayDeltaNormal.set(cache.rootNormal);
        replayDeltaNormal.invert();
        replayDeltaNormal.multiply(replayRootNormal);
        // what is kept is moved to the current root, so it stays right if it is ever replayed again
        cache.root.set(replayRoot);
        cache.rootNormal.set(replayRootNormal);

        // the parent transforms the render layer passes (cape, elytra) draw their parts from
        for (int i = 0; i < cache.layerParts.size(); i++) {
            PartCustomization saved = cache.layerCustomizations.get(i);
            saved.positionMatrix.multiply(replayDelta);
            saved.normalMatrix.multiply(replayDeltaNormal);
            cache.layerParts.get(i).savedCustomization = saved;
        }

        // the transforms the held items, armor and parrots are drawn with
        for (int i = 0; i < cache.pivotTypes.size(); i++) {
            FiguraMat4 position = cache.pivotPositions.get(i).multiply(replayDelta);
            FiguraMat3 normal = cache.pivotNormals.get(i).multiply(replayDeltaNormal);
            ConcurrentLinkedQueue<Pair<FiguraMat4, FiguraMat3>> queue = pivotCustomizations.computeIfAbsent(cache.pivotTypes.get(i), p -> new ConcurrentLinkedQueue<>());
            queue.add(new Pair<>(position.copy(), normal.copy()));
        }

        // render tasks draw straight into the buffer source, so they are drawn again (before the vertices, like in the model pass)
        if (!cache.tasks.isEmpty()) {
            FiguraMod.pushProfiler("renderTasks");
            interceptRendersIntoFigura = false;
            for (int i = 0; i < cache.tasks.size(); i++) {
                RenderTask task = cache.tasks.get(i);
                PartCustomization parent = cache.taskCustomizations.get(i);
                parent.positionMatrix.multiply(replayDelta);
                parent.normalMatrix.multiply(replayDeltaNormal);
                if (!task.shouldRender())
                    continue;
                int light = parent.light != null ? parent.light : LightTexture.FULL_BRIGHT;
                int overlay = parent.overlay != null ? parent.overlay : OverlayTexture.NO_OVERLAY;
                customizationStack.push(parent);
                task.render(customizationStack, bufferSource, light, overlay);
                customizationStack.pop();
            }
            interceptRendersIntoFigura = true;
            FiguraMod.popProfiler();
        }

        // the vertices, moved to the current root and written like the model pass does
        FiguraMod.pushProfiler("draw");
        int irisGroup = IrisCompat.beginAvatarGroup(bufferSource);
        try {
            for (RetainedType retained : cache.types) {
                VertexConsumer vertexConsumer = bufferSource.getBuffer(retained.renderType);
                List<Batch> batches = retained.batches;
                SodiumCompat.BulkWriter bulk = SodiumCompat.writerFor(vertexConsumer, retained.renderType);
                if (bulk != null) {
                    for (Batch batch : batches) {
                        batch.retarget(replayDelta, replayDeltaNormal);
                        batch.write(bulk);
                    }
                    bulk.flush();
                } else {
                    for (Batch batch : batches) {
                        batch.retarget(replayDelta, replayDeltaNormal);
                        batch.write(vertexConsumer);
                    }
                }
            }
        } finally {
            IrisCompat.endAvatarGroup(bufferSource, irisGroup);
        }
        FiguraMod.popProfiler(2);

        checkEmpty();
        return cache.complexityUsed;
    }

    // a model pass drawn from the model may be replayed by the next frame
    public boolean canReplay(long frame) {
        return frameCache.frame == frame - 1;
    }

    protected boolean renderPart(FiguraModelPart part, int[] remainingComplexity, boolean prevPredicate) {
        push(part.name);

        PartCustomization custom = part.customization;

        // test the current filter scheme
        push("predicate");
        Boolean thisPassedPredicate = currentFilterScheme.test(part.parentType, prevPredicate);
        if (thisPassedPredicate == null || (!custom.visible)) {
            if (part.parentType.isRenderLayer) {
                // the stack reuses its entries, so keep a copy for the render layer pass
                if (part.savedCustomizationStore == null)
                    part.savedCustomizationStore = new PartCustomization();
                customizationStack.peek().copyTo(part.savedCustomizationStore);
                part.savedCustomization = part.savedCustomizationStore;
                if (capturing)
                    frameCache.keepLayerParent(part, part.savedCustomizationStore);
            }
            pop(2);
            return true;
        }

        // simplified body: small parts are skipped as if they were hidden
        if (simplifyBody && !part.lodKeep && part.lodSize < FiguraModelPartReader.SIMPLIFY_MIN_SIZE && !needsFullRender(part)) {
            // the skipped faces still count, so a simplified avatar never shows more than the full one
            remainingComplexity[0] -= part.lodFaces;
            pop(2);
            return part.lodFaces == 0 || remainingComplexity[0] > 0;
        }

        // calculate part transforms

        // calculate vanilla parent
        popPush("copyVanillaPart");
        part.applyVanillaTransforms(vanillaModelData);
        part.applyExtraTransforms(customizationStack.peek());

        // visibility
        popPush("checkVanillaVisible");
        if (!ignoreVanillaVisibility && custom.vanillaVisible != null && !custom.vanillaVisible) {
            popPush("removeVanillaTransforms");
            part.resetVanillaTransforms();
            pop(2);
            return true;
        }

        // pre, mid and post render functions run together, or not at all this pass
        boolean runScripts = (part.preRender != null || part.midRender != null || part.postRender != null) && partScriptsDue(part);

        // pre render function
        if (part.preRender != null && runScripts) {
            popPush("preRenderFunction");
            avatar.run(part.preRender, avatar.render, tickDelta, avatar.scriptContext(), part);
        }

        // recalculate stuff
        popPush("calculatePartMatrices");
        // time limited render scripts: the part shows its script transforms (or setMatrix matrix) blended between the last runs,
        // the real values come back right after, so scripts always read what they set
        long frameNanos = Avatar.getLevelFrameNanos();
        boolean smoothed = (smoothInterval > 0 || part.interpolation != null)
                && ScriptTransformInterpolator.apply(part, smoothInterval, frameNanos, part.hasVanillaOffsets());
        custom.recalculate();
        if (smoothed)
            ScriptTransformInterpolator.restore(part);
        boolean matrixSmoothed = smoothInterval > 0 && ScriptTransformInterpolator.applyMatrix(part, smoothInterval, frameNanos);

        // void blocked matrices
        // that's right, check only for previous predicate
        // (the copies are restored right after the push, before any children, so one scratch copy is enough)
        boolean voidMatrices = !allowHiddenTransforms && !prevPredicate;
        if (voidMatrices) {
            popPush("clearMatrices");
            voidedPosition.set(custom.positionMatrix);
            voidedNormal.set(custom.normalMatrix);
            custom.positionMatrix.reset();
            custom.normalMatrix.reset();
        }

        // push stack
        popPush("pushCustomizationStack");
        customizationStack.push(custom);

        // restore variables
        if (voidMatrices) {
            popPush("restoreMatrices");
            custom.positionMatrix.set(voidedPosition);
            custom.normalMatrix.set(voidedNormal);
        }
        // the stack holds the blended matrix now, the part gets its real one back
        if (matrixSmoothed)
            ScriptTransformInterpolator.restoreMatrix(part);

        if (thisPassedPredicate) {
            // recalculate world matrices
            popPush("worldMatrices");
            if (allowMatrixUpdate) {
                FiguraMat4 mat = partToWorldMatrices(custom);
                part.savedPartToWorldMat.set(mat);
            }

            // recalculate light
            popPush("calculateLight");
            Level l;
            if (custom.light != null) {
                updateLight = false;
                pivotOffsetter.light = custom.light;
            }
            else if (updateLight && (l = Minecraft.getInstance().level) != null) {
                // the part origin in world space is the translation column of its world matrix
                FiguraMat4 mat = part.savedPartToWorldMat;
                int x = Mth.floor(mat.v14), y = Mth.floor(mat.v24), z = Mth.floor(mat.v34);
                if (!hasLastLight || x != lastLightX || y != lastLightY || z != lastLightZ) {
                    lightPos.set(x, y, z);
                    lastLight = LightTexture.pack(l.getBrightness(LightLayer.BLOCK, lightPos), l.getBrightness(LightLayer.SKY, lightPos));
                    lastLightX = x; lastLightY = y; lastLightZ = z;
                    hasLastLight = true;
                }
                customizationStack.peek().light = lastLight;
            }

            if (custom.alpha != null)
                pivotOffsetter.alpha = custom.alpha;
            if (custom.overlay != null)
                pivotOffsetter.overlay = custom.overlay;
        }

        // mid render function
        if (part.midRender != null && runScripts) {
            popPush("midRenderFunction");
            avatar.run(part.midRender, avatar.render, tickDelta, avatar.scriptContext(), part);
        }

        // render this
        popPush("pushVertices");
        boolean breakRender = thisPassedPredicate && !part.pushVerticesImmediate(this, remainingComplexity);
        if (trackWorldBounds && thisPassedPredicate)
            includeVertexBounds(part);

        // render extras
        popPush("extras");
        if (!breakRender && thisPassedPredicate) {
            boolean renderPivot = shouldRenderPivots > 0;
            boolean renderTasks = !part.renderTasks.isEmpty();
            boolean renderPivotParts = part.parentType.isPivot && allowPivotParts;

            if (renderPivot || renderTasks || renderPivotParts) {
                // fix light and overlay
                PartCustomization parent = customizationStack.peek();
                int light = parent.light != null ? parent.light : LightTexture.FULL_BRIGHT;
                int overlay = parent.overlay != null ? parent.overlay : OverlayTexture.NO_OVERLAY;

                // fix pivots
                push("fixMatricesPivot");

                pivotOffsetter.setPos(custom.getPivot(pivotScratch, true));
                pivotOffsetter.recalculate();
                customizationStack.push(pivotOffsetter);

                PartCustomization peek = customizationStack.peek();

                // render pivot indicators
                if (renderPivot) {
                    popPush("renderPivotCube");
                    renderPivot(part, peek);
                }

                // render tasks
                if (renderTasks) {
                    popPush("renderTasks");
                    interceptRendersIntoFigura = false;
                    for (RenderTask task : part.renderTasks.values()) {
                        if (!task.shouldRender())
                            continue;
                        int neededComplexity = task.getComplexity();
                        if (neededComplexity > remainingComplexity[0])
                            break;
                        push(task.getName());
                        task.render(customizationStack, bufferSource, light, overlay);
                        if (trackWorldBounds)
                            includeTaskBounds(peek, task);
                        if (capturing)
                            frameCache.keepTask(task, peek);
                        remainingComplexity[0] -= neededComplexity;
                        pop();
                    }
                    interceptRendersIntoFigura = true;
                }

                // render pivot parts
                if (renderPivotParts && part.parentType.isPivot) {
                    popPush("savePivotParts");
                    savePivotTransform(part.parentType, peek);
                }

                customizationStack.pop();
                pop();
            }
        }

        // render children
        popPush("children");
        // by index instead of a copy of the list: scripts may still add or remove children while rendering
        for (int i = 0; i < part.children.size(); i++) {
            FiguraModelPart child = part.children.get(i);
            if (!renderPart(child, remainingComplexity, thisPassedPredicate)) {
                breakRender = true;
                break;
            }
        }

        // reset the parent
        popPush("removeVanillaTransforms");
        part.resetVanillaTransforms();

        // post render function
        if (part.postRender != null && runScripts) {
            popPush("postRenderFunction");
            avatar.run(part.postRender, avatar.render, tickDelta, avatar.scriptContext(), part);
        }

        // pop
        customizationStack.pop();
        pop(2);

        return !breakRender;
    }

    // profiler labels of the part walk, only when the profiler is active (every part pushes a dozen of them)
    private void push(String name) {
        if (profiling) FiguraMod.pushProfiler(name);
    }

    private void popPush(String name) {
        if (profiling) FiguraMod.popPushProfiler(name);
    }

    private void pop() {
        if (profiling) FiguraMod.popProfiler();
    }

    private void pop(int times) {
        if (profiling) FiguraMod.popProfiler(times);
    }

    // if the part or one of its children does more than drawing its own faces
    // (scripts, render tasks, the head, or special parents like pivots and world parts), so it can't be skipped
    // only called for small parts, which rarely have many children
    private static boolean needsFullRender(FiguraModelPart part) {
        ParentType type = part.parentType;
        if (part.preRender != null || part.midRender != null || part.postRender != null || !part.renderTasks.isEmpty()
                || type.isSeparate || type.isPivot || type.isRenderLayer
                || type == ParentType.Head || type == ParentType.Skull || type == ParentType.Portrait)
            return true;
        for (FiguraModelPart child : part.children) {
            if (needsFullRender(child))
                return true;
        }
        return false;
    }

    protected void renderPivot(FiguraModelPart part, PartCustomization customization) {
        boolean group = part.customization.partType == PartCustomization.PartType.GROUP;
        FiguraVec3 color = group ? ColorUtils.Colors.FIGURA_BLUE.vec : ColorUtils.Colors.AWESOME_BLUE.vec;
        double boxSize = group ? 1 / 16d : 1 / 32d;
        boxSize /= Math.max(Math.cbrt(part.savedPartToWorldMat.det()), 0.02);

        PoseStack stack = customization.copyIntoGlobalPoseStack();

        LevelRenderer.renderLineBox(stack, bufferSource.getBuffer(RenderType.LINES),
                -boxSize, -boxSize, -boxSize,
                boxSize, boxSize, boxSize,
                (float) color.x, (float) color.y, (float) color.z, 1f);
    }

    protected void savePivotTransform(ParentType parentType, PartCustomization customization) {
        FiguraMat4 currentPosMat = customization.getPositionMatrix();
        FiguraMat3 currentNormalMat = customization.getNormalMatrix();
        ConcurrentLinkedQueue<Pair<FiguraMat4, FiguraMat3>> queue = pivotCustomizations.computeIfAbsent(parentType, p -> new ConcurrentLinkedQueue<>());
        queue.add(new Pair<>(currentPosMat, currentNormalMat)); // These are COPIES, so ok to add
        if (capturing)
            frameCache.keepPivot(parentType, currentPosMat, currentNormalMat);
    }

    // the returned matrix is reused by the next call, copy it to keep it
    protected FiguraMat4 partToWorldMatrices(PartCustomization cust) {
        FiguraMat4 customizePeek = worldMatrix;
        customizePeek.set(customizationStack.peek().positionMatrix);
        customizePeek.multiply(VIEW_TO_WORLD_MATRIX);
        FiguraVec3 piv = cust.getPivot(pivotScratch, false);
        // same as multiplying by a translation matrix on the right, only the last column changes
        customizePeek.translateFirst(piv.x, piv.y, piv.z);

        return customizePeek;
    }

    // -- world part bounds -- //

    // adds the corners of the box around the part vertices, as transformed by the current top of the stack
    private void includeVertexBounds(FiguraModelPart part) {
        float[] box = part.getVertexBox();
        if (box == null)
            return;

        FiguraMat4 m = boundsMatrix;
        m.set(customizationStack.peek().positionMatrix);
        m.multiply(VIEW_TO_WORLD_MATRIX);
        for (int i = 0; i < 8; i++)
            includePoint(m, box[(i & 1) == 0 ? 0 : 3], box[(i & 2) == 0 ? 1 : 4], box[(i & 4) == 0 ? 2 : 5]);
    }

    // adds the origin of a render task, its size is left to the margin added by AvatarCulling
    private void includeTaskBounds(PartCustomization parent, RenderTask task) {
        FiguraMat4 m = boundsMatrix;
        m.set(parent.positionMatrix);
        m.multiply(VIEW_TO_WORLD_MATRIX);
        FiguraMat4 t = task.getRenderedMatrix();
        includePoint(m, t.v14, t.v24, t.v34);
    }

    private void includePoint(FiguraMat4 m, double x, double y, double z) {
        double wx = m.v11 * x + m.v12 * y + m.v13 * z + m.v14;
        double wy = m.v21 * x + m.v22 * y + m.v23 * z + m.v24;
        double wz = m.v31 * x + m.v32 * y + m.v33 * z + m.v34;
        passBounds[0] = Math.min(passBounds[0], wx); passBounds[1] = Math.min(passBounds[1], wy); passBounds[2] = Math.min(passBounds[2], wz);
        passBounds[3] = Math.max(passBounds[3], wx); passBounds[4] = Math.max(passBounds[4], wy); passBounds[5] = Math.max(passBounds[5], wz);
    }

    protected void calculatePartMatrices(FiguraModelPart part) {
        push(part.name);

        PartCustomization custom = part.customization;

        // Store old visibility, but overwrite it in case we only want to render certain parts
        push("predicate");
        Boolean thisPassedPredicate = currentFilterScheme.test(part.parentType, true);
        if (thisPassedPredicate == null) {
            pop(2);
            return;
        }

        // calculate part transforms

        // calculate vanilla parent
        popPush("copyVanillaPart");
        part.applyVanillaTransforms(vanillaModelData);
        part.applyExtraTransforms(customizationStack.peek());

        // push customization stack
        popPush("calculatePartMatrices");
        custom.recalculate();
        popPush("applyOnStack");
        customizationStack.push(custom);

        // render extras
        if (thisPassedPredicate) {
            // part to world matrices
            popPush("worldMatrices");
            FiguraMat4 mat = partToWorldMatrices(custom);
            part.savedPartToWorldMat.set(mat);
        }

        // render children
        popPush("children");
        for (FiguraModelPart child : part.children)
            calculatePartMatrices(child);

        // reset the parent
        part.resetVanillaTransforms();

        // pop
        customizationStack.pop();
        pop(2);
    }

    // cost: the complexity taken by these faces, given back when nothing is drawn
    public void pushFaces(int faceCount, int cost, int[] remainingComplexity, FiguraTextureSet textureSet, List<Vertex> vertices) {
        pushFaces(faceCount, cost, remainingComplexity, textureSet, vertices, null, null);
    }

    // packed: the same vertices as one float array (FiguraModelPart.pack), read instead of the vertex objects
    // while owner (the part they belong to) has not exposed them to scripts, both may be null
    public void pushFaces(int faceCount, int cost, int[] remainingComplexity, FiguraTextureSet textureSet, List<Vertex> vertices, float[] packed, FiguraModelPart owner) {
        // Handle cases that we can quickly
        if (faceCount == 0 || vertices.isEmpty())
            return;

        PartCustomization customization = customizationStack.peek();

        VertexData primary = getTexture(customization, textureSet, true);
        VertexData secondary = getTexture(customization, textureSet, false);

        if (primary.renderType == null && secondary.renderType == null) {
            remainingComplexity[0] += cost;
            return;
        }

        // vertices edited by time limited render scripts (cloth, hair cards): drawn blended between the last runs of the scripts
        boolean blended = false;
        if (smoothInterval > 0 && owner != null && owner.verticesExposed()) {
            packed = ScriptTransformInterpolator.blendVertices(owner, vertices, smoothInterval, Avatar.getLevelFrameNanos());
            owner = null;
            blended = true;
        }

        if (primary.renderType != null)
            pushToBuffer(faceCount, primary, customization, textureSet, vertices, packed, owner, blended);
        if (secondary.renderType != null)
            pushToBuffer(faceCount, secondary, customization, textureSet, vertices, packed, owner, blended);
    }

    private VertexData getTexture(PartCustomization customization, FiguraTextureSet textureSet, boolean primary) {
        RenderTypes types = primary ? customization.getPrimaryRenderType() : customization.getSecondaryRenderType();
        TextureCustomization texture = primary ? customization.primaryTexture : customization.secondaryTexture;
        // reused for every face batch, its values are read right away by pushToBuffer
        VertexData ret = primary ? primaryData : secondaryData;

        // most batches in a row use the same texture and render type, so the last result is kept for the rest of the pass
        // (texture customizations are immutable, a script setting a new texture makes a new one, so identity is enough)
        if (ret.cacheValid && ret.cachedTypes == types && ret.cachedTexture == texture && ret.cachedSet == textureSet) {
            ret.color = types == RenderTypes.NONE ? null : primary ? customization.color : customization.color2;
            return ret;
        }
        ret.cacheValid = true;
        ret.cachedTypes = types;
        ret.cachedTexture = texture;
        ret.cachedSet = textureSet;

        ret.renderType = null;
        ret.opaqueRenderType = null;
        ret.figuraTexture = null;
        ret.region = null;
        ret.atlasRenderType = null;
        ret.atlasOpaqueRenderType = null;
        ret.fullBright = false;
        ret.vertexOffset = 0;
        ret.color = null;
        ret.primary = false;

        if (types == RenderTypes.NONE)
            return ret;

        // get texture
        ResourceLocation id = textureSet.getOverrideTexture(avatar.owner, texture);

        // color
        ret.color = primary ? customization.color : customization.color2;

        // primary
        ret.primary = primary;

        // get render type
        ret.renderType = renderTypeFor(types, id, ret);
        if (ret.renderType == null)
            return ret;

        // avatar textures: a cutout version for when the texture has no partly transparent pixels,
        // and the same render types on the avatar atlas for when the part's uvs stay inside the texture
        FiguraTexture figuraTexture = textureSet.getFiguraTexture(texture);
        if (figuraTexture == null)
            return ret;
        ret.figuraTexture = figuraTexture;
        ret.opaqueRenderType = opaqueRenderTypeFor(types, id);

        AvatarAtlas.Region region = atlasRegion(figuraTexture);
        if (region != null && atlasCompatible(types)) {
            ResourceLocation atlasId = region.atlas.getLocation();
            ret.region = region;
            ret.atlasRenderType = renderTypeFor(types, atlasId, null);
            ret.atlasOpaqueRenderType = opaqueRenderTypeFor(types, atlasId);
        }

        return ret;
    }

    // the render type for a texture, ret (may be null) gets the vertex offset and full bright flag
    private RenderType renderTypeFor(RenderTypes types, ResourceLocation id, VertexData ret) {
        if (id != null) {
            if (translucent)
                return RenderType.itemEntityTranslucentCull(id);
            if (glowing)
                return RenderType.outline(id);
        }

        if (types == null)
            return null;

        if (ret != null && offsetRenderLayers && !ret.primary && types.isOffset())
            ret.vertexOffset = FiguraMod.VERTEX_OFFSET;

        // Switch to cutout with fullbright if the iris emissive fix is enabled
        if (doIrisEmissiveFix && types == RenderTypes.EMISSIVE) {
            if (ret != null)
                ret.fullBright = true;
            return RenderTypes.TRANSLUCENT_CULL.get(id);
        }
        return types.get(id);
    }

    // translucent entity render types and their cutout twins: the same shader, lighting and culling, both discard alpha under 0.1,
    // so with only fully opaque or fully transparent pixels they draw the same, and cutout skips the sorting of translucent geometry
    private static final boolean OPAQUE_CUTOUT = !"false".equalsIgnoreCase(System.getProperty("figura.opaqueCutout"));

    private RenderType opaqueRenderTypeFor(RenderTypes types, ResourceLocation id) {
        if (!OPAQUE_CUTOUT || id == null || translucent || glowing)
            return null;
        if (types == RenderTypes.TRANSLUCENT)
            return RenderTypes.CUTOUT.get(id);
        if (types == RenderTypes.TRANSLUCENT_CULL)
            return RenderTypes.CUTOUT_CULL.get(id);
        return null;
    }

    // the render types that sample the texture plainly by its uvs (no scrolling, blurring or screen space), so an atlas region works
    private boolean atlasCompatible(RenderTypes types) {
        if (translucent || glowing)
            return true;
        return switch (types) {
            case CUTOUT, CUTOUT_CULL, TRANSLUCENT, TRANSLUCENT_CULL, EMISSIVE, EYES -> true;
            default -> false;
        };
    }

    // blended: packed holds vertices blended this frame (ScriptTransformInterpolator), see uvsInsideTexture
    private void pushToBuffer(int faceCount, VertexData vertexData, PartCustomization customization, FiguraTextureSet textureSet, List<Vertex> vertices, float[] packed, FiguraModelPart owner, boolean blended) {
        int vertCount = Math.min(faceCount * 4, vertices.size());
        int w = textureSet.getWidth(), h = textureSet.getHeight();

        // fully opaque or fully transparent texture, drawn fully opaque: the cutout twin
        FiguraTexture figuraTexture = vertexData.figuraTexture;
        boolean opaque = figuraTexture != null && customization.alpha >= 1f && figuraTexture.getAlphaKind() == FiguraTexture.ALPHA_BINARY;

        RenderType renderType;
        AvatarAtlas.Region region = null;
        if (vertexData.region != null && uvsInsideTexture(customization, w, h, vertices, packed, owner, vertCount, blended, vertexData.region)) {
            region = vertexData.region;
            renderType = opaque && vertexData.atlasOpaqueRenderType != null ? vertexData.atlasOpaqueRenderType : vertexData.atlasRenderType;
        } else {
            if (org.figuramc.figura.benchmark.FiguraBenchmark.ENABLED)
                org.figuramc.figura.benchmark.FiguraBenchmark.onStandalone(figuraTexture == null ? "notAvatarTexture" : vertexData.region == null ? (figuraTexture.getAtlasRegion() == null ? "notInAtlas" : "incompatibleType") : blended ? "blended" : owner != null && owner.verticesExposed() ? "scriptVertices" : "uvsOutside", vertCount);
            // drawn from the texture itself: make sure it is on the GPU on its own
            if (figuraTexture != null && figuraTexture.getAtlasRegion() != null)
                figuraTexture.requestStandalone();
            renderType = opaque && vertexData.opaqueRenderType != null ? vertexData.opaqueRenderType : vertexData.renderType;
        }

        // everything the write needs is copied now, the vertices themselves are read when the buffer is consumed
        // (the customization is a copy owned by the stack, so its values are the same as when the buffer is consumed)
        VERTEX_BUFFER.add(renderType, vertexData.primary).set(
                vertCount, vertices, packed, owner, customization, w, h, vertexData, region
        );
    }

    // uv bounds (in texture pixels) of packed vertex arrays: min u, min v, max u, max v, computed once per array
    private static final Map<float[], float[]> UV_BOUNDS = new WeakHashMap<>();

    // if every uv of the batch, after the part's uv matrix, stays within the texture: then it can be drawn from an atlas region
    // vertices scripts can reach are never atlased, a script may still change their uvs before the batch is written;
    // blended vertices are (ScriptTransformInterpolator.blendVertices): their array is only rewritten by the next blend, after this
    // pass wrote the batch, so its uvs are checked as they are now (not cached, they change from frame to frame)
    private static boolean uvsInsideTexture(PartCustomization customization, int w, int h, List<Vertex> vertices, float[] packed, FiguraModelPart owner, int vertCount, boolean blended, AvatarAtlas.Region region) {
        if (packed == null || (owner != null && owner.verticesExposed()) || w <= 0 || h <= 0)
            return false;
        float[] bounds = blended ? null : UV_BOUNDS.get(packed);
        if (blended) {
            float minU = Float.MAX_VALUE, minV = Float.MAX_VALUE, maxU = -Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
            int end = Math.min(packed.length, vertCount * FiguraModelPart.PACKED_STRIDE);
            for (int o = 0; o < end; o += FiguraModelPart.PACKED_STRIDE) {
                float u = packed[o + 3], v = packed[o + 4];
                if (u < minU) minU = u;
                if (u > maxU) maxU = u;
                if (v < minV) minV = v;
                if (v > maxV) maxV = v;
            }
            bounds = BLENDED_BOUNDS;
            bounds[0] = minU; bounds[1] = minV; bounds[2] = maxU; bounds[3] = maxV;
        } else if (bounds == null) {
            float minU = Float.MAX_VALUE, minV = Float.MAX_VALUE, maxU = -Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
            for (int o = 0; o < packed.length; o += FiguraModelPart.PACKED_STRIDE) {
                float u = packed[o + 3], v = packed[o + 4];
                if (u < minU) minU = u;
                if (u > maxU) maxU = u;
                if (v < minV) minV = v;
                if (v > maxV) maxV = v;
            }
            bounds = new float[] {minU, minV, maxU, maxV};
            UV_BOUNDS.put(packed, bounds);
        }
        if (bounds[0] > bounds[2])
            return false;

        // same uv transform as Batch.set: pixels to 0..1, then the uv matrix; affine, so the corners bound it
        // (up to the region's margin past the texture, where the atlas holds what the texture's repeat wrapping shows)
        double mu = region.marginU + UV_EPSILON, mv = region.marginV + UV_EPSILON;
        FiguraMat3 t = customization.uvMatrix;
        double t11 = t.v11 / w, t12 = t.v12 / h, t13 = t.v13;
        double t21 = t.v21 / w, t22 = t.v22 / h, t23 = t.v23;
        for (int corner = 0; corner < 4; corner++) {
            double u = (corner & 1) == 0 ? bounds[0] : bounds[2];
            double v = (corner & 2) == 0 ? bounds[1] : bounds[3];
            double tu = t11 * u + t12 * v + t13, tv = t21 * u + t22 * v + t23;
            if (tu < -mu || tu > 1 + mu || tv < -mv || tv > 1 + mv)
            {
                if (org.figuramc.figura.benchmark.FiguraBenchmark.ENABLED)
                    org.figuramc.figura.benchmark.FiguraBenchmark.onUvsOutside(w, h, bounds, tu, tv, t.v11 != 1 || t.v22 != 1 || t.v13 != 0 || t.v23 != 0 || t.v12 != 0 || t.v21 != 0, owner == null ? "blended" : owner.name);
                return false;
            }
        }
        return true;
    }

    private static final double UV_EPSILON = 1e-4;
    // scratch bounds of blended vertices (render thread only)
    private static final float[] BLENDED_BOUNDS = new float[4];

    private static class VertexData {
        public RenderType renderType;
        // the cutout twin of renderType, used when the texture has no partly transparent pixels, may be null
        public RenderType opaqueRenderType;
        // the avatar texture drawn, null for skins and other textures that are not the avatar's
        public FiguraTexture figuraTexture;
        // the texture's place in the avatar atlas and the render types there, null when the atlas can't be used
        public AvatarAtlas.Region region;
        public RenderType atlasRenderType, atlasOpaqueRenderType;
        public boolean fullBright;
        public float vertexOffset;
        public FiguraVec3 color;
        public boolean primary;

        // what the values above were resolved from, cleared at the start of every pass
        // (skins load late and the translucent, glowing and iris flags change between passes)
        public boolean cacheValid;
        public RenderTypes cachedTypes;
        public TextureCustomization cachedTexture;
        public FiguraTextureSet cachedSet;
    }

    // the vertices of one face batch, with the values needed to transform them
    // pooled and reused every frame
    private static class Batch {
        private List<Vertex> vertices;
        // the same vertices packed (FiguraModelPart.pack), read instead of the objects while the owner part has not exposed them
        private float[] packed;
        private FiguraModelPart owner;
        private int vertCount;

        private double p11, p12, p13, p14, p21, p22, p23, p24, p31, p32, p33, p34, p41, p42, p43, p44;
        private double n11, n12, n13, n21, n22, n23, n31, n32, n33;
        // uv matrix with the division by the texture size (which makes uv 0 to 1) folded in
        private double t11, t12, t13, t21, t22, t23;
        private double offset;
        private float r, g, b, a;
        private int overlay, light;

        void set(int vertCount, List<Vertex> vertices, float[] packed, FiguraModelPart owner, PartCustomization customization, double w, double h, VertexData vertexData, AvatarAtlas.Region region) {
            this.vertCount = vertCount;
            this.vertices = vertices;
            this.packed = packed;
            this.owner = owner;

            FiguraMat4 p = customization.positionMatrix;
            p11 = p.v11; p12 = p.v12; p13 = p.v13; p14 = p.v14;
            p21 = p.v21; p22 = p.v22; p23 = p.v23; p24 = p.v24;
            p31 = p.v31; p32 = p.v32; p33 = p.v33; p34 = p.v34;
            p41 = p.v41; p42 = p.v42; p43 = p.v43; p44 = p.v44;

            FiguraMat3 n = customization.normalMatrix;
            n11 = n.v11; n12 = n.v12; n13 = n.v13;
            n21 = n.v21; n22 = n.v22; n23 = n.v23;
            n31 = n.v31; n32 = n.v32; n33 = n.v33;

            FiguraMat3 t = customization.uvMatrix;
            t11 = t.v11 / w; t12 = t.v12 / h; t13 = t.v13;
            t21 = t.v21 / w; t22 = t.v22 / h; t23 = t.v23;
            // drawn from the avatar atlas: the 0 to 1 uv of the texture moved into its region
            if (region != null) {
                t11 *= region.scaleU; t12 *= region.scaleU; t13 = t13 * region.scaleU + region.offsetU;
                t21 *= region.scaleV; t22 *= region.scaleV; t23 = t23 * region.scaleV + region.offsetV;
            }

            offset = vertexData.vertexOffset;
            r = (float) vertexData.color.x; g = (float) vertexData.color.y; b = (float) vertexData.color.z; a = customization.alpha;
            overlay = customization.overlay;
            light = vertexData.fullBright ? LightTexture.FULL_BRIGHT : customization.light;
        }

        // moves the batch to another root: its matrices become delta * (what they were), see ImmediateAvatarRenderer.replayFrame
        void retarget(FiguraMat4 d, FiguraMat3 dn) {
            double a11 = d.v11 * p11 + d.v12 * p21 + d.v13 * p31 + d.v14 * p41;
            double a12 = d.v11 * p12 + d.v12 * p22 + d.v13 * p32 + d.v14 * p42;
            double a13 = d.v11 * p13 + d.v12 * p23 + d.v13 * p33 + d.v14 * p43;
            double a14 = d.v11 * p14 + d.v12 * p24 + d.v13 * p34 + d.v14 * p44;

            double a21 = d.v21 * p11 + d.v22 * p21 + d.v23 * p31 + d.v24 * p41;
            double a22 = d.v21 * p12 + d.v22 * p22 + d.v23 * p32 + d.v24 * p42;
            double a23 = d.v21 * p13 + d.v22 * p23 + d.v23 * p33 + d.v24 * p43;
            double a24 = d.v21 * p14 + d.v22 * p24 + d.v23 * p34 + d.v24 * p44;

            double a31 = d.v31 * p11 + d.v32 * p21 + d.v33 * p31 + d.v34 * p41;
            double a32 = d.v31 * p12 + d.v32 * p22 + d.v33 * p32 + d.v34 * p42;
            double a33 = d.v31 * p13 + d.v32 * p23 + d.v33 * p33 + d.v34 * p43;
            double a34 = d.v31 * p14 + d.v32 * p24 + d.v33 * p34 + d.v34 * p44;

            double a41 = d.v41 * p11 + d.v42 * p21 + d.v43 * p31 + d.v44 * p41;
            double a42 = d.v41 * p12 + d.v42 * p22 + d.v43 * p32 + d.v44 * p42;
            double a43 = d.v41 * p13 + d.v42 * p23 + d.v43 * p33 + d.v44 * p43;
            double a44 = d.v41 * p14 + d.v42 * p24 + d.v43 * p34 + d.v44 * p44;

            p11 = a11; p12 = a12; p13 = a13; p14 = a14;
            p21 = a21; p22 = a22; p23 = a23; p24 = a24;
            p31 = a31; p32 = a32; p33 = a33; p34 = a34;
            p41 = a41; p42 = a42; p43 = a43; p44 = a44;

            double b11 = dn.v11 * n11 + dn.v12 * n21 + dn.v13 * n31;
            double b12 = dn.v11 * n12 + dn.v12 * n22 + dn.v13 * n32;
            double b13 = dn.v11 * n13 + dn.v12 * n23 + dn.v13 * n33;

            double b21 = dn.v21 * n11 + dn.v22 * n21 + dn.v23 * n31;
            double b22 = dn.v21 * n12 + dn.v22 * n22 + dn.v23 * n32;
            double b23 = dn.v21 * n13 + dn.v22 * n23 + dn.v23 * n33;

            double b31 = dn.v31 * n11 + dn.v32 * n21 + dn.v33 * n31;
            double b32 = dn.v31 * n12 + dn.v32 * n22 + dn.v33 * n32;
            double b33 = dn.v31 * n13 + dn.v32 * n23 + dn.v33 * n33;

            n11 = b11; n12 = b12; n13 = b13;
            n21 = b21; n22 = b22; n23 = b23;
            n31 = b31; n32 = b32; n33 = b33;
        }

        // the packed array to read this batch from, null when the vertex objects have to be read
        // (a script may have got hold of them since the batch was set, then only the objects are up to date)
        private float[] source() {
            float[] p = packed;
            return p != null && (owner == null || !owner.verticesExposed()) ? p : null;
        }

        // bulk path, when Sodium can take the vertices directly
        void write(SodiumCompat.BulkWriter bulk) {
            int color = SodiumCompat.packColor(r, g, b, a);
            float[] p = source();
            if (p != null) {
                for (int i = 0, o = 0; i < vertCount; i++, o += FiguraModelPart.PACKED_STRIDE) {
                    double vx = p[o], vy = p[o + 1], vz = p[o + 2];
                    double x = p11 * vx + p12 * vy + p13 * vz + p14;
                    double y = p21 * vx + p22 * vy + p23 * vz + p24;
                    double z = p31 * vx + p32 * vy + p33 * vz + p34;
                    if (offset != 0) {
                        double pw = p41 * vx + p42 * vy + p43 * vz + p44;
                        double len = Math.sqrt(x * x + y * y + z * z + pw * pw);
                        if (len > 0) {
                            double s = 1 + offset / len;
                            x *= s; y *= s; z *= s;
                        }
                    }
                    double nx = p[o + 5], ny = p[o + 6], nz = p[o + 7];
                    double u = p[o + 3], vv = p[o + 4];
                    bulk.vertex(
                            (float) x, (float) y, (float) z,
                            color,
                            (float) (t11 * u + t12 * vv + t13), (float) (t21 * u + t22 * vv + t23),
                            overlay, light,
                            SodiumCompat.packNormal((float) (n11 * nx + n12 * ny + n13 * nz), (float) (n21 * nx + n22 * ny + n23 * nz), (float) (n31 * nx + n32 * ny + n33 * nz))
                    );
                }
                return;
            }

            for (int i = 0; i < vertCount; i++) {
                Vertex v = vertices.get(i);
                double vx = v.x, vy = v.y, vz = v.z;
                double x = p11 * vx + p12 * vy + p13 * vz + p14;
                double y = p21 * vx + p22 * vy + p23 * vz + p24;
                double z = p31 * vx + p32 * vy + p33 * vz + p34;
                if (offset != 0) {
                    double pw = p41 * vx + p42 * vy + p43 * vz + p44;
                    double len = Math.sqrt(x * x + y * y + z * z + pw * pw);
                    if (len > 0) {
                        double s = 1 + offset / len;
                        x *= s; y *= s; z *= s;
                    }
                }
                double nx = v.nx, ny = v.ny, nz = v.nz;
                double u = v.u, vv = v.v;
                bulk.vertex(
                        (float) x, (float) y, (float) z,
                        color,
                        (float) (t11 * u + t12 * vv + t13), (float) (t21 * u + t22 * vv + t23),
                        overlay, light,
                        SodiumCompat.packNormal((float) (n11 * nx + n12 * ny + n13 * nz), (float) (n21 * nx + n22 * ny + n23 * nz), (float) (n31 * nx + n32 * ny + n33 * nz))
                );
            }
        }

        void write(VertexConsumer vertexConsumer) {
            float[] p = source();
            if (p != null) {
                for (int i = 0, o = 0; i < vertCount; i++, o += FiguraModelPart.PACKED_STRIDE) {
                    double vx = p[o], vy = p[o + 1], vz = p[o + 2];
                    double x = p11 * vx + p12 * vy + p13 * vz + p14;
                    double y = p21 * vx + p22 * vy + p23 * vz + p24;
                    double z = p31 * vx + p32 * vy + p33 * vz + p34;
                    if (offset != 0) {
                        double pw = p41 * vx + p42 * vy + p43 * vz + p44;
                        double len = Math.sqrt(x * x + y * y + z * z + pw * pw);
                        if (len > 0) {
                            double s = 1 + offset / len;
                            x *= s; y *= s; z *= s;
                        }
                    }
                    double nx = p[o + 5], ny = p[o + 6], nz = p[o + 7];
                    double u = p[o + 3], vv = p[o + 4];
                    // one call per vertex, which buffers write directly instead of element by element
                    vertexConsumer.vertex(
                            (float) x, (float) y, (float) z,
                            r, g, b, a,
                            (float) (t11 * u + t12 * vv + t13), (float) (t21 * u + t22 * vv + t23),
                            overlay, light,
                            (float) (n11 * nx + n12 * ny + n13 * nz), (float) (n21 * nx + n22 * ny + n23 * nz), (float) (n31 * nx + n32 * ny + n33 * nz)
                    );
                }
                return;
            }

            for (int i = 0; i < vertCount; i++) {
                Vertex v = vertices.get(i);
                double vx = v.x, vy = v.y, vz = v.z;
                double x = p11 * vx + p12 * vy + p13 * vz + p14;
                double y = p21 * vx + p22 * vy + p23 * vz + p24;
                double z = p31 * vx + p32 * vy + p33 * vz + p34;
                if (offset != 0) {
                    double pw = p41 * vx + p42 * vy + p43 * vz + p44;
                    double len = Math.sqrt(x * x + y * y + z * z + pw * pw);
                    if (len > 0) {
                        double s = 1 + offset / len;
                        x *= s; y *= s; z *= s;
                    }
                }
                double nx = v.nx, ny = v.ny, nz = v.nz;
                double u = v.u, vv = v.v;
                // one call per vertex, which buffers write directly instead of element by element
                vertexConsumer.vertex(
                        (float) x, (float) y, (float) z,
                        r, g, b, a,
                        (float) (t11 * u + t12 * vv + t13), (float) (t21 * u + t22 * vv + t23),
                        overlay, light,
                        (float) (n11 * nx + n12 * ny + n13 * nz), (float) (n21 * nx + n22 * ny + n23 * nz), (float) (n31 * nx + n32 * ny + n33 * nz)
                );
            }
        }
    }

    private static class VertexBuffer {
        private final HashMap<RenderType, List<Batch>> primaryBuffers = new LinkedHashMap<>();
        private final HashMap<RenderType, List<Batch>> secondaryBuffers = new LinkedHashMap<>();
        // reused between frames, so a frame allocates nothing once warmed up
        private final ArrayDeque<Batch> freeBatches = new ArrayDeque<>();
        private final ArrayDeque<List<Batch>> freeLists = new ArrayDeque<>();

        public Batch add(RenderType renderType, boolean primary) {
            HashMap<RenderType, List<Batch>> buffer = primary ? primaryBuffers : secondaryBuffers;
            List<Batch> list = buffer.get(renderType);
            if (list == null) {
                list = freeLists.isEmpty() ? new ArrayList<>() : freeLists.pop();
                buffer.put(renderType, list);
            }
            Batch batch = freeBatches.isEmpty() ? new Batch() : freeBatches.pop();
            list.add(batch);
            return batch;
        }

        // keep: the frame cache the batches are kept in for a replay instead of being freed, or null
        public void consume(boolean primary, MultiBufferSource bufferSource, FrameCache keep) {
            HashMap<RenderType, List<Batch>> map = primary ? primaryBuffers : secondaryBuffers;
            for (Map.Entry<RenderType, List<Batch>> entry : map.entrySet()) {
                RenderType renderType = entry.getKey();
                if (org.figuramc.figura.benchmark.FiguraBenchmark.ENABLED)
                    org.figuramc.figura.benchmark.FiguraBenchmark.onRenderType(renderType);
                VertexConsumer vertexConsumer = bufferSource.getBuffer(renderType);
                List<Batch> batches = entry.getValue();

                // one bulk writer for the whole render type, so it is pushed in full chunks instead of once per part
                SodiumCompat.BulkWriter bulk = SodiumCompat.writerFor(vertexConsumer, renderType);
                if (bulk != null) {
                    for (Batch batch : batches)
                        batch.write(bulk);
                    bulk.flush();
                } else {
                    for (Batch batch : batches)
                        batch.write(vertexConsumer);
                }

                if (keep != null)
                    keep.retain(renderType, batches);
                else
                    free(batches);
            }
            map.clear();
        }

        // returns the batches and their list to the pools
        void free(List<Batch> batches) {
            for (Batch batch : batches) {
                batch.vertices = null;
                batch.packed = null;
                batch.owner = null;
                freeBatches.push(batch);
            }
            batches.clear();
            freeLists.push(batches);
        }
    }

    // the batches of one render type, in the order the model pass drew them
    private record RetainedType(RenderType renderType, List<Batch> batches) {}

    // everything one model pass drew, kept for the next frame to draw again (see replayFrame)
    private static class FrameCache {
        // the level frame of the pass, -1 when nothing is kept
        long frame = -1;
        // the entity root everything kept is relative to
        final FiguraMat4 root = FiguraMat4.of();
        final FiguraMat3 rootNormal = FiguraMat3.of();
        // the state of the pass, a replay must find the same
        int lodLevel, structureVersion;
        boolean simplifyBody, translucent, glowing, doIrisEmissiveFix, offsetRenderLayers;
        float alpha;
        int complexityUsed;

        final List<RetainedType> types = new ArrayList<>();
        // the render layer parts (cape, elytra) with the parent transform saved for their pass
        final List<FiguraModelPart> layerParts = new ArrayList<>();
        final List<PartCustomization> layerCustomizations = new ArrayList<>();
        // the pivot parts (held items, armor, parrots) with the transform saved for them
        final List<ParentType> pivotTypes = new ArrayList<>();
        final List<FiguraMat4> pivotPositions = new ArrayList<>();
        final List<FiguraMat3> pivotNormals = new ArrayList<>();
        // the render tasks drawn, with the transform of their part
        final List<RenderTask> tasks = new ArrayList<>();
        final List<PartCustomization> taskCustomizations = new ArrayList<>();
        // customization copies reused between frames
        private final List<PartCustomization> customizationPool = new ArrayList<>();
        private int customizationsUsed;

        // if the kept frame is the last one and was drawn in the same state as the pass starting now
        boolean matches(ImmediateAvatarRenderer renderer) {
            return frame == Avatar.getLevelFrame() - 1
                    && lodLevel == renderer.lodLevel && simplifyBody == renderer.simplifyBody
                    && translucent == renderer.translucent && glowing == renderer.glowing
                    && doIrisEmissiveFix == renderer.doIrisEmissiveFix && offsetRenderLayers == renderer.offsetRenderLayers
                    && structureVersion == renderer.structureVersion && alpha == renderer.alpha;
        }

        // drops the kept frame and starts keeping the pass starting now
        void begin(ImmediateAvatarRenderer renderer, double vertOffset) {
            clear();
            renderer.rootMatrices(root, rootNormal, vertOffset);
            lodLevel = renderer.lodLevel;
            simplifyBody = renderer.simplifyBody;
            translucent = renderer.translucent;
            glowing = renderer.glowing;
            doIrisEmissiveFix = renderer.doIrisEmissiveFix;
            offsetRenderLayers = renderer.offsetRenderLayers;
            structureVersion = renderer.structureVersion;
            alpha = renderer.alpha;
        }

        void finish(int complexityUsed) {
            this.complexityUsed = complexityUsed;
            frame = Avatar.getLevelFrame();
        }

        void clear() {
            frame = -1;
            for (RetainedType retained : types)
                VERTEX_BUFFER.free(retained.batches);
            types.clear();
            layerParts.clear();
            layerCustomizations.clear();
            pivotTypes.clear();
            pivotPositions.clear();
            pivotNormals.clear();
            tasks.clear();
            taskCustomizations.clear();
            customizationsUsed = 0;
        }

        void retain(RenderType renderType, List<Batch> batches) {
            types.add(new RetainedType(renderType, batches));
        }

        void keepLayerParent(FiguraModelPart part, PartCustomization parent) {
            layerParts.add(part);
            layerCustomizations.add(copy(parent));
        }

        void keepPivot(ParentType type, FiguraMat4 position, FiguraMat3 normal) {
            pivotTypes.add(type);
            pivotPositions.add(position.copy());
            pivotNormals.add(normal.copy());
        }

        void keepTask(RenderTask task, PartCustomization parent) {
            tasks.add(task);
            taskCustomizations.add(copy(parent));
        }

        private PartCustomization copy(PartCustomization customization) {
            if (customizationsUsed == customizationPool.size())
                customizationPool.add(new PartCustomization());
            PartCustomization copy = customizationPool.get(customizationsUsed++);
            customization.copyTo(copy);
            return copy;
        }
    }
}

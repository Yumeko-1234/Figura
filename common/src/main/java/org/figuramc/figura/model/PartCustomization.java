package org.figuramc.figura.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.datafixers.util.Pair;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.config.Configs;
import org.figuramc.figura.math.matrix.FiguraMat3;
import org.figuramc.figura.math.matrix.FiguraMat4;
import org.figuramc.figura.math.vector.FiguraVec3;
import org.figuramc.figura.model.rendering.texture.FiguraTextureSet;
import org.figuramc.figura.model.rendering.texture.RenderTypes;

import java.util.ArrayList;
import java.util.EmptyStackException;
import java.util.List;

public class PartCustomization {

    // -- Matrix thingies --// 
    /**
     * Boolean exists because blockbench sucks and uses a different rotation
     * formula for meshes than other types of items. Meshes rotate in XYZ order,
     * while literally everything else is in ZYX order.
     */
    public PartType partType = PartType.GROUP;

    public final FiguraMat4 positionMatrix = FiguraMat4.of();
    public final FiguraMat3 uvMatrix = FiguraMat3.of();
    public final FiguraMat3 normalMatrix = FiguraMat3.of();

    public boolean needsMatrixRecalculation = false;
    public boolean visible = true;
    public Boolean vanillaVisible = null;

    private final FiguraVec3 position = FiguraVec3.of();
    private final FiguraVec3 rotation = FiguraVec3.of();
    private final FiguraVec3 scale = FiguraVec3.of(1, 1, 1);
    private final FiguraVec3 pivot = FiguraVec3.of();

    // The "offset" values are for vanilla part scaling. The offset pivot and rot can be get and set from script.
    private final FiguraVec3 offsetPivot = FiguraVec3.of();
    private final FiguraVec3 offsetPos = FiguraVec3.of();
    private final FiguraVec3 offsetRot = FiguraVec3.of();
    private final FiguraVec3 offsetScale = FiguraVec3.of(1, 1, 1);

    // These values are set by animation players. They can be queried, though not set, by script.
    private final FiguraVec3 animPos = FiguraVec3.of();
    private final FiguraVec3 animRot = FiguraVec3.of();
    private final FiguraVec3 animScale = FiguraVec3.of(1, 1, 1);

    public final FiguraVec3 stackScale = FiguraVec3.of(1, 1, 1);
    public final FiguraVec3 color = FiguraVec3.of(1, 1, 1);
    public final FiguraVec3 color2 = FiguraVec3.of(1, 1, 1);
    public Float alpha = null;
    public Integer light = null;
    public Integer overlay = null;

    private RenderTypes primaryRenderType, secondaryRenderType;
    public TextureCustomization primaryTexture, secondaryTexture;

    public void applyToStack(PoseStack stack) {
        stack.mulPoseMatrix(positionMatrix.toMatrix4f());
        stack.last().normal().mul(normalMatrix.toMatrix3f());
    }

    /**
     * Recalculates the matrix if necessary.
     */
    public void recalculate() {
        if (!needsMatrixRecalculation)
            return;
        customMatrix = false;

        // vanilla parents and animations set their values again every frame, even when nothing moved,
        // so when every input is the same as last time (and the matrices weren't changed since), the result is the same too
        boolean cached = Configs.CACHE_PART_MATRICES != null && Configs.CACHE_PART_MATRICES.value
                && matrixCache != null && matrixCache.matches(this);
        if (cached && !FiguraMod.verifyOptimizations()) {
            needsMatrixRecalculation = false;
            return;
        }

        computeMatrices();
        needsMatrixRecalculation = false;

        // verifyOptimizations always computes, and reports when the cached result would have been different
        if (cached && !matrixCache.outputsMatch(this) && !matrixCacheMismatchLogged) {
            matrixCacheMismatchLogged = true;
            FiguraMod.LOGGER.warn("Cached part matrix differs from the computed one, please report this\ncomputed: {}\ncached: {}",
                    positionMatrix, matrixCache.positionMatrix);
        }

        // most parts are computed once and never again, only the ones computed again get a cache, to save memory
        if (Configs.CACHE_PART_MATRICES != null && Configs.CACHE_PART_MATRICES.value) {
            if (matrixCache == null && computedOnce)
                matrixCache = new MatrixCache();
            if (matrixCache != null)
                matrixCache.store(this);
        }
        computedOnce = true;
    }

    private static boolean matrixCacheMismatchLogged;
    private boolean computedOnce;
    private MatrixCache matrixCache;

    // the inputs of the last computeMatrices() and what it wrote
    private static final class MatrixCache {
        private PartType partType;
        private final FiguraVec3 position = FiguraVec3.of(), rotation = FiguraVec3.of(), scale = FiguraVec3.of(), pivot = FiguraVec3.of();
        private final FiguraVec3 offsetPivot = FiguraVec3.of(), offsetPos = FiguraVec3.of(), offsetRot = FiguraVec3.of(), offsetScale = FiguraVec3.of();
        private final FiguraVec3 animPos = FiguraVec3.of(), animRot = FiguraVec3.of(), animScale = FiguraVec3.of();
        private final FiguraMat4 positionMatrix = FiguraMat4.of();
        private final FiguraMat3 normalMatrix = FiguraMat3.of();
        private final FiguraVec3 stackScale = FiguraVec3.of();

        private void store(PartCustomization c) {
            partType = c.partType;
            position.set(c.position); rotation.set(c.rotation); scale.set(c.scale); pivot.set(c.pivot);
            offsetPivot.set(c.offsetPivot); offsetPos.set(c.offsetPos); offsetRot.set(c.offsetRot); offsetScale.set(c.offsetScale);
            animPos.set(c.animPos); animRot.set(c.animRot); animScale.set(c.animScale);
            positionMatrix.set(c.positionMatrix);
            normalMatrix.set(c.normalMatrix);
            stackScale.set(c.stackScale);
        }

        // exact compare, any NaN never matches so it is always computed
        private boolean matches(PartCustomization c) {
            return partType == c.partType
                    && position.equals(c.position) && rotation.equals(c.rotation) && scale.equals(c.scale) && pivot.equals(c.pivot)
                    && offsetPivot.equals(c.offsetPivot) && offsetPos.equals(c.offsetPos) && offsetRot.equals(c.offsetRot) && offsetScale.equals(c.offsetScale)
                    && animPos.equals(c.animPos) && animRot.equals(c.animRot) && animScale.equals(c.animScale)
                    && outputsMatch(c);
        }

        // the matrices may also be written from outside (setMatrix, the customization stack, voided matrices)
        private boolean outputsMatch(PartCustomization c) {
            return positionMatrix.equals(c.positionMatrix) && normalMatrix.equals(c.normalMatrix) && stackScale.equals(c.stackScale);
        }
    }

    private void computeMatrices() {
        positionMatrix.reset();

        // Position the pivot point at 0, 0, 0, and translate the part
        positionMatrix.translate(
                offsetPos.x - pivot.x - offsetPivot.x,
                offsetPos.y - pivot.y - offsetPivot.y,
                offsetPos.z - pivot.z - offsetPivot.z
        );

        // Scale the model part around the pivot
        stackScale.set(
                offsetScale.x * scale.x * animScale.x,
                offsetScale.y * scale.y * animScale.y,
                offsetScale.z * scale.z * animScale.z
        );
        positionMatrix.scale(stackScale);

        // Rotate the model part around the pivot
        if (partType == PartType.MESH) {
            positionMatrix.rotateZ(rotation.z + offsetRot.z + animRot.z);
            positionMatrix.rotateY(rotation.y + offsetRot.y + animRot.y);
            positionMatrix.rotateX(rotation.x + offsetRot.x + animRot.x);
        } else {
            positionMatrix.rotateZYX(
                    rotation.x + offsetRot.x + animRot.x,
                    rotation.y + offsetRot.y + animRot.y,
                    rotation.z + offsetRot.z + animRot.z
            );
        }

        // Undo the effects of the pivot translation
        positionMatrix.translate(
                position.x + animPos.x + pivot.x + offsetPivot.x,
                position.y + animPos.y + pivot.y + offsetPivot.y,
                position.z + animPos.z + pivot.z + offsetPivot.z
        );

        // Set up the normal matrix as well
        normalMatrix.reset();
        double x = scale.x * animScale.x;
        double y = scale.y * animScale.y;
        double z = scale.z * animScale.z;
        double c = Math.cbrt(x * y * z);
        normalMatrix.scale(
                c == 0 && x == 0 ? 1 : c / x,
                c == 0 && y == 0 ? 1 : c / y,
                c == 0 && z == 0 ? 1 : c / z
        );

        // Perform rotation of normals
        if (partType == PartType.MESH) {
            normalMatrix.rotateZ(rotation.z + offsetRot.z + animRot.z);
            normalMatrix.rotateY(rotation.y + offsetRot.y + animRot.y);
            normalMatrix.rotateX(rotation.x + offsetRot.x + animRot.x);
        } else {
            normalMatrix.rotateZYX(
                    rotation.x + offsetRot.x + animRot.x,
                    rotation.y + offsetRot.y + animRot.y,
                    rotation.z + offsetRot.z + animRot.z
            );
        }
    }

    public void setPos(FiguraVec3 pos) {
        setPos(pos.x, pos.y, pos.z);
    }
    public void setPos(double x, double y, double z) {
        position.set(x, y, z);
        needsMatrixRecalculation = true;
    }
    public FiguraVec3 getPos() {
        return position.copy();
    }

    public void setRot(FiguraVec3 rot) {
        setRot(rot.x, rot.y, rot.z);
    }
    public void setRot(double x, double y, double z) {
        rotation.set(x, y, z);
        needsMatrixRecalculation = true;
    }
    public FiguraVec3 getRot() {
        return rotation.copy();
    }

    public void setScale(FiguraVec3 scale) {
        setScale(scale.x, scale.y, scale.z);
    }
    public void setScale(double x, double y, double z) {
        scale.set(x, y, z);
        needsMatrixRecalculation = true;
    }
    public FiguraVec3 getScale() {
        return scale.copy();
    }

    public void setPivot(FiguraVec3 pivot) {
        setPivot(pivot.x, pivot.y, pivot.z);
    }
    public void setPivot(double x, double y, double z) {
        pivot.set(x, y, z);
        needsMatrixRecalculation = true;
    }
    public FiguraVec3 getPivot() {
        return pivot.copy();
    }
    // writes the pivot into out, optionally with the offset pivot added, without copying (used by the renderer for every part)
    public FiguraVec3 getPivot(FiguraVec3 out, boolean withOffset) {
        out.set(pivot);
        return withOffset ? out.add(offsetPivot) : out;
    }

    public void offsetPivot(FiguraVec3 pivot) {
        offsetPivot(pivot.x, pivot.y, pivot.z);
    }
    public void offsetPivot(double x, double y, double z) {
        offsetPivot.set(x, y, z);
        needsMatrixRecalculation = true;
    }
    public void addOffsetPivot(FiguraVec3 pivot) {
        addOffsetPivot(pivot.x, pivot.y, pivot.z);
    }
    public void addOffsetPivot(double x, double y, double z) {
        offsetPivot.add(x, y, z);
        needsMatrixRecalculation = true;
    }
    public FiguraVec3 getOffsetPivot() {
        return offsetPivot.copy();
    }

    public void offsetPos(FiguraVec3 pos) {
        offsetPos(pos.x, pos.y, pos.z);
    }
    public void offsetPos(double x, double y, double z) {
        offsetPos.set(x, y, z);
        needsMatrixRecalculation = true;
    }
    public void addOffsetPos(FiguraVec3 pos) {
        addOffsetPos(pos.x, pos.y, pos.z);
    }
    public void addOffsetPos(double x, double y, double z) {
        offsetPos.add(x, y, z);
        needsMatrixRecalculation = true;
    }
    public FiguraVec3 getOffsetPos() {
        return offsetPos.copy();
    }

    public void offsetRot(FiguraVec3 rot) {
        offsetRot(rot.x, rot.y, rot.z);
    }
    public void offsetRot(double x, double y, double z) {
        offsetRot.set(x, y, z);
        needsMatrixRecalculation = true;
    }
    public void addOffsetRot(FiguraVec3 rot) {
        addOffsetRot(rot.x, rot.y, rot.z);
    }
    public void addOffsetRot(double x, double y, double z) {
        offsetRot.add(x, y, z);
        needsMatrixRecalculation = true;
    }
    public FiguraVec3 getOffsetRot() {
        return offsetRot.copy();
    }

    public void offsetScale(FiguraVec3 scale) {
        offsetScale(scale.x, scale.y, scale.z);
    }
    public void offsetScale(double x, double y, double z) {
        offsetScale.set(x, y, z);
        needsMatrixRecalculation = true;
    }
    public void addOffsetScale(FiguraVec3 scale) {
        addOffsetScale(scale.x, scale.y, scale.z);
    }
    public void addOffsetScale(double x, double y, double z) {
        offsetScale.add(x, y, z);
        needsMatrixRecalculation = true;
    }
    public FiguraVec3 getOffsetScale() {
        return offsetScale.copy();
    }

    // writes the offsets into the given vectors without copying (used every frame to save and restore the vanilla transforms)
    public void getOffsets(FiguraVec3 pivot, FiguraVec3 pos, FiguraVec3 rot, FiguraVec3 scale) {
        pivot.set(offsetPivot);
        pos.set(offsetPos);
        rot.set(offsetRot);
        scale.set(offsetScale);
    }

    public void setAnimPos(double x, double y, double z) {
        animPos.set(x, y, z);
        needsMatrixRecalculation = true;
    }
    public FiguraVec3 getAnimPos() {
        return animPos.copy();
    }
    public void setAnimRot(double x, double y, double z) {
        animRot.set(x, y, z);
        needsMatrixRecalculation = true;
    }
    public FiguraVec3 getAnimRot() {
        return animRot.copy();
    }
    public void setAnimScale(double x, double y, double z) {
        animScale.set(x, y, z);
        needsMatrixRecalculation = true;
    }
    public FiguraVec3 getAnimScale() {
        return animScale.copy();
    }

    // -- render script smoothing (ScriptTransformInterpolator) -- //

    // the matrices were set directly (setMatrix) since they were last computed, so the transforms do not make them
    private boolean customMatrix;

    public boolean hasCustomMatrix() {
        return customMatrix && !needsMatrixRecalculation;
    }

    // position, rotation, scale and offset rotation, the transforms scripts set on the part
    void readScriptTransforms(double[] out) {
        out[0] = position.x; out[1] = position.y; out[2] = position.z;
        out[3] = rotation.x; out[4] = rotation.y; out[5] = rotation.z;
        out[6] = scale.x; out[7] = scale.y; out[8] = scale.z;
        out[9] = offsetRot.x; out[10] = offsetRot.y; out[11] = offsetRot.z;
    }

    void writeScriptTransforms(double[] in, boolean withOffsetRot) {
        position.set(in[0], in[1], in[2]);
        rotation.set(in[3], in[4], in[5]);
        scale.set(in[6], in[7], in[8]);
        if (withOffsetRot)
            offsetRot.set(in[9], in[10], in[11]);
        needsMatrixRecalculation = true;
    }

    // the position matrix (row by row) and the normal matrix
    void readMatrix(double[] position, double[] normal) {
        FiguraMat4 p = positionMatrix;
        position[0] = p.v11; position[1] = p.v12; position[2] = p.v13; position[3] = p.v14;
        position[4] = p.v21; position[5] = p.v22; position[6] = p.v23; position[7] = p.v24;
        position[8] = p.v31; position[9] = p.v32; position[10] = p.v33; position[11] = p.v34;
        position[12] = p.v41; position[13] = p.v42; position[14] = p.v43; position[15] = p.v44;
        FiguraMat3 n = normalMatrix;
        normal[0] = n.v11; normal[1] = n.v12; normal[2] = n.v13;
        normal[3] = n.v21; normal[4] = n.v22; normal[5] = n.v23;
        normal[6] = n.v31; normal[7] = n.v32; normal[8] = n.v33;
    }

    void writeMatrix(double[] position, double[] normal) {
        FiguraMat4 p = positionMatrix;
        p.v11 = position[0]; p.v12 = position[1]; p.v13 = position[2]; p.v14 = position[3];
        p.v21 = position[4]; p.v22 = position[5]; p.v23 = position[6]; p.v24 = position[7];
        p.v31 = position[8]; p.v32 = position[9]; p.v33 = position[10]; p.v34 = position[11];
        p.v41 = position[12]; p.v42 = position[13]; p.v43 = position[14]; p.v44 = position[15];
        FiguraMat3 n = normalMatrix;
        n.v11 = normal[0]; n.v12 = normal[1]; n.v13 = normal[2];
        n.v21 = normal[3]; n.v22 = normal[4]; n.v23 = normal[5];
        n.v31 = normal[6]; n.v32 = normal[7]; n.v33 = normal[8];
    }

    // shows a blended position matrix, with its normal matrix computed like setMatrix does, without changing the part's flags
    void writeBlendedMatrix(double[] position) {
        FiguraMat4 p = positionMatrix;
        p.v11 = position[0]; p.v12 = position[1]; p.v13 = position[2]; p.v14 = position[3];
        p.v21 = position[4]; p.v22 = position[5]; p.v23 = position[6]; p.v24 = position[7];
        p.v31 = position[8]; p.v32 = position[9]; p.v33 = position[10]; p.v34 = position[11];
        p.v41 = position[12]; p.v42 = position[13]; p.v43 = position[14]; p.v44 = position[15];
        normalMatrix.set(p.v11, p.v21, p.v31, p.v12, p.v22, p.v32, p.v13, p.v23, p.v33);
        normalMatrix.invert();
        normalMatrix.transpose();
    }

    // a matrix set by a script (part:setMatrix): smoothed between runs of time limited render scripts
    public void setScriptMatrix(FiguraMat4 matrix) {
        setMatrix(matrix);
        customMatrix = true;
    }

    // a matrix set from Java (camera parent, Epic Fight joints from figura_extra_fight...): new every frame, never smoothed
    public void setMatrix(FiguraMat4 matrix) {
        customMatrix = false;
        positionMatrix.set(matrix);
        // normal matrix = inverse transpose of the 3x3 part, computed in place instead of on a deaugmented copy
        normalMatrix.set(matrix.v11, matrix.v21, matrix.v31, matrix.v12, matrix.v22, matrix.v32, matrix.v13, matrix.v23, matrix.v33);
        normalMatrix.invert();
        normalMatrix.transpose();
        needsMatrixRecalculation = false;
    }

    public void setPositionMatrix(FiguraMat4 matrix) {
        positionMatrix.set(matrix);
    }

    public void setNormalMatrix(FiguraMat3 matrix) {
        normalMatrix.set(matrix);
    }

    public FiguraMat4 getPositionMatrix() {
        FiguraMat4 result = FiguraMat4.of();
        result.set(positionMatrix);
        return result;
    }
    public FiguraMat3 getNormalMatrix() {
        FiguraMat3 result = new FiguraMat3();
        result.set(normalMatrix);
        return result;
    }

    // -- Render type thingies --// 

    public void setPrimaryRenderType(RenderTypes type) {
        primaryRenderType = type;
    }
    public RenderTypes getPrimaryRenderType() {
        return primaryRenderType;
    }
    public void setSecondaryRenderType(RenderTypes type) {
        secondaryRenderType = type;
    }
    public RenderTypes getSecondaryRenderType() {
        return secondaryRenderType;
    }

    public void copyTo(PartCustomization target) {
        target.partType = partType;
        target.positionMatrix.set(positionMatrix);
        target.uvMatrix.set(uvMatrix);
        target.normalMatrix.set(normalMatrix);
        target.setPos(position);
        target.setRot(rotation);
        target.setScale(scale);
        target.setPivot(pivot);
        target.offsetPivot(offsetPivot);
        target.offsetPos(offsetPos);
        target.offsetRot(offsetRot);
        target.offsetScale(offsetScale);
        target.stackScale.set(stackScale);
        target.color.set(color);
        target.color2.set(color2);
        target.alpha = alpha;
        target.light = light;
        target.overlay = overlay;
        target.needsMatrixRecalculation = needsMatrixRecalculation;
        target.visible = visible;
        target.vanillaVisible = vanillaVisible;
        target.setPrimaryRenderType(primaryRenderType);
        target.setSecondaryRenderType(secondaryRenderType);
        target.primaryTexture = primaryTexture;
        target.secondaryTexture = secondaryTexture;
    }

    // sets this to parent with other applied on top: the same values as parent.copyTo(this) followed by this.modify(other)
    // for the fields a stack entry is read for (matrices, scale, colors, render state), without copying the matrices first
    // pos, rot, scale, pivot and the offsets are left as they are: a stack entry never recalculates and nothing reads them
    public void setComposed(PartCustomization parent, PartCustomization other) {
        partType = parent.partType;
        positionMatrix.setProduct(parent.positionMatrix, other.positionMatrix);
        uvMatrix.setProduct(parent.uvMatrix, other.uvMatrix);
        normalMatrix.setProduct(parent.normalMatrix, other.normalMatrix);

        primaryRenderType = other.primaryRenderType != null ? other.primaryRenderType : parent.primaryRenderType;
        secondaryRenderType = other.secondaryRenderType != null ? other.secondaryRenderType : parent.secondaryRenderType;

        visible = other.visible;
        vanillaVisible = other.vanillaVisible != null ? other.vanillaVisible : parent.vanillaVisible;
        light = other.light != null ? other.light : parent.light;
        overlay = other.overlay != null ? other.overlay : parent.overlay;

        if (other.alpha != null)
            alpha = parent.alpha != null ? parent.alpha * other.alpha : other.alpha;
        else
            alpha = parent.alpha;

        stackScale.set(parent.stackScale).multiply(other.stackScale);
        color.set(parent.color).multiply(other.color);
        color2.set(parent.color2).multiply(other.color2);

        primaryTexture = other.primaryTexture != null ? other.primaryTexture : parent.primaryTexture;
        secondaryTexture = other.secondaryTexture != null ? other.secondaryTexture : parent.secondaryTexture;

        needsMatrixRecalculation = false;
    }

    // Modify this object using the information contained in the other object
    public void modify(PartCustomization other) {
        positionMatrix.rightMultiply(other.positionMatrix);
        uvMatrix.rightMultiply(other.uvMatrix);
        normalMatrix.rightMultiply(other.normalMatrix);

        if (other.primaryRenderType != null)
            setPrimaryRenderType(other.primaryRenderType);
        if (other.secondaryRenderType != null)
            setSecondaryRenderType(other.secondaryRenderType);

        // if (other.visible != null)
            visible = other.visible;

        if (other.vanillaVisible != null)
            vanillaVisible = other.vanillaVisible;

        if (other.light != null)
            light = other.light;

        if (other.overlay != null)
            overlay = other.overlay;

        if (other.alpha != null) {
            if (alpha != null)
                alpha *= other.alpha;
            else
                alpha = other.alpha;
        }

        stackScale.multiply(other.stackScale);
        color.multiply(other.color);
        color2.multiply(other.color2);

        if (other.primaryTexture != null)
            primaryTexture = other.primaryTexture;
        if (other.secondaryTexture != null)
            secondaryTexture = other.secondaryTexture;

        needsMatrixRecalculation = false;
    }

    public static final PoseStack GLOBAL_CUSTOMIZATION_POSE_STACK = new PoseStack();

    public PoseStack copyIntoGlobalPoseStack() {
        recalculate();
        positionMatrix.copyDataTo(GLOBAL_CUSTOMIZATION_POSE_STACK.last().pose());
        normalMatrix.copyDataTo(GLOBAL_CUSTOMIZATION_POSE_STACK.last().normal());
        return GLOBAL_CUSTOMIZATION_POSE_STACK;
    }

    public enum PartType {
        MESH,
        CUBE,
        GROUP
    }

    public static class PartCustomizationStack {

        // entries are kept after a pop and reused by the next push at the same depth,
        // since a new customization (with all its matrices and vectors) for every part, every frame, keeps the GC busy
        // so an entry returned by peek() is only valid until it is popped, keep a copy to use it later
        private final List<PartCustomization> stack = new ArrayList<>() {{
            add(new PartCustomization());
        }};
        private int size = 1;

        public void push(PartCustomization customization) {
            if (size == stack.size())
                stack.add(new PartCustomization());

            // the parent entry with the customization applied on top
            stack.get(size).setComposed(stack.get(size - 1), customization);

            // add
            size++;
        }

        public void pop() {
            if (size <= 0)
                throw new EmptyStackException();
            size--;
        }

        public PartCustomization peek() {
            if (size <= 0)
                throw new EmptyStackException();
            return stack.get(size - 1);
        }

        public boolean isEmpty() {
            return size == 1;
        }
    }
}

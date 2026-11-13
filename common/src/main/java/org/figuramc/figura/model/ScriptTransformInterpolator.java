package org.figuramc.figura.model;

/**
 * Smooths the parts of an avatar whose render scripts are time limited (AvatarCulling.throttleRenderScripts).
 * <p>
 * A limited avatar runs its render scripts a few dozen times per second, and its parts would hold the pose of the last run in
 * between, so script driven motion (physics, smooth heads) would step. Instead, each part is shown moving from where it was last
 * shown towards the newest pose its scripts left, over the time until their next run: smooth, about one run behind.
 * <p>
 * Two kinds of script motion are blended:
 * <ul>
 * <li>the transforms scripts set on the part (position, rotation, scale, offset rotation), never the vanilla parent offsets
 * (already updated every frame) or animations (applied every frame);</li>
 * <li>a matrix set with setMatrix (physics libraries move their joints that way), blended element by element.</li>
 * </ul>
 * The blended values only exist while the part is drawn: the part gets its real values back right after,
 * so scripts always read what they set.
 */
public final class ScriptTransformInterpolator {

    // position, rotation, scale, offset rotation
    static final int VALUES = 12;
    private static final int ROTATION = 3, OFFSET_ROT = 9;
    // not drawn for this many intervals (culled, off screen): start again from where it is instead of blending from an old pose
    private static final int STALE_INTERVALS = 4;

    private ScriptTransformInterpolator() {}

    // per part, only for the parts of avatars being smoothed
    public static final class State {
        // transforms
        final double[] from = new double[VALUES], to = new double[VALUES], shown = new double[VALUES], real = new double[VALUES];
        long start, seen = Long.MIN_VALUE, interval;
        // the offset rotation held the vanilla rotation when blended, so it was left alone and restore must leave it alone too
        boolean keepOffsetRot;
        // setMatrix matrix: 16 position values, and the real normal matrix while the blended one is shown
        final double[] mFrom = new double[16], mTo = new double[16], mShown = new double[16], mReal = new double[16];
        final double[] nReal = new double[9];
        long mStart, mSeen = Long.MIN_VALUE, mInterval;
        // vertices edited by scripts, per vertex list of the part
        java.util.IdentityHashMap<java.util.List<org.figuramc.figura.model.rendering.Vertex>, VertexBlend> vertices;
    }

    private static State state(FiguraModelPart part) {
        State s = part.interpolation;
        if (s == null)
            part.interpolation = s = new State();
        return s;
    }

    private static double progress(long now, long start, long interval) {
        return interval <= 0 ? 1 : Math.max(0, Math.min(1, (double) (now - start) / interval));
    }

    /**
     * Called right before the matrices of the part are computed, after its pre render function.
     * @param interval nanoseconds between two runs of the avatar's render scripts, 0 stops smoothing the part
     * @param now the level frame time, the same for every pass of a frame (shadow, normal)
     * @param vanillaOffsets if the vanilla parent offsets were added to the part this frame (its offset rotation is then left alone)
     * @return if the part now shows blended transforms, which {@link #restore} must undo once its matrices are computed
     */
    public static boolean apply(FiguraModelPart part, long interval, long now, boolean vanillaOffsets) {
        // camera parts get a new matrix from Figura every frame, not from the scripts
        if (interval <= 0 || part.parentType == ParentType.Camera) {
            part.interpolation = null;
            return false;
        }
        PartCustomization custom = part.customization;
        // a matrix set by a script is blended by applyMatrix instead
        if (custom.hasCustomMatrix())
            return false;

        State s = state(part);
        custom.readScriptTransforms(s.real);

        // first time, or not drawn for a while: start from where it is now
        if (s.seen == Long.MIN_VALUE || now - s.seen > STALE_INTERVALS * interval) {
            System.arraycopy(s.real, 0, s.from, 0, VALUES);
            System.arraycopy(s.real, 0, s.to, 0, VALUES);
            System.arraycopy(s.real, 0, s.shown, 0, VALUES);
            s.start = now;
            s.seen = now;
            s.interval = interval;
            return false;
        }
        s.seen = now;

        // the vanilla rotation added to the offset rotation this frame changes every frame and is already smooth, so it is left out
        int compared = vanillaOffsets ? OFFSET_ROT : VALUES;

        // the scripts moved the part: blend from what is shown now towards its new values, over one run interval
        if (!same(s.real, s.to, compared)) {
            System.arraycopy(s.shown, 0, s.from, 0, VALUES);
            System.arraycopy(s.real, 0, s.to, 0, VALUES);
            s.start = now;
            s.interval = interval;
        }

        double t = progress(now, s.start, s.interval);
        for (int i = 0; i < VALUES; i++) {
            double from = s.from[i], delta = s.to[i] - from;
            // rotations take the short way round
            if (((i >= ROTATION && i < ROTATION + 3) || i >= OFFSET_ROT) && Math.abs(delta) > 180)
                delta = ((delta + 180) % 360 + 360) % 360 - 180;
            s.shown[i] = from + delta * t;
        }
        if (vanillaOffsets)
            System.arraycopy(s.real, OFFSET_ROT, s.shown, OFFSET_ROT, 3);

        if (same(s.shown, s.real, VALUES))
            return false;
        s.keepOffsetRot = vanillaOffsets;
        custom.writeScriptTransforms(s.shown, !vanillaOffsets);
        return true;
    }

    // gives the part its real transforms back, after its matrices were computed from the blended ones.
    // An offset rotation that held the vanilla rotation is not written back: a mod may have taken the vanilla rotation out of it
    // in between (figura_extra_fight resets the vanilla transforms before posing a part on an Epic Fight joint), and writing
    // it back would leave the vanilla rotation in it, added again every frame, so the part would spin
    public static void restore(FiguraModelPart part) {
        State s = part.interpolation;
        if (s != null)
            part.customization.writeScriptTransforms(s.real, !s.keepOffsetRot);
    }

    /**
     * Called right after the matrices of the part are computed: blends a matrix set with setMatrix.
     * @return if the part now shows a blended matrix, which {@link #restoreMatrix} must undo once the part was pushed
     */
    public static boolean applyMatrix(FiguraModelPart part, long interval, long now) {
        PartCustomization custom = part.customization;
        if (interval <= 0 || !custom.hasCustomMatrix() || part.parentType == ParentType.Camera) {
            if (part.interpolation != null)
                part.interpolation.mSeen = Long.MIN_VALUE;
            return false;
        }

        State s = state(part);
        custom.readMatrix(s.mReal, s.nReal);

        if (s.mSeen == Long.MIN_VALUE || now - s.mSeen > STALE_INTERVALS * interval) {
            System.arraycopy(s.mReal, 0, s.mFrom, 0, 16);
            System.arraycopy(s.mReal, 0, s.mTo, 0, 16);
            System.arraycopy(s.mReal, 0, s.mShown, 0, 16);
            s.mStart = now;
            s.mSeen = now;
            s.mInterval = interval;
            return false;
        }
        s.mSeen = now;

        if (!same(s.mReal, s.mTo, 16)) {
            System.arraycopy(s.mShown, 0, s.mFrom, 0, 16);
            System.arraycopy(s.mReal, 0, s.mTo, 0, 16);
            s.mStart = now;
            s.mInterval = interval;
        }

        double t = progress(now, s.mStart, s.mInterval);
        for (int i = 0; i < 16; i++)
            s.mShown[i] = s.mFrom[i] + (s.mTo[i] - s.mFrom[i]) * t;

        if (same(s.mShown, s.mReal, 16))
            return false;
        custom.writeBlendedMatrix(s.mShown);
        return true;
    }

    // gives the part its real matrices back
    public static void restoreMatrix(FiguraModelPart part) {
        State s = part.interpolation;
        if (s != null)
            part.customization.writeMatrix(s.mReal, s.nReal);
    }

    // -- vertices moved by scripts (vertex:setPos and the like, cloth and hair cards) -- //

    // one list of vertices of a part, in the packed layout (FiguraModelPart.PACKED_STRIDE floats per vertex)
    static final class VertexBlend {
        float[] from = new float[0], to = new float[0], shown = new float[0], real = new float[0];
        long start, seen = Long.MIN_VALUE, interval;
    }

    /**
     * The vertices of a part whose scripts edit them, blended between the last runs of the render scripts.
     * @return the blended vertices in the packed layout, owned by the part and rewritten on its next call, read by the batch this frame
     */
    public static float[] blendVertices(FiguraModelPart part, java.util.List<org.figuramc.figura.model.rendering.Vertex> vertices, long interval, long now) {
        State s = state(part);
        if (s.vertices == null)
            s.vertices = new java.util.IdentityHashMap<>();
        VertexBlend b = s.vertices.computeIfAbsent(vertices, v -> new VertexBlend());

        int stride = FiguraModelPart.PACKED_STRIDE;
        int n = vertices.size() * stride;
        boolean resized = b.real.length != n;
        if (resized) {
            b.real = new float[n];
            b.from = new float[n];
            b.to = new float[n];
            b.shown = new float[n];
        }
        float[] real = b.real;
        for (int i = 0, o = 0; i < vertices.size(); i++, o += stride) {
            org.figuramc.figura.model.rendering.Vertex v = vertices.get(i);
            real[o] = v.x; real[o + 1] = v.y; real[o + 2] = v.z;
            real[o + 3] = v.u; real[o + 4] = v.v;
            real[o + 5] = v.nx; real[o + 6] = v.ny; real[o + 7] = v.nz;
        }

        if (resized || b.seen == Long.MIN_VALUE || now - b.seen > STALE_INTERVALS * interval) {
            System.arraycopy(real, 0, b.from, 0, n);
            System.arraycopy(real, 0, b.to, 0, n);
            System.arraycopy(real, 0, b.shown, 0, n);
            b.start = now;
            b.seen = now;
            b.interval = interval;
            return b.shown;
        }
        b.seen = now;

        if (!java.util.Arrays.equals(real, b.to)) {
            System.arraycopy(b.shown, 0, b.from, 0, n);
            System.arraycopy(real, 0, b.to, 0, n);
            b.start = now;
            b.interval = interval;
        }

        float t = (float) progress(now, b.start, b.interval);
        float[] from = b.from, to = b.to, shown = b.shown;
        for (int i = 0; i < n; i++)
            shown[i] = from[i] + (to[i] - from[i]) * t;
        return shown;
    }

    private static boolean same(double[] a, double[] b, int count) {
        for (int i = 0; i < count; i++)
            if (Double.doubleToLongBits(a[i]) != Double.doubleToLongBits(b[i]))
                return false;
        return true;
    }
}

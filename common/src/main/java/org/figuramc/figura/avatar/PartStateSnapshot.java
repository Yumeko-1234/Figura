package org.figuramc.figura.avatar;

import org.figuramc.figura.math.matrix.FiguraMat3;
import org.figuramc.figura.math.matrix.FiguraMat4;
import org.figuramc.figura.model.FiguraModelPart;
import org.figuramc.figura.model.PartCustomization;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// verifyOptimizations only (-Dfigura.verifyOptimizations=true): what the render scripts left on every part after a pass,
// so the shadow pass can be compared with the normal pass that would reuse it
final class PartStateSnapshot {

    // visible, position matrix, uv matrix, color, color2, alpha, light, overlay
    private static final int VALUES_PER_PART = 1 + 16 + 9 + 3 + 3 + 3;

    private final List<FiguraModelPart> parts = new ArrayList<>();
    private double[] values = new double[VALUES_PER_PART * 64];

    void take(FiguraModelPart root) {
        parts.clear();
        add(root);
    }

    private void add(FiguraModelPart part) {
        int base = parts.size() * VALUES_PER_PART;
        if (values.length < base + VALUES_PER_PART)
            values = Arrays.copyOf(values, values.length * 2);
        parts.add(part);

        PartCustomization c = part.customization;
        c.recalculate();
        double[] v = values;
        int i = base;
        v[i++] = c.visible ? 1 : 0;

        FiguraMat4 p = c.positionMatrix;
        v[i++] = p.v11; v[i++] = p.v12; v[i++] = p.v13; v[i++] = p.v14;
        v[i++] = p.v21; v[i++] = p.v22; v[i++] = p.v23; v[i++] = p.v24;
        v[i++] = p.v31; v[i++] = p.v32; v[i++] = p.v33; v[i++] = p.v34;
        v[i++] = p.v41; v[i++] = p.v42; v[i++] = p.v43; v[i++] = p.v44;

        FiguraMat3 t = c.uvMatrix;
        v[i++] = t.v11; v[i++] = t.v12; v[i++] = t.v13;
        v[i++] = t.v21; v[i++] = t.v22; v[i++] = t.v23;
        v[i++] = t.v31; v[i++] = t.v32; v[i++] = t.v33;

        v[i++] = c.color.x; v[i++] = c.color.y; v[i++] = c.color.z;
        v[i++] = c.color2.x; v[i++] = c.color2.y; v[i++] = c.color2.z;
        v[i++] = c.alpha == null ? Double.NaN : c.alpha;
        v[i++] = c.light == null ? Double.NaN : c.light;
        v[i] = c.overlay == null ? Double.NaN : c.overlay;

        for (int j = 0; j < part.children.size(); j++)
            add(part.children.get(j));
    }

    // the first part that is not the same in both, or null when they match
    // (a different part tree, from a script adding or removing parts, counts as the root being different)
    FiguraModelPart firstDifference(PartStateSnapshot other) {
        if (parts.size() != other.parts.size())
            return parts.isEmpty() ? null : parts.get(0);
        for (int p = 0; p < parts.size(); p++) {
            if (parts.get(p) != other.parts.get(p))
                return parts.get(p);
            int base = p * VALUES_PER_PART;
            for (int i = base; i < base + VALUES_PER_PART; i++) {
                if (Double.doubleToLongBits(values[i]) != Double.doubleToLongBits(other.values[i]))
                    return parts.get(p);
            }
        }
        return null;
    }
}

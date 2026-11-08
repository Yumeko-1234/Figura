package org.figuramc.figura.lua;

import org.figuramc.figura.math.vector.FiguraVec2;
import org.figuramc.figura.math.vector.FiguraVec3;
import org.figuramc.figura.model.rendering.Vertex;
import org.luaj.vm2.LuaUserdata;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

// direct versions of a few methods scripts call thousands of times per frame (hair and cloth scripts moving vertices one by one)
// the common argument forms are handled here without the generic wrapper (argument conversion, a vector copy, a new userdata for the
// returned object); anything else, errors included, still goes through the generic wrapper, so the results and messages are the same
public final class LuaFastPaths {

    // turned off with -Dfigura.luaFastPaths=false
    private static final boolean ENABLED = !"false".equalsIgnoreCase(System.getProperty("figura.luaFastPaths"));

    private LuaFastPaths() {}

    public static LuaValue wrap(Class<?> clazz, String name, VarArgFunction generic) {
        if (!ENABLED || clazz != Vertex.class)
            return generic;
        return switch (name) {
            case "setPos", "pos" -> new Vec3Setter(generic, 0);
            case "setNormal", "normal" -> new Vec3Setter(generic, 1);
            case "setUV", "uv" -> new Vec2Setter(generic);
            default -> generic;
        };
    }

    private static Vertex vertexOf(Varargs args) {
        LuaValue self = args.arg1();
        return self instanceof LuaUserdata u && u.m_instance instanceof Vertex v ? v : null;
    }

    private static boolean isNumber(LuaValue value) {
        // real numbers only, the generic wrapper treats numeric strings differently
        return value.type() == LuaValue.TNUMBER;
    }

    // vertex:setPos(vec) / vertex:setPos(x, y, z), and the same for setNormal
    private static final class Vec3Setter extends VarArgFunction {
        private final VarArgFunction generic;
        private final int target; // 0 position, 1 normal

        Vec3Setter(VarArgFunction generic, int target) {
            this.generic = generic;
            this.target = target;
        }

        @Override
        public Varargs invoke(Varargs args) {
            Vertex vertex = vertexOf(args);
            if (vertex != null) {
                int narg = args.narg();
                LuaValue a = args.arg(2);
                double x, y, z;
                boolean handled = false;
                x = y = z = 0;
                if (narg <= 2 && a instanceof LuaUserdata u && u.m_instance instanceof FiguraVec3 vec) {
                    x = vec.x; y = vec.y; z = vec.z;
                    handled = true;
                } else if (narg == 4 && isNumber(a) && isNumber(args.arg(3)) && isNumber(args.arg(4))) {
                    x = a.todouble(); y = args.arg(3).todouble(); z = args.arg(4).todouble();
                    handled = true;
                }
                if (handled) {
                    if (target == 0) {
                        vertex.x = (float) x; vertex.y = (float) y; vertex.z = (float) z;
                    } else {
                        vertex.nx = (float) x; vertex.ny = (float) y; vertex.nz = (float) z;
                    }
                    // the method returns the vertex itself
                    return args.arg1();
                }
            }
            return generic.invoke(args);
        }
    }

    // vertex:setUV(vec2) / vertex:setUV(u, v)
    private static final class Vec2Setter extends VarArgFunction {
        private final VarArgFunction generic;

        Vec2Setter(VarArgFunction generic) {
            this.generic = generic;
        }

        @Override
        public Varargs invoke(Varargs args) {
            Vertex vertex = vertexOf(args);
            if (vertex != null) {
                int narg = args.narg();
                LuaValue a = args.arg(2);
                if (narg <= 2 && a instanceof LuaUserdata u && u.m_instance instanceof FiguraVec2 vec) {
                    vertex.u = (float) vec.x; vertex.v = (float) vec.y;
                    return args.arg1();
                }
                if (narg == 3 && isNumber(a) && isNumber(args.arg(3))) {
                    vertex.u = (float) a.todouble(); vertex.v = (float) args.arg(3).todouble();
                    return args.arg1();
                }
            }
            return generic.invoke(args);
        }
    }
}

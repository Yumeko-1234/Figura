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

    // methods and metamethods (names starting with __) of a class, typeManager wraps the results exactly like the generic wrapper
    public static LuaValue wrap(LuaTypeManager typeManager, Class<?> clazz, String name, VarArgFunction generic) {
        if (!ENABLED)
            return generic;
        if (clazz == Vertex.class) {
            return switch (name) {
                case "setPos", "pos" -> new Vec3Setter(generic, 0);
                case "setNormal", "normal" -> new Vec3Setter(generic, 1);
                case "setUV", "uv" -> new Vec2Setter(generic);
                default -> generic;
            };
        }
        if (clazz == FiguraVec3.class) {
            int op = switch (name) {
                case "__add" -> VecOp.ADD;
                case "__sub" -> VecOp.SUB;
                case "__mul" -> VecOp.MUL;
                case "__div" -> VecOp.DIV;
                case "__unm" -> VecOp.UNM;
                case "length" -> VecOp.LENGTH;
                case "lengthSquared" -> VecOp.LENGTH_SQUARED;
                case "copy" -> VecOp.COPY;
                case "normalized" -> VecOp.NORMALIZED;
                case "dot" -> VecOp.DOT;
                default -> -1;
            };
            return op < 0 ? generic : new VecOp(typeManager, generic, op);
        }
        return generic;
    }

    // a vec3 or a plain number as the generic wrapper would pass it to the method (numbers as Double, which the methods read
    // with doubleValue()), null for anything else, which is then left to the generic wrapper
    private static Object vecOrNumber(LuaValue value) {
        if (value instanceof LuaUserdata u && u.m_instance instanceof FiguraVec3 vec)
            return vec;
        if (value.type() == LuaValue.TNUMBER)
            return value.todouble();
        return null;
    }

    private static FiguraVec3 vecOf(LuaValue value) {
        return value instanceof LuaUserdata u && u.m_instance instanceof FiguraVec3 vec ? vec : null;
    }

    // vec3 arithmetic and the most used vec3 methods: the same Java methods are called with the same arguments,
    // only the generic wrapper's argument checks and conversions are skipped
    private static final class VecOp extends VarArgFunction {
        static final int ADD = 0, SUB = 1, MUL = 2, DIV = 3, UNM = 4, LENGTH = 5, LENGTH_SQUARED = 6, COPY = 7, NORMALIZED = 8, DOT = 9;

        private final LuaTypeManager typeManager;
        private final VarArgFunction generic;
        private final int op;

        VecOp(LuaTypeManager typeManager, VarArgFunction generic, int op) {
            this.typeManager = typeManager;
            this.generic = generic;
            this.op = op;
        }

        @Override
        public Varargs invoke(Varargs args) {
            switch (op) {
                case ADD, SUB, MUL, DIV -> {
                    Object a = vecOrNumber(args.arg1()), b = vecOrNumber(args.arg(2));
                    // number op number never reaches a vector metamethod, and the methods throw the same errors either way
                    if (a == null || b == null || !(a instanceof FiguraVec3 || b instanceof FiguraVec3))
                        return generic.invoke(args);
                    FiguraVec3 result = switch (op) {
                        case ADD -> FiguraVec3.__add(a, b);
                        case SUB -> FiguraVec3.__sub(a, b);
                        case MUL -> FiguraVec3.__mul(a, b);
                        default -> FiguraVec3.__div(a, b);
                    };
                    return typeManager.javaToLua(result);
                }
                case DOT -> {
                    FiguraVec3 self = vecOf(args.arg1()), other = vecOf(args.arg(2));
                    if (self == null || other == null)
                        return generic.invoke(args);
                    return typeManager.javaToLua(self.dot(other));
                }
                default -> {
                    FiguraVec3 self = vecOf(args.arg1());
                    if (self == null)
                        return generic.invoke(args);
                    Object result = switch (op) {
                        case UNM -> self.__unm();
                        case LENGTH -> self.length();
                        case LENGTH_SQUARED -> self.lengthSquared();
                        case COPY -> self.copy();
                        default -> self.normalized();
                    };
                    return typeManager.javaToLua(result);
                }
            }
        }
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

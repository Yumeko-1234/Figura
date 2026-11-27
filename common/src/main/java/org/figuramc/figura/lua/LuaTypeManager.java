package org.figuramc.figura.lua;

import net.minecraft.network.chat.Component;
import org.figuramc.figura.utils.FiguraWorkers;
import org.figuramc.figura.FiguraMod;
import org.figuramc.figura.lua.docs.FiguraDocsManager;
import org.figuramc.figura.lua.docs.LuaTypeDoc;
import org.figuramc.figura.math.vector.FiguraVec2;
import org.figuramc.figura.math.vector.FiguraVec3;
import org.figuramc.figura.math.vector.FiguraVec4;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.lang.reflect.*;
import java.util.*;

import static java.util.Map.entry;
import static org.luaj.vm2.LuaValue.*;

/**
 * One LuaTypeManager per LuaRuntime, so that people can be allowed to edit the metatables within.
 */
public class LuaTypeManager {

    private final Map<Class<?>, LuaTable> metatables = new HashMap<>();
    // classes that javaToLua wraps as userdata (none of the types it converts first), with their metatable
    // what javaToLua does only depends on the class, so the next value of that class goes straight to the userdata
    private final Map<Class<?>, LuaTable> wrapCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static boolean wrapMismatchLogged;

    // v.x and the like, read directly: no vector method has a one character name, so a one character key on a vector is always
    // a component (the same ones as FiguraVec2/3/4.__index), this skips the method table lookup, the reflective call and a Java string
    // physics scripts read vector components thousands of times per frame, null when the key is anything else
    private static LuaValue vectorComponent(LuaValue self, LuaValue key) {
        if (!(key instanceof LuaString s) || s.m_length != 1 || !(self instanceof LuaUserdata u))
            return null;
        char c = (char) s.m_bytes[s.m_offset];
        Object o = u.m_instance;
        if (o instanceof FiguraVec3 v) return switch (c) {
            case '1', 'x', 'r' -> valueOf(v.x);
            case '2', 'y', 'g' -> valueOf(v.y);
            case '3', 'z', 'b' -> valueOf(v.z);
            case '_' -> ZERO;
            default -> null;
        };
        if (o instanceof FiguraVec2 v) return switch (c) {
            case '1', 'x', 'r' -> valueOf(v.x);
            case '2', 'y', 'g' -> valueOf(v.y);
            case '_' -> ZERO;
            default -> null;
        };
        if (o instanceof FiguraVec4 v) return switch (c) {
            case '1', 'x', 'r' -> valueOf(v.x);
            case '2', 'y', 'g' -> valueOf(v.y);
            case '3', 'z', 'b' -> valueOf(v.z);
            case '4', 'w', 'a' -> valueOf(v.w);
            case '_' -> ZERO;
            default -> null;
        };
        return null;
    }

    public void generateMetatableFor(Class<?> clazz) {
        if (metatables.containsKey(clazz))
            return;
        if (!clazz.isAnnotationPresent(LuaWhitelist.class))
            throw new IllegalArgumentException("Tried to generate metatable for un-whitelisted class " + clazz.getName() + "!");

        // Ensure that all whitelisted superclasses are loaded before this one
        try {
            generateMetatableFor(clazz.getSuperclass());
        } catch (IllegalArgumentException ignored) {}

        LuaTable metatable = new LuaTable();

        LuaTable indexTable = new LuaTable();
        Class<?> currentClass = clazz;
        while (currentClass.isAnnotationPresent(LuaWhitelist.class)) {
            for (Method method : currentClass.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(LuaWhitelist.class)) {
                    continue;
                }
                String name = method.getName();
                if (name.startsWith("__")) { // metamethods
                    if (metatable.rawget(name) == LuaValue.NIL) { // Only add the most recently declared metamethod, in the most specific subclass.
                        if (name.equals("__index")) {
                            // Custom __index implementation. First checks the regular __index table, and if it gets NIL, then calls the custom-defined __index function.
                            metatable.set("__index", new TwoArgFunction() {
                                final LuaFunction wrappedIndexer = getWrapper(method);
                                @Override
                                public LuaValue call(LuaValue arg1, LuaValue arg2) {
                                    LuaValue fast = vectorComponent(arg1, arg2);
                                    if (fast != null)
                                        return fast;
                                    LuaValue result = indexTable.get(arg2);
                                    if (result == LuaValue.NIL)
                                        result = wrappedIndexer.call(arg1, arg2);
                                    return result;
                                }
                            });
                        } else {
                            metatable.set(name, LuaFastPaths.wrap(this, currentClass, name, getWrapper(method)));
                        }
                    }
                } else { // regular methods
                    indexTable.set(LuaStringInterner.intern(name), LuaFastPaths.wrap(this, currentClass, name, getWrapper(method)));
                }
            }
            currentClass = currentClass.getSuperclass();
        }

        if (metatable.rawget("__index") == LuaValue.NIL)
            metatable.set("__index", indexTable);

        // if we don't have a special toString, then have our toString give the type name from the annotation
        if (metatable.rawget("__tostring") == LuaValue.NIL) {
            metatable.set("__tostring", new OneArgFunction() {
                private final LuaString val = LuaString.valueOf(clazz.getName());
                @Override
                public LuaValue call(LuaValue arg) {
                    return val;
                }
            });
        }

        // if we don't have a special __index, then have our indexer look in the next metatable up in the java inheritance.
        if (indexTable.rawget("__index") == LuaValue.NIL) {
            LuaTable superclassMetatable = metatables.get(clazz.getSuperclass());
            if (superclassMetatable != null) {
                LuaTable newMetatable = new LuaTable();
                newMetatable.set("__index", superclassMetatable.get("__index"));
                indexTable.setmetatable(newMetatable);
            }
        }

        metatables.put(clazz, metatable);
        // a new metatable can change which metatable a subclass gets
        wrapCache.clear();
    }

    public void dumpMetatables(LuaTable table) {
        for (Map.Entry<Class<?>, LuaTable> entry : metatables.entrySet()) {
            if (!entry.getKey().isAnnotationPresent(LuaTypeDoc.class))
                continue;
            String name = entry.getKey().getAnnotation(LuaTypeDoc.class).name();
            if (table.get(name) != LuaValue.NIL)
                throw new IllegalStateException("Two classes have the same type name: " + name);
            table.set(name, entry.getValue());
        }
    }

    private final Map<Class<?>, String> namesCache = new HashMap<>();
    public String getTypeName(Class<?> clazz) {
        return namesCache.computeIfAbsent(clazz, someClass -> {
            if (someClass.isAnnotationPresent(LuaTypeDoc.class))
                return someClass.getAnnotation(LuaTypeDoc.class).name();
            return someClass.getSimpleName();
        });
    }

    private static boolean[] getRequiredNotNil(Method method) {
        Parameter[] params = method.getParameters();
        boolean[] result = new boolean[params.length];
        for (int i = 0; i < params.length; i++)
            if (params[i].isAnnotationPresent(LuaNotNil.class))
                result[i] = true;
        return result;
    }

    // how each parameter is read from lua, worked out once per method instead of from the type name on every call
    private static final int ARG_DOUBLE = 0, ARG_STRING = 1, ARG_BOOLEAN = 2, ARG_FLOAT = 3, ARG_INT = 4, ARG_LONG = 5,
            ARG_TABLE = 6, ARG_FUNCTION = 7, ARG_VALUE = 8, ARG_OBJECT = 9, ARG_ARRAY = 10, ARG_USERDATA = 11;

    private static int argKind(Class<?> type) {
        return switch (type.getName()) {
            case "java.lang.Number", "java.lang.Double", "double" -> ARG_DOUBLE;
            case "java.lang.String" -> ARG_STRING;
            case "java.lang.Boolean", "boolean" -> ARG_BOOLEAN;
            case "java.lang.Float", "float" -> ARG_FLOAT;
            case "java.lang.Integer", "int" -> ARG_INT;
            case "java.lang.Long", "long" -> ARG_LONG;
            case "org.luaj.vm2.LuaTable" -> ARG_TABLE;
            case "org.luaj.vm2.LuaFunction" -> ARG_FUNCTION;
            case "org.luaj.vm2.LuaValue" -> ARG_VALUE;
            case "java.lang.Object" -> ARG_OBJECT;
            default -> type.getName().startsWith("[") ? ARG_ARRAY : ARG_USERDATA;
        };
    }

    // the value given for a missing or nil argument
    private static Object missingArg(Class<?> type) {
        return switch (type.getName()) {
            case "double" -> 0D;
            case "int" -> 0;
            case "long" -> 0L;
            case "float" -> 0f;
            case "boolean" -> false;
            default -> null;
        };
    }

    private static boolean argMismatchLogged;

    // types whose methods only work on their own object (and the avatar it belongs to) or compute a value: worker threads
    // running different avatars' tick scripts call them side by side, everything else runs under FiguraWorkers.LOCK
    private static boolean threadSafe(Class<?> clazz) {
        String name = clazz.getName();
        return name.startsWith("org.figuramc.figura.math.")
                || name.equals("org.figuramc.figura.lua.api.math.VectorsAPI")
                || name.equals("org.figuramc.figura.lua.api.math.MatricesAPI")
                || name.equals("org.figuramc.figura.model.FiguraModelPart")
                || name.equals("org.figuramc.figura.model.rendering.Vertex")
                || name.equals("org.figuramc.figura.animation.Animation");
    }

    public VarArgFunction getWrapper(Method method) {
        // a public method of a public class passes the access check anyway, so it is skipped on every call
        if (Modifier.isPublic(method.getModifiers()) && Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
            try {
                method.setAccessible(true);
            } catch (RuntimeException ignored) {}
        }

        return new VarArgFunction() {

            private final boolean isStatic = Modifier.isStatic(method.getModifiers());
            private Object caller;


            private final Class<?> clazz = method.getDeclaringClass();
            private final Class<?>[] argumentTypes = method.getParameterTypes();
            private final Object[] actualArgs = new Object[argumentTypes.length];
            private final boolean[] requiredNotNil = getRequiredNotNil(method);
            private final int[] argKinds = Arrays.stream(argumentTypes).mapToInt(LuaTypeManager::argKind).toArray();
            private final Object[] missingArgs = Arrays.stream(argumentTypes).map(LuaTypeManager::missingArg).toArray();
            // a direct call instead of reflection, null when the method keeps using reflection
            private LuaDirectInvokers.Invoker direct = LuaDirectInvokers.get(method);
            // run under Figura's worker lock when called from a worker thread (see AvatarManager's parallel tick scripts)
            private final boolean locked = !threadSafe(clazz) || (clazz.getName().equals("org.figuramc.figura.model.FiguraModelPart") && method.getName().startsWith("new"));

            @Override
            public Varargs invoke(Varargs args) {
                if (locked && FiguraWorkers.needsApiLock()) {
                    FiguraWorkers.lockForApi();
                    try {
                        return call(args);
                    } finally {
                        FiguraWorkers.LOCK.unlock();
                    }
                }
                return call(args);
            }

            private Varargs call(Varargs args) {

                if (!isStatic) {
                    try {
                        caller = args.checkuserdata(1, clazz);
                    } catch (LuaError e) {
                        String methodName = method.getName();
                        String targetType = getTypeName(clazz);
                        if (OPERATORS.containsKey(methodName)) {
                            String typeName;
                            if (args.isuserdata(1)) {
                                typeName = FiguraDocsManager.getNameFor(args.checkuserdata(1).getClass());
                            } else typeName = args.arg1().typename();

                            OperatorInfo operator = OPERATORS.get(methodName);
                            if (operator.binary) throw new LuaError(String.format(
                                    "Expected left-hand side of %s to be a %s; actually got %s",
                                    operator.name,
                                    targetType,
                                    typeName
                            ));
                            else throw new LuaError(String.format(
                                    "Expected subject of %s to be a %s; actually got %s",
                                    operator.name,
                                    targetType,
                                    typeName
                            ));
                        }
                        throw new LuaError(String.format(
                                "Use a colon (:) to call %s on a %s, instead of a dot.\nFor example, change .%s( to :%s(",
                                methodName,
                                targetType,
                                methodName,
                                methodName
                        ));
                    }
                }

                // dirty hack for QOL of ignoring the first argument if the method is static and the arg matches the class type
                int offset = isStatic && argumentTypes.length > 0 && !argumentTypes[0].isAssignableFrom(clazz) && args.isuserdata(1) && clazz.isAssignableFrom(args.checkuserdata(1).getClass()) ? 1 : 0;

                // Fill in actualArgs from args
                // (each argument is read from the varargs once, args.checkX(i) is the same as args.arg(i).checkX())
                int narg = args.narg();
                for (int i = 0; i < argumentTypes.length; i++) {
                    int argIndex = i + (isStatic ? 1 : 2) + offset;
                    LuaValue arg = args.arg(argIndex);
                    boolean nil = arg.isnil();
                    if (nil && requiredNotNil[i])
                        throw new LuaError("bad argument: " + method.getName() + " " + argIndex + " do not allow nil values, expected " + FiguraDocsManager.getNameFor(argumentTypes[i]));
                    if (argIndex <= narg && !nil) {
                        try {
                            actualArgs[i] = switch (argKinds[i]) {
                                case ARG_DOUBLE -> arg.checkdouble();
                                case ARG_STRING -> arg.checkjstring();
                                case ARG_BOOLEAN -> arg.toboolean();
                                case ARG_FLOAT -> (float) arg.checkdouble();
                                case ARG_INT -> arg.checkint();
                                case ARG_LONG -> arg.checklong();
                                case ARG_TABLE -> arg.checktable();
                                case ARG_FUNCTION -> arg.checkfunction();
                                case ARG_VALUE -> arg;
                                case ARG_OBJECT -> luaToJava(arg);
                                case ARG_ARRAY -> luaVarargToJava(args, argIndex, argumentTypes[i]);
                                default -> arg.checkuserdata(argumentTypes[i]);
                            };
                            if (FiguraMod.verifyOptimizations())
                                verifyArg(actualArgs[i], args, argIndex, argumentTypes[i]);
                        } catch (LuaError err) {
                            String expectedType = FiguraDocsManager.getNameFor(argumentTypes[i]);
                            String actualType;
                            if (args.arg(argIndex).type() == LuaValue.TUSERDATA)
                                actualType = FiguraDocsManager.getNameFor(args.arg(argIndex).checkuserdata().getClass());
                            else
                                actualType = args.arg(argIndex).typename();
                            throw new LuaError("Invalid argument " + argIndex + " to function " + method.getName() + ". Expected " + expectedType + ", but got " + actualType);
                        }
                    } else {
                        actualArgs[i] = missingArgs[i];
                    }
                }

                // Invoke the wrapped method
                Object result;
                LuaDirectInvokers.Invoker invoker = direct;
                if (invoker != null) {
                    try {
                        result = invoker.invoke(caller, actualArgs);
                    } catch (LuaError l) {
                        throw l;
                    } catch (LinkageError | ClassCastException e) {
                        // thrown by the generated call itself (before the method ran): use reflection for this method from now on
                        if (!thrownBy(e, invoker, getClass())) throw new LuaError(e);
                        direct = null;
                        logDirectCallFailure(method, e);
                        result = invokeReflectively();
                    } catch (Throwable t) {
                        // the same as reflection: anything thrown by the method becomes a lua error
                        throw new LuaError(t);
                    }
                } else {
                    result = invokeReflectively();
                }

                // Convert the return value
                return result instanceof Varargs v ? v : javaToLua(result);
            }

            private Object invokeReflectively() {
                try {
                    return method.invoke(caller, actualArgs);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw e.getCause() instanceof LuaError l ? l : new LuaError(e.getCause());
                }
            }

            @Override
            public String tojstring() {
                return "function: " + method.getName();
            }
        };
    }

    // if the error comes from the generated call class itself, not from the method it calls
    // the generated class is hidden, so its frame is usually left out of stack traces and the wrapper calling it is on top instead
    // (an error from inside the method always has a frame of that method on top)
    private static boolean thrownBy(Throwable e, LuaDirectInvokers.Invoker invoker, Class<?> wrapper) {
        StackTraceElement[] trace = e.getStackTrace();
        if (trace.length == 0)
            return false;
        String top = trace[0].getClassName();
        return top.equals(invoker.getClass().getName()) || top.equals(wrapper.getName());
    }

    private static boolean directCallFailureLogged;

    private static void logDirectCallFailure(Method method, Throwable e) {
        if (directCallFailureLogged)
            return;
        directCallFailureLogged = true;
        FiguraMod.LOGGER.warn("Direct call of the lua api method {}.{} failed, it uses reflection from now on, please report this",
                method.getDeclaringClass().getName(), method.getName(), e);
    }

    // verifyOptimizations: reads the argument again the way it was read before (by type name) and reports the first difference
    private void verifyArg(Object value, Varargs args, int argIndex, Class<?> type) {
        Object expected = switch (type.getName()) {
            case "java.lang.Number", "java.lang.Double", "double" -> args.checkdouble(argIndex);
            case "java.lang.String" -> args.checkjstring(argIndex);
            case "java.lang.Boolean", "boolean" -> args.toboolean(argIndex);
            case "java.lang.Float", "float" -> (float) args.checkdouble(argIndex);
            case "java.lang.Integer", "int" -> args.checkint(argIndex);
            case "java.lang.Long", "long" -> args.checklong(argIndex);
            case "org.luaj.vm2.LuaTable" -> args.checktable(argIndex);
            case "org.luaj.vm2.LuaFunction" -> args.checkfunction(argIndex);
            case "org.luaj.vm2.LuaValue" -> args.arg(argIndex);
            case "java.lang.Object" -> luaToJava(args.arg(argIndex));
            default -> type.getName().startsWith("[") ? luaVarargToJava(args, argIndex, type) : args.checkuserdata(argIndex, type);
        };
        // same class and value (arrays by content, they are made new on every read)
        boolean same = value == expected || (value != null && expected != null && value.getClass() == expected.getClass()
                && (value.getClass().isArray() ? Arrays.deepEquals(new Object[] {value}, new Object[] {expected}) : value.equals(expected)));
        if (!same && !argMismatchLogged) {
            argMismatchLogged = true;
            FiguraMod.LOGGER.warn("Lua argument {} read as {} ({}) instead of {} ({}) for a {} parameter, please report this",
                    argIndex, value, value == null ? null : value.getClass().getName(), expected, expected == null ? null : expected.getClass().getName(), type.getName());
        }
    }

    private LuaValue wrap(Object instance) {
        Class<?> clazz = instance.getClass();
        LuaTable metatable = metatables.get(clazz);
        while (metatable == null) {
            clazz = clazz.getSuperclass();
            if (clazz == Object.class)
                throw new RuntimeException("Attempt to wrap illegal type " + instance.getClass().getName() + " (not registered in LuaTypeManager's \"metatables\" map)!");
            metatable = metatables.get(clazz);
        }

        wrapCache.put(instance.getClass(), metatable);
        LuaUserdata result = new LuaUserdata(instance);
        result.setmetatable(metatable);
        return result;
    }

    private LuaValue wrapMap(Map<?, ?> map) {
        LuaTable table = new LuaTable();

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            LuaValue key = javaToLua(entry.getKey()).arg1();
            LuaValue val = javaToLua(entry.getValue()).arg1();
            table.set(key, val);
        }

        return table;
    }

    private LuaValue wrapCollection(Collection<?> collection) {
        LuaTable table = new LuaTable();

        int i = 1;
        for (Object o : collection) {
            table.set(i++, javaToLua(o).arg1());
        }

        return table;
    }

    private Varargs wrapArray(Object array) {
        int len = Array.getLength(array);
        LuaValue[] args = new LuaValue[len];

        for (int i = 0; i < len; i++)
            args[i] = javaToLua(Array.get(array, i)).arg1();

        return LuaValue.varargsOf(args);
    }

    public Object luaVarargToJava(Varargs args, int argIndex, Class<?> argumentType) {
        if (args.arg(argIndex).istable()) {
            return luaVarargToJava(args.checktable(argIndex).unpack(), 1, argumentType);
        } else {
            Object[] obj = new Object[args.narg() - argIndex + 1];
            for (int start = argIndex; argIndex <= args.narg(); argIndex++) {
                obj[argIndex - start] = switch (argumentType.getName()) {
                    case "[Ljava.lang.Number;", "[Ljava.lang.Double;", "[D" -> args.checkdouble(argIndex);
                    case "[Ljava.lang.String;" -> args.checkjstring(argIndex);
                    case "[Ljava.lang.Boolean;", "[B" -> args.toboolean(argIndex);
                    case "[Ljava.lang.Float;", "[F" -> (float) args.checkdouble(argIndex);
                    case "[Ljava.lang.Integer;", "[I" -> args.checkint(argIndex);
                    case "[Ljava.lang.Long;", "[J" -> args.checklong(argIndex);
                    case "[Lorg.luaj.vm2.LuaTable;" -> args.checktable(argIndex);
                    case "[Lorg.luaj.vm2.LuaFunction;" -> args.checkfunction(argIndex);
                    case "[Lorg.luaj.vm2.LuaValue;" -> args.arg(argIndex);
                    case "[Ljava.lang.Object;" -> luaToJava(args.arg(argIndex));
                    default -> args.checkuserdata(argIndex, argumentType);
                };
            }
            return Arrays.copyOf(obj, obj.length, (Class<? extends Object[]>) argumentType);
        }
    }

    // we need to allow string being numbers here
    // however in places like pings and print we should keep strings as strings
    public Object luaToJava(LuaValue val) {
        if (val.istable())
            return val.checktable();
        else if (val.isnumber())
            if (val instanceof LuaInteger i) // dumb
                return i.checkint();
            else if (val.isint() && val instanceof LuaString s) // very dumb
                return s.checkint();
            else
                return val.checkdouble();
        else if (val.isstring())
            return val.checkjstring();
        else if (val.isboolean())
            return val.checkboolean();
        else if (val.isfunction())
            return val.checkfunction();
        else if (val.isuserdata())
            return val.checkuserdata(Object.class);
        else
            return null;
    }

    public Varargs javaToLua(Object val) {
        if (val == null)
            return LuaValue.NIL;

        LuaTable cached = wrapCache.get(val.getClass());
        if (cached != null) {
            LuaUserdata result = new LuaUserdata(val, cached);
            if (FiguraMod.verifyOptimizations())
                verifyWrap(val, result);
            return result;
        }

        return convertToLua(val);
    }

    // verifyOptimizations: converts the value again without the cache and reports the first difference
    private void verifyWrap(Object val, LuaUserdata cached) {
        Varargs expected = convertToLua(val);
        boolean same = expected instanceof LuaUserdata u && u.m_instance == val && u.getmetatable() == cached.getmetatable();
        if (!same && !wrapMismatchLogged) {
            wrapMismatchLogged = true;
            FiguraMod.LOGGER.warn("Cached lua conversion of a {} differs from the normal one, please report this", val.getClass().getName());
        }
    }

    private Varargs convertToLua(Object val) {
        if (val instanceof LuaValue l)
            return l;
        else if (val instanceof Double d)
            return LuaValue.valueOf(d);
        else if (val instanceof String s)
            return LuaValue.valueOf(s);
        else if (val instanceof Boolean b)
            return LuaValue.valueOf(b);
        else if (val instanceof Integer i)
            return LuaValue.valueOf(i);
        else if (val instanceof Float f)
            return LuaValue.valueOf(f);
        else if (val instanceof Byte b)
            return LuaValue.valueOf(b);
        else if (val instanceof Long l)
            return LuaValue.valueOf(l);
        else if (val instanceof Character c)
            return LuaValue.valueOf(c);
        else if (val instanceof Short s)
            return LuaValue.valueOf(s);
        else if (val instanceof Map<?,?> map)
            return wrapMap(map);
        else if (val instanceof Collection<?> collection)
            return wrapCollection(collection);
        else if (val.getClass().isArray())
            return wrapArray(val);
        else if (val instanceof Component c)
            return LuaValue.valueOf(Component.Serializer.toJson(c));
        else
            return wrap(val);
    }

    private record OperatorInfo(String name, boolean binary) {}

    private static final Map<String, OperatorInfo> OPERATORS = Map.ofEntries(
            entry(INDEX.tojstring(), new OperatorInfo("indexing", true)),
            entry(NEWINDEX.tojstring(), new OperatorInfo("indexing", true)),
            entry(CALL.tojstring(), new OperatorInfo("function call", false)),
            entry(ADD.tojstring(), new OperatorInfo("+", true)),
            entry(SUB.tojstring(), new OperatorInfo("-", true)),
            entry(DIV.tojstring(), new OperatorInfo("/", true)),
            entry(MUL.tojstring(), new OperatorInfo("*", true)),
            entry(POW.tojstring(), new OperatorInfo("^", true)),
            entry(MOD.tojstring(), new OperatorInfo("%", true)),
            entry(UNM.tojstring(), new OperatorInfo("-", false)),
            entry(LEN.tojstring(), new OperatorInfo("#", false)),
            entry(EQ.tojstring(), new OperatorInfo("==", true)),
            entry(LT.tojstring(), new OperatorInfo("<", true)),
            entry(LE.tojstring(), new OperatorInfo("<=", true)),
            entry(TOSTRING.tojstring(), new OperatorInfo("tostring", false)),
            entry(CONCAT.tojstring(), new OperatorInfo("..", true))
    );
}

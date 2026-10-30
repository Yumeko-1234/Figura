package org.figuramc.figura.lua;

import org.figuramc.figura.FiguraMod;
import org.luaj.vm2.LuaValue;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// generates a small class per lua api method that calls it directly, instead of through reflection
// the call is the same java call reflection makes (virtual dispatch included), only without the reflection layers in between
// methods it can't safely call this way keep using reflection
final class LuaDirectInvokers {

    // the generated class is defined next to this one, so it can only see what this package can see
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final Module OWN_MODULE = LuaDirectInvokers.class.getModule();
    private static final Module JAVA_MODULE = Object.class.getModule();
    private static final Module LUAJ_MODULE = LuaValue.class.getModule();

    // one per method for the whole game, the wrappers of every avatar share it
    private static final Map<Method, Invoker> CACHE = new ConcurrentHashMap<>();
    private static final Invoker NONE = new Invoker() {
        @Override
        public Object invoke(Object target, Object[] args) {
            throw new UnsupportedOperationException();
        }
    };

    private static boolean failureLogged;

    private LuaDirectInvokers() {}

    // the arguments are exactly the values reflection would get: already converted to the parameter types (primitives boxed)
    public abstract static class Invoker {
        public abstract Object invoke(Object target, Object[] args) throws Throwable;
    }

    // null when the method must keep using reflection
    static Invoker get(Method method) {
        try {
            Invoker invoker = CACHE.computeIfAbsent(method, LuaDirectInvokers::create);
            return invoker == NONE ? null : invoker;
        } catch (Throwable t) {
            logFailure(method, t);
            return null;
        }
    }

    private static void logFailure(Method method, Throwable t) {
        if (failureLogged)
            return;
        failureLogged = true;
        FiguraMod.LOGGER.warn("Failed to make a direct call for the lua api method {}.{}, it keeps using reflection (further failures are not logged)",
                method.getDeclaringClass().getName(), method.getName(), t);
    }

    private static Invoker create(Method method) {
        String reason = unsupportedReason(method);
        if (reason != null) {
            FiguraMod.debug("Lua api method {}.{} uses reflection: {}", method.getDeclaringClass().getSimpleName(), method.getName(), reason);
            return NONE;
        }
        try {
            byte[] bytes = Generator.generate(method);
            Class<?> clazz = LOOKUP.defineHiddenClass(bytes, true).lookupClass();
            return (Invoker) clazz.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            logFailure(method, t);
            return NONE;
        }
    }

    // only what the generated class can surely see and convert the same way reflection does
    private static String unsupportedReason(Method method) {
        Class<?> owner = method.getDeclaringClass();
        if (!Modifier.isPublic(method.getModifiers()) || !Modifier.isPublic(owner.getModifiers()))
            return "not public";
        if (owner.isInterface())
            return "declared in an interface";
        if (owner.getModule() != OWN_MODULE || owner.getClassLoader() != LuaDirectInvokers.class.getClassLoader())
            return "from another mod";
        for (Class<?> type : method.getParameterTypes()) {
            if (type.isPrimitive()) {
                // the only primitives the wrapper gives (as their boxes)
                if (type != double.class && type != int.class && type != long.class && type != float.class && type != boolean.class)
                    return "has a " + type.getName() + " parameter";
                continue;
            }
            Class<?> element = type;
            while (element.isArray())
                element = element.getComponentType();
            if (element.isPrimitive())
                continue;
            if (!Modifier.isPublic(element.getModifiers()))
                return "has a non public parameter type";
            Module module = element.getModule();
            if (module != OWN_MODULE && module != JAVA_MODULE && module != LUAJ_MODULE)
                return "has a parameter type from another mod";
        }
        return null;
    }

    // separate class, so without asm only this fails to load (and every method keeps using reflection)
    private static final class Generator {
        private static final String INVOKER = Type.getInternalName(Invoker.class);

        private static byte[] generate(Method method) {
            Class<?> owner = method.getDeclaringClass();
            boolean isStatic = Modifier.isStatic(method.getModifiers());
            String name = LuaDirectInvokers.class.getPackageName().replace('.', '/') + "/LuaDirectInvoker";

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER, name, null, INVOKER, null);

            MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, INVOKER, "<init>", "()V", false);
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();

            // Object invoke(Object target, Object[] args) { return owner.method((T0) args[0], ...); }
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", null, new String[] {"java/lang/Throwable"});
            mv.visitCode();
            String ownerName = Type.getInternalName(owner);
            if (!isStatic) {
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitTypeInsn(Opcodes.CHECKCAST, ownerName);
            }
            Class<?>[] params = method.getParameterTypes();
            for (int i = 0; i < params.length; i++) {
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                mv.visitLdcInsn(i);
                mv.visitInsn(Opcodes.AALOAD);
                Class<?> type = params[i];
                if (type.isPrimitive()) {
                    String box = Type.getInternalName(box(type));
                    mv.visitTypeInsn(Opcodes.CHECKCAST, box);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, box, type.getName() + "Value", "()" + Type.getDescriptor(type), false);
                } else if (type != Object.class) {
                    mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(type));
                }
            }
            mv.visitMethodInsn(isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL, ownerName, method.getName(), Type.getMethodDescriptor(method), false);

            Class<?> ret = method.getReturnType();
            if (ret == void.class) {
                mv.visitInsn(Opcodes.ACONST_NULL);
            } else if (ret.isPrimitive()) {
                String box = Type.getInternalName(box(ret));
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, box, "valueOf", "(" + Type.getDescriptor(ret) + ")L" + box + ";", false);
            }
            mv.visitInsn(Opcodes.ARETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();

            cw.visitEnd();
            return cw.toByteArray();
        }

        private static Class<?> box(Class<?> primitive) {
            if (primitive == double.class) return Double.class;
            if (primitive == int.class) return Integer.class;
            if (primitive == long.class) return Long.class;
            if (primitive == float.class) return Float.class;
            if (primitive == boolean.class) return Boolean.class;
            if (primitive == short.class) return Short.class;
            if (primitive == byte.class) return Byte.class;
            if (primitive == char.class) return Character.class;
            throw new IllegalArgumentException(primitive.getName());
        }
    }
}

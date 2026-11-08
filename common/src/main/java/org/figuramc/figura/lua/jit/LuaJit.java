package org.figuramc.figura.lua.jit;

import org.figuramc.figura.FiguraMod;
import org.luaj.vm2.CompiledFunction;
import org.luaj.vm2.Lua;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Prototype;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Compiles Lua functions (luaj prototypes) to JVM bytecode.
 * <p>
 * Each prototype becomes a hidden class extending {@link CompiledFunction}, whose {@code execute} does what the interpreter
 * (LuaClosure.execute) does for each of its instructions, as straight-line bytecode with real jumps instead of a dispatch loop:
 * the same stack array, upvalues, value operations, calls and call frames, so a compiled function behaves like the interpreted one.
 * Instructions are counted per block for the instruction limit (count hook), and the call frame's instruction is kept up to date
 * wherever a call or an error can happen, so error positions and tracebacks stay exact.
 * <p>
 * Tiered: functions run interpreted until they were called {@link #HOT_CALLS} times, then they are compiled on a background
 * thread and every closure of them switches to the compiled code on its next call. Functions it can't compile stay interpreted.
 * The classes are hidden classes without strong links, unloaded together with the avatar's functions.
 */
public final class LuaJit implements Opcodes {

    /** -Dfigura.luaJit=true / false overrides the config */
    private static final String PROPERTY = System.getProperty("figura.luaJit");
    /** calls before a function is compiled */
    public static final int HOT_CALLS = Integer.getInteger("figura.luaJit.hotCalls", 50);

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final AtomicInteger COMPILED = new AtomicInteger(), FAILED = new AtomicInteger();
    private static volatile boolean failureLogged;
    private static volatile ExecutorService worker;

    private static final String SELF = "org/figuramc/figura/lua/jit/LuaJitFunction";
    private static final String CC = "org/luaj/vm2/CompiledFunction", LC = "org/luaj/vm2/LuaClosure", LV = "org/luaj/vm2/LuaValue",
            VA = "org/luaj/vm2/Varargs", UV = "org/luaj/vm2/UpValue", PR = "org/luaj/vm2/Prototype",
            ST = "org/luaj/vm2/LuaThread$State", CF = "org/luaj/vm2/lib/DebugLib$CallFrame", TV = "org/luaj/vm2/TailcallVarargs",
            LT = "org/luaj/vm2/LuaTable";
    private static final String D_LV = "L" + LV + ";", D_VA = "L" + VA + ";", D_STACK = "[L" + LV + ";", D_UPS = "[L" + UV + ";",
            D_ST = "L" + ST + ";", D_CF = "L" + CF + ";", D_LC = "L" + LC + ";";

    // locals of the generated run(LuaClosure cl, LuaValue[] stack, LuaValue x0, LuaValue x1, LuaValue x2, Varargs varargs)
    private static final int CL = 1, STACK = 2, X0 = 3, VARARGS = 6, K = 7, OPENUPS = 8, V = 9, TOP = 10, FRAME = 11, PC = 12,
            TMP = 13, RET = 14, ERR = 15;

    private LuaJit() {}

    public static int compiledCount() {
        return COMPILED.get();
    }

    public static int failedCount() {
        return FAILED.get();
    }

    /** Turns compiling hot functions on or off (the system property wins over the config). Functions compiled stay compiled. */
    public static void configure(boolean enabled) {
        if (PROPERTY != null)
            enabled = Boolean.parseBoolean(PROPERTY);
        if (enabled) {
            CompiledFunction.listener = LuaJit::queue;
            CompiledFunction.threshold = Math.max(HOT_CALLS, 1);
        } else {
            CompiledFunction.threshold = 0;
            CompiledFunction.listener = null;
        }
    }

    private static void queue(Prototype p) {
        ExecutorService w = worker;
        if (w == null) {
            synchronized (LuaJit.class) {
                if ((w = worker) == null)
                    worker = w = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "Figura Lua compiler");
                        t.setDaemon(true);
                        t.setPriority(Thread.MIN_PRIORITY);
                        return t;
                    });
            }
        }
        w.execute(() -> compile(p));
    }

    /** Compiles the prototype and every function defined in it now; the ones that fail stay interpreted. */
    public static void compileAll(Prototype p) {
        compile(p);
        if (p.p != null)
            for (Prototype child : p.p)
                compileAll(child);
    }

    /** Compiles one prototype, its closures run the compiled code from their next call. */
    public static boolean compile(Prototype p) {
        if (p.compiled != null)
            return true;
        try {
            byte[] bytes = generate(p);
            MethodHandles.Lookup hidden = LOOKUP.defineHiddenClass(bytes, true);
            p.compiled = (CompiledFunction) hidden.findConstructor(hidden.lookupClass(), MethodType.methodType(void.class)).invoke();
            COMPILED.incrementAndGet();
            return true;
        } catch (Throwable t) {
            FAILED.incrementAndGet();
            if (!failureLogged) {
                failureLogged = true;
                FiguraMod.LOGGER.warn("Could not compile a Lua function ({}:{}), it stays interpreted (further failures are not logged)",
                        p.shortsource(), p.linedefined, t);
            }
            return false;
        }
    }

    // -- code generation -- //

    static byte[] generate(Prototype p) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return commonSuperClass(a, b);
            }
        };
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, SELF, null, CC, null);
        cw.visitSource(p.shortsource(), null);

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, CC, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor mv = cw.visitMethod(ACC_PROTECTED, "run", "(" + D_LC + D_STACK + D_LV + D_LV + D_LV + D_VA + ")" + D_VA, null, null);
        mv.visitCode();
        new Body(p, mv).emit();
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static String commonSuperClass(String a, String b) {
        try {
            ClassLoader loader = LuaValue.class.getClassLoader();
            Class<?> ca = Class.forName(a.replace('/', '.'), false, loader);
            Class<?> cb = Class.forName(b.replace('/', '.'), false, loader);
            if (ca.isAssignableFrom(cb))
                return a;
            if (cb.isAssignableFrom(ca))
                return b;
            if (ca.isInterface() || cb.isInterface())
                return "java/lang/Object";
            do {
                ca = ca.getSuperclass();
            } while (!ca.isAssignableFrom(cb));
            return ca.getName().replace('.', '/');
        } catch (ClassNotFoundException e) {
            return "java/lang/Object";
        }
    }

    // the generated execute method of one prototype
    private static final class Body {
        private final Prototype p;
        private final MethodVisitor mv;
        private final int[] code;
        private final int n;
        private final Label[] labels;
        // instructions starting a block (counted together), and words that are data of the instruction before (not run)
        private final boolean[] leader, data;
        // instructions jumped to (not only reached from the one before)
        private final boolean[] target;
        private final boolean openups;
        // registers captured as upvalues by inner functions stay in the stack array (their upvalues point at it),
        // all others live in JVM locals and are only copied to the array for the instructions reading ranges of it
        private final boolean[] captured;
        private final int maxReg;
        // false: no instruction uses the stack array, a direct call runs without one
        private final boolean needsStack;

        Body(Prototype p, MethodVisitor mv) {
            this.p = p;
            this.mv = mv;
            this.code = p.code;
            this.n = code.length;
            this.labels = new Label[n + 2];
            for (int i = 0; i < labels.length; i++)
                labels[i] = new Label();
            this.leader = new boolean[n + 2];
            this.data = new boolean[n + 2];
            this.target = new boolean[n + 2];
            this.openups = p.p.length > 0;
            this.maxReg = p.maxstacksize - 1;
            this.captured = new boolean[Math.max(p.maxstacksize, 1)];
            for (Prototype child : p.p)
                for (var uv : child.upvalues)
                    if (uv.instack && uv.idx >= 0 && uv.idx < captured.length)
                        captured[uv.idx] = true;
            findBlocks();
            this.needsStack = usesStack();
        }

        private void findBlocks() {
            leader[0] = true;
            for (int pc = 0; pc < n; pc++) {
                if (data[pc])
                    continue;
                int i = code[pc];
                switch (Lua.GET_OPCODE(i)) {
                    case Lua.OP_JMP, Lua.OP_FORLOOP, Lua.OP_FORPREP, Lua.OP_TFORLOOP -> {
                        mark(pc + 1 + Lua.GETARG_sBx(i));
                        markTarget(pc + 1 + Lua.GETARG_sBx(i));
                        mark(pc + 1);
                    }
                    case Lua.OP_EQ, Lua.OP_LT, Lua.OP_LE, Lua.OP_TEST, Lua.OP_TESTSET -> {
                        mark(pc + 1);
                        mark(pc + 2);
                        markTarget(pc + 2);
                    }
                    case Lua.OP_LOADBOOL -> {
                        if (Lua.GETARG_C(i) != 0) {
                            mark(pc + 1);
                            mark(pc + 2);
                            markTarget(pc + 2);
                        }
                    }
                    case Lua.OP_RETURN, Lua.OP_TAILCALL -> mark(pc + 1);
                    case Lua.OP_LOADKX -> data[pc + 1] = true;
                    case Lua.OP_SETLIST -> {
                        if (Lua.GETARG_C(i) == 0)
                            data[pc + 1] = true;
                    }
                    default -> {}
                }
            }
        }

        // the instructions reading ranges of registers from the stack array (see spill), captured registers and closures
        private boolean usesStack() {
            if (openups)
                return true;
            for (boolean c : captured)
                if (c)
                    return true;
            for (int pc = 0; pc < n; pc++) {
                if (data[pc])
                    continue;
                int i = code[pc], b = Lua.GETARG_B(i), c = Lua.GETARG_C(i);
                switch (Lua.GET_OPCODE(i)) {
                    case Lua.OP_CONCAT -> {
                        if (c != b + 1)
                            return true;
                    }
                    case Lua.OP_CALL, Lua.OP_TAILCALL -> {
                        if (b == 0 || b > 4)
                            return true;
                    }
                    case Lua.OP_RETURN -> {
                        // the RETURN the compiler puts after every TAILCALL is never reached
                        if ((b == 0 && !unreachable(pc)) || b > 4)
                            return true;
                    }
                    case Lua.OP_SETLIST, Lua.OP_CLOSURE -> {
                        return true;
                    }
                    default -> {}
                }
            }
            return false;
        }

        private void markTarget(int pc) {
            if (pc >= 0 && pc < target.length)
                target[pc] = true;
        }

        // the RETURN after a TAILCALL: only reached by jumping to it
        private boolean unreachable(int pc) {
            return pc > 0 && !target[pc] && !data[pc - 1] && Lua.GET_OPCODE(code[pc - 1]) == Lua.OP_TAILCALL;
        }

        private void mark(int pc) {
            if (pc >= 0 && pc < leader.length)
                leader[pc] = true;
        }

        // instructions run in the block starting at pc (data words are not instructions)
        private int blockLength(int pc) {
            int count = 0;
            for (int j = pc; j < n; j++) {
                if (j > pc && leader[j])
                    break;
                if (!data[j])
                    count++;
            }
            return count;
        }

        void emit() {
            // a stack array only when the function needs one (a direct call passes none)
            if (needsStack) {
                Label have = new Label();
                mv.visitVarInsn(ALOAD, STACK);
                mv.visitJumpInsn(IFNONNULL, have);
                mv.visitVarInsn(ALOAD, CL);
                mv.visitVarInsn(ALOAD, X0);
                mv.visitVarInsn(ALOAD, X0 + 1);
                mv.visitVarInsn(ALOAD, X0 + 2);
                mv.visitMethodInsn(INVOKESTATIC, CC, "newStack", "(" + D_LC + D_LV + D_LV + D_LV + ")" + D_STACK, false);
                mv.visitVarInsn(ASTORE, STACK);
                mv.visitLabel(have);
            }
            // k = p.k, openups, v = NONE, top = 0, pc = 0
            mv.visitVarInsn(ALOAD, CL);
            mv.visitFieldInsn(GETFIELD, LC, "p", "L" + PR + ";");
            mv.visitFieldInsn(GETFIELD, PR, "k", D_STACK);
            mv.visitVarInsn(ASTORE, K);
            if (openups) {
                mv.visitVarInsn(ALOAD, STACK);
                mv.visitInsn(ARRAYLENGTH);
                mv.visitTypeInsn(ANEWARRAY, UV);
            } else {
                mv.visitInsn(ACONST_NULL);
            }
            mv.visitVarInsn(ASTORE, OPENUPS);
            mv.visitFieldInsn(GETSTATIC, LV, "NONE", D_LV);
            mv.visitVarInsn(ASTORE, V);
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, TOP);
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, PC);
            mv.visitInsn(ACONST_NULL);
            mv.visitVarInsn(ASTORE, TMP);
            // registers: the parameters (the first three passed directly, more only in the stack array), the rest nil;
            // captured parameters are already in the stack array
            for (int r = 0; r <= maxReg; r++) {
                if (captured[r])
                    continue;
                if (r < p.numparams && r < 3) {
                    mv.visitVarInsn(ALOAD, X0 + r);
                } else if (r < p.numparams) {
                    mv.visitVarInsn(ALOAD, STACK);
                    push(r);
                    mv.visitInsn(AALOAD);
                } else {
                    mv.visitFieldInsn(GETSTATIC, LV, "NIL", D_LV);
                }
                mv.visitVarInsn(ASTORE, slot(r));
            }
            // the call frame
            mv.visitVarInsn(ALOAD, CL);
            mv.visitVarInsn(ALOAD, STACK);
            mv.visitVarInsn(ALOAD, VARARGS);
            mv.visitMethodInsn(INVOKESTATIC, CC, "enter", "(" + D_LC + D_STACK + D_VA + ")" + D_CF, false);
            mv.visitVarInsn(ASTORE, FRAME);

            Label start = new Label(), end = new Label(), handler = new Label();
            mv.visitTryCatchBlock(start, end, handler, null);
            mv.visitLabel(start);

            for (int pc = 0; pc < n; pc++) {
                mv.visitLabel(labels[pc]);
                if (data[pc])
                    continue;
                if (p.lineinfo != null && pc < p.lineinfo.length)
                    mv.visitLineNumber(p.lineinfo[pc], labels[pc]);
                if (leader[pc]) {
                    int count = blockLength(pc);
                    if (count > 0) {
                        mv.visitVarInsn(ALOAD, FRAME);
                        push(count);
                        mv.visitMethodInsn(INVOKESTATIC, CC, "count", "(" + D_CF + "I)V", false);
                    }
                }
                instruction(pc);
            }
            // falling off the end can't happen (the compiler always ends with RETURN), but keep the verifier happy
            mv.visitLabel(labels[n]);
            mv.visitFieldInsn(GETSTATIC, LV, "NONE", D_LV);
            returnValue();
            mv.visitLabel(end);

            // anything thrown: the interpreter's error handling, then the same exit
            mv.visitLabel(handler);
            mv.visitVarInsn(ASTORE, ERR);
            mv.visitVarInsn(ALOAD, CL);
            mv.visitVarInsn(ALOAD, ERR);
            mv.visitVarInsn(ILOAD, PC);
            mv.visitVarInsn(ALOAD, FRAME);
            mv.visitVarInsn(ALOAD, OPENUPS);
            mv.visitMethodInsn(INVOKESTATIC, CC, "fail", "(" + D_LC + "Ljava/lang/Throwable;I" + D_CF + D_UPS + ")Ljava/lang/Throwable;", false);
            mv.visitInsn(ATHROW);
        }

        private void instruction(int pc) {
            int i = code[pc];
            int op = Lua.GET_OPCODE(i);
            int a = Lua.GETARG_A(i), b = Lua.GETARG_B(i), c = Lua.GETARG_C(i), bx = Lua.GETARG_Bx(i), sbx = Lua.GETARG_sBx(i);
            switch (op) {
                case Lua.OP_MOVE -> {
                    reg(b);
                    store(a);
                }
                case Lua.OP_LOADK -> {
                    konst(bx);
                    store(a);
                }
                case Lua.OP_LOADKX -> {
                    int next = pc + 1 < n ? code[pc + 1] : 0;
                    if (Lua.GET_OPCODE(next) != Lua.OP_EXTRAARG) {
                        at(pc);
                        push(next);
                        mv.visitMethodInsn(INVOKESTATIC, CC, "badExtraArg", "(I)Lorg/luaj/vm2/LuaError;", false);
                        mv.visitInsn(ATHROW);
                    } else {
                        konst(next >>> 6);
                        store(a);
                    }
                }
                case Lua.OP_LOADBOOL -> {
                    mv.visitFieldInsn(GETSTATIC, LV, b != 0 ? "TRUE" : "FALSE", "Lorg/luaj/vm2/LuaBoolean;");
                    store(a);
                    if (c != 0)
                        jump(pc + 2);
                }
                case Lua.OP_LOADNIL -> {
                    for (int j = 0; j <= b; j++) {
                        mv.visitFieldInsn(GETSTATIC, LV, "NIL", D_LV);
                        store(a + j);
                    }
                }
                case Lua.OP_GETUPVAL -> {
                    upvalue(b);
                    store(a);
                }
                case Lua.OP_GETTABUP -> {
                    at(pc);
                    upvalue(b);
                    getKey(c);
                    store(a);
                }
                case Lua.OP_GETTABLE -> {
                    at(pc);
                    reg(b);
                    getKey(c);
                    store(a);
                }
                case Lua.OP_SETTABUP -> {
                    at(pc);
                    upvalue(a);
                    setKey(b, c);
                }
                case Lua.OP_SETUPVAL -> {
                    upvalueRef(b);
                    reg(a);
                    mv.visitMethodInsn(INVOKEVIRTUAL, UV, "setValue", "(" + D_LV + ")V", false);
                }
                case Lua.OP_SETTABLE -> {
                    at(pc);
                    reg(a);
                    setKey(b, c);
                }
                case Lua.OP_NEWTABLE -> {
                    mv.visitTypeInsn(NEW, LT);
                    mv.visitInsn(DUP);
                    push(b);
                    push(c);
                    mv.visitMethodInsn(INVOKESPECIAL, LT, "<init>", "(II)V", false);
                    store(a);
                }
                case Lua.OP_SELF -> {
                    // R(a+1) := R(b); R(a) := R(b)[RK(c)], RK(c) read after the first store like the interpreter
                    at(pc);
                    reg(b);
                    mv.visitInsn(DUP);
                    store(a + 1);
                    getKey(c);
                    store(a);
                }
                case Lua.OP_ADD, Lua.OP_SUB, Lua.OP_MUL, Lua.OP_DIV, Lua.OP_MOD, Lua.OP_POW -> {
                    at(pc);
                    rk(b);
                    rk(c);
                    String name = switch (op) {
                        case Lua.OP_ADD -> "add";
                        case Lua.OP_SUB -> "sub";
                        case Lua.OP_MUL -> "mul";
                        case Lua.OP_DIV -> "div";
                        case Lua.OP_MOD -> "mod";
                        default -> "pow";
                    };
                    mv.visitMethodInsn(INVOKEVIRTUAL, LV, name, "(" + D_LV + ")" + D_LV, false);
                    store(a);
                }
                case Lua.OP_UNM, Lua.OP_NOT, Lua.OP_LEN -> {
                    at(pc);
                    reg(b);
                    mv.visitMethodInsn(INVOKEVIRTUAL, LV, op == Lua.OP_UNM ? "neg" : op == Lua.OP_NOT ? "not" : "len", "()" + D_LV, false);
                    store(a);
                }
                case Lua.OP_CONCAT -> {
                    at(pc);
                    if (c == b + 1) {
                        reg(b);
                        reg(c);
                        mv.visitMethodInsn(INVOKEVIRTUAL, LV, "concat", "(" + D_LV + ")" + D_LV, false);
                    } else {
                        spill(b, c);
                        mv.visitVarInsn(ALOAD, STACK);
                        push(b);
                        push(c);
                        mv.visitMethodInsn(INVOKESTATIC, CC, "concat", "(" + D_STACK + "II)" + D_LV, false);
                    }
                    store(a);
                }
                case Lua.OP_JMP -> {
                    // upvalues are only open for captured registers, which live in the stack array
                    if (a > 0 && openups) {
                        mv.visitVarInsn(ALOAD, OPENUPS);
                        push(a - 1);
                        mv.visitMethodInsn(INVOKESTATIC, CC, "close", "(" + D_UPS + "I)V", false);
                    }
                    jump(pc + 1 + sbx);
                }
                case Lua.OP_EQ, Lua.OP_LT, Lua.OP_LE -> {
                    at(pc);
                    rk(b);
                    rk(c);
                    mv.visitMethodInsn(INVOKEVIRTUAL, LV, op == Lua.OP_EQ ? "eq_b" : op == Lua.OP_LT ? "lt_b" : "lteq_b", "(" + D_LV + ")Z", false);
                    // the next instruction is skipped when the result differs from A
                    mv.visitJumpInsn(a != 0 ? IFEQ : IFNE, labels[pc + 2]);
                }
                case Lua.OP_TEST -> {
                    reg(a);
                    mv.visitMethodInsn(INVOKEVIRTUAL, LV, "toboolean", "()Z", false);
                    mv.visitJumpInsn(c != 0 ? IFEQ : IFNE, labels[pc + 2]);
                }
                case Lua.OP_TESTSET -> {
                    reg(b);
                    mv.visitVarInsn(ASTORE, TMP);
                    mv.visitVarInsn(ALOAD, TMP);
                    mv.visitMethodInsn(INVOKEVIRTUAL, LV, "toboolean", "()Z", false);
                    mv.visitJumpInsn(c != 0 ? IFEQ : IFNE, labels[pc + 2]);
                    mv.visitVarInsn(ALOAD, TMP);
                    store(a);
                }
                case Lua.OP_CALL -> call(pc, a, b, c);
                case Lua.OP_TAILCALL -> tailcall(pc, a, b);
                case Lua.OP_RETURN -> {
                    switch (b) {
                        case 0 -> {
                            if (unreachable(pc)) {
                                // never run, and without a stack array to read
                                mv.visitFieldInsn(GETSTATIC, LV, "NONE", D_LV);
                                break;
                            }
                            spill(a, maxReg);
                            mv.visitVarInsn(ALOAD, STACK);
                            push(a);
                            mv.visitVarInsn(ILOAD, TOP);
                            mv.visitVarInsn(ALOAD, V);
                            mv.visitMethodInsn(INVOKESTATIC, CC, "returnAll", "(" + D_STACK + "II" + D_VA + ")" + D_VA, false);
                        }
                        case 1 -> mv.visitFieldInsn(GETSTATIC, LV, "NONE", D_LV);
                        case 2 -> reg(a);
                        case 3 -> {
                            reg(a);
                            reg(a + 1);
                            mv.visitMethodInsn(INVOKESTATIC, LV, "varargsOf", "(" + D_LV + D_VA + ")" + D_VA, false);
                        }
                        case 4 -> {
                            reg(a);
                            reg(a + 1);
                            reg(a + 2);
                            mv.visitMethodInsn(INVOKESTATIC, LV, "varargsOf", "(" + D_LV + D_LV + D_VA + ")" + D_VA, false);
                        }
                        default -> {
                            spill(a, a + b - 2);
                            mv.visitVarInsn(ALOAD, STACK);
                            push(a);
                            push(b - 1);
                            mv.visitMethodInsn(INVOKESTATIC, LV, "varargsOf", "(" + D_STACK + "II)" + D_VA, false);
                        }
                    }
                    returnValue();
                }
                case Lua.OP_FORLOOP -> {
                    // idx = R(a) + R(a+2); if (step > 0 ? idx <= limit : idx >= limit) { R(a) = R(a+3) = idx; jump back }
                    at(pc);
                    Label down = new Label(), test = new Label();
                    reg(a);
                    reg(a + 2);
                    mv.visitMethodInsn(INVOKEVIRTUAL, LV, "add", "(" + D_LV + ")" + D_LV, false);
                    mv.visitVarInsn(ASTORE, TMP);
                    reg(a + 2);
                    mv.visitInsn(ICONST_0);
                    mv.visitMethodInsn(INVOKEVIRTUAL, LV, "gt_b", "(I)Z", false);
                    mv.visitJumpInsn(IFEQ, down);
                    mv.visitVarInsn(ALOAD, TMP);
                    reg(a + 1);
                    mv.visitMethodInsn(INVOKEVIRTUAL, LV, "lteq_b", "(" + D_LV + ")Z", false);
                    mv.visitJumpInsn(GOTO, test);
                    mv.visitLabel(down);
                    mv.visitVarInsn(ALOAD, TMP);
                    reg(a + 1);
                    mv.visitMethodInsn(INVOKEVIRTUAL, LV, "gteq_b", "(" + D_LV + ")Z", false);
                    mv.visitLabel(test);
                    mv.visitJumpInsn(IFEQ, labels[pc + 1]);
                    mv.visitVarInsn(ALOAD, TMP);
                    store(a);
                    mv.visitVarInsn(ALOAD, TMP);
                    store(a + 3);
                    jump(pc + 1 + sbx);
                }
                case Lua.OP_FORPREP -> {
                    // checked in the interpreter's order: initial value, limit, step
                    at(pc);
                    reg(a);
                    checknumber("'for' initial value must be a number");
                    mv.visitVarInsn(ASTORE, TMP);
                    reg(a + 1);
                    checknumber("'for' limit must be a number");
                    store(a + 1);
                    reg(a + 2);
                    checknumber("'for' step must be a number");
                    mv.visitInsn(DUP);
                    store(a + 2);
                    mv.visitVarInsn(ALOAD, TMP);
                    mv.visitInsn(SWAP);
                    mv.visitMethodInsn(INVOKEVIRTUAL, LV, "sub", "(" + D_LV + ")" + D_LV, false);
                    store(a);
                    jump(pc + 1 + sbx);
                }
                case Lua.OP_TFORCALL -> {
                    at(pc);
                    reg(a);
                    reg(a + 1);
                    reg(a + 2);
                    mv.visitMethodInsn(INVOKESTATIC, LV, "varargsOf", "(" + D_LV + D_VA + ")" + D_VA, false);
                    mv.visitMethodInsn(INVOKEVIRTUAL, LV, "invoke", "(" + D_VA + ")" + D_VA, false);
                    mv.visitVarInsn(ASTORE, V);
                    for (int j = 0; j < c; j++) {
                        mv.visitVarInsn(ALOAD, V);
                        push(j + 1);
                        mv.visitMethodInsn(INVOKEVIRTUAL, VA, "arg", "(I)" + D_LV, false);
                        store(a + 3 + j);
                    }
                    mv.visitFieldInsn(GETSTATIC, LV, "NONE", D_LV);
                    mv.visitVarInsn(ASTORE, V);
                }
                case Lua.OP_TFORLOOP -> {
                    reg(a + 1);
                    mv.visitMethodInsn(INVOKEVIRTUAL, LV, "isnil", "()Z", false);
                    mv.visitJumpInsn(IFNE, labels[pc + 1]);
                    reg(a + 1);
                    store(a);
                    jump(pc + 1 + sbx);
                }
                case Lua.OP_SETLIST -> {
                    at(pc);
                    int count = c == 0 ? (pc + 1 < n ? code[pc + 1] : 0) : c;
                    spill(a, b == 0 ? maxReg : a + b);
                    mv.visitVarInsn(ALOAD, STACK);
                    push(a);
                    push(b);
                    push(count);
                    mv.visitVarInsn(ILOAD, TOP);
                    mv.visitVarInsn(ALOAD, V);
                    mv.visitMethodInsn(INVOKESTATIC, CC, "setlist", "(" + D_STACK + "IIII" + D_VA + ")V", false);
                    if (c == 0)
                        jump(pc + 2);
                }
                case Lua.OP_CLOSURE -> {
                    // the registers it captures are captured ones, in the stack array
                    at(pc);
                    mv.visitVarInsn(ALOAD, CL);
                    push(bx);
                    mv.visitVarInsn(ALOAD, STACK);
                    mv.visitVarInsn(ALOAD, OPENUPS);
                    mv.visitMethodInsn(INVOKESTATIC, CC, "closure", "(" + D_LC + "I" + D_STACK + D_UPS + ")L" + LC + ";", false);
                    store(a);
                }
                case Lua.OP_VARARG -> {
                    if (b == 0) {
                        push(a);
                        mv.visitVarInsn(ALOAD, VARARGS);
                        mv.visitMethodInsn(INVOKEVIRTUAL, VA, "narg", "()I", false);
                        mv.visitInsn(IADD);
                        mv.visitVarInsn(ISTORE, TOP);
                        mv.visitVarInsn(ALOAD, VARARGS);
                        mv.visitVarInsn(ASTORE, V);
                    } else {
                        for (int j = 1; j < b; j++) {
                            mv.visitVarInsn(ALOAD, VARARGS);
                            push(j);
                            mv.visitMethodInsn(INVOKEVIRTUAL, VA, "arg", "(I)" + D_LV, false);
                            store(a + j - 1);
                        }
                    }
                }
                default -> {
                    // OP_EXTRAARG on its own or an unknown opcode: the same error as the interpreter
                    at(pc);
                    mv.visitTypeInsn(NEW, "java/lang/IllegalArgumentException");
                    mv.visitInsn(DUP);
                    mv.visitLdcInsn((op == Lua.OP_EXTRAARG ? "Uexecutable opcode: OP_EXTRAARG" : "Illegal opcode: " + op));
                    mv.visitMethodInsn(INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>", "(Ljava/lang/String;)V", false);
                    mv.visitInsn(ATHROW);
                }
            }
        }

        // OP_CALL, the same fixed cases as the interpreter, then the general one
        private void call(int pc, int a, int b, int c) {
            at(pc);
            if (c == 0 && (b == 1 || b == 2)) {
                reg(a);
                if (b == 1)
                    mv.visitFieldInsn(GETSTATIC, LV, "NONE", D_LV);
                else
                    reg(a + 1);
                mv.visitMethodInsn(INVOKEVIRTUAL, LV, "invoke", "(" + D_VA + ")" + D_VA, false);
                mv.visitVarInsn(ASTORE, V);
                push(a);
                mv.visitVarInsn(ALOAD, V);
                mv.visitMethodInsn(INVOKEVIRTUAL, VA, "narg", "()I", false);
                mv.visitInsn(IADD);
                mv.visitVarInsn(ISTORE, TOP);
                return;
            }
            if ((c == 1 || c == 2) && b >= 1 && b <= 4) {
                reg(a);
                StringBuilder desc = new StringBuilder("(");
                for (int j = 1; j < b; j++) {
                    reg(a + j);
                    desc.append(D_LV);
                }
                mv.visitMethodInsn(INVOKEVIRTUAL, LV, "call", desc.append(")").append(D_LV).toString(), false);
                if (c == 2)
                    store(a);
                else
                    mv.visitInsn(POP);
                return;
            }
            // v = R(a).invoke(args): up to three arguments from registers, more or a variable number from the stack array
            if (b >= 1 && b <= 4) {
                reg(a);
                fixedArgs(a, b);
            } else {
                spill(a + 1, b == 0 ? maxReg : a + b - 1);
                reg(a);
                mv.visitVarInsn(ALOAD, STACK);
                push(a);
                push(b);
                mv.visitVarInsn(ILOAD, TOP);
                mv.visitVarInsn(ALOAD, V);
                mv.visitMethodInsn(INVOKESTATIC, CC, "args", "(" + D_STACK + "III" + D_VA + ")" + D_VA, false);
            }
            mv.visitMethodInsn(INVOKEVIRTUAL, LV, "invoke", "(" + D_VA + ")" + D_VA, false);
            mv.visitVarInsn(ASTORE, V);
            if (c > 0) {
                if (c == 2) {
                    mv.visitVarInsn(ALOAD, V);
                    mv.visitMethodInsn(INVOKEVIRTUAL, VA, "arg1", "()" + D_LV, false);
                    store(a);
                } else {
                    for (int j = 0; j < c - 1; j++) {
                        mv.visitVarInsn(ALOAD, V);
                        push(j + 1);
                        mv.visitMethodInsn(INVOKEVIRTUAL, VA, "arg", "(I)" + D_LV, false);
                        store(a + j);
                    }
                }
                mv.visitFieldInsn(GETSTATIC, LV, "NONE", D_LV);
                mv.visitVarInsn(ASTORE, V);
            } else {
                push(a);
                mv.visitVarInsn(ALOAD, V);
                mv.visitMethodInsn(INVOKEVIRTUAL, VA, "narg", "()I", false);
                mv.visitInsn(IADD);
                mv.visitVarInsn(ISTORE, TOP);
                mv.visitVarInsn(ALOAD, V);
                mv.visitMethodInsn(INVOKEVIRTUAL, VA, "dealias", "()" + D_VA, false);
                mv.visitVarInsn(ASTORE, V);
            }
        }

        // the arguments R(a+1) ... R(a+b-1) of a call, 1 <= b <= 4, as Varargs from the registers
        private void fixedArgs(int a, int b) {
            switch (b) {
                case 1 -> mv.visitFieldInsn(GETSTATIC, LV, "NONE", D_LV);
                case 2 -> reg(a + 1);
                case 3 -> {
                    reg(a + 1);
                    reg(a + 2);
                    mv.visitMethodInsn(INVOKESTATIC, LV, "varargsOf", "(" + D_LV + D_VA + ")" + D_VA, false);
                }
                default -> {
                    reg(a + 1);
                    reg(a + 2);
                    reg(a + 3);
                    mv.visitMethodInsn(INVOKESTATIC, LV, "varargsOf", "(" + D_LV + D_LV + D_VA + ")" + D_VA, false);
                }
            }
        }

        // OP_TAILCALL: return new TailcallVarargs(R(a), args)
        private void tailcall(int pc, int a, int b) {
            at(pc);
            if (b == 0 || b > 4)
                spill(a + 1, b == 0 ? maxReg : a + b - 1);
            mv.visitTypeInsn(NEW, TV);
            mv.visitInsn(DUP);
            reg(a);
            switch (b) {
                case 1 -> mv.visitFieldInsn(GETSTATIC, LV, "NONE", D_LV);
                case 2 -> reg(a + 1);
                case 3 -> {
                    reg(a + 1);
                    reg(a + 2);
                    mv.visitMethodInsn(INVOKESTATIC, LV, "varargsOf", "(" + D_LV + D_VA + ")" + D_VA, false);
                }
                case 4 -> {
                    reg(a + 1);
                    reg(a + 2);
                    reg(a + 3);
                    mv.visitMethodInsn(INVOKESTATIC, LV, "varargsOf", "(" + D_LV + D_LV + D_VA + ")" + D_VA, false);
                }
                default -> {
                    mv.visitVarInsn(ALOAD, STACK);
                    push(a);
                    push(b);
                    mv.visitVarInsn(ILOAD, TOP);
                    mv.visitVarInsn(ALOAD, V);
                    mv.visitMethodInsn(INVOKESTATIC, CC, "args", "(" + D_STACK + "III" + D_VA + ")" + D_VA, false);
                }
            }
            mv.visitMethodInsn(INVOKESPECIAL, TV, "<init>", "(" + D_LV + D_VA + ")V", false);
            returnValue();
        }

        // the value on the operand stack is returned, after closing the upvalues and popping the call frame
        private void returnValue() {
            mv.visitVarInsn(ASTORE, RET);
            mv.visitVarInsn(ALOAD, OPENUPS);
            mv.visitVarInsn(ALOAD, FRAME);
            mv.visitMethodInsn(INVOKESTATIC, CC, "exit", "(" + D_UPS + D_CF + ")V", false);
            mv.visitVarInsn(ALOAD, RET);
            mv.visitInsn(ARETURN);
        }

        // the instruction that can call or fail: kept for the error position and the call frame
        private void at(int pc) {
            push(pc);
            mv.visitVarInsn(ISTORE, PC);
            mv.visitVarInsn(ALOAD, FRAME);
            push(pc);
            mv.visitMethodInsn(INVOKESTATIC, CC, "at", "(" + D_CF + "I)V", false);
        }

        private void jump(int target) {
            mv.visitJumpInsn(GOTO, labels[Math.min(Math.max(target, 0), n)]);
        }

        private int slot(int r) {
            return ERR + 1 + r;
        }

        private boolean inArray(int r) {
            return r < 0 || r >= captured.length || captured[r];
        }

        // R(r) onto the operand stack
        private void reg(int r) {
            if (inArray(r)) {
                mv.visitVarInsn(ALOAD, STACK);
                push(r);
                mv.visitInsn(AALOAD);
            } else {
                mv.visitVarInsn(ALOAD, slot(r));
            }
        }

        // the value on the operand stack into R(r)
        private void store(int r) {
            if (inArray(r)) {
                mv.visitVarInsn(ALOAD, STACK);
                mv.visitInsn(SWAP);
                push(r);
                mv.visitInsn(SWAP);
                mv.visitInsn(AASTORE);
            } else {
                mv.visitVarInsn(ASTORE, slot(r));
            }
        }

        // registers from .. to (those in locals) copied to the stack array, for an instruction reading them from it
        private void spill(int from, int to) {
            for (int r = Math.max(from, 0); r <= Math.min(to, maxReg); r++) {
                if (captured[r])
                    continue;
                mv.visitVarInsn(ALOAD, STACK);
                push(r);
                mv.visitVarInsn(ALOAD, slot(r));
                mv.visitInsn(AASTORE);
            }
        }

        private void checknumber(String message) {
            mv.visitLdcInsn(message);
            mv.visitMethodInsn(INVOKEVIRTUAL, LV, "checknumber", "(Ljava/lang/String;)Lorg/luaj/vm2/LuaNumber;", false);
        }

        private void konst(int index) {
            mv.visitVarInsn(ALOAD, K);
            push(index);
            mv.visitInsn(AALOAD);
        }

        // RK(x): a constant when x > 0xff, else a register
        private void rk(int x) {
            if (x > 0xff)
                konst(x & 0xff);
            else
                reg(x);
        }

        private void upvalueRef(int index) {
            mv.visitVarInsn(ALOAD, CL);
            mv.visitFieldInsn(GETFIELD, LC, "upValues", D_UPS);
            push(index);
            mv.visitInsn(AALOAD);
        }

        private void upvalue(int index) {
            upvalueRef(index);
            mv.visitMethodInsn(INVOKEVIRTUAL, UV, "getValue", "()" + D_LV, false);
        }

        private boolean stringConstant(int x) {
            return x > 0xff && p.k[x & 0xff] instanceof org.luaj.vm2.LuaString;
        }

        // the table is on the operand stack: table[RK(x)], a constant string key without the generic lookup
        private void getKey(int x) {
            if (stringConstant(x)) {
                konst(x & 0xff);
                mv.visitTypeInsn(CHECKCAST, "org/luaj/vm2/LuaString");
                mv.visitMethodInsn(INVOKESTATIC, CC, "getStr", "(" + D_LV + "Lorg/luaj/vm2/LuaString;)" + D_LV, false);
            } else {
                rk(x);
                get();
            }
        }

        // the table is on the operand stack: table[RK(key)] = RK(value)
        private void setKey(int key, int value) {
            if (stringConstant(key)) {
                konst(key & 0xff);
                mv.visitTypeInsn(CHECKCAST, "org/luaj/vm2/LuaString");
                rk(value);
                mv.visitMethodInsn(INVOKESTATIC, LT, "setStr", "(" + D_LV + "Lorg/luaj/vm2/LuaString;" + D_LV + ")V", false);
            } else {
                rk(key);
                rk(value);
                mv.visitMethodInsn(INVOKEVIRTUAL, LV, "set", "(" + D_LV + D_LV + ")V", false);
            }
        }

        private void get() {
            mv.visitMethodInsn(INVOKEVIRTUAL, LV, "get", "(" + D_LV + ")" + D_LV, false);
        }

        private void push(int value) {
            if (value >= -1 && value <= 5)
                mv.visitInsn(ICONST_0 + value);
            else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE)
                mv.visitIntInsn(BIPUSH, value);
            else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE)
                mv.visitIntInsn(SIPUSH, value);
            else
                mv.visitLdcInsn(value);
        }
    }
}

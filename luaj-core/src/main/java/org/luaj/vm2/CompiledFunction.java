package org.luaj.vm2;

import org.luaj.vm2.lib.DebugLib;

/**
 * [bh] A Lua function compiled to JVM bytecode (Figura's LuaJit): the code of one {@link Prototype}, shared by all its closures.
 * <p>
 * {@link LuaClosure#execute} runs it instead of the interpreter once it is set on {@link Prototype#compiled}, so closures
 * made before it was compiled switch too. The generated {@link #execute} does what the interpreter does for each instruction,
 * on the same stack array, upvalues and call frames, so calls, varargs, metatables, coroutines, tracebacks and debug.getinfo
 * behave the same, and compiled and interpreted functions call each other freely.
 * <p>
 * Instructions are counted per block for the count hook ({@link DebugLib#onInstructions}) and the call frame's instruction is
 * kept up to date wherever a call or an error can happen. With a line, call or return hook set the interpreter runs instead.
 * <p>
 * Functions are compiled when they get hot: after {@link #threshold} calls the {@link #listener} is told, once per prototype.
 */
public abstract class CompiledFunction {

	/** Told about functions called {@link #threshold} times while interpreted. */
	public interface HotListener {
		void hot(Prototype p);
	}

	public static volatile HotListener listener;
	/** calls before a function is hot, 0 = never */
	public static volatile int threshold;

	protected CompiledFunction() {}

	/**
	 * Runs the function for the closure, like LuaClosure.execute.
	 * <p>
	 * The first three parameters come as x0, x1, x2 (nil beyond the parameters), so calls with up to three parameters need no
	 * stack array: stack is null then and the code makes one only when the function needs one (captured registers, instructions
	 * reading ranges of registers). Functions with more parameters always get the stack array holding them all.
	 */
	protected abstract Varargs run(LuaClosure cl, LuaValue[] stack, LuaValue x0, LuaValue x1, LuaValue x2, Varargs varargs);

	/** Runs the function with the parameters already in the stack array, like LuaClosure.execute. */
	final Varargs execute(LuaClosure cl, LuaValue[] stack, Varargs varargs) {
		int np = cl.p.numparams;
		return run(cl, stack, np > 0? stack[0]: LuaValue.NIL, np > 1? stack[1]: LuaValue.NIL, np > 2? stack[2]: LuaValue.NIL, varargs);
	}

	/** Direct calls skip the stack array, up to three parameters (see {@link #run}). */
	static boolean direct(LuaClosure cl) {
		return cl.p.numparams <= 3 && !mustInterpret(cl.globals);
	}

	// called by LuaClosure.execute while interpreting
	static void called(Prototype p) {
		int t = threshold;
		if (t > 0 && ++p.calls == t) {
			HotListener l = listener;
			if (l != null)
				l.hot(p);
		}
	}

	/** Line, call and return hooks need the interpreter, which reports every instruction. */
	static boolean mustInterpret(Globals g) {
		if (g == null || g.debuglib == null)
			return false;
		LuaThread.State s = g.running.state;
		return s.hookline || s.hookcall || s.hookrtrn;
	}

	// -- entry and exit, the same as LuaClosure.execute -- //

	private static final LuaValue[] NO_STACK = {};

	/** A stack array for a direct call that needs one (see {@link #run}): nil, with the first parameters. */
	protected static LuaValue[] newStack(LuaClosure cl, LuaValue x0, LuaValue x1, LuaValue x2) {
		Prototype p = cl.p;
		LuaValue[] stack = new LuaValue[p.maxstacksize];
		java.util.Arrays.fill(stack, LuaValue.NIL);
		int np = Math.min(p.numparams, stack.length);
		if (np > 0) stack[0] = x0;
		if (np > 1) stack[1] = x1;
		if (np > 2) stack[2] = x2;
		return stack;
	}

	/** Pushes the call frame, null without the debug library or inside a hook. */
	protected static DebugLib.CallFrame enter(LuaClosure cl, LuaValue[] stack, Varargs varargs) {
		Globals g = cl.globals;
		if (g == null || g.debuglib == null)
			return null;
		return g.debuglib.enterCompiled(cl, varargs, stack == null? NO_STACK: stack);
	}

	/** n instructions about to run, for the count hook. */
	protected static void count(DebugLib.CallFrame frame, int n) {
		if (frame != null)
			DebugLib.countCompiled(frame, n);
	}

	/** The instruction running, for the traceback and error position, when a call or an error can happen. */
	protected static void at(DebugLib.CallFrame frame, int pc) {
		if (frame != null)
			frame.pc(pc);
	}

	/** On every return: closes the upvalues still open and pops the call frame. */
	protected static void exit(UpValue[] openups, DebugLib.CallFrame frame) {
		if (openups != null)
			for (int u = openups.length; --u >= 0;)
				if (openups[u] != null)
					openups[u].close();
		if (frame != null)
			DebugLib.exitCompiled(frame);
	}

	/** On an exception: the same error handling as the interpreter, then {@link #exit}; returns what to throw. */
	protected static Throwable fail(LuaClosure cl, Throwable t, int pc, DebugLib.CallFrame frame, UpValue[] openups) {
		try {
			if (frame != null)
				frame.pc(pc);
			if (t instanceof LuaError) {
				LuaError le = (LuaError) t;
				if (le.traceback == null)
					cl.processErrorHooks(le, cl.p, pc);
				return le;
			}
			if (t instanceof Exception) {
				LuaError le = new LuaError(t);
				cl.processErrorHooks(le, cl.p, pc);
				return le;
			}
			return t;
		} finally {
			exit(openups, frame);
		}
	}

	// -- instructions, each the same as its case in LuaClosure.execute -- //

	/** OP_CONCAT: R(b) .. ... .. R(c) */
	protected static LuaValue concat(LuaValue[] stack, int b, int c) {
		if (c > b+1) {
			Buffer sb = stack[c].buffer();
			while (--c >= b)
				sb.concatTo(stack[c]);
			return sb.value();
		}
		return stack[c-1].concat(stack[c]);
	}

	/** OP_JMP with A: closes the upvalues of the registers from a on */
	protected static void close(UpValue[] openups, int a) {
		for (int b = openups.length; --b >= 0;)
			if (openups[b] != null && openups[b].index >= a) {
				openups[b].close();
				openups[b] = null;
			}
	}

	/** OP_FORPREP */
	protected static void forprep(LuaValue[] stack, int a) {
		LuaValue init = stack[a].checknumber("'for' initial value must be a number");
		LuaValue limit = stack[a+1].checknumber("'for' limit must be a number");
		LuaValue step = stack[a+2].checknumber("'for' step must be a number");
		stack[a] = init.sub(step);
		stack[a+1] = limit;
		stack[a+2] = step;
	}

	/** OP_FORLOOP: true to jump back */
	protected static boolean forloop(LuaValue[] stack, int a) {
		LuaValue limit = stack[a+1];
		LuaValue step = stack[a+2];
		LuaValue idx = stack[a].add(step);
		if (step.gt_b(0)? idx.lteq_b(limit): idx.gteq_b(limit)) {
			stack[a] = idx;
			stack[a+3] = idx;
			return true;
		}
		return false;
	}

	/** OP_TFORCALL */
	protected static void tforcall(LuaValue[] stack, int a, int c) {
		Varargs v = stack[a].invoke(LuaValue.varargsOf(stack[a+1], stack[a+2]));
		while (--c >= 0)
			stack[a+3+c] = v.arg(c+1);
	}

	/** OP_SETLIST (c already read from the next word when it was 0) */
	protected static void setlist(LuaValue[] stack, int a, int b, int c, int top, Varargs v) {
		int offset = (c-1)*Lua.LFIELDS_PER_FLUSH;
		LuaValue o = stack[a];
		if (b == 0) {
			b = top-a-1;
			int m = b-v.narg();
			int j = 1;
			for (; j <= m; j++)
				o.set(offset+j, stack[a+j]);
			for (; j <= b; j++)
				o.set(offset+j, v.arg(j-m));
		} else {
			o.presize(offset+b);
			for (int j = 1; j <= b; j++)
				o.set(offset+j, stack[a+j]);
		}
	}

	/** OP_CLOSURE */
	protected static LuaClosure closure(LuaClosure cl, int index, LuaValue[] stack, UpValue[] openups) {
		Prototype newp = cl.p.p[index];
		LuaClosure ncl = new LuaClosure(newp, cl.globals);
		Upvaldesc[] uv = newp.upvalues;
		for (int j = 0, nup = uv.length; j < nup; ++j) {
			if (uv[j].instack)
				ncl.upValues[j] = cl.findupval(stack, uv[j].idx, openups);
			else
				ncl.upValues[j] = cl.upValues[uv[j].idx];
		}
		return ncl;
	}

	/** OP_VARARG with B > 0 */
	protected static void vararg(LuaValue[] stack, int a, int b, Varargs varargs) {
		for (int j = 1; j < b; ++j)
			stack[a+j-1] = varargs.arg(j);
	}

	/** the arguments of OP_CALL / OP_TAILCALL beyond the fixed cases */
	protected static Varargs args(LuaValue[] stack, int a, int b, int top, Varargs v) {
		return b > 0? LuaValue.varargsOf(stack, a+1, b-1): LuaValue.varargsOf(stack, a+1, top-v.narg()-(a+1), v);
	}

	/** OP_CALL with C > 0: the results into R(a) ... R(a+n-1) */
	protected static void results(Varargs v, LuaValue[] stack, int a, int n) {
		v.copyto(stack, a, n);
	}

	/** OP_RETURN with B = 0 */
	protected static Varargs returnAll(LuaValue[] stack, int a, int top, Varargs v) {
		return LuaValue.varargsOf(stack, a, top-v.narg()-a, v);
	}

	/** OP_LOADKX / unexpected words */
	protected static LuaError badExtraArg(int i) {
		int op = i & 0x3f;
		return new LuaError("OP_EXTRAARG expected after OP_LOADKX, got " + (op < Print.OPNAMES.length-1? Print.OPNAMES[op]: "UNKNOWN_OP_" + op));
	}
}

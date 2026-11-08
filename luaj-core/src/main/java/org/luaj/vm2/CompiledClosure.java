package org.luaj.vm2;

import org.luaj.vm2.lib.DebugLib;

/**
 * [bh] Base class of Lua functions compiled to JVM bytecode (Figura's LuaJit).
 * <p>
 * A compiled function is still a LuaClosure: the same prototype, upvalues, stack array and call frames as an interpreted one,
 * only its {@link #execute} is generated code doing what the interpreter does for each instruction, as straight-line bytecode.
 * So calls, varargs, metatables, coroutines, tracebacks and debug.getinfo behave the same, and compiled and interpreted
 * functions call each other freely.
 * <p>
 * The generated code counts instructions per block for the count hook ({@link DebugLib#onInstructions}) and keeps the
 * call frame's instruction up to date wherever a call or an error can happen. With a line, call or return hook set it runs
 * the interpreter instead ({@link #mustInterpret}).
 */
public abstract class CompiledClosure extends LuaClosure {

	/** Makes the closures of a compiled prototype. */
	public interface Factory {
		LuaClosure create(Prototype p, LuaValue env);
	}

	/** A closure for the prototype: compiled when a compiler made a factory for it, interpreted otherwise. */
	public static LuaClosure newClosure(Prototype p, LuaValue env) {
		Factory f = p.compiled;
		if (f != null) {
			try {
				return f.create(p, env);
			} catch (Throwable t) {
				// never again for this prototype
				p.compiled = null;
			}
		}
		return new LuaClosure(p, env);
	}

	protected CompiledClosure(Prototype p, LuaValue env) {
		super(p, env);
	}

	// -- entry and exit, the same as LuaClosure.execute -- //

	/** Line, call and return hooks need the interpreter, which reports every instruction. */
	protected final boolean mustInterpret() {
		Globals g = globals;
		if (g == null || g.debuglib == null)
			return false;
		LuaThread.State s = g.running.state;
		return s.hookline || s.hookcall || s.hookrtrn;
	}

	protected final Varargs interpret(LuaValue[] stack, Varargs varargs) {
		return super.execute(stack, varargs);
	}

	/** Pushes the call frame, returns the running coroutine's hook state, null without the debug library. */
	protected final LuaThread.State enter(LuaValue[] stack, Varargs varargs) {
		Globals g = globals;
		if (g == null || g.debuglib == null)
			return null;
		g.debuglib.onCall(this, varargs, stack);
		return g.running.state;
	}

	/** The call frame pushed by {@link #enter}, null when none was. */
	protected final DebugLib.CallFrame frame(LuaThread.State s) {
		return s == null? null: globals.debuglib.topFrame(s);
	}

	/** n instructions about to run, for the count hook. */
	protected final void count(LuaThread.State s, int n) {
		if (s != null)
			globals.debuglib.onInstructions(s, n);
	}

	/** The instruction running, for the traceback and error position, when a call or an error can happen. */
	protected static void at(DebugLib.CallFrame frame, int pc) {
		if (frame != null)
			frame.pc(pc);
	}

	/** On every return: closes the upvalues still open and pops the call frame. */
	protected final void exit(UpValue[] openups) {
		if (openups != null)
			for (int u = openups.length; --u >= 0;)
				if (openups[u] != null)
					openups[u].close();
		if (globals != null && globals.debuglib != null)
			globals.debuglib.onReturn();
	}

	/** On an exception: the same error handling as the interpreter, then {@link #exit}; returns what to throw. */
	protected final Throwable fail(Throwable t, int pc, DebugLib.CallFrame frame, UpValue[] openups) {
		try {
			if (frame != null)
				frame.pc(pc);
			if (t instanceof LuaError) {
				LuaError le = (LuaError) t;
				if (le.traceback == null)
					processErrorHooks(le, p, pc);
				return le;
			}
			if (t instanceof Exception) {
				LuaError le = new LuaError(t);
				processErrorHooks(le, p, pc);
				return le;
			}
			return t;
		} finally {
			exit(openups);
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
		Varargs v = stack[a].invoke(varargsOf(stack[a+1], stack[a+2]));
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
	protected final LuaClosure closure(int index, LuaValue[] stack, UpValue[] openups) {
		Prototype newp = p.p[index];
		LuaClosure ncl = newClosure(newp, globals);
		Upvaldesc[] uv = newp.upvalues;
		for (int j = 0, nup = uv.length; j < nup; ++j) {
			if (uv[j].instack)
				ncl.upValues[j] = findupval(stack, uv[j].idx, openups);
			else
				ncl.upValues[j] = upValues[uv[j].idx];
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
		return b > 0? varargsOf(stack, a+1, b-1): varargsOf(stack, a+1, top-v.narg()-(a+1), v);
	}

	/** OP_CALL with C > 0: the results into R(a) ... R(a+n-1) */
	protected static void results(Varargs v, LuaValue[] stack, int a, int n) {
		v.copyto(stack, a, n);
	}

	/** OP_RETURN with B = 0 */
	protected static Varargs returnAll(LuaValue[] stack, int a, int top, Varargs v) {
		return varargsOf(stack, a, top-v.narg()-a, v);
	}

	/** OP_LOADKX / unexpected words */
	protected static LuaError badExtraArg(int i) {
		int op = i & 0x3f;
		return new LuaError("OP_EXTRAARG expected after OP_LOADKX, got " + (op < Print.OPNAMES.length-1? Print.OPNAMES[op]: "UNKNOWN_OP_" + op));
	}
}

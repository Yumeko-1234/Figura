package org.figuramc.figura.lua.jit;

import org.figuramc.figura.FiguraMod;
import org.luaj.vm2.CompiledFunction;

/**
 * The safe way in to the Lua compiler (LuaJit): nothing here needs ASM, so a game where LuaJit can't load (ASM not visible to
 * Figura, a different luaj on the classpath) keeps running every script interpreted instead of failing to load avatars.
 * <p>
 * The compiler also turns itself off for the rest of the session if compiled code ever throws a {@link LinkageError}
 * (a bug in the generated code that the JVM only finds when that instruction first runs): the avatar it happened in gets a
 * script error like any other, and every script from then on stays interpreted.
 */
public final class LuaCompiler {

    // LuaJit could not be loaded, or compiled code failed to link: never compile again this session
    private static volatile boolean disabled;

    private LuaCompiler() {}

    /** Turns compiling hot functions on or off, see LuaJit.configure; never throws. */
    public static void configure(boolean enabled) {
        if (disabled) {
            stop();
            return;
        }
        try {
            LuaJit.configure(enabled);
        } catch (LinkageError e) {
            disabled = true;
            stop();
            FiguraMod.LOGGER.warn("The Lua compiler could not be loaded, scripts run interpreted", e);
        }
    }

    /**
     * Called by the script entry points when a script threw a LinkageError: when it came from compiled code, the compiler is
     * turned off for good (functions compiled before keep their code until their avatar reloads).
     */
    public static void onLinkageError(LinkageError e) {
        if (disabled || !fromCompiledCode(e))
            return;
        disabled = true;
        stop();
        FiguraMod.LOGGER.error("Compiled Lua code failed, the Lua compiler is turned off until the game restarts (please report this)", e);
    }

    private static boolean fromCompiledCode(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            for (StackTraceElement element : t.getStackTrace()) {
                String c = element.getClassName();
                if (c.startsWith("org.figuramc.figura.lua.jit.") || c.equals("org.luaj.vm2.CompiledFunction"))
                    return true;
            }
            if (t.getCause() == t)
                break;
        }
        return false;
    }

    // no new functions are compiled (CompiledFunction is in luaj-core, it loads without ASM)
    private static void stop() {
        try {
            CompiledFunction.threshold = 0;
            CompiledFunction.listener = null;
        } catch (LinkageError ignored) {
            // the published luaj without the compiler hooks: nothing to stop
        }
    }

    public static boolean isDisabled() {
        return disabled;
    }
}

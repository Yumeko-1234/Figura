package org.figuramc.figura.utils;

import org.figuramc.figura.FiguraMod;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes what every thread is doing into the log when the render thread stops coming back to Minecraft.runTick for a while.
 * A hung game ("Not Responding", closed by the player) leaves no crash report and no hs_err file, so without this the log
 * just ends. The dumps name the thread and line the game was stuck on.
 * <p>
 * -Dfigura.watchdog.seconds=N sets how long a stall must last before the first dump (default 10, 0 turns it off).
 */
public final class FreezeWatchdog {

    private static final long STALL_SECONDS = Long.getLong("figura.watchdog.seconds", 10);
    // further dumps while the same stall goes on, to tell a hang (same line every time) from a very slow loop
    private static final long REPEAT_SECONDS = 20;
    private static final int MAX_DUMPS_PER_STALL = 4;
    private static final long CHECK_MILLIS = 1000;

    private static volatile long lastBeat;
    private static volatile Thread renderThread;
    private static boolean started;

    private FreezeWatchdog() {}

    // called by the render thread at the start of every frame (Minecraft.runTick)
    public static void beat() {
        lastBeat = System.nanoTime();
        if (!started) {
            started = true;
            renderThread = Thread.currentThread();
            if (STALL_SECONDS > 0) {
                Thread watcher = new Thread(FreezeWatchdog::watch, "Figura Freeze Watchdog");
                watcher.setDaemon(true);
                watcher.setPriority(Thread.MAX_PRIORITY);
                watcher.start();
                FiguraMod.LOGGER.info("Figura freeze watchdog on: threads are dumped to the log after {}s without a frame", STALL_SECONDS);
            }
        }
    }

    private static void watch() {
        long stallNanos = STALL_SECONDS * 1_000_000_000L;
        long repeatNanos = REPEAT_SECONDS * 1_000_000_000L;
        long reportedBeat = -1; // lastBeat of the stall already reported
        int dumps = 0;
        long nextDumpAt = 0;
        long lastCheck = System.nanoTime();

        while (true) {
            try {
                Thread.sleep(CHECK_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
            try {
                long now = System.nanoTime();
                long beat = lastBeat;

                // the watcher itself was held up (pc asleep, debugger, whole process paused): the render thread was too, start over
                if (now - lastCheck > CHECK_MILLIS * 5_000_000L) {
                    lastCheck = now;
                    lastBeat = now;
                    continue;
                }
                lastCheck = now;

                if (reportedBeat != -1 && beat != reportedBeat) {
                    FiguraMod.LOGGER.warn("[Figura watchdog] Render thread is running again after a {}s stall",
                            String.format("%.1f", (beat - reportedBeat) / 1e9));
                    reportedBeat = -1;
                    dumps = 0;
                }

                long stalled = now - beat;
                if (stalled < stallNanos)
                    continue;

                if (reportedBeat != beat) {
                    reportedBeat = beat;
                    dumps = 0;
                    nextDumpAt = now;
                }
                if (dumps < MAX_DUMPS_PER_STALL && now >= nextDumpAt) {
                    dumps++;
                    nextDumpAt = now + repeatNanos;
                    dump(stalled, dumps);
                }
            } catch (Throwable t) {
                // never let the watcher die, but don't spam either
                FiguraMod.LOGGER.error("[Figura watchdog] failed to check the render thread", t);
            }
        }
    }

    private static void dump(long stalledNanos, int number) {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        ThreadInfo[] infos = mx.dumpAllThreads(mx.isObjectMonitorUsageSupported(), mx.isSynchronizerUsageSupported());
        Thread render = renderThread;
        long renderId = render == null ? -1 : render.getId();

        StringBuilder out = new StringBuilder(64 * 1024);
        out.append("[Figura watchdog] Render thread has not started a frame for ")
                .append(String.format("%.1f", stalledNanos / 1e9)).append("s (dump ").append(number).append('/')
                .append(MAX_DUMPS_PER_STALL).append(", Figura ").append(FiguraMod.VERSION).append(")\n");

        long[] deadlocked = mx.isSynchronizerUsageSupported() ? mx.findDeadlockedThreads() : mx.findMonitorDeadlockedThreads();
        if (deadlocked != null && deadlocked.length > 0) {
            out.append("!!! DEADLOCK between threads:");
            for (long id : deadlocked)
                out.append(' ').append(id);
            out.append('\n');
        }

        // the render thread first, then the threads it waits on and Figura's own, then everything else
        ThreadInfo renderInfo = null;
        List<ThreadInfo> first = new ArrayList<>();
        List<ThreadInfo> rest = new ArrayList<>();
        for (ThreadInfo info : infos) {
            if (info == null) continue;
            if (info.getThreadId() == renderId) renderInfo = info;
            else if (info.getThreadName().startsWith("Figura")) first.add(info);
            else rest.add(info);
        }
        if (renderInfo != null) {
            append(out, renderInfo);
            long owner = renderInfo.getLockOwnerId();
            if (owner != -1) {
                for (int i = 0; i < rest.size(); i++) {
                    if (rest.get(i).getThreadId() == owner) {
                        first.add(0, rest.remove(i));
                        break;
                    }
                }
            }
        } else {
            out.append("(render thread not found)\n");
        }
        for (ThreadInfo info : first) append(out, info);
        out.append("---- other threads ----\n");
        for (ThreadInfo info : rest) append(out, info);

        FiguraMod.LOGGER.warn(out.toString());
    }

    // like ThreadInfo.toString, but with the whole stack (toString stops after 8 frames)
    private static void append(StringBuilder out, ThreadInfo info) {
        out.append('"').append(info.getThreadName()).append("\" id=").append(info.getThreadId())
                .append(' ').append(info.getThreadState());
        if (info.getLockName() != null)
            out.append(" on ").append(info.getLockName());
        if (info.getLockOwnerName() != null)
            out.append(" owned by \"").append(info.getLockOwnerName()).append("\" id=").append(info.getLockOwnerId());
        out.append('\n');

        StackTraceElement[] stack = info.getStackTrace();
        MonitorInfo[] monitors = info.getLockedMonitors();
        for (int i = 0; i < stack.length; i++) {
            out.append("\tat ").append(stack[i]).append('\n');
            if (i == 0 && info.getLockInfo() != null) {
                Thread.State state = info.getThreadState();
                if (state == Thread.State.BLOCKED)
                    out.append("\t-  blocked on ").append(info.getLockInfo()).append('\n');
                else if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING)
                    out.append("\t-  waiting on ").append(info.getLockInfo()).append('\n');
            }
            for (MonitorInfo m : monitors)
                if (m.getLockedStackDepth() == i)
                    out.append("\t-  locked ").append(m).append('\n');
        }
        LockInfo[] syncs = info.getLockedSynchronizers();
        if (syncs.length > 0) {
            out.append("\tLocked synchronizers:\n");
            for (LockInfo l : syncs)
                out.append("\t- ").append(l).append('\n');
        }
        out.append('\n');
    }
}

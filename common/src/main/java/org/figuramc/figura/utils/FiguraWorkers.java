package org.figuramc.figura.utils;

import org.figuramc.figura.FiguraMod;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntConsumer;

/**
 * Worker threads for avatar work that can run side by side: writing avatar vertices (ImmediateAvatarRenderer) and running
 * the tick scripts of different avatars (AvatarManager). The calling thread waits for all of it, and does a share itself.
 * <p>
 * Jobs come in quick succession while avatars are drawn (one per avatar), so a worker spins for a short while after a job before it
 * sleeps: it is still awake for the next one, instead of the calling thread doing all the work while the workers wake up.
 * <p>
 * -Dfigura.workers=N sets the number of workers (default: half the processors minus one, at most 6), 0 turns them off;
 * -Dfigura.workerSpinMicros=N how long an idle worker waits awake (default 200).
 */
public final class FiguraWorkers {

    public static final int THREADS;
    private static final long SPIN_NANOS = Long.getLong("figura.workerSpinMicros", 200) * 1000;

    // held by worker threads around everything that is not safe to run side by side (most Figura apis, chat), see LuaTypeManager
    public static final Object LOCK = new Object();

    private static final Worker[] WORKERS;
    // the current job, workers take its tasks until none are left
    private static volatile Job job;

    private static final class Job {
        final IntConsumer task;
        final int count;
        final AtomicInteger next = new AtomicInteger(), done = new AtomicInteger();
        final AtomicReference<Throwable> error = new AtomicReference<>();

        Job(IntConsumer task, int count) {
            this.task = task;
            this.count = count;
        }

        void work() {
            int i;
            while ((i = next.getAndIncrement()) < count) {
                try {
                    task.accept(i);
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                } finally {
                    done.incrementAndGet();
                }
            }
        }
    }

    private static final class Worker extends Thread {
        volatile boolean sleeping;

        Worker(int index) {
            super("Figura Worker " + index);
            setDaemon(true);
        }

        @Override
        public void run() {
            Job last = null;
            while (true) {
                Job current = job;
                if (current != null && current != last) {
                    last = current;
                    current.work();
                    continue;
                }
                // awake for a while for the next job, then asleep until one comes
                long until = System.nanoTime() + SPIN_NANOS;
                while (job == last && System.nanoTime() < until)
                    Thread.onSpinWait();
                if (job == last) {
                    sleeping = true;
                    if (job == last)
                        LockSupport.park(FiguraWorkers.class);
                    sleeping = false;
                }
            }
        }
    }

    static {
        int cores = Runtime.getRuntime().availableProcessors();
        int threads = Integer.getInteger("figura.workers", Math.min(6, cores / 2 - 1));
        THREADS = Math.max(0, threads);
        WORKERS = new Worker[THREADS];
        for (int i = 0; i < THREADS; i++) {
            WORKERS[i] = new Worker(i);
            WORKERS[i].start();
        }
        if (THREADS > 0)
            FiguraMod.LOGGER.info("Figura uses {} worker threads", THREADS);
    }

    private FiguraWorkers() {}

    // if this is one of the workers
    public static boolean isWorker() {
        return Thread.currentThread() instanceof Worker;
    }

    public static boolean available() {
        return THREADS > 0;
    }

    /**
     * Runs task(0) ... task(count - 1) on the calling thread and the workers, and returns when all of them are done.
     * Tasks are taken one at a time from a shared counter, so the calling thread never waits for a worker that is still waking up,
     * only for tasks a worker already started. The first exception thrown by a task is thrown again here, after all are done.
     * Only one thread (the render thread) runs jobs.
     */
    public static void run(int count, IntConsumer task) {
        if (count <= 0)
            return;
        if (THREADS == 0 || count == 1 || isWorker()) {
            for (int i = 0; i < count; i++)
                task.accept(i);
            return;
        }
        Job current = new Job(task, count);
        job = current;
        for (Worker worker : WORKERS)
            if (worker.sleeping)
                LockSupport.unpark(worker);
        current.work();
        // only tasks a worker already took are left
        while (current.done.get() < count)
            Thread.onSpinWait();

        Throwable t = current.error.get();
        if (t instanceof RuntimeException re)
            throw re;
        if (t instanceof Error err)
            throw err;
        if (t != null)
            throw new RuntimeException(t);
    }
}

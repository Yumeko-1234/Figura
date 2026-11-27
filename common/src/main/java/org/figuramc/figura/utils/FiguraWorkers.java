package org.figuramc.figura.utils;

import org.figuramc.figura.FiguraMod;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntConsumer;

/**
 * Worker threads for avatar work that can run side by side: writing avatar vertices (ImmediateAvatarRenderer), running
 * the tick scripts of different avatars (AvatarManager), and walking other players' models while the render thread goes on
 * with the next entities (background tasks, see ImmediateAvatarRenderer's parallel model walk).
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

    /**
     * Held by worker threads around everything that is not safe to run side by side (most Figura apis, chat), see LuaTypeManager.
     * While background tasks are out, the render thread holds it too (holdForRenderThread), so a worker only gets it while the render
     * thread is waiting for one of them: the game state the apis touch never changes under the render thread.
     */
    public static final ReentrantLock LOCK = new ReentrantLock();

    private static final Worker[] WORKERS;
    // the current job, workers take its tasks until none are left
    private static volatile Job job;
    // background tasks, taken by idle workers in order, or by the render thread when it needs one that did not start yet
    private static final ConcurrentLinkedQueue<Task> TASKS = new ConcurrentLinkedQueue<>();

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

    /**
     * A background task: runs once, on a worker or on the thread that awaits it first.
     */
    public static final class Task {
        private static final int WAITING = 0, RUNNING = 1, DONE = 2;

        private final Runnable work;
        private final AtomicInteger state = new AtomicInteger(WAITING);
        private volatile Thread runner;
        private volatile Throwable error;

        private Task(Runnable work) {
            this.work = work;
        }

        // false when another thread took it first
        private boolean tryRun() {
            if (!state.compareAndSet(WAITING, RUNNING))
                return false;
            runner = Thread.currentThread();
            try {
                work.run();
            } catch (Throwable t) {
                error = t;
            } finally {
                runner = null;
                state.set(DONE);
            }
            return true;
        }

        public boolean isDone() {
            return state.get() == DONE;
        }

        // if the calling thread is the one running it right now
        public boolean isRunningHere() {
            return runner == Thread.currentThread();
        }

        /**
         * Returns once the task has run, running it here if no worker took it yet. The lock is given up while waiting, a worker
         * may need it to finish. The exception the task threw, if any, is returned (not thrown).
         */
        public Throwable await() {
            return await(true);
        }

        // steal: run it here if no worker took it yet (only the render thread may: it runs the walk outside of any lock)
        public Throwable await(boolean steal) {
            if (!(steal && tryRun()) && state.get() != DONE) {
                int holds = LOCK.isHeldByCurrentThread() ? LOCK.getHoldCount() : 0;
                for (int i = 0; i < holds; i++)
                    LOCK.unlock();
                try {
                    long spins = 0;
                    while (state.get() != DONE) {
                        if (++spins < 20000)
                            Thread.onSpinWait();
                        else
                            Thread.yield();
                    }
                } finally {
                    for (int i = 0; i < holds; i++)
                        LOCK.lock();
                }
            }
            return error;
        }
    }

    private static final class Worker extends Thread {
        volatile boolean sleeping;
        // time spent waiting for LOCK in lockForApi, not script time (Avatar.run takes it out of its timing)
        long lockWaitNanos;

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
                Task task = TASKS.poll();
                if (task != null) {
                    task.tryRun();
                    continue;
                }
                // awake for a while for the next job, then asleep until one comes
                long until = System.nanoTime() + SPIN_NANOS;
                while (job == last && TASKS.isEmpty() && System.nanoTime() < until)
                    Thread.onSpinWait();
                if (job == last && TASKS.isEmpty()) {
                    sleeping = true;
                    if (job == last && TASKS.isEmpty())
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

    // the thread inside run() working on the job's tasks next to the workers, null when no job runs
    private static volatile Thread jobCaller;

    /**
     * If an api call that is not thread safe must take LOCK here: on a worker, and on the thread running a job's tasks
     * next to them (the render thread runs some avatars' tick scripts itself during Parallel Tick Scripts; without the lock
     * its calls ran at the same time as the workers' locked ones).
     */
    public static boolean needsApiLock() {
        Thread current = Thread.currentThread();
        return current instanceof Worker || current == jobCaller;
    }

    public static boolean available() {
        return THREADS > 0;
    }

    // takes LOCK for an api call of a script, counting the time a worker waits for it
    public static void lockForApi() {
        if (LOCK.tryLock())
            return;
        long start = System.nanoTime();
        LOCK.lock();
        if (Thread.currentThread() instanceof Worker worker)
            worker.lockWaitNanos += System.nanoTime() - start;
    }

    // how long this worker waited in lockForApi so far, 0 on other threads
    public static long lockWaitNanos() {
        return Thread.currentThread() instanceof Worker worker ? worker.lockWaitNanos : 0;
    }

    // -- background tasks -- //

    // if the render thread holds the lock for the background tasks it handed out (render thread only)
    private static boolean renderThreadHolds;

    /**
     * Hands a task to the workers and returns right away; await it (Task.await) before using what it makes.
     * The render thread holds the lock from now until releaseRenderThreadHold.
     */
    public static Task submit(Runnable work) {
        if (!renderThreadHolds) {
            LOCK.lock();
            renderThreadHolds = true;
        }
        Task task = new Task(work);
        TASKS.add(task);
        for (Worker worker : WORKERS) {
            if (worker.sleeping) {
                LockSupport.unpark(worker);
                break;
            }
        }
        return task;
    }

    // after every background task was awaited: the workers may take the lock again whenever they need it
    public static void releaseRenderThreadHold() {
        if (renderThreadHolds) {
            renderThreadHolds = false;
            LOCK.unlock();
        }
    }

    /**
     * Runs task(0) ... task(count - 1) on the calling thread and the workers, and returns when all of them are done.
     * Tasks are taken one at a time from a shared counter, so the calling thread never waits for a worker that is still waking up,
     * only for tasks a worker already started. The first exception thrown by a task is thrown again here, after all are done.
     * Only one thread (the render thread) runs jobs. Workers busy with a background task join in once they are done with it.
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
        // the calling thread runs tasks side by side with the workers: its api calls must take the lock like theirs
        Thread previous = jobCaller;
        jobCaller = Thread.currentThread();
        try {
            current.work();
        } finally {
            jobCaller = previous;
        }
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

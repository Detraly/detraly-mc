package com.detraly.analytics.neoforge;

import com.detraly.analytics.platform.Scheduler;
import com.detraly.analytics.safety.Guard;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleConsumer;
import java.util.logging.Logger;

final class NeoForgeScheduler implements Scheduler {
    private static final int WINDOW = 100;

    private final ExecutorService asyncPool = Executors.newCachedThreadPool(new java.util.concurrent.ThreadFactory() {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "Detraly-Async-" + count.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });
    private final ConcurrentLinkedQueue<Runnable> syncQueue = new ConcurrentLinkedQueue<>();
    private final CopyOnWriteArrayList<Timer> timers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<DoubleConsumer> tickListeners = new CopyOnWriteArrayList<>();
    private final double[] window = new double[WINDOW];

    private final Logger logger;
    private long tick;
    private long tickStartNanos;
    private int windowFilled;
    private int windowIndex;
    private volatile double averageMillis = Double.NaN;

    private static final class Timer implements Task {
        final Runnable task;
        final long period;
        long nextRun;
        volatile boolean cancelled;

        Timer(Runnable task, long nextRun, long period) {
            this.task = task;
            this.nextRun = nextRun;
            this.period = period;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    NeoForgeScheduler(Logger logger) {
        this.logger = logger;
    }

    void onTickStart() {
        tickStartNanos = System.nanoTime();
    }

    void onTickEnd() {
        double millis = (System.nanoTime() - tickStartNanos) / 1_000_000.0;
        window[windowIndex] = millis;
        windowIndex = (windowIndex + 1) % WINDOW;
        windowFilled = Math.min(WINDOW, windowFilled + 1);
        double sum = 0.0;
        for (int i = 0; i < windowFilled; i++) {
            sum += window[i];
        }
        averageMillis = sum / windowFilled;
        for (DoubleConsumer listener : tickListeners) {
            Guard.run(logger, "a tick listener", () -> listener.accept(millis));
        }

        Runnable next;
        while ((next = syncQueue.poll()) != null) {
            Guard.run(logger, "a scheduled task", next);
        }
        tick++;
        for (Timer timer : timers) {
            if (timer.cancelled) {
                timers.remove(timer);
            } else if (timer.nextRun <= tick) {
                if (timer.period > 0L) {
                    timer.nextRun = tick + timer.period;
                } else {
                    timers.remove(timer);
                }
                Guard.run(logger, "a scheduled task", timer.task);
            }
        }
    }

    double averageTickMillis() {
        return averageMillis;
    }

    Runnable listenTick(DoubleConsumer listener) {
        tickListeners.add(listener);
        return () -> tickListeners.remove(listener);
    }

    @Override
    public Task timer(Runnable task, long delayTicks, long periodTicks) {
        Timer timer = new Timer(task, tick + Math.max(1L, delayTicks), Math.max(1L, periodTicks));
        timers.add(timer);
        return timer;
    }

    @Override
    public void later(Runnable task, long delayTicks) {
        timers.add(new Timer(task, tick + Math.max(1L, delayTicks), 0L));
    }

    @Override
    public void async(Runnable task) {
        asyncPool.execute(task);
    }

    @Override
    public void sync(Runnable task) {
        syncQueue.add(task);
    }

    void shutdown() {
        asyncPool.shutdown();
    }
}

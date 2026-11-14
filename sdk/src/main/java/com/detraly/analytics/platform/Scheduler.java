package com.detraly.analytics.platform;

public interface Scheduler {
    interface Task {
        void cancel();
    }

    Task timer(Runnable task, long delayTicks, long periodTicks);

    void later(Runnable task, long delayTicks);

    void async(Runnable task);

    void sync(Runnable task);
}

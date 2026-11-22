package com.detraly.analytics.safety;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

public final class Guard {
    private static final long REPORT_INTERVAL_MILLIS = 60_000L;
    private static final AtomicLong lastReport = new AtomicLong(Long.MIN_VALUE);

    private Guard() {
    }

    public static boolean run(Logger logger, String what, Runnable action) {
        try {
            action.run();
            return true;
        } catch (Throwable e) {
            report(logger, what, e);
            return false;
        }
    }

    public static Runnable wrap(Logger logger, String what, Runnable action) {
        return () -> run(logger, what, action);
    }

    public static void report(Logger logger, String what, Throwable e) {
        try {
            long now = System.currentTimeMillis();
            long last = lastReport.get();
            if (last != Long.MIN_VALUE && now - last < REPORT_INTERVAL_MILLIS) {
                return;
            }
            if (!lastReport.compareAndSet(last, now)) {
                return;
            }
            logger.warning("Detraly hit an error in " + what + " and kept going (" + e.getClass().getSimpleName()
                    + "). The server is not affected.");
        } catch (Throwable ignored) {

        }
    }
}

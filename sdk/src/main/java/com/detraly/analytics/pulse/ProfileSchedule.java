package com.detraly.analytics.pulse;

import java.util.random.RandomGenerator;

public final class ProfileSchedule {
    public static final long MIN_NANOS = 25_000_000L;
    public static final long MAX_NANOS = 75_000_000L;

    private ProfileSchedule() {
    }

    public static long nextDelayNanos(RandomGenerator random) {
        return MIN_NANOS + (long) (random.nextDouble() * (MAX_NANOS - MIN_NANOS));
    }
}

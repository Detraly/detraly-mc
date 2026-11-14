package com.detraly.analytics.pulse;

import java.util.Random;

public final class ProfileScheduleCheck {
    private static final long TICK = 50_000_000L;
    private static int failures;

    private static boolean busyAt(long nanos) {
        return Math.floorMod(nanos, TICK) < 20_000_000L;
    }

    public static void main(String[] args) {

        Random random = new Random(42);
        double sum = 0.0;
        int n = 100_000;
        for (int i = 0; i < n; i++) {
            long d = ProfileSchedule.nextDelayNanos(random);
            check("delay range " + d, d >= 25_000_000L && d <= 75_000_000L);
            sum += d;
        }
        double mean = sum / n;
        check("mean delay " + mean, Math.abs(mean - 50_000_000.0) < 500_000.0);

        int fixedBusy = 0;
        long t = 10_000_000L;
        for (int i = 0; i < 1200; i++) {
            t += TICK;
            if (busyAt(t)) {
                fixedBusy++;
            }
        }
        check("fixed sampler aliases (" + fixedBusy + "/1200)", fixedBusy == 1200);

        for (int window = 0; window < 20; window++) {
            long now = (long) window * 7_919_000L;
            int busy = 0;
            int samples = 0;
            long end = now + 60_000_000_000L;
            while (true) {
                now += ProfileSchedule.nextDelayNanos(random);
                if (now >= end) {
                    break;
                }
                samples++;
                if (busyAt(now)) {
                    busy++;
                }
            }
            double fraction = (double) busy / samples;
            check("window " + window + " busy fraction " + fraction, Math.abs(fraction - 0.4) < 0.06);
        }

        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("ProfileScheduleCheck: all passed");
    }

    private static void check(String name, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + name);
        }
    }
}

package com.detraly.analytics.pulse;

public final class TickHistogram {

    public static final double LAG_MILLIS = 50.0;
    private static final int FINE = 200;
    private static final int MID = 90;
    private static final int COARSE = 60;
    private static final int BUCKETS = FINE + MID + COARSE;
    private static final double MAX_MILLIS = 60_000.0;

    public record Snapshot(long count, double max, long lagTicks, double p95) {
    }

    private final int[] buckets = new int[BUCKETS];
    private long count;
    private double max;
    private long lagTicks;

    public synchronized void record(double millis) {
        if (!(millis >= 0.0) || Double.isInfinite(millis)) {
            return;
        }
        double v = Math.min(millis, MAX_MILLIS);
        buckets[index(v)]++;
        count++;
        if (v > max) {
            max = v;
        }
        if (v > LAG_MILLIS) {
            lagTicks++;
        }
    }

    public synchronized Snapshot drain() {
        Snapshot snapshot = new Snapshot(count, max, lagTicks, percentile(0.95));
        java.util.Arrays.fill(buckets, 0);
        count = 0L;
        max = 0.0;
        lagTicks = 0L;
        return snapshot;
    }

    private double percentile(double fraction) {
        if (count == 0L) {
            return 0.0;
        }
        long rank = (long) Math.ceil(fraction * count);
        long seen = 0L;
        for (int i = 0; i < BUCKETS; i++) {
            seen += buckets[i];
            if (seen >= rank) {

                return Math.min(upper(i), max);
            }
        }
        return max;
    }

    static int index(double millis) {
        if (millis < 100.0) {
            return (int) (millis * 2.0);
        }
        if (millis < 1000.0) {
            return FINE + (int) ((millis - 100.0) / 10.0);
        }
        return FINE + MID + Math.min(COARSE - 1, (int) ((millis - 1000.0) / 1000.0));
    }

    static double upper(int index) {
        if (index < FINE) {
            return (index + 1) / 2.0;
        }
        if (index < FINE + MID) {
            return 100.0 + (index - FINE + 1) * 10.0;
        }
        return 1000.0 + (index - FINE - MID + 1) * 1000.0;
    }
}

package com.detraly.analytics.pulse;

public final class TickHistogramCheck {
    private static int failures;

    public static void main(String[] args) {
        TickHistogram h = new TickHistogram();

        TickHistogram.Snapshot empty = h.drain();
        check("empty count", empty.count() == 0L);
        check("empty max", empty.max() == 0.0 && empty.lagTicks() == 0L);

        for (int i = 0; i < 94; i++) {
            h.record(10.0);
        }
        for (int i = 0; i < 5; i++) {
            h.record(40.0);
        }
        h.record(400.0);
        TickHistogram.Snapshot s = h.drain();
        check("count", s.count() == 100L);
        check("max", s.max() == 400.0);
        check("lag ticks", s.lagTicks() == 1L);

        check("p95 near 40 ms: " + s.p95(), s.p95() >= 40.0 && s.p95() <= 41.0);

        check("reset", h.drain().count() == 0L);

        h.record(5000.0);
        TickHistogram.Snapshot big = h.drain();
        check("p95 capped by max", big.p95() == 5000.0 && big.max() == 5000.0);

        h.record(50.0);
        h.record(50.5);
        check("lag threshold", h.drain().lagTicks() == 1L);

        h.record(Double.NaN);
        h.record(-1.0);
        h.record(Double.POSITIVE_INFINITY);
        check("bad values ignored", h.drain().count() == 0L);
        h.record(1.0e9);
        TickHistogram.Snapshot huge = h.drain();
        check("capped at 60000", huge.count() == 1L && huge.max() == 60_000.0 && huge.p95() <= 60_000.0);

        double last = 0.0;
        for (double ms = 0.0; ms <= 60_000.0 && failures < 5; ms += 0.25) {
            int i = TickHistogram.index(ms);
            check("index range " + ms, i >= 0 && i < 350);
            check("upper covers " + ms, TickHistogram.upper(i) >= ms);
            check("monotonic " + ms, TickHistogram.upper(i) >= last);
            last = TickHistogram.upper(i);
        }

        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("TickHistogramCheck: all passed");
    }

    private static void check(String name, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + name);
        }
    }
}

package com.detraly.analytics.pulse;

public final class ServerStatsCheck {
    private static int failures;

    public static void main(String[] args) {
        long start = 123_456_789L;
        check("right after begin", ServerStats.tooSoon(start + 2_000_000L, start));
        check("same instant", ServerStats.tooSoon(start, start));
        check("just under a second", ServerStats.tooSoon(start + 999_999_999L, start));
        check("one second", !ServerStats.tooSoon(start + 1_000_000_000L, start));
        check("one interval (5 s)", !ServerStats.tooSoon(start + 5_000_000_000L, start));

        check("negative origin", !ServerStats.tooSoon(-1_000_000_000L + 5_000_000_000L, -1_000_000_000L));

        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("ServerStatsCheck: all passed");
    }

    private static void check(String name, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + name);
        }
    }
}

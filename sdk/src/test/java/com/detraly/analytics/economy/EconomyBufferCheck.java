package com.detraly.analytics.economy;

import java.util.List;

public final class EconomyBufferCheck {
    private static int failures;

    private static final String UUID = "0f8e7c52-1a2b-4c3d-8e9f-001122334455";

    public static void main(String[] args) {
        long now = 1_730_000_000L;

        check("the default capacity is 20000", EconomyBuffer.CAPACITY == 20_000);

        EconomyBuffer b = new EconomyBuffer(10);
        for (int i = 0; i < 4; i++) {
            b.add(balance(now, "poll"));
        }
        check("under half full: no backpressure", !b.backpressure());
        for (int i = 0; i < 2; i++) {
            b.add(balance(now, "sweep"));
        }
        check("more than half full: backpressure", b.backpressure());
        check("size counts both kinds", b.size() == 6);

        b = new EconomyBuffer(5);
        EconomyEvent firstLive = balance(now, "poll");
        b.add(firstLive);
        for (int i = 0; i < 4; i++) {
            b.add(balance(now, "sweep"));
        }
        b.add(balance(now, "poll"));
        check("full: a sweep sample is dropped first", b.size() == 5 && b.takeDropped() == 1L);
        List<EconomyEvent> all = b.take(100, now);
        check("the live events survive", all.get(0) == firstLive && all.size() == 5);

        b = new EconomyBuffer(3);
        EconomyEvent oldest = balance(now, "poll");
        b.add(oldest);
        b.add(balance(now, "poll"));
        b.add(balance(now, "poll"));
        b.add(balance(now, "poll"));
        List<EconomyEvent> kept = b.take(100, now);
        check("no sweep samples: the oldest live event goes", kept.size() == 3 && !kept.contains(oldest));

        b = new EconomyBuffer(3);
        b.add(balance(now, "sweep"));
        b.add(balance(now, "sweep"));
        b.add(balance(now, "sweep"));
        b.add(balance(now, "poll"));
        kept = b.take(100, now);
        check("a live event stays when only sweep samples can go", kept.size() == 3
                && kept.get(0).toJson().getString("reason").equals("poll"));

        b = new EconomyBuffer(100);
        EconomyEvent sweep = balance(now, "sweep");
        EconomyEvent live = balance(now, "poll");
        b.add(sweep);
        b.add(live);
        kept = b.take(1, now);
        check("live events are taken before sweep samples", kept.size() == 1 && kept.get(0) == live);

        b = new EconomyBuffer(100);
        EconomyEvent e1 = balance(now, "poll");
        EconomyEvent e2 = balance(now, "poll");
        EconomyEvent e3 = balance(now, "poll");
        b.add(e1);
        b.add(e2);
        b.add(e3);
        List<EconomyEvent> batch = b.take(2, now);
        b.putBack(batch);
        List<EconomyEvent> again = b.take(10, now);
        check("putBack keeps the order", again.size() == 3 && again.get(0) == e1 && again.get(1) == e2
                && again.get(2) == e3);

        b = new EconomyBuffer(100);
        b.add(balance(now - 24L * 3600L, "poll"));
        b.add(balance(now, "poll"));
        check("events too old are skipped", b.take(10, now).size() == 1);

        b.add(balance(now, "poll"));
        b.clear();
        check("clear empties it", b.isEmpty() && b.size() == 0);

        System.out.println(failures == 0 ? "all passed" : failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static EconomyEvent balance(long ts, String reason) {
        return new EconomyEvent.Balance(EconomyEvent.newEid(), ts, UUID, "Steve", 10.0, reason);
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }
}

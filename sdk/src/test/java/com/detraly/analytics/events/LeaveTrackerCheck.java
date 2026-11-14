package com.detraly.analytics.events;

import java.util.UUID;

public final class LeaveTrackerCheck {
    private static int failures;

    public static void main(String[] args) {
        LeaveTracker t = new LeaveTracker();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        expect("unknown player (reload) gets a shutdown leave", !t.leaveAlreadySubmitted(a));
        t.markJoin(a);
        expect("joined player gets a shutdown leave", !t.leaveAlreadySubmitted(a));
        t.markLeave(a);
        expect("queued quit means no shutdown leave", t.leaveAlreadySubmitted(a));
        expect("other player unaffected", !t.leaveAlreadySubmitted(b));
        t.markJoin(a);
        expect("rejoin clears the leave", !t.leaveAlreadySubmitted(a));
        expect("null is not submitted", !t.leaveAlreadySubmitted(null));
        t.markLeave(null);
        t.markJoin(null);

        for (int i = 0; i < LeaveTracker.MAX_ENTRIES * 3; i++) {
            t.markLeave(UUID.randomUUID());
        }
        t.markLeave(b);
        expect("still remembers the newest after the bound is hit", t.leaveAlreadySubmitted(b));

        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("LeaveTrackerCheck OK");
    }

    private static void expect(String what, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}

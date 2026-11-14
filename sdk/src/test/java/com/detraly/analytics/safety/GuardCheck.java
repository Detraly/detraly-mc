package com.detraly.analytics.safety;

import java.util.logging.Logger;

public final class GuardCheck {
    private static int failures;

    public static void main(String[] args) {
        Logger log = Logger.getLogger("guard-check");
        check("runtime exception is caught", !Guard.run(log, "t", () -> {
            throw new IllegalStateException("x");
        }));
        check("error is caught", !Guard.run(log, "t", () -> {
            throw new NoClassDefFoundError("x");
        }));
        check("stack overflow is caught", !Guard.run(log, "t", GuardCheck::recurse));
        check("out of memory is caught", !Guard.run(log, "t", () -> {
            throw new OutOfMemoryError();
        }));
        check("success returns true", Guard.run(log, "t", () -> { }));
        Runnable wrapped = Guard.wrap(log, "t", () -> {
            throw new LinkageError();
        });
        boolean ok = true;
        try {
            wrapped.run();
        } catch (Throwable e) {
            ok = false;
        }
        check("wrapped runnable never throws", ok);
        Logger broken = new Logger("b", null) {
            @Override
            public void warning(String msg) {
                throw new RuntimeException("logger broke");
            }
        };
        check("a failing logger does not throw", !Guard.run(broken, "t", () -> {
            throw new RuntimeException();
        }));
        System.out.println(failures == 0 ? "all passed" : failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void recurse() {
        recurse();
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }
}

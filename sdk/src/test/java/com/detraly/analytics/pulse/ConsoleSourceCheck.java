package com.detraly.analytics.pulse;

import java.util.List;

public final class ConsoleSourceCheck {
    private static int failures;

    public static void main(String[] args) {
        check("null", ConsoleSource.clean(null) == null);
        check("empty", ConsoleSource.clean("") == null);
        check("plain", "EssentialsX".equals(ConsoleSource.clean("EssentialsX")));
        check("class name", "net.minecraft.server.MinecraftServer".equals(
                ConsoleSource.clean("net.minecraft.server.MinecraftServer")));
        check("allowed set", "a_b.c$d e-f".equals(ConsoleSource.clean("a_b.c$d e-f")));
        check("replaced", "My_Plugin__x_".equals(ConsoleSource.clean("My/Plugin<>xé")));
        check("cut at 64", ConsoleSource.clean("a".repeat(100)).length() == 64);
        check("only bad chars kept as underscores", "___".equals(ConsoleSource.clean("\n\t\u0000")));

        ConsoleBuffer buffer = new ConsoleBuffer();
        long now = System.currentTimeMillis() / 1000L;
        buffer.add(new ConsoleBuffer.Line("[INFO] hi", now, "EssentialsX"));
        buffer.add(new ConsoleBuffer.Line("[INFO] hi", now, "Other"));
        buffer.add(new ConsoleBuffer.Line("[INFO] yo", now));
        List<ConsoleBuffer.Line> batch = buffer.take(10);
        check("same raw and timestamp stays one line", batch.size() == 2);
        check("first source kept", "EssentialsX".equals(batch.get(0).source()));
        check("two-argument line has no source", batch.get(1).source() == null);

        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("ConsoleSourceCheck ok");
    }

    private static void check(String what, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    private ConsoleSourceCheck() {
    }
}

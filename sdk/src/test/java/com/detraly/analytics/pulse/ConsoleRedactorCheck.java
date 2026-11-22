package com.detraly.analytics.pulse;

import java.util.List;

public final class ConsoleRedactorCheck {
    private static int failures;

    public static void main(String[] args) {

        hidden("Steve[/203.0.113.9:51234] logged in", "203.0.113.9");
        hidden("Disconnecting /203.0.113.5.", "203.0.113.5");
        hidden("lost connection from 203.0.113.5. Bye", "203.0.113.5");
        hidden("Banned IP 203.0.113.5.", "203.0.113.5");
        hidden("x.203.0.113.5", "203.0.113.5");
        hidden("Steve[/0:0:0:0:0:0:0:1:55555] logged in", "0:0:0:0:0:0:0:1");
        hidden("Steve[/2001:db8:85a3:0:0:8a2e:370:7334:55555]", "2001:db8:85a3");
        hidden("Steve[/2001:db8::1:55555]", "2001:db8");
        hidden("addr 2001:db8::1: failed", "2001:db8");
        hidden("1:2:3:4:5:6:7::", "1:2:3:4");
        hidden("Alex[/[2001:db8::1]:40000] logged in", "2001:db8");
        hidden("mapped ::ffff:203.0.113.5 here", "203.0.113.5");
        hidden("zone fe80::1%eth0 up", "fe80::1");
        hidden("IP:2001:db8::1 failed", "2001:db8");
        hidden("ip:2001:db8:85a3::8a2e:370:7334", "2001:db8:85a3");
        hidden("(IP:203.0.113.5)", "203.0.113.5");
        hidden("\u00A7a203.\u00A7r0.113.5", "203.0.113.5");
        hidden("boom \u001B[31m203.0.113.5\u001B[0m", "203.0.113.5");

        String token = "dtl_" + "A".repeat(43);
        hidden("leaked " + token + " oops", "dtl_");
        hidden("leaked \u00A7a" + token.substring(0, 10) + "\u00A7r" + token.substring(10), "AAAAAAAAAA");
        dropped("detraly link 01234567-89ab-cdef-0123-456789abcdef");
        dropped("/detraly \u00A7rlink 01234567-89ab-cdef-0123-456789abcdef");
        dropped("/detraly\tlink 01234567-89ab-cdef-0123-456789abcdef");
        dropped("/detraly\u00A0link 01234567-89ab-cdef-0123-456789abcdef");
        dropped("DETRALY   LINK abc");
        dropped("/detraly" + (char) 0x2060 + " link 01234567-89ab-cdef-0123-456789abcdef");
        dropped("/detraly " + (char) 0xFEFF + "link 01234567-89ab-cdef-0123-456789abcdef");
        dropped("/detraly" + (char) 0x200B + " " + (char) 0x2060 + "link 01234567-89ab-cdef-0123-456789abcdef");
        dropped("Steve tried command: /login hunter2");
        dropped("[AuthMe] Steve typed /register hunter2 hunter2");
        dropped("Steve ran /changepassword old new");
        dropped("Steve issued server command: /login hunter2");
        dropped("Steve issued server command: /register hunter2 hunter2");
        dropped("<Steve> my password is hunter2");
        dropped("[Not Secure] <Steve> my password is hunter2");

        kept("Done (4.512s)! For help, type \"help\"", "Done (4.512s)!");
        kept("Time is 12:30:45 now", "12:30:45");
        kept("Starting minecraft server version 1.21.1", "1.21.1");
        kept("Player UUID is 069a79f4-44e9-4726-a5be-fca90e38aaf5", "069a79f4-44e9-4726-a5be-fca90e38aaf5");
        kept("Steve joined the game", "Steve joined the game");
        kept("Paper 1.21.1-123 build", "1.21.1-123");

        check("blank lines are dropped", ConsoleRedactor.clean("   \n\t\n").isEmpty());
        check("control characters are removed", ConsoleRedactor.clean("a\u0000b\u001Bc").equals(List.of("abc")));
        check("one line each for a multi-line message", ConsoleRedactor.clean("one\ntwo\r\nthree").size() == 3);
        String huge = ConsoleRedactor.clean("\u00E9".repeat(10_000)).get(0);
        check("a long line stays under the API limit with room for a prefix",
                huge.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= ConsoleRedactor.MAX_LINE_BYTES);
        long start = System.nanoTime();
        ConsoleRedactor.clean("1:".repeat(500_000));
        ConsoleRedactor.clean("1.".repeat(500_000));
        ConsoleRedactor.clean("::".repeat(500_000));
        ConsoleRedactor.clean("dtl_" + "A".repeat(1_000_000));
        ConsoleRedactor.clean("a:".repeat(500_000));
        ConsoleRedactor.clean("IP:".repeat(300_000));
        ConsoleRedactor.clean("1:2:3:4:".repeat(100_000) + "g");
        check("hostile lines are cleaned quickly", (System.nanoTime() - start) / 1_000_000L < 2000L);

        if (failures > 0) {
            System.out.println(failures + " FAILED");
            System.exit(1);
        }
        System.out.println("all redactor checks passed");
    }

    private static void hidden(String input, String secret) {
        String out = String.join("\n", ConsoleRedactor.clean(input));
        check("hides " + secret + " in: " + input.replace('\u001B', '^').replace('\u00A7', '&'),
                !out.contains(secret) && !out.isEmpty());
    }

    private static void dropped(String input) {
        check("drops: " + input.replace('\u00A7', '&').replace('\t', ' '), ConsoleRedactor.clean(input).isEmpty());
    }

    private static void kept(String input, String expected) {
        check("keeps " + expected, String.join("\n", ConsoleRedactor.clean(input)).contains(expected));
    }

    private static void check(String what, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    private ConsoleRedactorCheck() {
    }
}

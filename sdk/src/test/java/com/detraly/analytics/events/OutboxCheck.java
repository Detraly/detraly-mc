package com.detraly.analytics.events;

import com.detraly.analytics.model.JoinLeaveEvent;
import com.detraly.analytics.storage.JsonDepth;
import com.detraly.analytics.storage.TokenFingerprint;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

public final class OutboxCheck {
    private static final long NOW = 1_731_400_000L;
    private static final String FP = "0123456789abcdef";
    private static int failures;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("outbox-check");
        try {
            run(dir);
        } finally {
            try (Stream<Path> s = Files.walk(dir)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        if (failures > 0) {
            System.out.println(failures + " FAILED");
            System.exit(1);
        }
        System.out.println("all outbox checks passed");
    }

    private static void run(Path dir) throws IOException {
        Logger log = Logger.getLogger("outbox-check");
        log.setUseParentHandlers(false);
        Outbox box = new Outbox(dir.toFile(), log);
        Path file = dir.resolve(Outbox.FILE_NAME);

        check("missing file loads empty", box.load(NOW, FP).isEmpty());

        UUID u = UUID.randomUUID();
        List<JoinLeaveEvent> in = List.of(
                ev(JoinLeaveEvent.Type.JOIN, "Steve", u, NOW - 100, "203.0.113.9"),
                ev(JoinLeaveEvent.Type.LEAVE, "Steve", u, NOW - 50, null),
                ev(JoinLeaveEvent.Type.JOIN, "Steve", u, NOW - 100, "203.0.113.9"));
        check("save ok", box.save(in, NOW, FP));
        String raw = Files.readString(file);
        check("raw has no ip value", !raw.contains("203.0.113.9"));
        check("raw has no ip key", !raw.contains("\"ip\""));
        check("raw has no token", !raw.contains("dtl_"));
        List<JoinLeaveEvent> out = box.load(NOW, FP);
        check("round trip size (duplicates kept)", out.size() == 3);
        check("round trip fields", out.size() == 3 && out.get(0).type() == JoinLeaveEvent.Type.JOIN
                && out.get(0).username().equals("Steve") && out.get(0).uuid().equals(u)
                && out.get(0).timestampSeconds() == NOW - 100 && out.get(1).type() == JoinLeaveEvent.Type.LEAVE);
        boolean ipNull = true;
        for (JoinLeaveEvent e : out) {
            ipNull &= e.ip() == null;
        }
        check("loaded ip null", ipNull);
        check("load does not delete", Files.exists(file));
        check("no tmp files", noTmp(dir));

        check("save window ok", box.save(List.of(
                ev(JoinLeaveEvent.Type.JOIN, "Old", u, NOW - Outbox.MAX_AGE_SECONDS - 1, null),
                ev(JoinLeaveEvent.Type.JOIN, "Edge", u, NOW - Outbox.MAX_AGE_SECONDS, null),
                ev(JoinLeaveEvent.Type.JOIN, "Fut", u, NOW + Outbox.MAX_FUTURE_SECONDS + 1, null),
                ev(JoinLeaveEvent.Type.JOIN, "Soon", u, NOW + Outbox.MAX_FUTURE_SECONDS, null)), NOW, FP));
        out = box.load(NOW, FP);
        check("too old and too future dropped on save", out.size() == 2 && out.get(0).username().equals("Edge")
                && out.get(1).username().equals("Soon"));
        out = box.load(NOW + 10, FP);
        check("too old dropped on load", out.size() == 1 && out.get(0).username().equals("Soon"));
        out = box.load(NOW - 1000, FP);
        check("too future dropped on load", out.size() == 1 && out.get(0).username().equals("Edge"));

        List<JoinLeaveEvent> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(ev(JoinLeaveEvent.Type.JOIN, "Ok_1", u, NOW, null));
        withNull.add(ev(JoinLeaveEvent.Type.JOIN, "bad name!", u, NOW, null));
        withNull.add(ev(JoinLeaveEvent.Type.JOIN, "ThisNameIsWayTooLong", u, NOW, null));
        withNull.add(ev(null, "Nulltype", u, NOW, null));
        check("null element save", box.save(withNull, NOW, FP));
        out = box.load(NOW, FP);
        check("null/invalid elements dropped", out.size() == 1 && out.get(0).username().equals("Ok_1"));
        check("null list save ok and deletes", box.save(null, NOW, FP) && !Files.exists(file));

        List<JoinLeaveEvent> many = new ArrayList<>();
        for (int i = 0; i < Outbox.MAX_EVENTS + 10; i++) {
            many.add(ev(JoinLeaveEvent.Type.JOIN, "P" + i, u, NOW - 1000 + (i % 900), null));
        }
        check("cap save", box.save(many, NOW, FP));
        out = box.load(NOW, FP);
        check("cap size", out.size() == Outbox.MAX_EVENTS);
        check("cap keeps newest", out.size() == Outbox.MAX_EVENTS && out.get(0).username().equals("P10")
                && out.get(out.size() - 1).username().equals("P" + (Outbox.MAX_EVENTS + 9)));
        check("cap file within bytes", Files.size(file) <= Outbox.MAX_BYTES);

        check("empty save ok", box.save(new ArrayList<>(), NOW, FP));
        check("empty save deletes file", !Files.exists(file));
        check("empty save when none ok", box.save(List.of(), NOW, FP));

        write(file, "garbage not json");
        check("garbage -> empty", box.load(NOW, FP).isEmpty());
        write(file, "{\"version\":1,\"events\":[{\"type\":\"join\",\"username\":\"St");
        check("truncated -> empty", box.load(NOW, FP).isEmpty());
        write(file, "{\"version\":2,\"events\":[" + good("Steve", u, NOW) + "]}");
        check("wrong version -> empty", box.load(NOW, FP).isEmpty());
        write(file, "{\"events\":[" + good("Steve", u, NOW) + "]}");
        check("no version -> empty", box.load(NOW, FP).isEmpty());
        write(file, "{\"version\":1,\"events\":\"x\"}");
        check("events not array -> empty", box.load(NOW, FP).isEmpty());
        write(file, "[]");
        check("array root -> empty", box.load(NOW, FP).isEmpty());
        byte[] huge = new byte[(int) Outbox.MAX_BYTES + 1];
        Arrays.fill(huge, (byte) ' ');
        Files.write(file, huge);
        check("huge file -> empty", box.load(NOW, FP).isEmpty());

        write(file, "{\"version\":1,\"fp\":\"" + FP + "\",\"events\":[" + good("Alex", u, NOW - 5) + ","
                + entry("join", "Alex", u.toString(), "1.5") + ","
                + entry("join", "Alex", u.toString(), "\"" + NOW + "\"") + ","
                + entry("join", "Alex", u.toString(), "99999999999999999999999") + ","
                + entry("kick", "Alex", u.toString(), "" + NOW) + ","
                + entry("join", "a b", u.toString(), "" + NOW) + ","
                + entry("join", "Alex", "nope", "" + NOW) + ","
                + entry("join", "Alex", "1-1-1-1-1", "" + NOW) + ","
                + "{\"type\":\"join\",\"username\":\"Alex\",\"timestamp\":" + NOW + "},"
                + "null,5,\"x\","
                + "{\"type\":\"join\",\"username\":\"Alex\",\"uuid\":\"" + u + "\",\"timestamp\":" + (NOW - 10)
                + ",\"ip\":\"203.0.113.9\"},"
                + good("Zed", u, NOW - 1) + "]}");
        out = box.load(NOW, FP);
        check("invalid skipped, valid survive", out.size() == 3 && out.get(0).username().equals("Alex")
                && out.get(1).timestampSeconds() == NOW - 10 && out.get(2).username().equals("Zed"));
        boolean nullIp = true;
        for (JoinLeaveEvent e : out) {
            nullIp &= e.ip() == null;
        }
        check("ip in file ignored on load", nullIp);

        Files.delete(file);
        Files.createDirectory(file);
        Files.createFile(file.resolve("x"));
        check("directory load -> empty", box.load(NOW, FP).isEmpty());
        check("directory save -> false, no throw",
                !box.save(List.of(ev(JoinLeaveEvent.Type.JOIN, "A", u, NOW, null)), NOW, FP));
        check("directory empty save -> false, no throw", !box.save(List.of(), NOW, FP));
        Files.delete(file.resolve("x"));
        Files.delete(file);

        List<JoinLeaveEvent> big = new ArrayList<>();
        for (int i = 0; i < Outbox.MAX_EVENTS * 3; i++) {
            big.add(ev(JoinLeaveEvent.Type.LEAVE, "Name_" + i, UUID.randomUUID(), NOW - 10, null));
        }
        check("big save", box.save(big, NOW, FP));
        check("big file <= MAX_BYTES", Files.size(file) <= Outbox.MAX_BYTES);
        out = box.load(NOW, FP);
        check("big load keeps newest MAX_EVENTS", out.size() == Outbox.MAX_EVENTS
                && out.get(out.size() - 1).username().equals("Name_" + (Outbox.MAX_EVENTS * 3 - 1)));

        write(file, "[".repeat(200_000));
        check("200k '[' -> empty, no throw", box.load(NOW, FP).isEmpty());
        write(file, "{\"version\":1,\"fp\":\"" + FP + "\",\"events\":" + "[".repeat(100_000));
        check("deep events -> empty", box.load(NOW, FP).isEmpty());
        write(file, "{\"version\":1,\"fp\":\"" + FP + "\",\"events\":[" + good("Deep", u, NOW) + "]}");
        check("shallow file with fp loads", box.load(NOW, FP).size() == 1);
        write(file, "{\"version\":1,\"fp\":\"" + FP + "\",\"events\":[{\"type\":\"join\",\"username\":\"[[[[[[[[[[[[[[[[[[[[\","
                + "\"uuid\":\"" + u + "\",\"timestamp\":" + NOW + "}]}");
        write(file, "{\"version\":1,\"events\":['\"'," + "[".repeat(100_000));
        check("single-quoted string hides a double quote: deep -> empty, no throw", box.load(NOW, FP).isEmpty()
                && JsonDepth.exceeds("['\"'," + "[".repeat(20), JsonDepth.MAX_DEPTH)
                && !JsonDepth.exceeds("['" + "[".repeat(100) + "\"']", JsonDepth.MAX_DEPTH));
        check("brackets inside strings do not count", box.load(NOW, FP).isEmpty()
                && JsonDepth.exceeds("[".repeat(17), JsonDepth.MAX_DEPTH)
                && !JsonDepth.exceeds("\"" + "[".repeat(100) + "\\\"[\"", JsonDepth.MAX_DEPTH));

        check("fp save", box.save(in, NOW, FP));
        check("fp file holds a fingerprint, not a token", Files.readString(file).contains("\"fp\":\"" + FP + "\""));
        check("other fingerprint -> discarded", box.load(NOW, "ffffffffffffffff").isEmpty());
        check("no current token -> discarded", box.load(NOW, null).isEmpty());
        check("same fingerprint loads", box.load(NOW, FP).size() == 3);
        write(file, "{\"version\":1,\"events\":[" + good("Steve", u, NOW) + "]}");
        check("file without fingerprint -> discarded", box.load(NOW, FP).isEmpty());
        check("save without fingerprint deletes file", box.save(in, NOW, null) && !Files.exists(file));
        check("fingerprint helper", TokenFingerprint.of("dtl_" + "A".repeat(43)).matches("[0-9a-f]{16}")
                && TokenFingerprint.of("x").equals(TokenFingerprint.of("x"))
                && !TokenFingerprint.of("x").equals(TokenFingerprint.of("y")) && TokenFingerprint.of(null) == null);

        Files.writeString(dir.resolve("outbox.json123.tmp"), "x");
        Files.writeString(dir.resolve("other.tmp"), "x");
        box.deleteLeftovers();
        check("leftover outbox tmp removed", !Files.exists(dir.resolve("outbox.json123.tmp")));
        check("other tmp untouched", Files.exists(dir.resolve("other.tmp")));
        Files.delete(dir.resolve("other.tmp"));

        check("no tmp files at end", noTmp(dir));
    }

    private static String good(String name, UUID u, long ts) {
        return entry("join", name, u.toString(), "" + ts);
    }

    private static String entry(String type, String name, String uuid, String ts) {
        return "{\"type\":\"" + type + "\",\"username\":\"" + name + "\",\"uuid\":\"" + uuid
                + "\",\"timestamp\":" + ts + "}";
    }

    private static void write(Path p, String s) throws IOException {
        Files.write(p, s.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean noTmp(Path dir) {
        File[] files = dir.toFile().listFiles();
        if (files == null) {
            return false;
        }
        for (File f : files) {
            if (f.getName().endsWith(".tmp")) {
                return false;
            }
        }
        return true;
    }

    private static JoinLeaveEvent ev(JoinLeaveEvent.Type t, String n, UUID u, long ts, String ip) {
        return new JoinLeaveEvent(t, n, u, ts, ip);
    }

    private static void check(String what, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    private OutboxCheck() {
    }
}

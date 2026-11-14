package com.detraly.analytics.events;

import com.detraly.analytics.events.RosterStore.Entry;
import com.detraly.analytics.events.RosterStore.Snapshot;
import com.detraly.analytics.model.JoinLeaveEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Logger;
import java.util.stream.Stream;

public final class RosterStoreCheck {
    private static int failures;
    private static final Logger QUIET = Logger.getAnonymousLogger();
    private static final long NOW = 1_731_400_000L;
    private static final String FP = "0123456789abcdef";

    static {
        QUIET.setUseParentHandlers(false);
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("rostercheck");
        try {
            roundTrip(dir(root, 0));
            closedAfterClean(dir(root, 1));
            raceWithClean(dir(root, 2));
            leaves();
            capAndFilter(dir(root, 3));
            badFiles(dir(root, 4));
            edgeCases(dir(root, 5));
            nestingAndFingerprint(dir(root, 6));
            noTemp(root);
        } finally {
            deleteAll(root);
        }
        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("all roster checks passed");
    }

    private static File dir(Path root, int n) throws IOException {
        return Files.createDirectory(root.resolve("d" + n)).toFile();
    }

    private static Entry e(String name) {
        return new Entry(UUID.randomUUID(), name);
    }

    private static void roundTrip(File d) {
        RosterStore s = new RosterStore(d, QUIET);
        check("missing file is empty", s.read(FP).isEmpty());
        List<Entry> in = List.of(e("Steve"), e("Alex_1"));
        check("write ok", s.write(in, NOW, FP));
        Optional<Snapshot> r = s.read(FP);
        check("read present", r.isPresent());
        if (r.isPresent()) {
            check("timestamp", r.get().timestampSeconds() == NOW);
            check("not clean", !r.get().clean());
            check("players equal", r.get().players().equals(in));
        }
        check("markClean ok", s.markClean(NOW + 5, FP));
        Optional<Snapshot> c = s.read(FP);
        check("clean read", c.isPresent() && c.get().clean() && c.get().players().isEmpty()
                && c.get().timestampSeconds() == NOW + 5);
    }

    private static void closedAfterClean(File d) {
        RosterStore s = new RosterStore(d, QUIET);
        s.markClean(NOW, FP);
        check("write after clean refused", !s.write(List.of(e("Steve")), NOW + 1, FP));
        check("file stays clean", s.read(FP).map(Snapshot::clean).orElse(false));
    }

    private static void raceWithClean(File d) throws Exception {
        for (int i = 0; i < 400; i++) {
            RosterStore s = new RosterStore(d, QUIET);
            s.write(List.of(e("Steve")), NOW, FP);
            CountDownLatch go = new CountDownLatch(1);
            Thread t = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException ignored) {
                    return;
                }
                for (int k = 0; k < 5; k++) {
                    s.write(List.of(e("Alex")), NOW + 1, FP);
                }
            });
            t.start();
            go.countDown();
            s.markClean(NOW + 2, FP);
            t.join();
            if (!s.read(FP).map(Snapshot::clean).orElse(false)) {
                check("race left clean=false at iteration " + i, false);
                return;
            }
        }
        check("race never leaves clean=false", true);
    }

    private static void leaves() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<Entry> ps = List.of(new Entry(a, "Steve"), new Entry(b, "Alex"));
        check("clean -> empty", RosterStore.crashLeaves(new Snapshot(NOW, true, ps), Set.of(), NOW + 10).isEmpty());
        List<JoinLeaveEvent> ev = RosterStore.crashLeaves(new Snapshot(NOW, false, ps), Set.of(), NOW + 10);
        check("two leaves", ev.size() == 2);
        check("leave fields", ev.stream().allMatch(x -> x.type() == JoinLeaveEvent.Type.LEAVE
                && x.timestampSeconds() == NOW && x.ip() == null));
        List<JoinLeaveEvent> ex = RosterStore.crashLeaves(new Snapshot(NOW, false, ps), Set.of(a), NOW + 10);
        check("online excluded", ex.size() == 1 && ex.get(0).uuid().equals(b));
        check("too old -> empty", RosterStore.crashLeaves(new Snapshot(NOW, false, ps),
                Set.of(), NOW + RosterStore.MAX_AGE_SECONDS + 1).isEmpty());
        check("exactly max age ok", RosterStore.crashLeaves(new Snapshot(NOW, false, ps),
                Set.of(), NOW + RosterStore.MAX_AGE_SECONDS).size() == 2);
        List<JoinLeaveEvent> fu = RosterStore.crashLeaves(new Snapshot(NOW + 500, false, ps), Set.of(), NOW);
        check("future clamped", fu.size() == 2 && fu.get(0).timestampSeconds() == NOW);
        List<Entry> dup = List.of(new Entry(a, "Steve"), new Entry(a, "Other"));
        List<JoinLeaveEvent> dd = RosterStore.crashLeaves(new Snapshot(NOW, false, dup), Set.of(), NOW);
        check("dedupe keeps first", dd.size() == 1 && dd.get(0).username().equals("Steve"));
        List<Entry> bad = new ArrayList<>();
        bad.add(new Entry(UUID.randomUUID(), "bad name"));
        bad.add(new Entry(UUID.randomUUID(), "waytoolongusername1"));
        bad.add(new Entry(UUID.randomUUID(), null));
        bad.add(null);
        bad.add(new Entry(null, "Steve"));
        bad.add(new Entry(b, "Ok_1"));
        List<JoinLeaveEvent> bb = RosterStore.crashLeaves(new Snapshot(NOW, false, bad), Set.of(), NOW);
        check("bad names skipped", bb.size() == 1 && bb.get(0).username().equals("Ok_1"));
        check("null snapshot", RosterStore.crashLeaves(null, null, NOW).isEmpty());
        check("null online", RosterStore.crashLeaves(new Snapshot(NOW, false, ps), null, NOW).size() == 2);
    }

    private static void capAndFilter(File d) {
        RosterStore s = new RosterStore(d, QUIET);
        List<Entry> many = new ArrayList<>();
        many.add(e("bad name"));
        many.add(null);
        for (int i = 0; i < RosterStore.MAX_PLAYERS + 50; i++) {
            many.add(e("P" + (i % 1000)));
        }
        check("big write ok", s.write(many, NOW, FP));
        Optional<Snapshot> r = s.read(FP);
        check("capped", r.isPresent() && r.get().players().size() == RosterStore.MAX_PLAYERS);
        check("file under max bytes", new File(d, RosterStore.FILE_NAME).length() <= RosterStore.MAX_BYTES);
    }

    private static void badFiles(File d) throws IOException {
        RosterStore s = new RosterStore(d, QUIET);
        Path f = new File(d, RosterStore.FILE_NAME).toPath();
        String u = UUID.randomUUID().toString();
        String[] bad = {
            "", "garbage", "[1,2]", "{}",
            "{\"version\":2,\"clean\":false,\"timestamp\":1,\"players\":[]}",
            "{\"version\":1,\"timestamp\":1,\"players\":[]}",
            "{\"version\":1,\"clean\":\"no\",\"timestamp\":1,\"players\":[]}",
            "{\"version\":1,\"clean\":false,\"timestamp\":\"x\",\"players\":[]}",
            "{\"version\":1,\"clean\":false,\"timestamp\":1.5,\"players\":[]}",
            "{\"version\":1,\"clean\":false,\"timestamp\":1}",
            "{\"version\":1,\"clean\":false,\"timestamp\":1,\"players\":{}}",
        };
        for (String b : bad) {
            Files.writeString(f, b);
            check("unusable: " + b, s.read(FP).isEmpty());
        }
        Files.write(f, new byte[(int) RosterStore.MAX_BYTES + 1]);
        check("huge -> empty", s.read(FP).isEmpty());
        Files.writeString(f, "{\"version\":1,\"fp\":\"" + FP + "\",\"clean\":false,\"timestamp\":" + NOW + ",\"players\":["
                + "{\"uuid\":\"nope\",\"name\":\"Steve\"},{\"uuid\":\"" + u + "\",\"name\":\"bad name\"},"
                + "5,{\"uuid\":\"" + u + "\",\"name\":\"Good\"}]}");
        Optional<Snapshot> r = s.read(FP);
        check("invalid entries skipped", r.isPresent() && r.get().players().size() == 1
                && r.get().players().get(0).name().equals("Good"));
    }

    private static void edgeCases(File d) throws IOException {
        RosterStore s = new RosterStore(d, QUIET);
        check("null list ok", s.write(null, NOW, FP));
        check("null list empty roster", s.read(FP).map(x -> x.players().isEmpty()).orElse(false));

        File sub = new File(d, "x");
        Files.createDirectory(sub.toPath());
        Files.createDirectory(new File(sub, RosterStore.FILE_NAME).toPath());
        RosterStore s2 = new RosterStore(sub, QUIET);
        check("directory read empty", s2.read(FP).isEmpty());
        check("directory write false", !s2.write(List.of(e("Steve")), NOW, FP));
        check("directory markClean false", !s2.markClean(NOW, FP));
        check("closed even after failed markClean", !s2.write(List.of(), NOW, FP));

        RosterStore s3 = new RosterStore(null, QUIET);
        s3.write(List.of(), NOW, FP);
        s3.read(FP);
        s3.markClean(NOW, FP);
        check("null folder does not throw", true);
    }

    private static void nestingAndFingerprint(File d) throws IOException {
        RosterStore s = new RosterStore(d, QUIET);
        Path f = new File(d, RosterStore.FILE_NAME).toPath();
        Files.writeString(f, "[".repeat(200_000));
        check("200k '[' -> empty, no throw", s.read(FP).isEmpty());
        Files.writeString(f, "{\"version\":1,\"fp\":\"" + FP + "\",\"clean\":false,\"timestamp\":" + NOW
                + ",\"players\":" + "[".repeat(100_000));
        check("deep players -> empty", s.read(FP).isEmpty());

        Files.writeString(f, "{\"version\":1,\"clean\":false,\"players\":['\"'," + "[".repeat(100_000));
        check("single-quoted string hides a double quote: deep -> empty, no throw", s.read(FP).isEmpty());

        check("fp write", s.write(List.of(e("Steve")), NOW, FP));
        check("fp file holds fingerprint", Files.readString(f).contains("\"fp\":\"" + FP + "\""));
        check("other fingerprint -> discarded", s.read("ffffffffffffffff").isEmpty());
        check("no token -> discarded", s.read(null).isEmpty());
        check("same fingerprint reads", s.read(FP).isPresent());
        Files.writeString(f, "{\"version\":1,\"clean\":false,\"timestamp\":" + NOW + ",\"players\":[]}");
        check("file without fingerprint -> discarded", s.read(FP).isEmpty());

        Files.writeString(new File(d, "roster.json77.tmp").toPath(), "x");
        Files.writeString(new File(d, "keep.tmp").toPath(), "x");
        s.deleteLeftovers();
        check("leftover roster tmp removed", !new File(d, "roster.json77.tmp").exists());
        check("other tmp untouched", new File(d, "keep.tmp").exists());
        new File(d, "keep.tmp").delete();
    }

    private static void noTemp(Path root) throws IOException {
        try (Stream<Path> w = Files.walk(root)) {
            check("no tmp files left", w.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }

    private static void deleteAll(Path root) throws IOException {
        try (Stream<Path> w = Files.walk(root)) {
            w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static void check(String name, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + name);
        }
    }
}

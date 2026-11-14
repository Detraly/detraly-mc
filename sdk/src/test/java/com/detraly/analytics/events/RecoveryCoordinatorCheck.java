package com.detraly.analytics.events;

import com.detraly.analytics.api.DetralyApiClient;
import com.detraly.analytics.api.GeolocationState;
import com.detraly.analytics.config.DetralyConfig;
import com.detraly.analytics.model.JoinLeaveEvent;
import com.detraly.analytics.storage.TokenFingerprint;
import com.detraly.analytics.storage.TokenStore;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import com.detraly.analytics.config.ConfigSource;
import org.json.JSONObject;

public final class RecoveryCoordinatorCheck {
    private static final String TOKEN_A = "dtl_" + "A".repeat(43);
    private static final String TOKEN_B = "dtl_" + "B".repeat(43);
    private static volatile String mode = "ok";
    private static final List<JSONObject> received = new CopyOnWriteArrayList<>();
    private static final Logger LOG = Logger.getLogger("RecoveryCoordinatorCheck");
    private static int failures;
    private static DetralyConfig config;
    private static GeolocationState geo;
    private static DetralyApiClient client;

    public static void main(String[] args) throws Exception {
        LOG.setLevel(Level.OFF);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/api/v1/join-leave-log", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new JSONObject(body));
            try {
                ex.sendResponseHeaders(mode.equals("down") ? 503 : 202, -1);
            } catch (IOException ignored) {

            }
            ex.close();
        });
        server.start();
        java.util.Map<String, Object> yaml = new java.util.HashMap<>();
        yaml.put("api-url", "http://127.0.0.1:" + server.getAddress().getPort());
        config = DetralyConfig.load(ConfigSource.of(yaml));
        geo = new GeolocationState();
        client = new DetralyApiClient(config, geo);

        Path root = Files.createTempDirectory("recovery-check");
        try {
            reloadSequence(newDir(root));
            notStartedLeavesFilesAlone(newDir(root));
            fingerprintMismatch(root);
            clampAndRelink(root);
            leavesSentAndStaleSnapshot(root);
            saveFailureKeepsRosterUnclean(newDir(root));
            unlinkedRosterIsEmpty(newDir(root));
        } finally {
            server.stop(0);
            try (Stream<Path> s = Files.walk(root)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("RecoveryCoordinatorCheck OK");
        System.exit(0);
    }

    private static File newDir(Path root) throws IOException {
        return Files.createTempDirectory(root, "d").toFile();
    }

    private static TokenStore tokens(File dir, String token) {
        TokenStore t = new TokenStore(dir, LOG);
        if (token != null) {
            t.save(token);
        }
        return t;
    }

    private static final class Stack {
        final EventDispatcher dispatcher;
        final RecoveryCoordinator coordinator;
        final LeaveTracker tracker = new LeaveTracker();

        Stack(File dir, TokenStore tokens) {
            dispatcher = new EventDispatcher(LOG, client, tokens, geo, config);
            coordinator = new RecoveryCoordinator(LOG, dispatcher, new Outbox(dir, LOG), new RosterStore(dir, LOG),
                    tracker, tokens);
        }
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    private static List<RosterStore.Entry> online(UUID u) {
        return List.of(new RosterStore.Entry(u, "Pat_1"));
    }

    private static JSONObject only(String type) {
        JSONObject found = null;
        for (JSONObject o : received) {
            if (type.equals(o.optString("type"))) {
                if (found != null) {
                    return null;
                }
                found = o;
            }
        }
        return found;
    }

    private static void reloadSequence(File dir) {
        TokenStore tokens = tokens(dir, TOKEN_A);
        UUID p = UUID.randomUUID();

        received.clear();
        Stack one = new Stack(dir, tokens);
        long t = now();
        UUID q = UUID.randomUUID();
        List<RosterStore.Entry> pq = List.of(new RosterStore.Entry(p, "Pat_1"), new RosterStore.Entry(q, "Quin_1"));
        one.coordinator.onStart(List.of(), t);
        one.dispatcher.start();
        one.coordinator.onStop(pq, t, 3000L, false);
        expect("reload: onStop(sendLeaves=false) sends no leave", received.isEmpty());
        RosterStore.Snapshot off = new RosterStore(dir, LOG).read(TokenFingerprint.of(TOKEN_A)).orElse(null);
        expect("reload: roster is unclean, lists the online players, carries the disable time",
                off != null && !off.clean() && off.players().size() == 2 && off.timestampSeconds() == t);
        one.coordinator.writeRoster(List.of(), t + 5);
        off = new RosterStore(dir, LOG).read(TokenFingerprint.of(TOKEN_A)).orElse(null);
        expect("reload: a later periodic write cannot replace the final roster",
                off != null && !off.clean() && off.players().size() == 2 && off.timestampSeconds() == t);

        Stack two = new Stack(dir, tokens);
        two.coordinator.onStart(online(p), t + 10);
        two.dispatcher.start();
        two.coordinator.onStop(online(p), t + 10, 3000L, false);
        JSONObject leaveQ = only("leave");
        expect("reload + quit while off: crash leave for the player who left, at the disable time",
                leaveQ != null && leaveQ.optLong("timestamp_from_server") == t
                        && leaveQ.optString("username").equals("Quin_1"));
        expect("reload: no join for the player who stayed online", only("join") == null && received.size() == 1);

        received.clear();
        Stack twoB = new Stack(dir, tokens);
        twoB.coordinator.onStart(online(p), t + 20);
        twoB.dispatcher.start();
        twoB.coordinator.onStop(online(p), t + 20, 3000L, false);
        expect("second reload: no leave, no join", received.isEmpty());

        UUID fresh = UUID.randomUUID();
        received.clear();
        Stack twoC = new Stack(dir, tokens);
        twoC.coordinator.onStart(List.of(new RosterStore.Entry(p, "Pat_1"), new RosterStore.Entry(fresh, "New_1")),
                t + 30);
        twoC.dispatcher.start();
        twoC.coordinator.onStop(online(p), t + 30, 3000L, false);
        JSONObject freshJoin = only("join");
        expect("not listed in the roster: join now", freshJoin != null && received.size() == 1
                && freshJoin.optString("username").equals("New_1")
                && freshJoin.optLong("timestamp_from_server") == t + 30);

        received.clear();
        Stack three = new Stack(dir, tokens);
        long t2 = now();
        three.coordinator.onStart(online(p), t2);
        three.dispatcher.start();
        three.coordinator.onStop(online(p), t2, 3000L, true);
        JSONObject leave = only("leave");
        expect("stop: sendLeaves=true sends the leave", leave != null);
        long leaveTs = leave == null ? 0 : leave.optLong("timestamp_from_server");
        received.clear();
        Stack four = new Stack(dir, tokens);
        four.coordinator.onStart(online(p), t2);
        four.dispatcher.start();
        four.coordinator.onStop(online(p), t2, 3000L, false);
        JSONObject join = only("join");
        expect("leave then start: a join is sent", join != null);
        expect("leave then start: join is stamped after the leave",
                join != null && join.optLong("timestamp_from_server") > leaveTs);

        UUID gone = UUID.randomUUID();
        List<RosterStore.Entry> both = List.of(new RosterStore.Entry(p, "Pat_1"), new RosterStore.Entry(gone, "Gone_1"));
        Stack five = new Stack(dir, tokens);
        five.coordinator.onStart(both, t2 + 1);
        five.coordinator.writeRoster(both, t2 + 2);
        received.clear();
        Stack six = new Stack(dir, tokens);
        six.coordinator.onStart(online(p), t2 + 100);
        six.dispatcher.start();
        six.coordinator.onStop(online(p), t2 + 100, 3000L, false);
        JSONObject crashLeave = only("leave");
        expect("crash: estimated leave only for the player who is gone, at the roster time",
                crashLeave != null && crashLeave.optLong("timestamp_from_server") == t2 + 2
                        && crashLeave.optString("username").equals("Gone_1"));
        expect("crash: the player online again gets neither a leave nor a join (session still open)",
                only("join") == null && received.size() == 1);
    }

    private static void clampAndRelink(Path root) throws IOException, InterruptedException {
        TokenStore tokens = tokens(newDir(root), TOKEN_A);
        File dir = newDir(root);
        UUID p = UUID.randomUUID();
        String fp = TokenFingerprint.of(TOKEN_A);
        long t = now();
        new RosterStore(dir, LOG).markClean(t + 10_000L, fp);
        received.clear();
        Stack s = new Stack(dir, tokens);
        s.coordinator.onStart(online(p), t);
        s.dispatcher.start();
        s.coordinator.onStop(online(p), t, 3000L, false);
        JSONObject join = only("join");
        expect("future clean roster: join is clamped to now + 60",
                join != null && join.optLong("timestamp_from_server") == t + 60L);

        mode = "down";
        received.clear();
        EventDispatcher d = new EventDispatcher(LOG, client, tokens, geo, config);
        d.start();
        d.submit(new JoinLeaveEvent(JoinLeaveEvent.Type.JOIN, "Old_1", p, t, null));
        d.submit(new JoinLeaveEvent(JoinLeaveEvent.Type.JOIN, "Old_2", p, t, null));
        Thread.sleep(400L);
        d.clearQueue();
        Thread.sleep(1300L);
        mode = "ok";
        int seenBefore = received.size();
        List<JoinLeaveEvent> left = d.stopAndDrain(1000L);
        expect("relink: clearQueue leaves nothing to save or send (left=" + left.size() + ")", left.isEmpty());
        expect("relink: nothing delivered after the clear", received.size() == seenBefore);
        d.submit(new JoinLeaveEvent(JoinLeaveEvent.Type.JOIN, "Old_3", p, t, null));
        expect("relink: stopped dispatcher stays closed", d.stopAndDrain(0L).isEmpty());
    }

    private static void notStartedLeavesFilesAlone(File dir) throws IOException {
        TokenStore tokens = tokens(dir, TOKEN_A);
        UUID p = UUID.randomUUID();
        String fp = TokenFingerprint.of(TOKEN_A);
        Outbox box = new Outbox(dir, LOG);
        RosterStore roster = new RosterStore(dir, LOG);
        long t = now();
        box.save(List.of(new JoinLeaveEvent(JoinLeaveEvent.Type.JOIN, "Pat_1", p, t - 5, null)), t, fp);
        roster.write(online(p), t - 3, fp);
        Path outboxFile = dir.toPath().resolve(Outbox.FILE_NAME);
        Path rosterFile = dir.toPath().resolve(RosterStore.FILE_NAME);
        byte[] outboxBefore = Files.readAllBytes(outboxFile);
        byte[] rosterBefore = Files.readAllBytes(rosterFile);

        received.clear();
        Stack s = new Stack(dir, tokens);
        s.dispatcher.start();
        s.coordinator.onStop(online(p), t, 3000L, true);
        expect("not started: outbox.json untouched",
                Files.exists(outboxFile) && Arrays.equals(outboxBefore, Files.readAllBytes(outboxFile)));
        expect("not started: roster.json untouched (not marked clean)",
                Arrays.equals(rosterBefore, Files.readAllBytes(rosterFile)));
        expect("not started: no leave sent", received.isEmpty());
        s.coordinator.writeRoster(online(p), t + 50);
        expect("not started: periodic write does not replace the roster",
                Arrays.equals(rosterBefore, Files.readAllBytes(rosterFile)));
    }

    private static void fingerprintMismatch(Path root) throws IOException {
        File dir = newDir(root);
        UUID p = UUID.randomUUID();
        String fpA = TokenFingerprint.of(TOKEN_A);
        long t = now();
        Outbox box = new Outbox(dir, LOG);
        RosterStore roster = new RosterStore(dir, LOG);
        box.save(List.of(new JoinLeaveEvent(JoinLeaveEvent.Type.JOIN, "Pat_1", p, t - 5, null)), t, fpA);
        roster.write(online(p), t - 3, fpA);

        received.clear();
        Stack s = new Stack(dir, tokens(newDir(root), TOKEN_B));
        s.coordinator.onStart(List.of(), t);
        s.dispatcher.start();
        s.coordinator.onStop(List.of(), t, 3000L, true);
        expect("mismatch: neither the outbox events nor crash leaves are sent", received.isEmpty());

        box.save(List.of(new JoinLeaveEvent(JoinLeaveEvent.Type.JOIN, "Pat_1", p, t - 5, null)), t, fpA);
        roster.write(online(p), t - 3, fpA);
        received.clear();
        Stack c = new Stack(dir, tokens(newDir(root), TOKEN_A));
        c.coordinator.onStart(List.of(), t);
        c.dispatcher.start();
        c.coordinator.onStop(List.of(), t, 3000L, true);
        expect("control: with the matching token the outbox join and the crash leave are sent",
                only("join") != null && only("leave") != null && received.size() == 2);
    }

    private static void leavesSentAndStaleSnapshot(Path root) throws IOException {
        String fp = TokenFingerprint.of(TOKEN_A);
        UUID p = UUID.randomUUID();

        File dir = newDir(root);
        TokenStore tokens = tokens(dir, TOKEN_A);
        Path blocker = dir.toPath().resolve(Outbox.FILE_NAME);
        Files.createDirectory(blocker);
        Files.createFile(blocker.resolve("x"));
        received.clear();
        Stack s = new Stack(dir, tokens);
        long t = now();
        s.coordinator.onStart(List.of(), t - 100);
        s.dispatcher.start();
        s.coordinator.onStop(online(p), t, 3000L, true);
        JSONObject leave = only("leave");
        expect("spigot reload + save failure: the leave was delivered", leave != null);
        expect("spigot reload + save failure: roster is unclean and says leaves were sent",
                Files.readString(dir.toPath().resolve(RosterStore.FILE_NAME)).contains("\"leaves_sent\":true")
                        && !new RosterStore(dir, LOG).read(fp).get().clean());
        Files.delete(blocker.resolve("x"));
        Files.delete(blocker);
        received.clear();
        Stack s2 = new Stack(dir, tokens);
        s2.coordinator.onStart(online(p), t);
        s2.dispatcher.start();
        s2.coordinator.onStop(online(p), t, 3000L, false);
        JSONObject join = only("join");
        expect("spigot reload + save failure: join after the leave",
                join != null && leave != null && join.optLong("timestamp_from_server") == t + 1L
                        && join.optLong("timestamp_from_server") > leave.optLong("timestamp_from_server"));

        File dirK = newDir(root);
        Files.writeString(dirK.toPath().resolve(RosterStore.FILE_NAME), "{\"version\":1,\"fp\":\"" + fp
                + "\",\"clean\":false,\"timestamp\":" + (t - 60) + ",\"players\":[{\"uuid\":\"" + p
                + "\",\"name\":\"Pat_1\"}]}");
        expect("roster without leaves_sent reads as false",
                !new RosterStore(dirK, LOG).read(fp).get().leavesSent());

        File dir2 = newDir(root);
        new RosterStore(dir2, LOG).write(online(p), t - 24L * 3600L, fp);
        received.clear();
        Stack old = new Stack(dir2, tokens(dir2, TOKEN_A));
        old.coordinator.onStart(online(p), t);
        old.dispatcher.start();
        old.coordinator.onStop(online(p), t, 3000L, false);
        JSONObject oldJoin = only("join");
        expect("unclean roster 24 h old listing an online player: join at now",
                oldJoin != null && oldJoin.optLong("timestamp_from_server") == t);

        File dir3 = newDir(root);
        new RosterStore(dir3, LOG).write(online(p), t - 11L * 3600L, fp);
        received.clear();
        Stack recent = new Stack(dir3, tokens(dir3, TOKEN_A));
        recent.coordinator.onStart(online(p), t);
        recent.dispatcher.start();
        recent.coordinator.onStop(online(p), t, 3000L, false);
        expect("unclean roster 11 h old listing an online player: no join", only("join") == null);
    }

    private static void saveFailureKeepsRosterUnclean(File dir) throws IOException {
        TokenStore tokens = tokens(dir, TOKEN_A);
        UUID p = UUID.randomUUID();
        String fp = TokenFingerprint.of(TOKEN_A);

        Path blocker = dir.toPath().resolve(Outbox.FILE_NAME);
        Files.createDirectory(blocker);
        Files.createFile(blocker.resolve("x"));
        mode = "down";
        received.clear();
        Stack s = new Stack(dir, tokens);
        s.coordinator.onStart(List.of(), now() - 500);
        s.dispatcher.start();
        long stopTime = now() - 20;
        s.coordinator.onStop(online(p), stopTime, 800L, true);
        mode = "ok";
        RosterStore.Snapshot snap = new RosterStore(dir, LOG).read(fp).orElse(null);
        expect("save failed: roster present and still unclean", snap != null && !snap.clean());
        expect("save failed: roster carries the stop time", snap != null && snap.timestampSeconds() == stopTime);
        expect("save failed: roster still lists the online player",
                snap != null && snap.players().size() == 1 && snap.players().get(0).uuid().equals(p));
        Files.delete(blocker.resolve("x"));
        Files.delete(blocker);
    }

    private static void unlinkedRosterIsEmpty(File dir) throws IOException {
        TokenStore tokens = tokens(dir, null);
        UUID p = UUID.randomUUID();
        Stack s = new Stack(dir, tokens);
        long t = now();
        s.coordinator.onStart(online(p), t);
        Path rosterFile = dir.toPath().resolve(RosterStore.FILE_NAME);
        String text = Files.readString(rosterFile);
        expect("unlinked: start roster is unclean and empty",
                text.contains("\"clean\":false") && text.contains("\"players\":[]"));
        s.coordinator.writeRoster(online(p), t + 30);
        text = Files.readString(rosterFile);
        expect("unlinked: periodic roster has no names or uuids", !text.contains(p.toString())
                && !text.contains("Pat_1") && text.contains("\"clean\":false") && text.contains("\"players\":[]"));
        s.dispatcher.stop();
    }

    private static void expect(String what, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        } else {
            System.out.println("ok: " + what);
        }
    }
}

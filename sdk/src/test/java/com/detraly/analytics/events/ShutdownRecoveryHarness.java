package com.detraly.analytics.events;

import com.detraly.analytics.api.DetralyApiClient;
import com.detraly.analytics.api.GeolocationState;
import com.detraly.analytics.config.DetralyConfig;
import com.detraly.analytics.model.JoinLeaveEvent;
import com.detraly.analytics.model.JoinLeaveEvent.Type;
import com.detraly.analytics.storage.TokenStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import com.detraly.analytics.config.ConfigSource;
import org.json.JSONObject;

public final class ShutdownRecoveryHarness {
    private static final Logger LOG = Logger.getLogger("harness");
    private static File data;
    private static Path expected;
    private static EventDispatcher dispatcher;
    private static RecoveryCoordinator coordinator;
    private static LeaveTracker tracker;

    public static void main(String[] a) throws Exception {
        String phase = a[0];
        data = new File(a[1]);
        String url = a[2];
        expected = Path.of(a[3]);
        data.mkdirs();
        switch (phase) {
            case "setup" -> setup(new File(a[4]));
            case "stop-with-players" -> stopWithPlayers(url);
            case "outage-stop" -> outageStop(url);
            case "backlog-start" -> backlogStart(url);
            case "crash-child" -> crashChild(url);
            case "crash" -> crashOrchestrate(a);
            case "replay" -> replay(url);
            case "reload" -> reload(url);
            case "relink-a" -> outageStop(url);
            case "relink-b" -> relinkB(url, new File(a[4]));
            case "reload-quit" -> reloadQuit(url);
            default -> throw new IllegalArgumentException(phase);
        }
        System.out.println("PHASE " + phase + " done");
        System.exit(0);
    }

    private static void enable(String url, List<RosterStore.Entry> online, long now) {
        java.util.Map<String, Object> yaml = new java.util.HashMap<>();
        yaml.put("api-url", url);
        DetralyConfig config = DetralyConfig.load(ConfigSource.of(yaml));
        GeolocationState geo = new GeolocationState();
        TokenStore tokens = new TokenStore(data, LOG);
        tokens.load();
        DetralyApiClient client = new DetralyApiClient(config, geo);
        dispatcher = new EventDispatcher(LOG, client, tokens, geo, config);
        tracker = new LeaveTracker();
        coordinator = new RecoveryCoordinator(LOG, dispatcher, new Outbox(data, LOG), new RosterStore(data, LOG),
                tracker, tokens);
        coordinator.onStart(online, now);
        dispatcher.start();
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    private static String redact(String json) {
        return json.replaceAll("\"name\":\"[^\"]*\"", "\"name\":\"..\"");
    }

    private static void expect(Type t, UUID u, long ts) throws Exception {
        Files.writeString(expected, t.name() + " " + u + " " + ts + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void join(String name, UUID u, long ts, String ip) throws Exception {
        dispatcher.submit(new JoinLeaveEvent(Type.JOIN, name, u, ts, ip));
        tracker.markJoin(u);
        expect(Type.JOIN, u, ts);
    }

    private static void quit(String name, UUID u, long ts) throws Exception {
        dispatcher.submit(new JoinLeaveEvent(Type.LEAVE, name, u, ts, null));
        tracker.markLeave(u);
        expect(Type.LEAVE, u, ts);
    }

    private static void setup(File linkJson) throws Exception {
        String token = new JSONObject(Files.readString(linkJson.toPath())).getString("token");
        if (!new TokenStore(data, LOG).save(token)) {
            throw new IllegalStateException("token not saved");
        }
        System.out.println("token saved: " + new TokenStore(data, LOG).load().isPresent());
    }

    private static void stopWithPlayers(String url) throws Exception {
        enable(url, List.of(), now());
        UUID pa = UUID.randomUUID();
        UUID pb = UUID.randomUUID();
        long t = now() - 60;
        join("Alice_e2e", pa, t, null);
        join("Bobby_e2e", pb, t + 1, null);
        quit("Bobby_e2e", pb, t + 30);
        List<RosterStore.Entry> online = List.of(new RosterStore.Entry(pa, "Alice_e2e"),
                new RosterStore.Entry(pb, "Bobby_e2e"));
        long stopAt = now();
        coordinator.onStop(online, stopAt, 3000, true);
        expect(Type.LEAVE, pa, stopAt);
        System.out.println("S1 alice=" + pa + " bob=" + pb + " stop_ts=" + stopAt);
        System.out.println("S1 roster: " + redact(Files.readString(new File(data, "roster.json").toPath())));
    }

    private static void outageStop(String url) throws Exception {
        enable(url, List.of(), now());
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        long t = now() - 120;
        join("Carol_e2e", p1, t, "203.0.113.77");
        join("Dave_e2e", p2, t + 1, null);
        quit("Carol_e2e", p1, t + 50);
        long stopAt = now();
        coordinator.onStop(List.of(new RosterStore.Entry(p2, "Dave_e2e")), stopAt, 1500, true);
        expect(Type.LEAVE, p2, stopAt);
        Path ob = new File(data, "outbox.json").toPath();
        String body = Files.readString(ob);
        System.out.println("S2 outbox.json exists=" + Files.exists(ob) + " bytes=" + body.length()
                + " has-ip-field=" + body.toLowerCase().contains("\"ip\"") + " has-address=" + body.contains("203.0.113"));
        System.out.println("S2 outbox body: " + body.replaceAll("[0-9a-f]{8}-[0-9a-f-]{27}", "<uuid>"));
        System.out.println("S2 roster after outage stop: " + redact(Files.readString(new File(data, "roster.json").toPath())));
    }

    private static void backlogStart(String url) throws Exception {
        enable(url, List.of(), now());
        Thread.sleep(2500);
        coordinator.onStop(List.of(), now(), 3000, true);
        File ob = new File(data, "outbox.json");
        System.out.println("S2 outbox.json after delivery exists=" + ob.exists()
                + (ob.exists() ? " body=" + Files.readString(ob.toPath()).replaceAll("[0-9a-f]{8}-[0-9a-f-]{27}", "<uuid>") : ""));
    }

    private static void crashChild(String url) throws Exception {
        long past = now() - 600;
        UUID x = UUID.randomUUID();
        UUID y = UUID.randomUUID();
        enable(url, List.of(), past);

        coordinator.writeRoster(List.of(new RosterStore.Entry(x, "Xena_e2e"), new RosterStore.Entry(y, "Yuri_e2e")), past);
        System.out.println("CRASHIDS " + x + " " + y + " " + past);
        System.out.println("READY");
        System.out.flush();
        Thread.sleep(600_000);
    }

    private static void crashRecover(String url, UUID y) throws Exception {
        long start = now();
        enable(url, List.of(new RosterStore.Entry(y, "Yuri_e2e")), start);

        Thread.sleep(2500);
        coordinator.onStop(List.of(), now(), 3000, true);
    }

    private static void crashOrchestrate(String[] a) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder pb = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                ShutdownRecoveryHarness.class.getName(), "crash-child", a[1], a[2], a[3]);
        pb.redirectErrorStream(true);
        Process child = pb.start();
        UUID x = null;
        UUID y = null;
        long past = 0;
        try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(child.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("CRASHIDS ")) {
                    String[] p = line.split(" ");
                    x = UUID.fromString(p[1]);
                    y = UUID.fromString(p[2]);
                    past = Long.parseLong(p[3]);
                }
                if (line.equals("READY")) {
                    break;
                }
            }
            child.destroyForcibly();
            System.out.println("S3 child exit code after destroyForcibly: " + child.waitFor());
        }
        System.out.println("S3 roster left by killed child: " + redact(Files.readString(new File(data, "roster.json").toPath())));
        expect(Type.LEAVE, x, past);
        crashRecover(a[2], y);
        System.out.println("S3 xena=" + x + " yuri(online again)=" + y + " roster_ts=" + past);
    }

    private static void replay(String url) throws Exception {
        enable(url, List.of(), now());
        List<JoinLeaveEvent> all = new ArrayList<>();
        for (String line : Files.readAllLines(expected)) {
            String[] p = line.split(" ");
            all.add(new JoinLeaveEvent(Type.valueOf(p[0]), "Replay_e2e", UUID.fromString(p[1]),
                    Long.parseLong(p[2]), null));
        }
        for (int i = 0; i < 2; i++) {
            for (JoinLeaveEvent e : all) {
                dispatcher.submit(e);
            }
        }
        List<JoinLeaveEvent> left = dispatcher.stopAndDrain(8000);
        System.out.println("S4 replayed " + all.size() + " events x2; undelivered=" + left.size());
    }

    private static void reload(String url) throws Exception {
        enable(url, List.of(), now());
        UUID pat = UUID.randomUUID();
        long t = now() - 30;
        join("Patty_e2e", pat, t, null);
        List<RosterStore.Entry> online = List.of(new RosterStore.Entry(pat, "Patty_e2e"));
        Thread.sleep(1500);
        coordinator.onStop(online, now(), 3000, false);
        String rosterAfterStop = Files.readString(new File(data, "roster.json").toPath());
        long rosterTs = new JSONObject(rosterAfterStop).getLong("timestamp");
        System.out.println("S5 roster after reload-stop: " + redact(rosterAfterStop));
        long start = now();
        enable(url, online, start);
        long stamp = rosterTs;

        System.out.println("S5 pat=" + pat + " first_join=" + t + " rejoin_stamp=" + stamp);
        Thread.sleep(2000);
        coordinator.onStop(online, now(), 3000, false);
    }

    private static void relinkB(String url, File linkJsonB) throws Exception {
        System.out.println("S6 outbox before relink exists=" + new File(data, "outbox.json").exists()
                + " roster before relink exists=" + new File(data, "roster.json").exists());
        setup(linkJsonB);
        enable(url, List.of(), now());
        Thread.sleep(3000);
        coordinator.onStop(List.of(), now(), 3000, true);
        System.out.println("S6 outbox after start exists=" + new File(data, "outbox.json").exists()
                + " roster=" + redact(Files.readString(new File(data, "roster.json").toPath())));
    }

    private static void reloadQuit(String url) throws Exception {
        enable(url, List.of(), now());
        UUID quinn = UUID.randomUUID();
        UUID stan = UUID.randomUUID();
        long t = now() - 40;
        join("Quinn_e2e", quinn, t, null);
        join("Stan_e2e", stan, t + 1, null);
        List<RosterStore.Entry> online = List.of(new RosterStore.Entry(quinn, "Quinn_e2e"),
                new RosterStore.Entry(stan, "Stan_e2e"));
        Thread.sleep(1500);
        coordinator.onStop(online, now(), 3000, false);
        String rosterJson = Files.readString(new File(data, "roster.json").toPath());
        long disableTs = new JSONObject(rosterJson).getLong("timestamp");
        System.out.println("S7 roster after disable: " + redact(rosterJson));
        Thread.sleep(2000);
        enable(url, List.of(new RosterStore.Entry(stan, "Stan_e2e")), now());
        expect(Type.LEAVE, quinn, disableTs);
        System.out.println("S7 quinn=" + quinn + " stan=" + stan + " disable_ts=" + disableTs);
        Thread.sleep(2000);
        coordinator.onStop(List.of(new RosterStore.Entry(stan, "Stan_e2e")), now(), 3000, false);
    }
}

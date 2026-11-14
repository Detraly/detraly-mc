package com.detraly.analytics.events;

import com.detraly.analytics.api.DetralyApiClient;
import com.detraly.analytics.api.GeolocationState;
import com.detraly.analytics.api.PrivacyApi.Result;
import com.detraly.analytics.config.ConfigSource;
import com.detraly.analytics.config.DetralyConfig;
import com.detraly.analytics.model.JoinLeaveEvent;
import com.detraly.analytics.platform.PlayerRef;
import com.detraly.analytics.platform.Scheduler;
import com.detraly.analytics.privacy.OptOutStore;
import com.detraly.analytics.privacy.PrivacyService;
import com.detraly.analytics.storage.TokenStore;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Opted-out players send nothing; the privacy route is called with the right request; fake HTTP server. */
public final class OptOutTrackingCheck {
    private static final String TOKEN = "dtl_" + "B".repeat(43);
    private static final List<String> joinLeave = new CopyOnWriteArrayList<>();
    private static final List<String> privacy = new CopyOnWriteArrayList<>();
    private static volatile int privacyCode = 200;
    private static int failures;

    private static final class DirectScheduler implements Scheduler {
        @Override public Task timer(Runnable task, long delayTicks, long periodTicks) { return () -> { }; }
        @Override public void later(Runnable task, long delayTicks) { }
        @Override public void async(Runnable task) { task.run(); }
        @Override public void sync(Runnable task) { task.run(); }
    }

    private static final class Player implements PlayerRef {
        final UUID id;
        final String name;
        Player(UUID id, String name) { this.id = id; this.name = name; }
        @Override public String name() { return name; }
        @Override public UUID uuid() { return id; }
        @Override public InetSocketAddress address() { return new InetSocketAddress("8.8.8.8", 25565); }
        @Override public boolean isOnline() { return true; }
        @Override public void sendPrivacyNotice() { }
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/join-leave-log", ex -> {
            joinLeave.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(202, -1);
            ex.close();
        });
        server.createContext("/api/v1/player-privacy", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            String type = ex.getRequestHeaders().getFirst("Content-Type");
            privacy.add(ex.getRequestMethod() + "|" + ("Bearer " + TOKEN).equals(auth) + "|" + type + "|"
                    + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"success\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(privacyCode, privacyCode == 200 ? body.length : -1);
            if (privacyCode == 200) {
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        server.start();

        File dir = Files.createTempDirectory("optouttracking").toFile();
        Logger logger = Logger.getLogger("OptOutTrackingCheck");
        logger.setLevel(Level.OFF);
        TokenStore tokens = new TokenStore(dir, logger);
        tokens.save(TOKEN);
        Map<String, Object> yaml = new HashMap<>();
        yaml.put("api-url", "http://127.0.0.1:" + server.getAddress().getPort());
        DetralyConfig config = DetralyConfig.load(ConfigSource.of(yaml));
        GeolocationState geo = new GeolocationState();
        DetralyApiClient client = new DetralyApiClient(config, geo);

        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        Result r = client.setOptedOut(TOKEN, id, true);
        expect(r == Result.OK, "200 maps to OK");
        expect(privacy.size() == 1 && privacy.get(0).startsWith("POST|true|application/json|"),
                "POST with bearer token and JSON: " + privacy);
        org.json.JSONObject sent = new org.json.JSONObject(privacy.get(0).substring(privacy.get(0).lastIndexOf('|') + 1));
        expect(sent.length() == 2 && sent.getString("uuid").equals(id.toString()) && sent.getBoolean("opted_out"),
                "body has only uuid and opted_out");
        for (Map.Entry<Integer, Result> e : Map.of(400, Result.BAD_REQUEST, 401, Result.UNAUTHORIZED,
                403, Result.FORBIDDEN, 429, Result.RETRY_LATER, 503, Result.RETRY_LATER).entrySet()) {
            privacyCode = e.getKey();
            expect(client.setOptedOut(TOKEN, id, false) == e.getValue(), "status " + e.getKey());
        }
        privacyCode = 200;
        server.stop(0);
        expect(client.setOptedOut(TOKEN, id, true) == Result.NETWORK_ERROR, "dead server is NETWORK_ERROR");

        joinLeave.clear();
        privacy.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", server.getAddress().getPort()), 0);
        server.createContext("/api/v1/join-leave-log", ex -> {
            joinLeave.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(202, -1);
            ex.close();
        });
        server.createContext("/api/v1/player-privacy", ex -> {
            privacy.add("hit");
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();

        OptOutStore store = new OptOutStore(dir, logger);
        EventDispatcher dispatcher = new EventDispatcher(logger, client, tokens, geo, config);
        dispatcher.setSuppressed(store::isOptedOut);
        PrivacyService service = new PrivacyService(logger, new DirectScheduler(), client, tokens, store);
        PlayerTracker tracker = new PlayerTracker(logger, new DirectScheduler(), dispatcher, geo, store, service,
                config, new LeaveTracker());

        UUID quiet = UUID.randomUUID();
        UUID normal = UUID.randomUUID();
        store.optOut(quiet);
        tracker.onJoin(new Player(quiet, "Quiet"));
        tracker.onJoin(new Player(normal, "Normal"));
        tracker.onQuit(new Player(quiet, "Quiet"));
        tracker.onQuit(new Player(normal, "Normal"));
        dispatcher.submit(new JoinLeaveEvent(JoinLeaveEvent.Type.LEAVE, "Quiet", quiet,
                System.currentTimeMillis() / 1000L, null));
        dispatcher.submitFirst(List.of(new JoinLeaveEvent(JoinLeaveEvent.Type.JOIN, "Quiet", quiet,
                System.currentTimeMillis() / 1000L, null)));
        dispatcher.start();
        dispatcher.stopAndDrain(3000L);

        expect(joinLeave.size() == 2, "only the normal player's join and leave were sent: " + joinLeave);
        for (String body : joinLeave) {
            expect(body.contains(normal.toString()) && !body.contains(quiet.toString()),
                    "no event mentions the opted-out player");
            expect(!body.contains("\"ip\"") || body.contains(normal.toString()), "no ip for the opted-out player");
        }
        expect(privacy.size() == 1, "opted-out player's join syncs the opt-out once: " + privacy);
        expect(!store.isPendingSync(quiet), "pending cleared after the join sync");

        tracker.onJoin(new Player(quiet, "Quiet"));
        expect(privacy.size() == 1, "no second sync once confirmed");

        server.stop(0);
        for (File f : dir.listFiles()) {
            f.delete();
        }
        dir.delete();
        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("OptOutTrackingCheck OK");
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}

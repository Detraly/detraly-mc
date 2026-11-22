package com.detraly.analytics.events;

import com.detraly.analytics.api.DetralyApiClient;
import com.detraly.analytics.api.GeolocationState;
import com.detraly.analytics.config.DetralyConfig;
import com.detraly.analytics.model.JoinLeaveEvent;
import com.detraly.analytics.storage.TokenStore;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.detraly.analytics.config.ConfigSource;

public final class EventDispatcherDrainCheck {
    private static final long GRACE = 300L;
    private static volatile String mode = "ok";
    private static final List<String> received = new CopyOnWriteArrayList<>();
    private static int failures;

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/api/v1/join-leave-log", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(body);
            int code = 202;
            if (mode.equals("down")) {
                code = 503;
            } else if (mode.equals("slow503")) {
                try {
                    Thread.sleep(700L);
                } catch (InterruptedException ignored) {

                }
                code = 503;
            } else if (mode.equals("hang")) {
                try {
                    Thread.sleep(8000L);
                } catch (InterruptedException ignored) {

                }
            }
            try {
                ex.sendResponseHeaders(code, -1);
            } catch (java.io.IOException ignored) {

            }
            ex.close();
        });
        server.start();

        File dir = Files.createTempDirectory("detraly-check").toFile();
        Logger logger = Logger.getLogger("DrainCheck");
        logger.setLevel(Level.OFF);
        TokenStore tokens = new TokenStore(dir, logger);
        tokens.save("dtl_" + "A".repeat(43));
        java.util.Map<String, Object> yaml = new java.util.HashMap<>();
        yaml.put("api-url", "http://127.0.0.1:" + server.getAddress().getPort());
        DetralyConfig config = DetralyConfig.load(ConfigSource.of(yaml));
        GeolocationState geo = new GeolocationState();
        DetralyApiClient client = new DetralyApiClient(config, geo);

        mode = "ok";
        received.clear();
        EventDispatcher d = new EventDispatcher(logger, client, tokens, geo, config);
        d.submit(ev("Aa1"));
        d.submit(ev("Bb2"));
        d.submit(ev("Cc3"));
        d.start();
        long t0 = System.nanoTime();
        List<JoinLeaveEvent> left = d.stopAndDrain(3000L);
        long ms = ms(t0);
        expect("drain delivered everything (left=" + left.size() + ")", left.isEmpty());
        expect("server saw 3 requests (saw " + received.size() + ")", received.size() == 3);
        expect("drain finished well before the budget (" + ms + "ms)", ms < 2000L);
        d.submit(ev("Dd4"));
        expect("submit after stop does not throw or queue", d.stopAndDrain(0L).isEmpty());

        received.clear();
        d = new EventDispatcher(logger, client, tokens, geo, config);
        d.submit(ev("Tail"));
        d.submitFirst(List.of(ev("Head1"), ev("Head2")));
        d.start();
        d.stopAndDrain(3000L);
        expect("submitFirst order Head1, Head2, Tail", order(received, "Head1", "Head2", "Tail"));

        mode = "down";
        received.clear();
        d = new EventDispatcher(logger, client, tokens, geo, config);
        d.submit(ev("Ee5"));
        d.submit(ev("Ff6"));
        d.submit(ev("Gg7"));
        d.start();
        t0 = System.nanoTime();
        left = d.stopAndDrain(2000L);
        ms = ms(t0);
        expect("API down returns all 3 (got " + left.size() + ")", left.size() == 3);
        expect("API down returns quickly, no backoff sleep (" + ms + "ms)", ms < 1000L);
        expect("API down keeps order", left.size() == 3 && left.get(0).username().equals("Ee5")
                && left.get(2).username().equals("Gg7"));

        mode = "hang";
        received.clear();
        d = new EventDispatcher(logger, client, tokens, geo, config);
        d.submit(ev("Hh8"));
        d.submit(ev("Ii9"));
        d.start();
        Thread.sleep(300L);
        t0 = System.nanoTime();
        left = d.stopAndDrain(500L);
        ms = ms(t0);
        expect("hang: both events returned incl. in-flight (got " + left.size() + ")", left.size() == 2);
        expect("hang: in-flight first", left.size() == 2 && left.get(0).username().equals("Hh8"));
        expect("hang: within budget + grace (" + ms + "ms)", ms <= 500L + GRACE);

        d = new EventDispatcher(logger, client, tokens, geo, config);
        d.submit(ev("Jj0"));
        d.start();
        Thread.sleep(200L);
        t0 = System.nanoTime();
        d.stop();
        ms = ms(t0);
        expect("stop() bounded (" + ms + "ms)", ms <= GRACE);

        mode = "down";
        received.clear();
        d = new EventDispatcher(logger, client, tokens, geo, config);
        d.submit(ev("Kk1"));
        d.submit(ev("Ll2"));
        d.start();
        Thread.sleep(1500L);
        mode = "ok";
        t0 = System.nanoTime();
        left = d.stopAndDrain(3000L);
        ms = ms(t0);
        expect("backoff wake: everything delivered (left=" + left.size() + ")", left.isEmpty());
        expect("backoff wake: well within budget, not after the sleep (" + ms + "ms)", ms < 700L);

        mode = "slow503";
        received.clear();
        d = new EventDispatcher(logger, client, tokens, geo, config);
        d.submit(ev("Mm1"));
        d.start();
        Thread.sleep(250L);
        d.clearQueue();
        Thread.sleep(900L);
        mode = "ok";
        d.submit(ev("Nn2"));
        t0 = System.nanoTime();
        left = d.stopAndDrain(3000L);
        ms = ms(t0);
        expect("clearQueue in flight: nothing left (left=" + left.size() + ")", left.isEmpty());
        expect("clearQueue in flight: old event sent once, new event delivered (saw " + received.size() + ")",
                received.size() == 2 && received.get(0).contains("\"Mm1\"") && received.get(1).contains("\"Nn2\""));
        expect("clearQueue in flight: finished within budget (" + ms + "ms)", ms < 1500L);

        server.stop(0);
        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("EventDispatcherDrainCheck OK");
        System.exit(0);
    }

    private static JoinLeaveEvent ev(String name) {
        return new JoinLeaveEvent(JoinLeaveEvent.Type.LEAVE, name, UUID.randomUUID(),
                System.currentTimeMillis() / 1000L, null);
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static boolean order(List<String> bodies, String... names) {
        List<String> copy = new ArrayList<>(bodies);
        if (copy.size() != names.length) {
            return false;
        }
        for (int i = 0; i < names.length; i++) {
            if (!copy.get(i).contains("\"" + names[i] + "\"")) {
                return false;
            }
        }
        return true;
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

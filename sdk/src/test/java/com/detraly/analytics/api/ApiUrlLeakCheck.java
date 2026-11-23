package com.detraly.analytics.api;

import com.detraly.analytics.config.ConfigSource;
import com.detraly.analytics.config.DetralyConfig;
import com.detraly.analytics.model.JoinLeaveEvent;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Checks that nothing is sent to an api-url that is not allowed. A local server listens on every address of
 * this machine; 127.0.0.2 reaches it but is not on the allowed list (only localhost, 127.0.0.1 and [::1] are),
 * so a request that arrives there is a leak.
 */
public final class ApiUrlLeakCheck {
    private static int failures;

    public static void main(String[] args) throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            ex.sendResponseHeaders(202, -1);
            ex.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        try {
            DetralyApiClient blocked = client("http://127.0.0.2:" + port);
            blocked.link("11111111-1111-1111-1111-111111111111");
            blocked.sendJoinLeave("dtl_secret", event());
            blocked.setOptedOut("dtl_secret", UUID.randomUUID(), true);
            check("no request reaches a plain http host that is not local (" + hits.get() + " arrived)",
                    hits.get() == 0);

            DetralyApiClient allowed = client("http://127.0.0.1:" + port);
            allowed.sendJoinLeave("dtl_secret", event());
            check("a request still reaches http://127.0.0.1 (" + hits.get() + " arrived)", hits.get() == 1);
        } finally {
            server.stop(0);
        }
        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("ApiUrlLeakCheck: all passed");
    }

    private static DetralyApiClient client(String url) {
        Map<String, Object> yaml = new HashMap<>();
        yaml.put("api-url", url);
        return new DetralyApiClient(DetralyConfig.load(ConfigSource.of(yaml)), new GeolocationState());
    }

    private static JoinLeaveEvent event() {
        return new JoinLeaveEvent(JoinLeaveEvent.Type.JOIN, "Steve", UUID.randomUUID(),
                System.currentTimeMillis() / 1000L, null);
    }

    private static void check(String name, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + name);
        }
    }
}

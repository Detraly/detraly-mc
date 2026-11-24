package com.detraly.analytics.api;

import com.detraly.analytics.config.ConfigSource;
import com.detraly.analytics.config.DetralyConfig;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

public final class ApiUrlPolicyCheck {
    private static int failures;

    public static void main(String[] args) {
        allowed("https://api.detraly.com");
        allowed("https://api.detraly.com:8443/base");
        allowed("HTTPS://API.DETRALY.COM");
        allowed("Https://api.detraly.com");
        allowed("http://localhost");
        allowed("http://localhost:8080");
        allowed("http://LOCALHOST:8080");
        allowed("HTTP://localhost:8080");
        allowed("http://127.0.0.1");
        allowed("http://127.0.0.1:8080");
        allowed("http://[::1]");
        allowed("http://[::1]:8080");
        allowed("https://localhost");

        refused("http://api.detraly.com");
        refused("HTTP://api.detraly.com");
        refused("http://192.168.1.10:8080");
        refused("http://10.0.0.5");
        refused("http://127.0.0.2");
        refused("http://0.0.0.0");
        refused("http://[::2]");
        refused("http://localhost.evil.com");
        refused("http://localhost.");
        refused("http://evil.com/localhost");
        refused("http://evil.com?x=localhost");
        refused("http://evil.com#localhost");
        refused("http://127.0.0.1.evil.com");
        refused("http://localhost@evil.com");
        refused("http://localhost:80@evil.com");
        refused("http://127.0.0.1@evil.com");
        refused("http://evil.com@localhost");
        refused("https://user:pass@api.detraly.com");
        refused("https://api.detraly.com@evil.com");
        refused("http://localhost%2eevil.com");
        refused("http://localhost:8080evil");
        refused("http://localhost\\@evil.com");
        refused("http:///nohost");
        refused("http:localhost");
        refused("https:");
        refused("https://");
        refused("ftp://api.detraly.com");
        refused("ws://localhost");
        refused("wss://api.detraly.com");
        refused("file:///etc/passwd");
        refused("api.detraly.com");
        refused("//api.detraly.com");
        refused("localhost:8080");
        refused("");
        refused("   ");
        refused(null);
        refused("https://api detraly.com");

        check("whitespace around a good address is tolerated", ApiUrlPolicy.isAllowed("  https://api.detraly.com  "));

        URI link = ApiUrlPolicy.httpUri("https://api.detraly.com", "/api/v1/link");
        check("https request address", link != null && link.toString().equals("https://api.detraly.com/api/v1/link"));
        check("http://localhost request address", ApiUrlPolicy.httpUri("http://localhost:8080", "/api/v1/link") != null);
        check("http to another host: no request address", ApiUrlPolicy.httpUri("http://api.detraly.com", "/x") == null);
        check("userinfo: no request address", ApiUrlPolicy.httpUri("http://localhost@evil.com", "/x") == null);
        check("a path that would move the host is refused",
                ApiUrlPolicy.httpUri("http://localhost", "@evil.com/x") == null);
        check("a path that would move the host is refused (https)",
                ApiUrlPolicy.httpUri("https://api.detraly.com", ".evil.com/x") == null);

        socket("https://api.detraly.com", "wss://api.detraly.com/ws/v1/pulse");
        socket("https://api.detraly.com:8443/base", "wss://api.detraly.com:8443/base/ws/v1/pulse");
        socket("HTTPS://api.detraly.com", "wss://api.detraly.com/ws/v1/pulse");
        socket("http://localhost:8080", "ws://localhost:8080/ws/v1/pulse");
        socket("http://127.0.0.1:8080", "ws://127.0.0.1:8080/ws/v1/pulse");
        socket("http://[::1]:8080", "ws://[::1]:8080/ws/v1/pulse");
        socket("http://api.detraly.com", null);
        socket("http://localhost.evil.com", null);
        socket("http://localhost@evil.com", null);
        socket("ws://api.detraly.com", null);
        socket("wss://api.detraly.com", null);

        check("wss to a public host", ApiUrlPolicy.isAllowedWebSocket(URI.create("wss://api.detraly.com/ws")));
        check("WSS in capitals", ApiUrlPolicy.isAllowedWebSocket(URI.create("WSS://api.detraly.com/ws")));
        check("ws to localhost", ApiUrlPolicy.isAllowedWebSocket(URI.create("ws://localhost:1/ws")));
        check("ws to 127.0.0.1", ApiUrlPolicy.isAllowedWebSocket(URI.create("ws://127.0.0.1/ws")));
        check("ws to [::1]", ApiUrlPolicy.isAllowedWebSocket(URI.create("ws://[::1]/ws")));
        check("ws to a public host is refused", !ApiUrlPolicy.isAllowedWebSocket(URI.create("ws://api.detraly.com/ws")));
        check("ws to localhost.evil.com is refused",
                !ApiUrlPolicy.isAllowedWebSocket(URI.create("ws://localhost.evil.com/ws")));
        check("ws with user info is refused", !ApiUrlPolicy.isAllowedWebSocket(URI.create("ws://localhost@evil.com/ws")));
        check("https is not a web socket scheme", !ApiUrlPolicy.isAllowedWebSocket(URI.create("https://api.detraly.com/ws")));
        check("null web socket address", !ApiUrlPolicy.isAllowedWebSocket(null));

        Map<String, Object> yaml = new HashMap<>();
        check("config: default address has no problem", DetralyConfig.load(ConfigSource.of(yaml)).apiUrlProblem() == null);
        yaml.put("api-url", "http://api.detraly.com/");
        DetralyConfig bad = DetralyConfig.load(ConfigSource.of(yaml));
        check("config: plain http is flagged at load", bad.apiUrlProblem() != null);
        check("config: the address is kept as written, not swapped for the default",
                bad.apiBaseUrl().equals("http://api.detraly.com"));
        check("config: the message does not repeat the address", !bad.apiUrlProblem().contains("api.detraly.com"));
        yaml.put("api-url", "https://user:hunter2@api.detraly.com");
        DetralyConfig secret = DetralyConfig.load(ConfigSource.of(yaml));
        check("config: user info flagged", secret.apiUrlProblem() != null);
        check("config: the message does not repeat a password", !secret.apiUrlProblem().contains("hunter2"));
        yaml.put("api-url", "http://127.0.0.1:8080");
        check("config: http://127.0.0.1 is fine", DetralyConfig.load(ConfigSource.of(yaml)).apiUrlProblem() == null);
        yaml.put("api-url", "");
        check("config: empty falls back to the https default",
                DetralyConfig.load(ConfigSource.of(yaml)).apiUrlProblem() == null);

        check("IpFilter follows the same rule: https", IpFilter.mayTransmitIp("https://api.detraly.com"));
        check("IpFilter follows the same rule: http local", IpFilter.mayTransmitIp("http://127.0.0.1:1"));
        check("IpFilter follows the same rule: http remote", !IpFilter.mayTransmitIp("http://api.detraly.com"));

        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("ApiUrlPolicyCheck: all passed");
    }

    private static void allowed(String url) {
        check("allowed: " + url, ApiUrlPolicy.isAllowed(url) && ApiUrlPolicy.problem(url) == null);
    }

    private static void refused(String url) {
        check("refused: " + url, !ApiUrlPolicy.isAllowed(url) && ApiUrlPolicy.problem(url) != null
                && ApiUrlPolicy.httpUri(url, "/api/v1/link") == null && ApiUrlPolicy.webSocketUri(url, "/ws") == null);
    }

    private static void socket(String base, String expected) {
        URI uri = ApiUrlPolicy.webSocketUri(base, "/ws/v1/pulse");
        check("live connection for " + base + " -> " + expected + " (got " + uri + ")",
                expected == null ? uri == null : uri != null && uri.toString().equals(expected));
    }

    private static void check(String name, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + name);
        }
    }
}

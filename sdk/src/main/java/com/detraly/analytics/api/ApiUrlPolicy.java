package com.detraly.analytics.api;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * The one rule for where the plugin may send the link code and the API token: {@code https}, or plain
 * {@code http} only to this machine (localhost, 127.0.0.1 and [::1]) for development. The live connection
 * follows it too: {@code wss}, or {@code ws} only to this machine.
 *
 * <p>Every request goes through {@link #httpUri} or {@link #webSocketUri} before anything secret is built, so
 * no caller needs its own check. Messages never repeat the address: it may hold a password.
 */
public final class ApiUrlPolicy {
    public static final String FIX_HINT = "Set api-url in the Detraly config to an address that starts with "
            + "https:// (the default is " + com.detraly.analytics.config.DetralyConfig.DEFAULT_API_URL
            + "). Plain http:// is accepted only for localhost, 127.0.0.1 and [::1]. "
            + "Nothing is sent to Detraly until this is fixed and the server is restarted.";

    private ApiUrlPolicy() {
    }

    /** True when {@code apiBaseUrl} may be used: https, or http to this machine only. */
    public static boolean isAllowed(String apiBaseUrl) {
        return problem(apiBaseUrl) == null;
    }

    /** Why {@code apiBaseUrl} may not be used, in words for the operator, or {@code null} when it may. */
    public static String problem(String apiBaseUrl) {
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            return "api-url is empty.";
        }
        URI uri;
        try {
            uri = new URI(apiBaseUrl.trim());
        } catch (URISyntaxException e) {
            return "api-url is not a valid web address.";
        }
        return problem(uri, false);
    }

    /**
     * The URI for a request to {@code path} under {@code apiBaseUrl}, or {@code null} when the base address is
     * not allowed or the joined address is not (checked again on the final URI).
     */
    public static URI httpUri(String apiBaseUrl, String path) {
        if (!isAllowed(apiBaseUrl)) {
            return null;
        }
        try {
            URI base = new URI(apiBaseUrl.trim());
            URI full = new URI(apiBaseUrl.trim() + path);
            if (problem(full, false) != null || !sameHost(base, full)) {
                return null;
            }
            return full;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * The live connection address for {@code path} under {@code apiBaseUrl}: {@code wss} for https, {@code ws}
     * only for http to this machine. {@code null} when the base address is not allowed.
     */
    public static URI webSocketUri(String apiBaseUrl, String path) {
        URI http = httpUri(apiBaseUrl, path);
        if (http == null) {
            return null;
        }
        try {
            String scheme = "https".equalsIgnoreCase(http.getScheme()) ? "wss" : "ws";
            URI ws = new URI(scheme, null, http.getHost(), http.getPort(), http.getRawPath(), null, null);
            return isAllowedWebSocket(ws) ? ws : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** True when {@code uri} is {@code wss}, or {@code ws} to this machine, with a host and no user info. */
    public static boolean isAllowedWebSocket(URI uri) {
        return uri != null && problem(uri, true) == null;
    }

    private static String problem(URI uri, boolean webSocket) {
        String scheme = uri.getScheme();
        if (scheme == null) {
            return "api-url has no scheme; it must start with https://.";
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        boolean secure;
        if (webSocket) {
            if (!scheme.equals("wss") && !scheme.equals("ws")) {
                return "the live connection address must use wss.";
            }
            secure = scheme.equals("wss");
        } else {
            if (!scheme.equals("https") && !scheme.equals("http")) {
                return "api-url must start with https://, not another scheme.";
            }
            secure = scheme.equals("https");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return "api-url has no usable host name.";
        }
        String authority = uri.getRawAuthority();
        if (uri.getRawUserInfo() != null || (authority != null && authority.indexOf('@') >= 0)) {
            return "api-url must not contain a user name or password.";
        }
        if (!secure && !isLoopback(host)) {
            return "api-url uses plain http to a host other than this machine, "
                    + "which would send the link code and token unencrypted.";
        }
        return null;
    }

    private static boolean isLoopback(String host) {
        return host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
    }

    private static boolean sameHost(URI a, URI b) {
        return a.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost()) && a.getPort() == b.getPort();
    }
}

package com.detraly.analytics.config;



public final class DetralyConfig {
    public static final String DEFAULT_API_URL = "https://api.detraly.com";

    private final String apiBaseUrl;
    private final boolean behindProxy;
    private final boolean liveConnection;

    private final boolean profiler;
    private final String apiUrlProblem;

    private DetralyConfig(String apiBaseUrl, boolean behindProxy, boolean liveConnection, boolean profiler) {
        this.apiBaseUrl = apiBaseUrl;
        this.apiUrlProblem = com.detraly.analytics.api.ApiUrlPolicy.problem(apiBaseUrl);
        this.behindProxy = behindProxy;
        this.liveConnection = liveConnection;
        this.profiler = profiler;
    }

    public static DetralyConfig load(ConfigSource config) {
        String url = config.getString("api-url", DEFAULT_API_URL).trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.isEmpty()) {
            url = DEFAULT_API_URL;
        }
        return new DetralyConfig(url, config.getBoolean("behind-proxy", false),
                config.getBoolean("live-connection", true), config.getBoolean("profiler", true));
    }

    /**
     * Why the configured api-url may not be used (it must be https, or http to this machine only), or null when
     * it may. The address is kept as written and every request refuses it; it is never swapped for the default.
     */
    public String apiUrlProblem() {
        return apiUrlProblem;
    }

    public boolean profiler() {
        return profiler;
    }

    public boolean liveConnection() {
        return liveConnection;
    }

    public String apiBaseUrl() {
        return apiBaseUrl;
    }

    public boolean behindProxy() {
        return behindProxy;
    }
}

package com.detraly.analytics.api;

import com.detraly.analytics.config.DetralyConfig;
import com.detraly.analytics.model.JoinLeaveEvent;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

public final class DetralyApiClient implements PrivacyApi {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String GEO_HEADER = "X-Detraly-Geolocation";

    private final DetralyConfig config;
    private final GeolocationState geoState;
    private final HttpClient http;

    public DetralyApiClient(DetralyConfig config, GeolocationState geoState) {
        this.config = config;
        this.geoState = geoState;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public record LinkResult(Status status, String token) {

        public enum Status {
            OK,
            BAD_REQUEST,
            FORBIDDEN,
            NOT_FOUND,
            RATE_LIMITED,
            SERVER_ERROR,
            NETWORK_ERROR
        }

        @Override
        public String toString() {
            return "LinkResult[" + status + "]";
        }
    }

    public enum SendResult {
        ACCEPTED,
        BAD_REQUEST,
        UNAUTHORIZED,
        RETRY_LATER
    }

    public LinkResult link(String linkCode) {
        JSONObject body = new JSONObject();
        body.put("link_code", linkCode);
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.apiBaseUrl() + "/api/v1/link"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new LinkResult(LinkResult.Status.NETWORK_ERROR, null);
        } catch (IOException | IllegalArgumentException e) {
            return new LinkResult(LinkResult.Status.NETWORK_ERROR, null);
        }

        int code = response.statusCode();
        if (code == 200) {
            try {
                JSONObject json = new JSONObject(response.body());
                String token = json.isNull("token") ? null : json.optString("token", null);
                if (token == null || token.isEmpty()) {
                    return new LinkResult(LinkResult.Status.SERVER_ERROR, null);
                }
                return new LinkResult(LinkResult.Status.OK, token);
            } catch (JSONException e) {
                return new LinkResult(LinkResult.Status.SERVER_ERROR, null);
            }
        }
        LinkResult.Status status;
        if (code == 400) {
            status = LinkResult.Status.BAD_REQUEST;
        } else if (code == 403) {
            status = LinkResult.Status.FORBIDDEN;
        } else if (code == 404) {
            status = LinkResult.Status.NOT_FOUND;
        } else if (code == 429) {
            status = LinkResult.Status.RATE_LIMITED;
        } else {
            status = LinkResult.Status.SERVER_ERROR;
        }
        return new LinkResult(status, null);
    }

    public SendResult sendJoinLeave(String token, JoinLeaveEvent event) {
        JSONObject body = new JSONObject();
        body.put("type", event.type().wire());
        body.put("username", event.username());
        body.put("uuid", event.uuid().toString().toLowerCase(java.util.Locale.ROOT));
        body.put("timestamp_from_server", event.timestampSeconds());

        if (event.ip() != null
                && event.type() == JoinLeaveEvent.Type.JOIN
                && IpFilter.mayTransmitIp(config.apiBaseUrl())
                && IpFilter.isPublicAddress(event.ip())) {
            body.put("ip", event.ip());
        }

        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.apiBaseUrl() + "/api/v1/join-leave-log"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.RETRY_LATER;
        } catch (IOException | IllegalArgumentException e) {
            return SendResult.RETRY_LATER;
        }

        int code = response.statusCode();
        if (code == 202) {
            String header = response.headers().firstValue(GEO_HEADER).orElse(null);
            geoState.onAccepted("on".equals(header));
            return SendResult.ACCEPTED;
        }
        if (code == 401) {
            return SendResult.UNAUTHORIZED;
        }
        if (code == 429 || code >= 500) {
            return SendResult.RETRY_LATER;
        }
        return SendResult.BAD_REQUEST;
    }

    @Override
    public Result setOptedOut(String token, UUID uuid, boolean optedOut) {
        JSONObject body = new JSONObject();
        body.put("uuid", uuid.toString().toLowerCase(java.util.Locale.ROOT));
        body.put("opted_out", optedOut);
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.apiBaseUrl() + "/api/v1/player-privacy"))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                    .build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.NETWORK_ERROR;
        } catch (IOException | IllegalArgumentException e) {
            return Result.NETWORK_ERROR;
        }
        int code = response.statusCode();
        if (code == 200) {
            return Result.OK;
        }
        if (code == 401) {
            return Result.UNAUTHORIZED;
        }
        if (code == 403) {
            return Result.FORBIDDEN;
        }
        if (code == 429 || code >= 500) {
            return Result.RETRY_LATER;
        }
        return Result.BAD_REQUEST;
    }
}

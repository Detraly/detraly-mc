package com.detraly.analytics.pulse;

import com.detraly.analytics.api.IpFilter;
import com.detraly.analytics.config.DetralyConfig;
import com.detraly.analytics.economy.EconomyEvent;
import com.detraly.analytics.economy.EconomyHost;
import com.detraly.analytics.economy.EconomyMessages;
import com.detraly.analytics.economy.EconomyTracker;
import com.detraly.analytics.storage.TokenStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class PulseClient {
    private static final String PATH = "/ws/v1/pulse";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final long SEND_TIMEOUT_SECONDS = 10L;
    private static final long ACK_TIMEOUT_MILLIS = 15_000L;
    private static final long BACKOFF_START_MILLIS = 2_000L;
    private static final long RATE_LIMITED_WAIT_MILLIS = 60_000L;
    private static final long NOT_FOUND_WAIT_MILLIS = 10L * 60L * 1000L;
    private static final long READY_TIMEOUT_MILLIS = 5_000L;
    private static final long BACKOFF_MAX_MILLIS = 60_000L;
    private static final long STABLE_AFTER_MILLIS = 60_000L;
    private static final long UNAUTHORIZED_RETRY_MILLIS = 10L * 60L * 1000L;
    private static final long REPLACED_WAIT_MILLIS = 60_000L;
    private static final long LOG_INTERVAL_MILLIS = 10L * 60L * 1000L;
    private static final long CONSOLE_INTERVAL_MILLIS = 1_000L;
    private static final long CONSOLE_RETRY_START_MILLIS = 2_000L;
    private static final long CONSOLE_RETRY_MAX_MILLIS = 30_000L;
    private static final int CONSOLE_BATCH_LINES = 50;

    private static final long ECONOMY_INTERVAL_MILLIS = 1_000L;
    private static final long ECONOMY_RETRY_START_MILLIS = 2_000L;
    private static final long ECONOMY_RETRY_MAX_MILLIS = 60_000L;

    private static final int CONSOLE_BATCH_CHARS = 150_000;
    private static final int MAX_INCOMING_CHARS = 16 * 1024;

    private static final long PROFILE_INTERVAL_MILLIS = 60_000L;

    private static final int CLOSE_UNAUTHORIZED = 4401;
    private static final int CLOSE_REPLACED = 4409;

    public interface Source {
        JSONObject specsMessage();

        JSONObject statsMessage();

        default void begin() {
        }

        default void begin(boolean statsV2) {
            begin();
        }
    }

    public enum Status {
        OFF("off in config.yml"),
        NOT_LINKED("waiting for a link"),
        INSECURE_URL("off: api-url must be https"),
        CONNECTING("connecting"),
        CONNECTED("connected"),
        UNAUTHORIZED("token rejected"),
        STOPPED("stopped");

        private final String text;

        Status(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    private final Logger logger;
    private final DetralyConfig config;
    private final TokenStore tokenStore;
    private final Source stats;
    private final ConsoleBuffer console;
    private final Profiler profiler;
    private final EconomyTracker economy;
    private final HttpClient http;

    private final Object lock = new Object();
    private Thread worker;
    private volatile boolean running;

    private volatile boolean linked;
    private volatile Status status = Status.STOPPED;

    private volatile long tokenGeneration;

    private long backoffMillis = BACKOFF_START_MILLIS;
    private long lastUnauthorizedLogMillis;
    private long lastReplacedLogMillis;
    private long lastInvalidLogMillis;
    private long lastEconomyInvalidLogMillis;
    /** One counter for console and economy messages, so an ack id is never ambiguous. */
    private long nextConsoleId = 1L;

    public PulseClient(Logger logger, DetralyConfig config, TokenStore tokenStore, Source stats,
            ConsoleBuffer console) {
        this(logger, config, tokenStore, stats, console, null);
    }

    public PulseClient(Logger logger, DetralyConfig config, TokenStore tokenStore, Source stats,
            ConsoleBuffer console, Profiler profiler) {
        this(logger, config, tokenStore, stats, console, profiler, null);
    }

    public PulseClient(Logger logger, DetralyConfig config, TokenStore tokenStore, Source stats,
            ConsoleBuffer console, Profiler profiler, EconomyTracker economy) {
        this.profiler = profiler;
        this.economy = economy;
        this.logger = logger;
        this.config = config;
        this.tokenStore = tokenStore;
        this.stats = stats;
        this.console = console;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public Status status() {
        return status;
    }

    public boolean wantsConsole() {
        return running && linked && status != Status.INSECURE_URL;
    }

    public void start() {
        synchronized (lock) {
            if (running) {
                return;
            }
            running = true;
            linked = tokenStore.hasToken();
            worker = new Thread(this::runLoop, "Detraly-Pulse");
            worker.setDaemon(true);
            worker.start();
        }
    }

    public void stop() {
        Thread t;
        synchronized (lock) {
            running = false;
            t = worker;
            worker = null;
            lock.notifyAll();
        }
        if (t != null) {
            t.interrupt();
            try {
                t.join(3000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        status = Status.STOPPED;

        http.shutdownNow();
    }

    public void tokenChanged() {
        synchronized (lock) {
            tokenGeneration++;
            linked = true;
            backoffMillis = BACKOFF_START_MILLIS;
            lock.notifyAll();
        }
    }

    private void runLoop() {
        URI uri = pulseUri();
        if (uri == null) {
            status = Status.INSECURE_URL;
            logger.warning("Detraly live connection is off: api-url must start with https:// "
                    + "(http is allowed only for localhost).");
            return;
        }
        while (running) {
            try {

                long generation = tokenGeneration;
                Optional<String> token = tokenStore.get();
                linked = token.isPresent();
                if (token.isEmpty()) {
                    status = Status.NOT_LINKED;
                    if (console != null) {
                        console.clear();
                    }
                    if (economy != null) {
                        economy.reset();
                    }
                    pause(5_000L, generation);
                    continue;
                }
                status = Status.CONNECTING;
                Session session = connect(uri, token.get(), generation);
                if (session == null) {
                    continue;
                }
                long connectedAt = System.currentTimeMillis();
                status = Status.CONNECTED;
                try {
                    serve(session, token.get(), generation);
                } finally {
                    if (running) {
                        session.abort();
                    } else {

                        session.close();
                    }
                }
                if (!running) {
                    break;
                }
                status = Status.CONNECTING;
                if (System.currentTimeMillis() - connectedAt >= STABLE_AFTER_MILLIS) {
                    backoffMillis = BACKOFF_START_MILLIS;
                }
                afterClose(session.closeCode, generation);
            } catch (Throwable e) {

                if (!running) {
                    break;
                }
                backoff();
            }
        }
    }

    private Session connect(URI uri, String token, long generation) {
        Session session = new Session();
        try {
            WebSocket ws = http.newWebSocketBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .header("Authorization", "Bearer " + token)
                    .buildAsync(uri, session)
                    .get(CONNECT_TIMEOUT.toSeconds() + 5L, TimeUnit.SECONDS);
            session.socket = ws;
            return session;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            int refused = e.getCause() instanceof WebSocketHandshakeException handshake
                    && handshake.getResponse() != null ? handshake.getResponse().statusCode() : 0;
            if (refused == 401) {
                unauthorized(generation);
            } else if (refused == 429 || refused == 503) {

                backoff(RATE_LIMITED_WAIT_MILLIS);
            } else if (refused == 404) {

                pause(NOT_FOUND_WAIT_MILLIS + ThreadLocalRandom.current().nextLong(NOT_FOUND_WAIT_MILLIS / 2L));
            } else {
                backoff();
            }
            return null;
        } catch (TimeoutException | RuntimeException e) {
            backoff();
            return null;
        }
    }

    private void serve(Session session, String token, long generation) {

        if (!session.awaitReady(READY_TIMEOUT_MILLIS) || !session.send(stats.specsMessage())) {
            return;
        }
        stats.begin(session.statsV2);

        boolean profiling = session.profile && profiler != null;
        if (profiling) {
            profiler.startSampling();
        }
        long nextProfile = System.currentTimeMillis() + PROFILE_INTERVAL_MILLIS;
        long nextStats = 0L;
        long nextConsole = 0L;
        long consoleRetryMillis = CONSOLE_RETRY_START_MILLIS;
        List<ConsoleBuffer.Line> inFlight = null;
        long inFlightId = 0L;
        long inFlightSince = 0L;

        boolean economyOn = session.economy && economy != null;
        if (economy != null) {
            // Every ready says whether the API offers the economy; when it stops, collecting stops too.
            economy.offered(session.economy);
        }
        int infoSent = -1;
        long nextEconomy = 0L;
        long economyRetryMillis = ECONOMY_RETRY_START_MILLIS;
        List<EconomyEvent> economyBatch = null;
        long economyId = 0L;
        long economySince = 0L;
        long economyResendAt = 0L;

        try {
            while (running && !session.closed) {
                if (generation != tokenGeneration && !token.equals(tokenStore.get().orElse(null))) {
                    session.close();
                    return;
                }
                long now = System.currentTimeMillis();

                if (now >= nextStats) {

                    JSONObject sample = stats.statsMessage();
                    if (sample != null && !session.send(sample)) {
                        return;
                    }
                    nextStats = now + session.statsIntervalMillis;
                }

                if (profiling && now >= nextProfile) {
                    nextProfile = now + PROFILE_INTERVAL_MILLIS;
                    JSONObject profile = profiler.drainMessage();
                    if (profile != null && !session.send(profile)) {
                        return;
                    }
                }

                if (economyOn) {
                    EconomyHost.Info info = economy.info();
                    int version = economy.infoVersion();
                    if (info != null && version != infoSent) {
                        if (!session.send(EconomyMessages.info(info))) {
                            return;
                        }
                        infoSent = version;
                    }
                }

                Ack ack;
                while ((ack = session.acks.poll()) != null) {
                    if (economyBatch != null && economyResendAt == 0L && ack.id == economyId) {
                        switch (ack.result) {
                            case "accepted":
                                economyRetryMillis = ECONOMY_RETRY_START_MILLIS;
                                nextEconomy = now + ECONOMY_INTERVAL_MILLIS;
                                economyBatch = null;
                                break;
                            case "invalid":
                                logThrottledEconomyInvalid();
                                nextEconomy = now + ECONOMY_INTERVAL_MILLIS;
                                economyBatch = null;
                                break;
                            default:
                                economyResendAt = now + economyRetryMillis;
                                economyRetryMillis = Math.min(economyRetryMillis * 2L, ECONOMY_RETRY_MAX_MILLIS);
                                break;
                        }
                        continue;
                    }
                    if (inFlight == null || ack.id != inFlightId) {
                        continue;
                    }
                    switch (ack.result) {
                        case "accepted":
                            consoleRetryMillis = CONSOLE_RETRY_START_MILLIS;
                            nextConsole = now + CONSOLE_INTERVAL_MILLIS;
                            break;
                        case "invalid":

                            logThrottledInvalid();
                            nextConsole = now + CONSOLE_INTERVAL_MILLIS;
                            break;
                        default:

                            console.putBack(inFlight);
                            nextConsole = now + consoleRetryMillis;
                            consoleRetryMillis = Math.min(consoleRetryMillis * 2L, CONSOLE_RETRY_MAX_MILLIS);
                            break;
                    }
                    inFlight = null;
                }

                if (console != null) {
                    if (inFlight != null && now - inFlightSince > ACK_TIMEOUT_MILLIS) {
                        return;
                    }
                    if (inFlight == null && now >= nextConsole) {
                        List<ConsoleBuffer.Line> batch = console.take(CONSOLE_BATCH_LINES);
                        if (!batch.isEmpty()) {
                            inFlightId = nextConsoleId++;
                            inFlight = batch;
                            inFlightSince = now;
                            JSONObject message = consoleMessage(inFlightId, batch);
                            if (!session.send(message)) {
                                return;
                            }
                        } else {
                            nextConsole = now + CONSOLE_INTERVAL_MILLIS;
                        }
                    }
                }
                if (economyOn) {
                    if (economyBatch != null && economyResendAt == 0L && now - economySince > ACK_TIMEOUT_MILLIS) {
                        return;
                    }
                    boolean resend = economyBatch != null && economyResendAt != 0L && now >= economyResendAt;
                    if (economyBatch == null && now >= nextEconomy) {
                        List<EconomyEvent> batch = economy.buffer().take(EconomyMessages.MAX_EVENTS,
                                now / 1000L);
                        if (batch.isEmpty()) {
                            nextEconomy = now + ECONOMY_INTERVAL_MILLIS;
                        } else {
                            economyBatch = batch;
                            resend = true;
                        }
                    }
                    if (resend) {
                        economyId = nextConsoleId++;
                        economySince = now;
                        economyResendAt = 0L;
                        if (!session.send(EconomyMessages.batch(economyId, economyBatch))) {
                            return;
                        }
                    }
                }
                session.await(250L);
            }
        } finally {
            if (economyBatch != null && economy != null) {
                economy.buffer().putBack(economyBatch);
            }
            if (profiling) {
                profiler.stopSampling();
            }

            if (inFlight != null && console != null) {
                console.putBack(inFlight);
            }
        }
    }

    private JSONObject consoleMessage(long id, List<ConsoleBuffer.Line> batch) {
        JSONArray lines = new JSONArray();
        int chars = 0;
        int used = 0;
        for (ConsoleBuffer.Line line : batch) {
            JSONObject item = new JSONObject();
            item.put("raw", line.raw());
            item.put("timestamp", line.timestampSeconds());
            int size = item.toString().length();
            if (used > 0 && chars + size > CONSOLE_BATCH_CHARS) {
                break;
            }
            lines.put(item);
            chars += size;
            used++;
        }
        if (used < batch.size()) {
            console.putBack(List.copyOf(batch.subList(used, batch.size())));
            batch.subList(used, batch.size()).clear();
        }
        JSONObject message = new JSONObject();
        message.put("type", "console");
        message.put("id", id);
        message.put("lines", lines);
        return message;
    }

    private void afterClose(int code, long generation) {
        if (code == CLOSE_UNAUTHORIZED) {
            unauthorized(generation);
        } else if (code == CLOSE_REPLACED) {
            long now = System.currentTimeMillis();
            if (now - lastReplacedLogMillis >= LOG_INTERVAL_MILLIS) {
                lastReplacedLogMillis = now;
                logger.warning("Another server connected to Detraly with this server's token. "
                        + "Each server needs its own link: create one in the dashboard and run detraly link <code>.");
            }

            pause(REPLACED_WAIT_MILLIS + ThreadLocalRandom.current().nextLong(REPLACED_WAIT_MILLIS));
        } else {
            backoff();
        }
    }

    private void unauthorized(long generation) {
        if (generation != tokenGeneration) {

            return;
        }
        status = Status.UNAUTHORIZED;
        if (console != null) {
            console.clear();
        }
        if (economy != null) {
            economy.reset();
        }
        long now = System.currentTimeMillis();
        if (now - lastUnauthorizedLogMillis >= LOG_INTERVAL_MILLIS) {
            lastUnauthorizedLogMillis = now;
            logger.severe("The Detraly API rejected this server's token. "
                    + "Re-link with /detraly link <code> using a new code from the dashboard.");
        }
        pause(UNAUTHORIZED_RETRY_MILLIS, generation);
    }

    private void logThrottledInvalid() {
        long now = System.currentTimeMillis();
        if (now - lastInvalidLogMillis >= LOG_INTERVAL_MILLIS) {
            lastInvalidLogMillis = now;
            logger.warning("Detraly API rejected some console lines as invalid; dropping them. "
                    + "If this keeps happening, check that this server's clock is correct.");
        }
    }

    private void logThrottledEconomyInvalid() {
        long now = System.currentTimeMillis();
        if (now - lastEconomyInvalidLogMillis >= LOG_INTERVAL_MILLIS) {
            lastEconomyInvalidLogMillis = now;
            logger.warning("Detraly API rejected some economy events as invalid; dropping them. "
                    + "If this keeps happening, check that this server's clock is correct.");
        }
    }

    private void backoff() {
        backoff(0L);
    }

    private void backoff(long minimumMillis) {
        long wait = 500L + ThreadLocalRandom.current().nextLong(backoffMillis);
        if (minimumMillis > 0L) {
            wait = minimumMillis + ThreadLocalRandom.current().nextLong(minimumMillis / 2L + 1L);
        }
        backoffMillis = Math.min(backoffMillis * 2L, BACKOFF_MAX_MILLIS);
        pause(wait);
    }

    private void pause(long millis) {
        pause(millis, tokenGeneration);
    }

    private void pause(long millis, long generation) {
        long end = System.currentTimeMillis() + millis;
        synchronized (lock) {
            while (running && generation == tokenGeneration) {
                long left = end - System.currentTimeMillis();
                if (left <= 0L) {
                    return;
                }
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    private URI pulseUri() {
        String base = config.apiBaseUrl();
        if (!IpFilter.mayTransmitIp(base)) {
            return null;
        }
        try {
            URI api = URI.create(base);
            String scheme = "https".equalsIgnoreCase(api.getScheme()) ? "wss" : "ws";
            String path = api.getRawPath() == null ? "" : api.getRawPath();
            return new URI(scheme, null, api.getHost(), api.getPort(), path + PATH, null, null);
        } catch (IllegalArgumentException | java.net.URISyntaxException e) {
            return null;
        }
    }

    private record Ack(long id, String result) {
    }

    private static final class Session implements WebSocket.Listener {
        private final Object signal = new Object();
        private final ConcurrentLinkedQueue<Ack> acks = new ConcurrentLinkedQueue<>();
        private final StringBuilder partial = new StringBuilder();
        private volatile WebSocket socket;
        private volatile boolean closed;

        private volatile boolean gone;
        private volatile int closeCode;
        private volatile long statsIntervalMillis = 5_000L;
        private volatile boolean ready;

        private volatile boolean statsV2;
        private volatile boolean profile;
        private volatile boolean economy;

        boolean send(JSONObject message) {
            WebSocket ws = socket;
            if (ws == null || closed) {
                return false;
            }
            try {
                ws.sendText(message.toString(), true).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException | RuntimeException e) {

            }
            closed = true;
            return false;
        }

        void close() {
            boolean interrupted = Thread.interrupted();
            try {
                WebSocket ws = socket;
                if (ws != null && !gone) {
                    try {
                        ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(2L, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        interrupted = true;
                    } catch (ExecutionException | TimeoutException | RuntimeException e) {

                    }
                }
                abort();
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        void abort() {
            closed = true;
            WebSocket ws = socket;
            if (ws != null) {
                ws.abort();
            }
        }

        boolean awaitReady(long millis) {
            long end = System.currentTimeMillis() + millis;
            synchronized (signal) {
                while (!ready && !closed) {
                    long left = end - System.currentTimeMillis();
                    if (left <= 0L) {
                        return false;
                    }
                    try {
                        signal.wait(left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return ready;
            }
        }

        void await(long millis) {
            synchronized (signal) {
                if (closed || !acks.isEmpty()) {
                    return;
                }
                try {
                    signal.wait(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    closed = true;
                }
            }
        }

        private void wake() {
            synchronized (signal) {
                signal.notifyAll();
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            if (partial.length() + data.length() > MAX_INCOMING_CHARS) {

                abort();
                wake();
                return null;
            }
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                handle(text);
            }
            webSocket.request(1);
            return null;
        }

        private void handle(String text) {
            try {
                JSONObject json = new JSONObject(text);
                String type = json.optString("type", "");
                if ("ready".equals(type)) {
                    long seconds = Math.max(1L, Math.min(60L, json.optLong("stats_interval", 5L)));

                    long idle = Math.max(2L, Math.min(600L, json.optLong("idle_timeout", 30L)));
                    statsIntervalMillis = Math.min(seconds, idle / 2L) * 1000L;
                    JSONArray features = json.optJSONArray("features");
                    if (features != null) {
                        for (int i = 0; i < features.length(); i++) {
                            String feature = features.optString(i, "");
                            if (feature.equals("stats_v2")) {
                                statsV2 = true;
                            } else if (feature.equals("profile")) {
                                profile = true;
                            } else if (feature.equals("economy")) {
                                economy = true;
                            }
                        }
                    }
                    ready = true;
                    wake();
                } else if ("ack".equals(type)) {
                    acks.add(new Ack(json.optLong("id", -1L), json.optString("result", "")));
                    wake();
                }

            } catch (JSONException e) {

            }
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, java.nio.ByteBuffer data, boolean last) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode = statusCode;
            gone = true;
            closed = true;
            wake();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            gone = true;
            closed = true;
            wake();
        }
    }
}

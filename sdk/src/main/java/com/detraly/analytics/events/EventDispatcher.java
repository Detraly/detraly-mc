package com.detraly.analytics.events;

import com.detraly.analytics.api.DetralyApiClient;
import com.detraly.analytics.api.DetralyApiClient.SendResult;
import com.detraly.analytics.api.GeolocationState;
import com.detraly.analytics.api.IpFilter;
import com.detraly.analytics.config.DetralyConfig;
import com.detraly.analytics.model.JoinLeaveEvent;
import com.detraly.analytics.storage.TokenStore;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Logger;

public final class EventDispatcher {
    private static final int CAPACITY = 5000;
    private static final long MAX_AGE_SECONDS = 23L * 60L * 60L;
    private static final long WARN_INTERVAL_MILLIS = 60_000L;
    private static final long UNAUTHORIZED_LOG_INTERVAL_MILLIS = 10L * 60L * 1000L;
    private static final long BACKOFF_START_MILLIS = 1_000L;
    private static final long BACKOFF_MAX_MILLIS = 60_000L;

    private static final long INTERRUPT_GRACE_MILLIS = 200L;

    private volatile Predicate<UUID> suppressed = uuid -> false;

    private final Logger logger;
    private final DetralyApiClient client;
    private final TokenStore tokenStore;
    private final GeolocationState geoState;
    private final DetralyConfig config;

    private final Object lock = new Object();
    private final ArrayDeque<JoinLeaveEvent> queue = new ArrayDeque<>();
    private Thread worker;
    private volatile boolean running;

    private volatile boolean draining;

    private boolean closed;

    private JoinLeaveEvent inFlight;

    private int generation;

    private int takenGeneration;

    private boolean sleeping;

    private long lastFullWarnMillis;
    private long lastUnauthorizedLogMillis;
    private long backoffMillis = BACKOFF_START_MILLIS;

    public EventDispatcher(Logger logger, DetralyApiClient client, TokenStore tokenStore,
            GeolocationState geoState, DetralyConfig config) {
        this.logger = logger;
        this.client = client;
        this.tokenStore = tokenStore;
        this.geoState = geoState;
        this.config = config;
    }

    public void start() {
        synchronized (lock) {
            if (running) {
                return;
            }
            running = true;
            draining = false;
            closed = false;
            worker = new Thread(this::runLoop, "Detraly-EventDispatcher");
            worker.setDaemon(true);
            worker.start();
        }
    }

    public void stop() {
        stopAndDrain(0L);
    }

    public List<JoinLeaveEvent> stopAndDrain(long budgetMillis) {
        long deadline = System.nanoTime() + Math.max(0L, budgetMillis) * 1_000_000L;
        Thread t;
        synchronized (lock) {
            draining = true;
            t = worker;
            if (sleeping && t != null) {
                t.interrupt();
            }
            lock.notifyAll();
        }
        if (t != null) {
            joinUntil(t, deadline);
            if (t.isAlive()) {
                synchronized (lock) {
                    running = false;
                    lock.notifyAll();
                }
                t.interrupt();
                joinUntil(t, System.nanoTime() + INTERRUPT_GRACE_MILLIS * 1_000_000L);
            }
        }
        List<JoinLeaveEvent> undelivered = new ArrayList<>();
        synchronized (lock) {
            running = false;
            closed = true;
            worker = null;
            if (inFlight != null) {
                undelivered.add(inFlight);
                inFlight = null;
            }
            undelivered.addAll(queue);
            queue.clear();
            lock.notifyAll();
        }
        return undelivered;
    }

    private static void joinUntil(Thread t, long deadlineNanos) {
        long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        if (remainingMillis <= 0L) {
            return;
        }
        try {
            t.join(remainingMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void setSuppressed(Predicate<UUID> suppressed) {
        this.suppressed = suppressed == null ? uuid -> false : suppressed;
    }

    private boolean isSuppressed(JoinLeaveEvent event) {
        return suppressed.test(event.uuid());
    }

    public void clearQueue() {
        synchronized (lock) {
            queue.clear();
            inFlight = null;
            generation++;
        }
    }

    public void submit(JoinLeaveEvent event) {
        if (event == null || !tokenStore.hasToken()) {
            return;
        }
        if (isTooOld(event) || isSuppressed(event)) {
            return;
        }
        boolean dropped = false;
        synchronized (lock) {
            if (closed) {
                return;
            }
            if (queue.size() >= CAPACITY) {
                queue.pollFirst();
                dropped = true;
            }
            queue.addLast(event);
            lock.notifyAll();
        }
        if (dropped) {
            warnQueueFull();
        }
    }

    public void submitFirst(List<JoinLeaveEvent> events) {
        if (events == null || events.isEmpty() || !tokenStore.hasToken()) {
            return;
        }
        List<JoinLeaveEvent> valid = new ArrayList<>(events.size());
        for (JoinLeaveEvent e : events) {
            if (e != null && !isTooOld(e) && !isSuppressed(e)) {
                valid.add(e);
            }
        }
        boolean dropped;
        synchronized (lock) {
            if (closed) {
                return;
            }
            int room = Math.max(0, CAPACITY - queue.size());
            int from = Math.max(0, valid.size() - room);
            dropped = from > 0;
            for (int i = valid.size() - 1; i >= from; i--) {
                queue.addFirst(valid.get(i));
            }
            lock.notifyAll();
        }
        if (dropped) {
            warnQueueFull();
        }
    }

    private static boolean isTooOld(JoinLeaveEvent event) {
        return System.currentTimeMillis() / 1000L - event.timestampSeconds() > MAX_AGE_SECONDS;
    }

    private void warnQueueFull() {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            if (now - lastFullWarnMillis < WARN_INTERVAL_MILLIS) {
                return;
            }
            lastFullWarnMillis = now;
        }
        logger.warning("Detraly event queue is full; dropping the oldest events.");
    }

    private JoinLeaveEvent take() {
        synchronized (lock) {
            while (running && queue.isEmpty() && !draining) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    return null;
                }
            }
            if (!running || queue.isEmpty()) {
                return null;
            }
            inFlight = queue.pollFirst();
            takenGeneration = generation;
            return inFlight;
        }
    }

    private void finish() {
        synchronized (lock) {
            inFlight = null;
        }
    }

    private void putBack(JoinLeaveEvent event) {
        synchronized (lock) {
            if (closed || takenGeneration != generation) {
                return;
            }
            inFlight = null;
            queue.addFirst(event);
        }
    }

    private void runLoop() {
        while (running) {
            JoinLeaveEvent event = take();
            if (event == null) {
                break;
            }
            try {
                if (process(event) && draining) {
                    break;
                }
                finish();
            } catch (Throwable e) {

                logger.warning("Unexpected error while sending an event; will retry.");
                putBack(event);
                if (draining || !backoff()) {
                    break;
                }
            }
        }
    }

    private boolean process(JoinLeaveEvent original) {
        if (isTooOld(original) || isSuppressed(original)) {
            return false;
        }
        Optional<String> token = tokenStore.get();
        if (token.isEmpty()) {
            return false;
        }
        JoinLeaveEvent toSend = original;
        if (toSend.ip() != null && !mayAttachIp(toSend)) {
            toSend = toSend.withoutIp();
        }

        SendResult result = client.sendJoinLeave(token.get(), toSend);
        if (result == SendResult.BAD_REQUEST && toSend.ip() != null) {
            logger.warning("Detraly API refused the player address; resending the event without it.");
            toSend = toSend.withoutIp();
            result = client.sendJoinLeave(token.get(), toSend);
        }

        switch (result) {
            case ACCEPTED:
                backoffMillis = BACKOFF_START_MILLIS;
                return false;
            case BAD_REQUEST:
                logger.warning("Detraly API rejected an event as invalid; dropping it.");
                return false;
            case UNAUTHORIZED:
                logUnauthorized();
                return false;
            case RETRY_LATER:
            default:
                putBack(toSend);
                if (draining) {
                    return true;
                }
                backoff();
                return false;
        }
    }

    private boolean mayAttachIp(JoinLeaveEvent event) {
        return event.type() == JoinLeaveEvent.Type.JOIN
                && geoState.isOn()
                && IpFilter.mayTransmitIp(config.apiBaseUrl())
                && !config.behindProxy();
    }

    private void logUnauthorized() {
        long now = System.currentTimeMillis();
        if (now - lastUnauthorizedLogMillis < UNAUTHORIZED_LOG_INTERVAL_MILLIS) {
            return;
        }
        lastUnauthorizedLogMillis = now;
        logger.severe("The Detraly API rejected this server's token. "
                + "Re-link with /detraly link <code> using a new code from the dashboard.");
    }

    private boolean backoff() {
        long wait = backoffMillis;
        backoffMillis = Math.min(backoffMillis * 2L, BACKOFF_MAX_MILLIS);
        synchronized (lock) {
            if (draining || !running) {
                return running;
            }
            sleeping = true;
        }
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {

        }
        synchronized (lock) {
            sleeping = false;
            if (running) {
                Thread.interrupted();
            }
            return running;
        }
    }
}

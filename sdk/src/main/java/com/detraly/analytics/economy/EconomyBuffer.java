package com.detraly.analytics.economy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Economy events waiting to be sent. Safe to use from the main thread (adds) and the Pulse thread (takes). When it
 * is full the oldest sweep sample goes first, then the oldest other event. Live events are taken before sweep
 * samples.
 */
public final class EconomyBuffer {
    public static final int CAPACITY = 20_000;

    /** The API accepts timestamps from a limited window; older events are not worth sending. */
    static final long MAX_AGE_SECONDS = 23L * 60L * 60L;

    private final int capacity;
    private final ArrayDeque<EconomyEvent> live = new ArrayDeque<>();
    private final ArrayDeque<EconomyEvent> sweep = new ArrayDeque<>();
    private long dropped;

    public EconomyBuffer() {
        this(CAPACITY);
    }

    public EconomyBuffer(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public synchronized void add(EconomyEvent event) {
        (event.isSweep() ? sweep : live).addLast(event);
        trim();
    }

    /** Takes up to {@code max} events, live ones first, and skips events too old to be accepted. */
    public synchronized List<EconomyEvent> take(int max, long nowSeconds) {
        List<EconomyEvent> batch = new ArrayList<>();
        long oldest = nowSeconds - MAX_AGE_SECONDS;
        while (batch.size() < max) {
            EconomyEvent event = live.pollFirst();
            if (event == null) {
                event = sweep.pollFirst();
            }
            if (event == null) {
                break;
            }
            if (event.ts() >= oldest) {
                batch.add(event);
            }
        }
        return batch;
    }

    /** Returns an unacknowledged batch to the front of the queue, in its original order. */
    public synchronized void putBack(List<EconomyEvent> batch) {
        for (int i = batch.size() - 1; i >= 0; i--) {
            EconomyEvent event = batch.get(i);
            (event.isSweep() ? sweep : live).addFirst(event);
        }
        trim();
    }

    public synchronized void clear() {
        live.clear();
        sweep.clear();
    }

    public synchronized int size() {
        return live.size() + sweep.size();
    }

    public synchronized boolean isEmpty() {
        return live.isEmpty() && sweep.isEmpty();
    }

    /** True when the buffer is more than half full: the sweep waits. */
    public synchronized boolean backpressure() {
        return live.size() + sweep.size() > capacity / 2;
    }

    public synchronized long takeDropped() {
        long n = dropped;
        dropped = 0L;
        return n;
    }

    private void trim() {
        while (live.size() + sweep.size() > capacity) {
            if (!sweep.isEmpty()) {
                sweep.pollFirst();
            } else {
                live.pollFirst();
            }
            dropped++;
        }
    }
}

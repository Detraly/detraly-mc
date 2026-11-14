package com.detraly.analytics.pulse;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ConsoleBuffer {
    static final int CAPACITY = 5000;

    static final long MAX_BYTES = 4L * 1024L * 1024L;

    private static final long MAX_AGE_SECONDS = 23L * 60L * 60L;

    public record Line(String raw, long timestampSeconds) {
        long weight() {
            return 2L * raw.length() + 48L;
        }
    }

    private final ArrayDeque<Line> queue = new ArrayDeque<>();
    private long bytes;
    private long dropped;

    public synchronized void add(Line line) {
        queue.addLast(line);
        bytes += line.weight();
        trim();
    }

    public synchronized List<Line> take(int max) {
        List<Line> batch = new ArrayList<>();
        Set<Line> seen = new HashSet<>();
        long oldest = System.currentTimeMillis() / 1000L - MAX_AGE_SECONDS;
        while (batch.size() < max && !queue.isEmpty()) {
            Line line = queue.pollFirst();
            bytes -= line.weight();
            if (line.timestampSeconds() < oldest || !seen.add(line)) {
                continue;
            }
            batch.add(line);
        }
        return batch;
    }

    public synchronized void putBack(List<Line> batch) {
        for (int i = batch.size() - 1; i >= 0; i--) {
            Line line = batch.get(i);
            if (queue.size() >= CAPACITY || bytes + line.weight() > MAX_BYTES) {
                dropped++;
                continue;
            }
            queue.addFirst(line);
            bytes += line.weight();
        }
    }

    public synchronized void clear() {
        queue.clear();
        bytes = 0L;
    }

    public synchronized boolean isEmpty() {
        return queue.isEmpty();
    }

    public synchronized long takeDropped() {
        long n = dropped;
        dropped = 0;
        return n;
    }

    private void trim() {
        while (!queue.isEmpty() && (queue.size() > CAPACITY || bytes > MAX_BYTES)) {
            bytes -= queue.pollFirst().weight();
            dropped++;
        }
    }
}

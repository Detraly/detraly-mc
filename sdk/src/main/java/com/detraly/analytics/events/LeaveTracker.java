package com.detraly.analytics.events;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LeaveTracker {
    static final int MAX_ENTRIES = 2048;

    private final Set<UUID> leftSinceJoin = ConcurrentHashMap.newKeySet();

    public void markJoin(UUID uuid) {
        if (uuid != null) {
            leftSinceJoin.remove(uuid);
        }
    }

    public void markLeave(UUID uuid) {
        if (uuid == null) {
            return;
        }
        if (leftSinceJoin.size() >= MAX_ENTRIES) {
            leftSinceJoin.clear();
        }
        leftSinceJoin.add(uuid);
    }

    public boolean leaveAlreadySubmitted(UUID uuid) {
        return uuid != null && leftSinceJoin.contains(uuid);
    }
}

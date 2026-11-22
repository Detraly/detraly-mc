package com.detraly.analytics.api;

public final class GeolocationState {
    private static final long STALE_AFTER_MILLIS = 5L * 60L * 1000L;

    private volatile boolean on;
    private volatile long lastAcceptedAtMillis;

    public void onAccepted(boolean headerOn) {
        this.on = headerOn;
        this.lastAcceptedAtMillis = System.currentTimeMillis();
    }

    public boolean isOn() {
        return on && System.currentTimeMillis() - lastAcceptedAtMillis <= STALE_AFTER_MILLIS;
    }

    public void reset() {
        this.on = false;
        this.lastAcceptedAtMillis = 0L;
    }
}

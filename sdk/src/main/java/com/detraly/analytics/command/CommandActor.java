package com.detraly.analytics.command;

import java.util.UUID;

public interface CommandActor {
    boolean isConsole();

    UUID playerId();

    boolean isAdmin();

    void send(Segment... parts);
}

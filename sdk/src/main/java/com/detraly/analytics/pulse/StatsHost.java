package com.detraly.analytics.pulse;

import java.util.function.DoubleConsumer;

public interface StatsHost {
    record WorldCounts(int worlds, long entities, long chunks) {
    }

    String pluginVersion();

    String minecraftVersion();

    String softwareName();

    int playersOnline();

    int maxPlayers();

    double averageTickMillis();

    int averagePingMillis();

    boolean cheapWorldCounts();

    WorldCounts worldCounts();

    Runnable listenTickEnd(DoubleConsumer tickMillis);
}

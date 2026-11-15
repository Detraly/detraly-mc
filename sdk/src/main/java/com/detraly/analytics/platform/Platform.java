package com.detraly.analytics.platform;

import com.detraly.analytics.config.ConfigSource;
import com.detraly.analytics.economy.EconomyHost;
import com.detraly.analytics.events.RosterStore;
import com.detraly.analytics.pulse.Profiler;
import com.detraly.analytics.pulse.StatsHost;
import java.io.File;
import java.util.List;
import java.util.logging.Logger;

public interface Platform {
    Logger logger();

    File dataFolder();

    ConfigSource config();

    Scheduler scheduler();

    List<RosterStore.Entry> onlinePlayers();

    boolean serverIsStopping();

    StatsHost statsHost();

    Profiler createProfiler();

    /** The economy of this server, or null when the platform has none (the default). */
    default EconomyHost economyHost() {
        return null;
    }

    /**
     * Why this platform can never have an economy, shown by {@code /detraly status}, or null when it simply has no
     * economy plugin installed (the default).
     */
    default String economyUnsupportedReason() {
        return null;
    }
}

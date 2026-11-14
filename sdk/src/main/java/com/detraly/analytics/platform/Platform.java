package com.detraly.analytics.platform;

import com.detraly.analytics.config.ConfigSource;
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
}

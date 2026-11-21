package com.detraly.analytics.neoforge;

import com.detraly.analytics.config.ConfigSource;
import com.detraly.analytics.events.RosterStore;
import com.detraly.analytics.platform.Platform;
import com.detraly.analytics.platform.Scheduler;
import com.detraly.analytics.pulse.Profiler;
import com.detraly.analytics.pulse.StatsHost;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

final class NeoForgePlatform implements Platform {
    private final MinecraftServer server;
    private final NeoForgeScheduler scheduler;
    private final Logger logger;
    private final File dataFolder;
    private final ConfigSource config;
    private final String version;

    NeoForgePlatform(MinecraftServer server, NeoForgeScheduler scheduler, Logger logger, File dataFolder,
            ConfigSource config, String version) {
        this.server = server;
        this.scheduler = scheduler;
        this.logger = logger;
        this.dataFolder = dataFolder;
        this.config = config;
        this.version = version;
    }

    @Override
    public Logger logger() {
        return logger;
    }

    @Override
    public File dataFolder() {
        return dataFolder;
    }

    @Override
    public ConfigSource config() {
        return config;
    }

    @Override
    public Scheduler scheduler() {
        return scheduler;
    }

    @Override
    public List<RosterStore.Entry> onlinePlayers() {
        List<RosterStore.Entry> out = new ArrayList<>();
        for (ServerPlayer p : new ArrayList<>(server.getPlayerList().getPlayers())) {
            out.add(new RosterStore.Entry(p.getUUID(), p.getGameProfile().name()));
        }
        return out;
    }

    @Override
    public boolean serverIsStopping() {
        return true;
    }

    @Override
    public StatsHost statsHost() {
        return new NeoForgeStatsHost(server, scheduler, version);
    }

    @Override
    public String economyUnsupportedReason() {
        return "not supported on NeoForge yet. We only support Vault API at the moment.";
    }

    @Override
    public Profiler createProfiler() {
        return null;
    }
}

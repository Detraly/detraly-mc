package com.detraly.analytics.fabric;

import com.detraly.analytics.pulse.StatsHost;
import java.util.function.DoubleConsumer;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

final class FabricStatsHost implements StatsHost {
    private final MinecraftServer server;
    private final FabricScheduler scheduler;
    private final String version;

    FabricStatsHost(MinecraftServer server, FabricScheduler scheduler, String version) {
        this.server = server;
        this.scheduler = scheduler;
        this.version = version;
    }

    @Override
    public String pluginVersion() {
        return version;
    }

    @Override
    public String minecraftVersion() {
        return SharedConstants.getCurrentVersion().getName();
    }

    @Override
    public String softwareName() {
        return "Fabric";
    }

    @Override
    public int playersOnline() {
        return server.getPlayerCount();
    }

    @Override
    public int maxPlayers() {
        return server.getMaxPlayers();
    }

    @Override
    public double averageTickMillis() {
        return scheduler.averageTickMillis();
    }

    @Override
    public int averagePingMillis() {
        long total = 0L;
        int count = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            total += Math.max(0, player.connection.latency());
            count++;
        }
        return count == 0 ? -1 : (int) Math.min(100_000L, total / count);
    }

    @Override
    public boolean cheapWorldCounts() {
        return false;
    }

    @Override
    public WorldCounts worldCounts() {
        int worlds = 0;
        long entities = 0L;
        long chunks = 0L;
        for (ServerLevel level : server.getAllLevels()) {
            worlds++;
            chunks += level.getChunkSource().getLoadedChunksCount();
            for (Entity ignored : level.getAllEntities()) {
                entities++;
            }
        }
        return new WorldCounts(worlds, entities, chunks);
    }

    @Override
    public Runnable listenTickEnd(DoubleConsumer tickMillis) {
        return scheduler.listenTick(tickMillis);
    }
}

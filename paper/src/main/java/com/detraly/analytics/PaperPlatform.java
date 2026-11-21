package com.detraly.analytics;

import com.detraly.analytics.config.ConfigSource;
import com.detraly.analytics.events.RosterStore;
import com.detraly.analytics.platform.Platform;
import com.detraly.analytics.platform.Scheduler;
import com.detraly.analytics.pulse.PaperStatsHost;
import com.detraly.analytics.pulse.Profiler;
import com.detraly.analytics.pulse.StatsHost;
import com.detraly.analytics.pulse.TickProfiler;
import com.detraly.analytics.vault.VaultEconomyHost;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

final class PaperPlatform implements Platform, Scheduler {
    private final JavaPlugin plugin;
    private Method isStoppingMethod;
    private boolean stoppingLookedUp;

    private final VaultEconomyHost economyHost;
    private final FoliaScheduler folia;

    PaperPlatform(JavaPlugin plugin) {
        this.plugin = plugin;
        this.folia = FoliaScheduler.isFolia() ? FoliaScheduler.create(plugin) : null;
        if (FoliaScheduler.isFolia() && folia == null) {
            // Never fall back to the Bukkit scheduler on Folia: it throws there.
            throw new IllegalStateException("Folia was detected but its schedulers could not be used");
        }
        // Vault economies are not region-safe, so Folia has none.
        this.economyHost = folia == null ? new VaultEconomyHost(plugin) : null;
    }

    @Override
    public Logger logger() {
        return plugin.getLogger();
    }

    @Override
    public File dataFolder() {
        return plugin.getDataFolder();
    }

    @Override
    public ConfigSource config() {
        return new ConfigSource() {
            @Override
            public String getString(String key, String fallback) {
                return plugin.getConfig().getString(key, fallback);
            }

            @Override
            public boolean getBoolean(String key, boolean fallback) {
                return plugin.getConfig().getBoolean(key, fallback);
            }

            @Override
            public int getInt(String key, int fallback) {
                return plugin.getConfig().getInt(key, fallback);
            }

            @Override
            public List<String> getStringList(String key) {
                return plugin.getConfig().isList(key) ? plugin.getConfig().getStringList(key) : null;
            }
        };
    }

    @Override
    public Scheduler scheduler() {
        return folia != null ? folia : this;
    }

    @Override
    public String economyUnsupportedReason() {
        return folia != null ? "not supported on Folia yet. Vault economies are not safe across Folia regions." : null;
    }

    @Override
    public List<RosterStore.Entry> onlinePlayers() {
        List<RosterStore.Entry> out = new ArrayList<>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            out.add(new RosterStore.Entry(p.getUniqueId(), p.getName()));
        }
        return out;
    }

    @Override
    public boolean serverIsStopping() {
        try {
            if (!stoppingLookedUp) {
                stoppingLookedUp = true;
                isStoppingMethod = plugin.getServer().getClass().getMethod("isStopping");
            }
            Method m = isStoppingMethod;
            return m == null || !Boolean.FALSE.equals(m.invoke(plugin.getServer()));
        } catch (Throwable e) {
            return true;
        }
    }

    @Override
    public StatsHost statsHost() {
        return new PaperStatsHost(plugin, folia != null);
    }

    @Override
    public Profiler createProfiler() {
        // Folia has no single main thread to sample and no tick events: no profiler, as with `profiler: false`.
        return folia != null ? null : new TickProfiler(plugin, scheduler(), false);
    }

    @Override
    public VaultEconomyHost economyHost() {
        return economyHost;
    }

    @Override
    public Task timer(Runnable task, long delayTicks, long periodTicks) {
        org.bukkit.scheduler.BukkitTask handle = plugin.getServer().getScheduler().runTaskTimer(plugin, task,
                delayTicks, periodTicks);
        return handle::cancel;
    }

    @Override
    public void later(Runnable task, long delayTicks) {
        plugin.getServer().getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    @Override
    public void async(Runnable task) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override
    public void sync(Runnable task) {
        plugin.getServer().getScheduler().runTask(plugin, task);
    }
}

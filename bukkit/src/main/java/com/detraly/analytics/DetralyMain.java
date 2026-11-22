package com.detraly.analytics;

import com.detraly.analytics.command.DetralyCommand;
import com.detraly.analytics.events.JoinLeaveListener;
import com.detraly.analytics.economy.EconomyTracker;
import com.detraly.analytics.safety.Guard;
import com.detraly.analytics.vault.EconomyListener;
import com.detraly.analytics.vault.VaultEconomyHost;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class DetralyMain extends JavaPlugin {
    private DetralyCore core;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            PaperPlatform platform = new PaperPlatform(this);
            core = new DetralyCore(platform);

            DetralyCommand executor = new DetralyCommand(core.commands());
            PluginCommand command = getCommand("detraly");
            if (command != null) {
                command.setExecutor(executor);
                command.setTabCompleter(executor);
            }
            getServer().getPluginManager().registerEvents(new JoinLeaveListener(core.players()), this);

            EconomyTracker economy = core.economy();
            if (economy != null) {
                // Vault and the economy plugin load after Detraly (it loads at STARTUP): look the provider up
                // when the server has finished loading, and again right after the first tick for a plugin that
                // was enabled late.
                VaultEconomyHost host = platform.economyHost();
                getServer().getPluginManager().registerEvents(new EconomyListener(getLogger(), host, economy), this);
                getServer().getScheduler().runTask(this, Guard.wrap(getLogger(), "the economy lookup", host::refresh));
            }

            core.start();
        } catch (Throwable e) {
            Guard.report(getLogger(), "start-up", e);
        }
    }

    @Override
    public void onDisable() {
        DetralyCore c = core;
        core = null;
        if (c != null) {
            Guard.run(getLogger(), "shut-down", c::stop);
        }
    }
}

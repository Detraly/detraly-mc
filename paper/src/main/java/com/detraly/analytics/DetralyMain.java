package com.detraly.analytics;

import com.detraly.analytics.command.DetralyCommand;
import com.detraly.analytics.events.JoinLeaveListener;
import com.detraly.analytics.safety.Guard;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class DetralyMain extends JavaPlugin {
    private DetralyCore core;

    @Override
    public void onEnable() {
        try {
            saveDefaultConfig();
            core = new DetralyCore(new PaperPlatform(this));

            DetralyCommand executor = new DetralyCommand(core.commands());
            PluginCommand command = getCommand("detraly");
            if (command != null) {
                command.setExecutor(executor);
                command.setTabCompleter(executor);
            }
            getServer().getPluginManager().registerEvents(new JoinLeaveListener(core.players()), this);

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

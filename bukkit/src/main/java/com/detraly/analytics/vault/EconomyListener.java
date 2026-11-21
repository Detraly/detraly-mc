package com.detraly.analytics.vault;

import com.detraly.analytics.economy.EconomyTracker;
import com.detraly.analytics.safety.Guard;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.server.ServiceEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;

/**
 * Feeds the economy tracker. Every handler is guarded: analytics never break a join, a command or the server. The
 * text of a command goes straight to the matcher and is not kept.
 */
public final class EconomyListener implements Listener {
    private static final String ECONOMY_SERVICE = "net.milkbowl.vault.economy.Economy";

    private final Logger logger;
    private final VaultEconomyHost host;
    private final EconomyTracker tracker;

    public EconomyListener(Logger logger, VaultEconomyHost host, EconomyTracker tracker) {
        this.logger = logger;
        this.host = host;
        this.tracker = tracker;
    }

    @EventHandler
    public void onServerLoad(ServerLoadEvent e) {
        Guard.run(logger, "the economy lookup", host::refresh);
    }

    @EventHandler
    public void onServiceRegister(ServiceRegisterEvent e) {
        refreshIfEconomy(e);
    }

    @EventHandler
    public void onServiceUnregister(ServiceUnregisterEvent e) {
        refreshIfEconomy(e);
    }

    private void refreshIfEconomy(ServiceEvent e) {
        Guard.run(logger, "the economy lookup", () -> {
            if (ECONOMY_SERVICE.equals(e.getProvider().getService().getName())) {
                host.refresh();
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Guard.run(logger, "the economy join sample", () -> {
            Player p = e.getPlayer();
            tracker.onJoin(p.getUniqueId(), p.getName());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        Guard.run(logger, "the economy quit sample", () -> {
            Player p = e.getPlayer();
            tracker.onQuit(p.getUniqueId(), p.getName());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        Guard.run(logger, "the economy command check", () -> {
            Player p = e.getPlayer();
            tracker.onPlayerCommand(p.getUniqueId(), p.getName(), e.getMessage());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent e) {
        Guard.run(logger, "the economy command check", () -> tracker.onConsoleCommand(e.getCommand()));
    }
}

package com.detraly.analytics.events;

import com.detraly.analytics.platform.PlayerRef;
import com.detraly.analytics.privacy.PrivacyNotice;
import java.net.InetSocketAddress;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class JoinLeaveListener implements Listener {
    private final PlayerTracker tracker;

    public JoinLeaveListener(PlayerTracker tracker) {
        this.tracker = tracker;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent e) {
        tracker.onJoin(new BukkitPlayer(e.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent e) {
        tracker.onQuit(new BukkitPlayer(e.getPlayer()));
    }

    private static final class BukkitPlayer implements PlayerRef {
        private final Player player;

        BukkitPlayer(Player player) {
            this.player = player;
        }

        @Override
        public String name() {
            return player.getName();
        }

        @Override
        public UUID uuid() {
            return player.getUniqueId();
        }

        @Override
        public InetSocketAddress address() {
            return player.getAddress();
        }

        @Override
        public boolean isOnline() {
            return player.isOnline();
        }

        @Override
        public void sendPrivacyNotice() {
            PrivacyNotice.send(player);
        }
    }
}

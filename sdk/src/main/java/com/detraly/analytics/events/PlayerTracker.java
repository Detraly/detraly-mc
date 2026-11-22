package com.detraly.analytics.events;

import com.detraly.analytics.api.GeolocationState;
import com.detraly.analytics.api.IpFilter;
import com.detraly.analytics.config.DetralyConfig;
import com.detraly.analytics.model.JoinLeaveEvent;
import com.detraly.analytics.platform.PlayerRef;
import com.detraly.analytics.platform.Scheduler;
import com.detraly.analytics.privacy.OptOutStore;
import com.detraly.analytics.privacy.PrivacyService;
import com.detraly.analytics.safety.Guard;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public final class PlayerTracker {
    static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final long NOTICE_DELAY_TICKS = 20L;

    private final Logger logger;
    private final Scheduler scheduler;
    private final EventDispatcher dispatcher;
    private final GeolocationState geoState;
    private final OptOutStore optOutStore;
    private final PrivacyService privacy;
    private final DetralyConfig config;
    private final LeaveTracker tracker;

    public PlayerTracker(Logger logger, Scheduler scheduler, EventDispatcher dispatcher, GeolocationState geoState,
            OptOutStore optOutStore, PrivacyService privacy, DetralyConfig config, LeaveTracker tracker) {
        this.logger = logger;
        this.scheduler = scheduler;
        this.dispatcher = dispatcher;
        this.geoState = geoState;
        this.optOutStore = optOutStore;
        this.privacy = privacy;
        this.config = config;
        this.tracker = tracker;
    }

    public void onJoin(PlayerRef player) {
        Guard.run(logger, "the join listener", () -> handleJoin(player));
    }

    public void onQuit(PlayerRef player) {
        Guard.run(logger, "the quit listener", () -> handleQuit(player));
    }

    private void handleJoin(PlayerRef player) {
        UUID optedOutId = player.uuid();
        if (optOutStore.isOptedOut(optedOutId)) {
            privacy.syncOnJoin(optedOutId);
            return;
        }
        String name = player.name();
        if (name == null || !USERNAME.matcher(name).matches()) {
            return;
        }
        UUID uuid = player.uuid();
        String ip = resolveIp(player, uuid);

        dispatcher.submit(new JoinLeaveEvent(JoinLeaveEvent.Type.JOIN, name, uuid,
                System.currentTimeMillis() / 1000L, ip));
        tracker.markJoin(uuid);

        if (ip != null) {
            scheduler.later(Guard.wrap(logger, "the privacy notice", () -> {
                if (player.isOnline()) {
                    player.sendPrivacyNotice();
                }
            }), NOTICE_DELAY_TICKS);
        }
    }

    private void handleQuit(PlayerRef player) {
        if (optOutStore.isOptedOut(player.uuid())) {
            return;
        }
        String name = player.name();
        if (name == null || !USERNAME.matcher(name).matches()) {
            return;
        }
        dispatcher.submit(new JoinLeaveEvent(JoinLeaveEvent.Type.LEAVE, name, player.uuid(),
                System.currentTimeMillis() / 1000L, null));
        tracker.markLeave(player.uuid());
    }

    private String resolveIp(PlayerRef player, UUID uuid) {
        if (!geoState.isOn()
                || config.behindProxy()
                || !IpFilter.mayTransmitIp(config.apiBaseUrl())) {
            return null;
        }
        String address = IpFilter.hostAddressOf(player.address());
        if (address == null || !IpFilter.isPublicAddress(address)) {
            return null;
        }
        return address;
    }
}

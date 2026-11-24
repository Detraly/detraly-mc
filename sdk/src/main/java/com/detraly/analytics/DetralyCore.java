package com.detraly.analytics;

import com.detraly.analytics.api.ApiUrlPolicy;
import com.detraly.analytics.api.DetralyApiClient;
import com.detraly.analytics.api.GeolocationState;
import com.detraly.analytics.command.CommandCore;
import com.detraly.analytics.config.DetralyConfig;
import com.detraly.analytics.economy.EconomyBuffer;
import com.detraly.analytics.economy.EconomyConfig;
import com.detraly.analytics.economy.EconomyHost;
import com.detraly.analytics.economy.EconomyTracker;
import com.detraly.analytics.economy.SweepState;
import com.detraly.analytics.events.EventDispatcher;
import com.detraly.analytics.events.LeaveTracker;
import com.detraly.analytics.events.Outbox;
import com.detraly.analytics.events.PlayerTracker;
import com.detraly.analytics.events.RecoveryCoordinator;
import com.detraly.analytics.events.RosterStore;
import com.detraly.analytics.link.LinkService;
import com.detraly.analytics.platform.Platform;
import com.detraly.analytics.platform.Scheduler;
import com.detraly.analytics.privacy.OptOutStore;
import com.detraly.analytics.privacy.PrivacyService;
import com.detraly.analytics.pulse.ConsoleBuffer;
import com.detraly.analytics.pulse.ConsoleCapture;
import com.detraly.analytics.pulse.Profiler;
import com.detraly.analytics.pulse.PulseClient;
import com.detraly.analytics.pulse.ServerStats;
import com.detraly.analytics.safety.Guard;
import com.detraly.analytics.storage.TokenFingerprint;
import com.detraly.analytics.storage.TokenStore;
import java.util.List;
import java.util.logging.Logger;

public final class DetralyCore {
    private static final long ROSTER_INTERVAL_TICKS = 600L;
    private static final long SHUTDOWN_BUDGET_MILLIS = 3000L;

    private final Platform platform;
    private final Logger logger;
    private final DetralyConfig config;
    private final TokenStore tokenStore;
    private final LinkService linkService;
    private final EventDispatcher dispatcher;
    private final CommandCore commands;
    private final LeaveTracker leaveTracker = new LeaveTracker();
    private final PlayerTracker players;

    private RecoveryCoordinator coordinator;
    private Scheduler.Task rosterTask;
    private PulseClient pulse;
    private ServerStats stats;
    private Profiler profiler;
    private ConsoleCapture consoleCapture;
    private EconomyTracker economy;
    private Scheduler.Task economyTask;

    public DetralyCore(Platform platform) {
        this.platform = platform;
        this.logger = platform.logger();
        this.config = DetralyConfig.load(platform.config());
        if (config.apiUrlProblem() != null) {
            logger.severe("Detraly will not connect: " + config.apiUrlProblem() + " " + ApiUrlPolicy.FIX_HINT);
        }

        GeolocationState geoState = new GeolocationState();
        this.tokenStore = new TokenStore(platform.dataFolder(), logger);
        OptOutStore optOutStore = new OptOutStore(platform.dataFolder(), logger);
        optOutStore.load();

        DetralyApiClient client = new DetralyApiClient(config, geoState);
        this.linkService = new LinkService(logger, platform.scheduler(), client, tokenStore, geoState);
        this.dispatcher = new EventDispatcher(logger, client, tokenStore, geoState, config);
        linkService.onLinked(dispatcher::clearQueue);
        PrivacyService privacy = new PrivacyService(logger, platform.scheduler(), client, tokenStore, optOutStore);
        dispatcher.setSuppressed(optOutStore::isOptedOut);

        this.commands = new CommandCore(logger, linkService, tokenStore, geoState, privacy);

        EconomyConfig economyConfig = EconomyConfig.load(platform.config());
        EconomyHost economyHost = platform.economyHost();
        if (economyHost != null && economyConfig.enabled()) {
            this.economy = new EconomyTracker(logger, platform.scheduler(), economyHost, economyConfig,
                    new EconomyBuffer(), platform::onlinePlayers, optOutStore::isOptedOut,
                    new SweepState(platform.dataFolder()),
                    () -> TokenFingerprint.of(tokenStore.get().orElse(null)));
            commands.setEconomyStatus(economy::status);
        } else if (economyHost == null && platform.economyUnsupportedReason() != null) {
            String reason = platform.economyUnsupportedReason();
            commands.setEconomyStatus(() -> reason);
        }
        this.players = new PlayerTracker(logger, platform.scheduler(), dispatcher, geoState, optOutStore, privacy, config,
                leaveTracker);
    }

    public CommandCore commands() {
        return commands;
    }

    public PlayerTracker players() {
        return players;
    }

    /** The economy tracker, or null when this platform has no economy or it is turned off in the config. */
    public EconomyTracker economy() {
        return economy;
    }

    public void start() {
        try {
            begin();
        } catch (Throwable e) {
            Guard.report(logger, "start-up", e);
            Guard.run(logger, "clean-up", this::abandon);
        }
    }

    private void begin() {
        try {
            coordinator = new RecoveryCoordinator(logger, dispatcher, new Outbox(platform.dataFolder(), logger),
                    new RosterStore(platform.dataFolder(), logger), leaveTracker, tokenStore);
            coordinator.onStart(platform.onlinePlayers(), System.currentTimeMillis() / 1000L);
        } catch (Throwable e) {
            logger.warning("Could not start event recovery; the rest of the plugin works. "
                    + e.getClass().getSimpleName());
        }

        dispatcher.start();

        Scheduler scheduler = platform.scheduler();
        rosterTask = scheduler.timer(Guard.wrap(logger, "the roster task", () -> {
            List<RosterStore.Entry> online = platform.onlinePlayers();
            RecoveryCoordinator c = coordinator;
            if (c == null) {
                return;
            }
            long now = System.currentTimeMillis() / 1000L;
            scheduler.async(Guard.wrap(logger, "the roster write", () -> c.writeRoster(online, now)));
        }), ROSTER_INTERVAL_TICKS, ROSTER_INTERVAL_TICKS);

        if (config.liveConnection()) {
            try {
                startPulse();
            } catch (Throwable e) {
                logger.warning("Could not start the live connection to Detraly; the rest of the plugin works. "
                        + e.getClass().getSimpleName());
                stopPulse();
            }
        }

        if (!tokenStore.hasToken()) {
            logger.warning("This server is not linked to Detraly yet. In the console run: detraly link <code>");
        }
    }

    private void startPulse() {
        stats = new ServerStats(logger, platform.scheduler(), platform.statsHost());
        stats.start();

        ConsoleBuffer buffer = new ConsoleBuffer();
        if (config.profiler()) {
            profiler = platform.createProfiler();
            if (profiler != null) {
                profiler.attach();
            }
        }
        PulseClient client = new PulseClient(logger, config, tokenStore, stats, buffer, profiler, economy);
        pulse = client;
        if (economy != null) {
            EconomyTracker tracker = economy;
            economyTask = platform.scheduler().timer(Guard.wrap(logger, "the economy tracker", tracker::tick), 1L,
                    1L);
        }
        consoleCapture = new ConsoleCapture(buffer, client::wantsConsole);
        if (!consoleCapture.start()) {
            logger.warning("Could not read the server console; console lines will not be sent to Detraly.");
            consoleCapture = null;
        }
        linkService.onLinked(client::tokenChanged);
        commands.setLiveStatus(() -> client.status().text());
        client.start();
        logger.info("Live connection to Detraly is on: server stats and console lines (IP addresses, "
                + "player commands and chat are removed) are sent while the server is linked. "
                + "Turn it off with live-connection: false in the config.");
    }

    private void abandon() {
        Guard.run(logger, "clean-up", this::cancelRoster);
        Guard.run(logger, "clean-up", this::stopPulse);
        Guard.run(logger, "clean-up", () -> {
            dispatcher.stop();
        });
    }

    private void cancelRoster() {
        if (rosterTask != null) {
            rosterTask.cancel();
            rosterTask = null;
        }
    }

    public void stop() {
        Guard.run(logger, "shut-down", this::cancelRoster);
        Guard.run(logger, "shut-down", this::stopPulse);
        RecoveryCoordinator c = coordinator;
        coordinator = null;
        if (c != null) {
            boolean done = Guard.run(logger, "shut-down", () -> c.onStop(platform.onlinePlayers(),
                    System.currentTimeMillis() / 1000L, SHUTDOWN_BUDGET_MILLIS, platform.serverIsStopping()));
            if (!done) {
                Guard.run(logger, "shut-down", dispatcher::stop);
            }
        } else {
            Guard.run(logger, "shut-down", dispatcher::stop);
        }
    }

    private void stopPulse() {
        if (economyTask != null) {
            economyTask.cancel();
            economyTask = null;
        }
        if (consoleCapture != null) {
            consoleCapture.stop();
            consoleCapture = null;
        }
        if (pulse != null) {
            pulse.stop();
            pulse = null;
        }
        if (profiler != null) {
            profiler.detach();
            profiler = null;
        }
        if (stats != null) {
            stats.stop();
            stats = null;
        }
    }
}

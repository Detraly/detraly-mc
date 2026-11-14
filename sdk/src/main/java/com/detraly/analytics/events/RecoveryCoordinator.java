package com.detraly.analytics.events;

import com.detraly.analytics.model.JoinLeaveEvent;
import com.detraly.analytics.storage.TokenFingerprint;
import com.detraly.analytics.storage.TokenStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

public final class RecoveryCoordinator {
    private final Logger logger;
    private final EventDispatcher dispatcher;
    private final Outbox outbox;
    private final RosterStore roster;
    private final LeaveTracker tracker;
    private static final long REJOIN_FUTURE_SECONDS = 60L;

    private static final long OPEN_SESSION_SECONDS = 12L * 3600L;

    private final TokenStore tokenStore;

    private volatile boolean started;

    public RecoveryCoordinator(Logger logger, EventDispatcher dispatcher, Outbox outbox, RosterStore roster,
            LeaveTracker tracker, TokenStore tokenStore) {
        this.logger = logger;
        this.dispatcher = dispatcher;
        this.outbox = outbox;
        this.roster = roster;
        this.tracker = tracker;
        this.tokenStore = tokenStore;
    }

    public void onStart(List<RosterStore.Entry> onlineNow, long nowSeconds) {
        try {
            outbox.deleteLeftovers();
            roster.deleteLeftovers();
            String fp = fingerprint();
            List<JoinLeaveEvent> saved = outbox.load(nowSeconds, fp);
            if (!saved.isEmpty()) {
                dispatcher.submitFirst(saved);
                logger.info("Re-queued " + saved.size() + " event(s) saved by the previous run.");
            }
            Optional<RosterStore.Snapshot> snapshot = roster.read(fp);
            if (snapshot.isPresent() && !snapshot.get().clean()) {
                List<JoinLeaveEvent> leaves = RosterStore.crashLeaves(snapshot.get(), uuidsOf(onlineNow), nowSeconds);
                for (JoinLeaveEvent leave : leaves) {
                    dispatcher.submit(leave);
                }
                if (!leaves.isEmpty()) {
                    logger.info("The previous run did not stop cleanly; queued " + leaves.size()
                            + " estimated leave(s).");
                }
            }

            boolean afterLeaves = snapshot.isPresent()
                    && (snapshot.get().clean() || snapshot.get().leavesSent());
            Set<UUID> listed = new HashSet<>();
            if (snapshot.isPresent() && !afterLeaves
                    && snapshot.get().timestampSeconds() >= nowSeconds - OPEN_SESSION_SECONDS) {
                for (RosterStore.Entry e : snapshot.get().players()) {
                    listed.add(e.uuid());
                }
            }
            long joinStamp = nowSeconds;
            if (afterLeaves) {
                joinStamp = Math.min(Math.max(nowSeconds, snapshot.get().timestampSeconds() + 1L),
                        nowSeconds + REJOIN_FUTURE_SECONDS);
            }
            int rejoined = 0;
            for (RosterStore.Entry p : new ArrayList<>(onlineNow)) {
                if (!usable(p) || listed.contains(p.uuid())) {
                    continue;
                }
                dispatcher.submit(new JoinLeaveEvent(JoinLeaveEvent.Type.JOIN, p.name(), p.uuid(), joinStamp, null));
                tracker.markJoin(p.uuid());
                rejoined++;
            }
            if (rejoined > 0) {
                logger.info("Queued " + rejoined + " join(s) for players already online.");
            }
            roster.write(rosterPlayers(onlineNow), nowSeconds, fp);
            started = true;
        } catch (RuntimeException e) {
            logger.warning("Could not recover events from the previous run.");
        }
    }

    public void writeRoster(List<RosterStore.Entry> online, long nowSeconds) {
        if (!started) {
            return;
        }
        try {
            roster.write(rosterPlayers(online), nowSeconds, fingerprint());
        } catch (RuntimeException e) {
            logger.warning("Could not save the Detraly player roster.");
        }
    }

    public void onStop(List<RosterStore.Entry> onlineNow, long nowSeconds, long budgetMillis,
            boolean sendLeaves) {
        try {
            if (!started) {
                dispatcher.stop();
                logger.warning("Detraly recovery did not start; leaving the saved files as they were.");
                return;
            }
            int queued = 0;
            if (sendLeaves) {
                for (RosterStore.Entry p : onlineNow) {
                    if (!usable(p) || tracker.leaveAlreadySubmitted(p.uuid())) {
                        continue;
                    }
                    dispatcher.submit(new JoinLeaveEvent(JoinLeaveEvent.Type.LEAVE, p.name(), p.uuid(),
                            nowSeconds, null));
                    tracker.markLeave(p.uuid());
                    queued++;
                }
            }
            List<JoinLeaveEvent> undelivered = dispatcher.stopAndDrain(budgetMillis);
            long saveTime = System.currentTimeMillis() / 1000L;
            String fp = fingerprint();
            boolean saved = outbox.save(undelivered, saveTime, fp);
            if (saved && sendLeaves) {
                roster.markClean(saveTime, fp);
            } else if (saved) {

                roster.closeUnclean(rosterPlayers(onlineNow), nowSeconds, fp, sendLeaves);
            } else {

                roster.closeUnclean(rosterPlayers(onlineNow), nowSeconds, fp, sendLeaves);
                logger.warning("Could not save undelivered events; estimated leaves will be sent at next start.");
            }
            logger.info("Shutdown: queued " + queued + " leave(s), " + undelivered.size()
                    + " event(s) saved for next start.");
        } catch (RuntimeException e) {
            logger.warning("Could not finish saving Detraly events at shutdown.");
        }
    }

    private String fingerprint() {
        return tokenStore.get().map(TokenFingerprint::of).orElse(null);
    }

    private List<RosterStore.Entry> rosterPlayers(List<RosterStore.Entry> online) {
        return tokenStore.hasToken() ? online : List.of();
    }

    private static boolean usable(RosterStore.Entry p) {
        return p != null && p.uuid() != null && p.name() != null
                && PlayerTracker.USERNAME.matcher(p.name()).matches();
    }

    private static Set<UUID> uuidsOf(List<RosterStore.Entry> entries) {
        Set<UUID> out = new HashSet<>();
        for (RosterStore.Entry e : new ArrayList<>(entries)) {
            if (e != null && e.uuid() != null) {
                out.add(e.uuid());
            }
        }
        return out;
    }
}

package com.detraly.analytics.privacy;

import com.detraly.analytics.api.PrivacyApi;
import com.detraly.analytics.api.PrivacyApi.Result;
import com.detraly.analytics.command.CommandActor;
import com.detraly.analytics.command.Segment;
import com.detraly.analytics.command.Tone;
import com.detraly.analytics.platform.Scheduler;
import com.detraly.analytics.safety.Guard;
import com.detraly.analytics.storage.TokenStore;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Keeps a player's global opt-out in step with the Detraly API. The local store is always written first, so this
 * server stops sending about the player at once; the API call runs off the server thread.
 *
 * <p>Commands for one player are serialised: each command takes a ticket, the API calls for a player run under that
 * player's lock, and a command or retry whose ticket is no longer the newest does nothing. An opt-in checks its
 * ticket again after the API call, so a command typed while it was in flight is never undone. So a late opt-out
 * retry can never land after an opt-in.
 */
public final class PrivacyService {
    /** Waits before retry 1, 2 and 3 of a failed opt-out sync: 30 seconds, 2 minutes, 10 minutes. */
    static final long[] RETRY_DELAY_TICKS = {600L, 2400L, 12000L};

    private final Logger logger;
    private final Scheduler scheduler;
    private final PrivacyApi api;
    private final TokenStore tokenStore;
    private final OptOutStore store;
    private final Set<UUID> joinSyncs = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<UUID, Slot> slots = new ConcurrentHashMap<>();

    /**
     * Per player: the newest command's ticket; the object itself is the lock for that player's API calls. A slot
     * stays in the map while {@code users} is above zero (a queued task or a scheduled retry holds one use) and is
     * removed when the last use is released, so the map does not grow with every player who ever ran a command.
     */
    private static final class Slot {
        final AtomicLong ticket = new AtomicLong();
        int users; // only changed inside ConcurrentHashMap.compute
    }

    public PrivacyService(Logger logger, Scheduler scheduler, PrivacyApi api, TokenStore tokenStore,
            OptOutStore store) {
        this.logger = logger;
        this.scheduler = scheduler;
        this.api = api;
        this.tokenStore = tokenStore;
        this.store = store;
    }

    private Slot acquire(UUID id) {
        Slot[] out = new Slot[1];
        slots.compute(id, (k, s) -> {
            Slot slot = s == null ? new Slot() : s;
            slot.users++;
            out[0] = slot;
            return slot;
        });
        return out[0];
    }

    private void release(UUID id) {
        slots.computeIfPresent(id, (k, s) -> --s.users <= 0 ? null : s);
    }

    int slotCount() {
        return slots.size();
    }

    public void optOut(CommandActor actor) {
        UUID id = actor.playerId();
        Slot slot = acquire(id); // this use is handed to syncOptOut
        // Bump the ticket before writing the store, so an opt-in in flight can see this command and restore it.
        long ticket = slot.ticket.incrementAndGet();
        store.optOut(id);
        syncOptOut(id, slot, ticket, 0, result -> reply(actor, optOutMessage(result)));
    }

    public void optIn(CommandActor actor) {
        UUID id = actor.playerId();
        Slot slot = acquire(id);
        long ticket = slot.ticket.incrementAndGet();
        try {
            scheduler.async(() -> {
                try {
                    Segment[] message;
                    synchronized (slot) {
                        if (slot.ticket.get() != ticket) {
                            return; // a newer command for this player replaces this one
                        }
                        Result result = send(id, false);
                        if (slot.ticket.get() != ticket) {
                            // A newer command arrived while the API call was in flight. Leave the local state and
                            // the player's chat alone; the newer command's own sync runs next and decides.
                            return;
                        }
                        if (result == Result.OK) {
                            store.optIn(id);
                            if (slot.ticket.get() != ticket) {
                                // An opt-out was typed while the local store was being changed: it wins.
                                store.optOut(id);
                                return;
                            }
                        }
                        message = optInMessage(result);
                    }
                    reply(actor, message);
                } finally {
                    release(id);
                }
            });
        } catch (Throwable e) {
            release(id);
            throw e;
        }
    }

    /** Called when a locally opted-out player joins: sends the opt-out again if it is still unconfirmed. */
    public void syncOnJoin(UUID id) {
        if (id == null || !store.isOptedOut(id) || !store.isPendingSync(id) || !joinSyncs.add(id)) {
            return;
        }
        Slot slot = acquire(id); // handed to syncOptOut
        syncOptOut(id, slot, slot.ticket.get(), RETRY_DELAY_TICKS.length, result -> joinSyncs.remove(id));
    }

    /** The caller has acquired one use of the slot; this call releases it when it is done, or hands it on. */
    private void syncOptOut(UUID id, Slot slot, long ticket, int attempt, Consumer<Result> first) {
        try {
            scheduler.async(() -> {
                try {
                    Result result;
                    synchronized (slot) {
                        if (slot.ticket.get() != ticket || !store.isOptedOut(id)) {
                            joinSyncs.remove(id); // superseded by a newer command, which answers the player
                            return;
                        }
                        result = send(id, true);
                        if (result == Result.OK) {
                            store.markSynced(id);
                        }
                    }
                    if (first != null) {
                        try {
                            first.accept(result);
                        } catch (Throwable e) {
                            Guard.report(logger, "the opt-out reply", e);
                        }
                    }
                    boolean retryable = result == Result.RETRY_LATER || result == Result.NETWORK_ERROR;
                    if (retryable && attempt < RETRY_DELAY_TICKS.length) {
                        scheduleRetry(id, slot, ticket, attempt);
                    }
                } finally {
                    release(id);
                }
            });
        } catch (Throwable e) {
            release(id);
            throw e;
        }
    }

    private void scheduleRetry(UUID id, Slot slot, long ticket, int attempt) {
        acquire(id); // held by the scheduled retry
        try {
            scheduler.later(Guard.wrap(logger, "the opt-out retry", () -> {
                if (slot.ticket.get() == ticket && store.isOptedOut(id) && store.isPendingSync(id)) {
                    syncOptOut(id, slot, ticket, attempt + 1, null); // takes over the use held here
                } else {
                    release(id);
                }
            }), RETRY_DELAY_TICKS[attempt]);
        } catch (Throwable e) {
            release(id);
            throw e;
        }
    }

    private Result send(UUID id, boolean optedOut) {
        try {
            Optional<String> token = tokenStore.get();
            if (token.isEmpty()) {
                return Result.NOT_LINKED;
            }
            return api.setOptedOut(token.get(), id, optedOut);
        } catch (Throwable e) {
            return Result.NETWORK_ERROR;
        }
    }

    private void reply(CommandActor actor, Segment[] message) {
        try {
            scheduler.sync(Guard.wrap(logger, "the opt-out reply", () -> actor.send(message)));
        } catch (Throwable e) {
            Guard.report(logger, "the opt-out reply", e);
        }
    }

    static Segment[] optOutMessage(Result result) {
        if (result == Result.OK) {
            return Segment.prefixed(Tone.GOOD,
                    "You are opted out of Detraly. Your joins, leaves and IP address are no longer sent from "
                            + "this server, and Detraly discards them from any other server. Server console "
                            + "output is not affected and may mention your name. To resume, run /detraly optin.");
        }
        return Segment.prefixed(Tone.WARN,
                "You are opted out on this server now. Detraly could not confirm it yet, so it is saved here "
                        + "and will sync automatically.");
    }

    static Segment[] optInMessage(Result result) {
        switch (result) {
            case OK:
                return Segment.prefixed(Tone.GOOD,
                        "You are opted in to Detraly. Your Detraly opt-out is removed, so tracking resumes on "
                                + "every server except servers where you ran /detraly optout yourself; run "
                                + "/detraly optin on each of those too.");
            case NOT_LINKED:
            case UNAUTHORIZED:
                return Segment.prefixed(Tone.BAD,
                        "This server is not linked to Detraly right now, so your choice could not be saved. "
                                + "You are still opted out.");
            case BAD_REQUEST:
                return Segment.prefixed(Tone.BAD,
                        "Detraly rejected the request. You are still opted out.");
            default:
                // network error, rate limit, 503 and the unused FORBIDDEN: nothing changed here, try again
                return Segment.prefixed(Tone.BAD,
                        "Could not reach Detraly, so you are still opted out. Try again shortly.");
        }
    }
}

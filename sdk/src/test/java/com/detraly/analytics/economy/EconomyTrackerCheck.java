package com.detraly.analytics.economy;

import com.detraly.analytics.economy.EconomyHost.Info;
import com.detraly.analytics.economy.EconomyHost.Known;
import com.detraly.analytics.events.RosterStore;
import com.detraly.analytics.platform.Scheduler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Checks the confirmation rules, filtering and pacing of the economy tracker with a fake host. */
public final class EconomyTrackerCheck {
    private static int failures;

    private static final UUID STEVE = UUID.fromString("0f8e7c52-1a2b-4c3d-8e9f-001122334455");
    private static final UUID ALEX = UUID.fromString("1a2b3c4d-5e6f-4a1b-9c2d-3e4f5a6b7c8d");
    private static final UUID NOTCH = UUID.fromString("2b3c4d5e-6f70-4b2c-8d3e-4f5a6b7c8d9e");

    private static final class FakeHost implements EconomyHost {
        final Map<UUID, Double> balances = new HashMap<>();
        final Set<UUID> noAccount = new HashSet<>();
        final Map<String, Known> names = new HashMap<>();
        final List<Known> everyone = new ArrayList<>();
        Info info = new Info("Fake Economy", "Dollar", "Dollars", 2, false);
        boolean throwOnBalance;
        long nanosPerCall;
        AtomicLong clock;
        int calls;
        int accountChecks;
        int idListings;
        int nameLookups;

        @Override
        public Info info() {
            return info;
        }

        @Override
        public OptionalDouble balance(UUID id, boolean checkAccount) {
            calls++;
            if (clock != null) {
                clock.addAndGet(nanosPerCall);
            }
            if (throwOnBalance) {
                throw new IllegalStateException("provider broke");
            }
            if (checkAccount) {
                accountChecks++;
                if (noAccount.contains(id)) {
                    return OptionalDouble.empty();
                }
            }
            Double b = balances.get(id);
            return b == null ? OptionalDouble.empty() : OptionalDouble.of(b);
        }

        @Override
        public List<UUID> knownPlayerIds() {
            idListings++;
            List<UUID> ids = new ArrayList<>();
            for (Known k : everyone) {
                ids.add(k.uuid());
            }
            return ids;
        }

        @Override
        public Known knownPlayer(UUID id) {
            nameLookups++;
            for (Known k : everyone) {
                if (k.uuid().equals(id)) {
                    return k.name() == null ? null : k;
                }
            }
            return null;
        }

        @Override
        public Known resolve(String name) {
            return names.get(name.toLowerCase());
        }
    }

    private static final class InlineScheduler implements Scheduler {
        /** When true, async work waits in {@link #queued} until the check runs it, like a worker thread would. */
        boolean deferAsync;
        final List<Runnable> queued = new ArrayList<>();

        @Override
        public Task timer(Runnable task, long delayTicks, long periodTicks) {
            return () -> { };
        }

        @Override
        public void later(Runnable task, long delayTicks) {
            task.run();
        }

        @Override
        public void async(Runnable task) {
            if (deferAsync) {
                queued.add(task);
            } else {
                task.run();
            }
        }

        @Override
        public void sync(Runnable task) {
            task.run();
        }
    }

    private static final class Rig {
        final FakeHost host = new FakeHost();
        final EconomyBuffer buffer = new EconomyBuffer();
        final Set<UUID> optedOut = new HashSet<>();
        final List<RosterStore.Entry> online = new ArrayList<>();
        final AtomicLong millis = new AtomicLong(1_730_000_000_000L);
        final AtomicLong nanos = new AtomicLong();
        final AtomicInteger warnings = new AtomicInteger();
        final Logger logger = Logger.getAnonymousLogger();
        final InlineScheduler scheduler = new InlineScheduler();
        final EconomyTracker tracker;

        Rig(EconomyConfig config) {
            logger.setUseParentHandlers(false);
            logger.addHandler(new Handler() {
                @Override
                public void publish(LogRecord record) {
                    if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                        warnings.incrementAndGet();
                    }
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            });
            host.clock = nanos;
            tracker = new EconomyTracker(logger, scheduler, host, config, buffer, () -> online,
                    optedOut::contains, null, () -> "fingerprint", millis::get, nanos::get);
            host.balances.put(STEVE, 1000.0);
            host.balances.put(ALEX, 500.0);
            host.names.put("steve", new Known(STEVE, "Steve"));
            host.names.put("alex", new Known(ALEX, "Alex"));
            online.add(new RosterStore.Entry(STEVE, "Steve"));
            online.add(new RosterStore.Entry(ALEX, "Alex"));
        }

        void ticks(int n) {
            for (int i = 0; i < n; i++) {
                tracker.tick();
            }
        }

        List<EconomyEvent> drain() {
            return buffer.take(10_000, millis.get() / 1000L);
        }
    }

    static EconomyConfig config(int pollSeconds, int sweepHours) {
        return new EconomyConfig(true, pollSeconds, sweepHours, EconomyConfig.DEFAULT_PAY_COMMANDS,
                EconomyConfig.DEFAULT_ADMIN_COMMANDS);
    }

    private static Rig started() {
        Rig r = new Rig(config(30, 0));
        r.tracker.offered(true);
        r.ticks(1);
        r.drain();
        return r;
    }

    private static List<EconomyEvent.Balance> balances(List<EconomyEvent> events, String reason) {
        List<EconomyEvent.Balance> out = new ArrayList<>();
        for (EconomyEvent e : events) {
            if (e instanceof EconomyEvent.Balance b && b.reason().equals(reason)) {
                out.add(b);
            }
        }
        return out;
    }

    private static <T> List<T> of(List<EconomyEvent> events, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (EconomyEvent e : events) {
            if (type.isInstance(e)) {
                out.add(type.cast(e));
            }
        }
        return out;
    }

    public static void main(String[] args) {
        statusAndActivation();
        polling();
        joinAndQuit();
        confirmationRules();
        transfersEndToEnd();
        adminEndToEnd();
        adminMustMatchTheAmount();
        privacyAndLimits();
        providerFailures();
        slowProvider();
        sweep();
        sweepListsOffTheMainThread();
        oneCommandPerPlayer();
        mergedCommands();
        slowModeSkipsCommands();
        commandTimeCountsTowardTheBudget();
        offeredCanBeWithdrawn();

        System.out.println(failures == 0 ? "all passed" : failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void statusAndActivation() {
        Rig r = new Rig(config(30, 0));
        check("status: waiting for the API before the API offers it", r.tracker.status().equals("waiting for the API"));
        r.ticks(5);
        check("nothing is read before the API offers economy", r.host.calls == 0 && r.buffer.isEmpty());
        r.tracker.offered(true);
        r.ticks(1);
        check("economy_info source is published", r.tracker.info() != null
                && r.tracker.info().provider().equals("Fake Economy") && r.tracker.infoVersion() == 1);
        check("status names the provider and the players tracked",
                r.tracker.status().equals("Fake Economy, 2 players tracked"));

        r.host.info = new Info("Other Economy", "", "", 0, true);
        r.ticks(20);
        check("a provider change bumps the info version", r.tracker.infoVersion() == 2
                && r.tracker.info().provider().equals("Other Economy"));
        r.host.info = null;
        r.ticks(20);
        check("status: not found when the provider is gone", r.tracker.status().equals("not found")
                && r.tracker.info() == null);

        Rig off = new Rig(new EconomyConfig(false, 30, 0, List.of(), List.of()));
        off.tracker.offered(true);
        off.ticks(5);
        check("status: off", off.tracker.status().equals("off") && off.host.calls == 0);

        Rig reset = started();
        reset.tracker.reset();
        check("reset stops collecting and clears the buffer", reset.buffer.isEmpty()
                && reset.tracker.status().equals("waiting for the API"));
    }

    private static void polling() {
        Rig r = new Rig(config(30, 0));
        r.tracker.offered(true);
        r.ticks(1);
        List<EconomyEvent.Balance> first = balances(r.drain(), "poll");
        check("the first poll sends a baseline for each online player", first.size() == 2);

        r.ticks(599);
        check("an unchanged balance sends nothing", r.drain().isEmpty());
        r.host.balances.put(STEVE, 1100.0);
        r.ticks(40);
        List<EconomyEvent.Balance> changed = balances(r.drain(), "poll");
        check("a changed balance sends a poll sample", changed.size() == 1 && changed.get(0).balance() == 1100.0
                && changed.get(0).uuid().equals(STEVE.toString()) && changed.get(0).name().equals("Steve"));
        check("online players are read without an account check", r.host.accountChecks == 0);
    }

    private static void joinAndQuit() {
        Rig r = started();
        UUID notch = NOTCH;
        r.host.balances.put(notch, 42.0);
        r.online.add(new RosterStore.Entry(notch, "Notch"));
        r.tracker.onJoin(notch, "Notch");
        check("a join is sampled one tick later, not at once", r.buffer.isEmpty());
        r.ticks(1);
        List<EconomyEvent.Balance> join = balances(r.drain(), "join");
        check("join sample", join.size() == 1 && join.get(0).balance() == 42.0);

        r.host.balances.put(notch, 50.0);
        r.online.removeIf(e -> e.uuid().equals(notch));
        r.tracker.onQuit(notch, "Notch");
        List<EconomyEvent.Balance> quit = balances(r.drain(), "quit");
        check("quit sample is immediate", quit.size() == 1 && quit.get(0).balance() == 50.0);

        r.ticks(700);
        check("a player who left is no longer polled", balances(r.drain(), "poll").stream()
                .noneMatch(b -> b.uuid().equals(notch.toString())));

        Rig quick = started();
        quick.online.add(new RosterStore.Entry(NOTCH, "Notch"));
        quick.host.balances.put(NOTCH, 1.0);
        quick.tracker.onJoin(NOTCH, "Notch");
        quick.tracker.onQuit(NOTCH, "Notch");
        quick.ticks(2);
        check("a player who left before the join sample is not sampled again",
                balances(quick.drain(), "join").isEmpty());
    }

    private static void confirmationRules() {
        double a = 250.0;
        check("transfer: exact", EconomyTracker.transferConfirmed(a, d(1000), d(750), d(500), d(750)));
        check("transfer: tax taken from the payer", EconomyTracker.transferConfirmed(a, d(1000), d(740), d(500),
                d(750)));
        check("transfer: payer lost less than the amount", !EconomyTracker.transferConfirmed(a, d(1000), d(850),
                d(500), d(750)));
        check("transfer: receiver gained less", !EconomyTracker.transferConfirmed(a, d(1000), d(750), d(500),
                d(600)));
        check("transfer: receiver gained more", !EconomyTracker.transferConfirmed(a, d(1000), d(750), d(500),
                d(900)));
        check("transfer: nothing moved", !EconomyTracker.transferConfirmed(a, d(1000), d(1000), d(500), d(500)));
        check("transfer: within the rounding tolerance", EconomyTracker.transferConfirmed(a, d(1000), d(750.2),
                d(500), d(749.9)));
        check("transfer: outside the tolerance", !EconomyTracker.transferConfirmed(a, d(1000), d(750), d(500),
                d(750.4)));
        check("transfer: small amounts use the 0.01 floor", EconomyTracker.transferConfirmed(1.0, d(10), d(8.995),
                d(5), d(5.995)));
        check("transfer: receiver unreadable, payer lost the amount", EconomyTracker.transferConfirmed(a, d(1000),
                d(750), none(), none()));
        check("transfer: receiver unreadable, payer lost more", !EconomyTracker.transferConfirmed(a, d(1000),
                d(700), none(), none()));
        check("transfer: receiver unreadable after only", EconomyTracker.transferConfirmed(a, d(1000), d(750),
                d(500), none()));
        check("transfer: payer unreadable", !EconomyTracker.transferConfirmed(a, none(), d(750), d(500), d(750)));
        check("transfer: payer unreadable after", !EconomyTracker.transferConfirmed(a, d(1000), none(), d(500),
                d(750)));

        check("admin give matches", EconomyTracker.adminAmount("give", 10, 10, 20).orElse(-1) == 10);
        check("admin give lowered", EconomyTracker.adminAmount("give", 10, 10, 5).isEmpty());
        check("admin give unchanged", EconomyTracker.adminAmount("give", 10, 10, 10).isEmpty());
        check("admin give raised by another amount", EconomyTracker.adminAmount("give", 10, 10, 15).isEmpty());
        check("admin give within the tolerance", EconomyTracker.adminAmount("give", 1000, 0, 999.5).isPresent()
                && EconomyTracker.adminAmount("give", 1000, 0, 998.0).isEmpty());
        check("admin take matches", EconomyTracker.adminAmount("take", 5, 10, 5).orElse(-1) == 5);
        check("admin take raised", EconomyTracker.adminAmount("take", 5, 10, 15).isEmpty());
        check("admin take of another amount", EconomyTracker.adminAmount("take", 5, 10, 8).isEmpty());
        check("admin take capped at zero reports what moved",
                EconomyTracker.adminAmount("take", 100, 30, 0).orElse(-1) == 30);
        check("admin take that is short but not at zero is not confirmed",
                EconomyTracker.adminAmount("take", 100, 300, 250).isEmpty());
        check("admin take cannot move more than typed", EconomyTracker.adminAmount("take", 10, 300, 0).isEmpty());
        check("admin set any change", EconomyTracker.adminAmount("set", 7, 10, 5).orElse(-1) == 7
                && EconomyTracker.adminAmount("set", 7, 10, 15).isPresent()
                && EconomyTracker.adminAmount("set", 7, 10, 10).isEmpty());
        check("admin reset any change", EconomyTracker.adminAmount("reset", 0, 10, 0).isPresent()
                && EconomyTracker.adminAmount("reset", 0, 0, 0).isEmpty());
        check("admin unknown action", EconomyTracker.adminAmount("gift", 10, 10, 20).isEmpty());
    }

    private static void transfersEndToEnd() {
        Rig r = started();
        r.tracker.onPlayerCommand(STEVE, "Steve", "/pay Alex 250");
        r.host.balances.put(STEVE, 750.0);
        r.host.balances.put(ALEX, 750.0);
        r.ticks(1);
        check("a transfer is not reported before 2 ticks", r.buffer.isEmpty());
        r.ticks(1);
        List<EconomyEvent> events = r.drain();
        List<EconomyEvent.Transfer> transfers = of(events, EconomyEvent.Transfer.class);
        check("a confirmed transfer is reported once", transfers.size() == 1);
        EconomyEvent.Transfer t = transfers.get(0);
        check("transfer fields", t.fromUuid().equals(STEVE.toString()) && t.fromName().equals("Steve")
                && t.toUuid().equals(ALEX.toString()) && t.toName().equals("Alex") && t.amount() == 250.0
                && t.command().equals("pay") && t.eid().matches("[0-9a-f]{32}"));
        List<EconomyEvent.Balance> after = balances(events, "after");
        check("after samples for both players", after.size() == 2);
        check("the command text is nowhere in the events", !events.toString().contains("/pay")
                && !events.toString().contains("250.0 now"));

        r.tracker.onPlayerCommand(STEVE, "Steve", "pay Alex 250");
        r.ticks(2);
        events = r.drain();
        check("a failed transfer (no money moved) sends only after samples",
                of(events, EconomyEvent.Transfer.class).isEmpty() && balances(events, "after").size() == 2);

        r.host.balances.put(STEVE, 1000.0);
        r.host.balances.put(ALEX, 500.0);
        r.drain();
        r.tracker.onPlayerCommand(STEVE, "Steve", "pay Alex 100");
        r.host.balances.put(STEVE, 895.0);
        r.host.balances.put(ALEX, 550.0);
        r.ticks(2);
        check("a receiver gain that does not match is not reported", of(r.drain(), EconomyEvent.Transfer.class)
                .isEmpty());

        r.host.balances.put(STEVE, 1000.0);
        r.host.balances.put(ALEX, 500.0);
        r.drain();
        r.tracker.onPlayerCommand(STEVE, "Steve", "pay Alex 100");
        r.host.balances.put(STEVE, 895.0);
        r.host.balances.put(ALEX, 600.0);
        r.ticks(2);
        check("a taxed transfer is reported", of(r.drain(), EconomyEvent.Transfer.class).size() == 1);

        r.drain();
        r.tracker.onPlayerCommand(STEVE, "Steve", "pay Nobody 100");
        r.tracker.onPlayerCommand(STEVE, "Steve", "pay Steve 100");
        r.tracker.onPlayerCommand(STEVE, "Steve", "say pay Alex 100");
        r.ticks(3);
        check("unknown target, self payment and other commands are ignored", r.drain().isEmpty());

        Rig fresh = new Rig(config(30, 0));
        fresh.online.removeIf(e -> e.uuid().equals(ALEX));
        fresh.tracker.offered(true);
        fresh.ticks(1);
        fresh.drain();
        fresh.tracker.onPlayerCommand(STEVE, "Steve", "pay Alex 100");
        fresh.host.balances.put(STEVE, 900.0);
        fresh.host.balances.put(ALEX, 600.0);
        fresh.ticks(2);
        check("an offline receiver is read with an account check", fresh.host.accountChecks >= 2
                && of(fresh.drain(), EconomyEvent.Transfer.class).size() == 1);
    }

    private static void adminEndToEnd() {
        Rig r = started();
        r.tracker.onConsoleCommand("eco give Alex 1000");
        r.host.balances.put(ALEX, 1500.0);
        r.ticks(2);
        List<EconomyEvent> events = r.drain();
        List<EconomyEvent.Admin> admins = of(events, EconomyEvent.Admin.class);
        check("console admin action is reported", admins.size() == 1 && admins.get(0).actorUuid() == null
                && admins.get(0).actorName() == null && admins.get(0).action().equals("give")
                && admins.get(0).amount() == 1000.0 && admins.get(0).command().equals("eco")
                && admins.get(0).targetUuid().equals(ALEX.toString()));
        check("an after sample is sent", balances(events, "after").size() == 1);

        r.tracker.onPlayerCommand(STEVE, "Steve", "/eco take Alex 100");
        r.host.balances.put(ALEX, 1400.0);
        r.ticks(2);
        admins = of(r.drain(), EconomyEvent.Admin.class);
        check("a player's admin action names the actor", admins.size() == 1
                && STEVE.toString().equals(admins.get(0).actorUuid()) && "Steve".equals(admins.get(0).actorName()));

        r.tracker.onConsoleCommand("eco reset Alex");
        r.host.balances.put(ALEX, 0.0);
        r.ticks(2);
        admins = of(r.drain(), EconomyEvent.Admin.class);
        check("reset has amount 0", admins.size() == 1 && admins.get(0).action().equals("reset")
                && admins.get(0).amount() == 0.0);

        r.tracker.onConsoleCommand("eco give Alex 100");
        r.ticks(2);
        check("an admin command that changed nothing is not reported",
                of(r.drain(), EconomyEvent.Admin.class).isEmpty());

        r.tracker.onConsoleCommand("eco take Alex 100");
        r.host.balances.put(ALEX, 99999.0);
        r.ticks(2);
        check("a balance that moved the wrong way is not reported",
                of(r.drain(), EconomyEvent.Admin.class).isEmpty());

        r.tracker.onConsoleCommand("pay Alex 100");
        r.ticks(2);
        check("the console does not make transfers", r.drain().isEmpty());
    }

    private static void privacyAndLimits() {
        Rig r = started();
        r.optedOut.add(ALEX);
        r.tracker.onPlayerCommand(STEVE, "Steve", "pay Alex 250");
        r.host.balances.put(STEVE, 750.0);
        r.host.balances.put(ALEX, 750.0);
        r.ticks(2);
        check("a transfer naming an opted-out player is dropped, with its samples", r.drain().isEmpty());

        r.host.balances.put(ALEX, 1.0);
        r.ticks(700);
        List<EconomyEvent> events = r.drain();
        check("opted-out players are never polled or sampled", events.stream().noneMatch(
                e -> e.toJson().toString().contains(ALEX.toString())));

        r.tracker.onConsoleCommand("eco give Alex 5");
        r.host.balances.put(ALEX, 6.0);
        r.ticks(2);
        check("an admin action on an opted-out player is dropped", r.drain().isEmpty());

        r.optedOut.add(STEVE);
        r.tracker.onPlayerCommand(STEVE, "Steve", "pay Alex 1");
        r.ticks(2);
        check("an opted-out actor's commands are ignored", r.drain().isEmpty());

        Rig flood = started();
        for (int i = 0; i < 500; i++) {
            flood.tracker.onPlayerCommand(STEVE, "Steve", "pay Alex 1");
        }
        check("a flood of commands from one player leaves one pending", flood.tracker.pendingCommands() == 1);
        flood.ticks(3);
        check("the player's slot is released once it is confirmed", flood.tracker.pendingCommands() == 0);

        Rig crowd = started();
        for (int i = 0; i < 200; i++) {
            UUID id = new UUID(8L, i + 1L);
            crowd.host.balances.put(id, 100.0);
            crowd.tracker.onJoin(id, "P" + i);
            crowd.tracker.onPlayerCommand(id, "P" + i, "pay Alex 1");
        }
        check("pending confirmations are capped", crowd.tracker.pendingCommands() == EconomyTracker.MAX_PENDING_COMMANDS);
    }

    private static void sweepListsOffTheMainThread() {
        Rig r = new Rig(config(30, 1));
        r.online.clear();
        r.scheduler.deferAsync = true;
        UUID a = new UUID(9L, 1L);
        UUID b = new UUID(9L, 2L);
        UUID c = new UUID(9L, 3L);
        r.host.everyone.add(new Known(a, "Anna"));
        r.host.everyone.add(new Known(b, null));
        r.host.everyone.add(new Known(c, "Cleo"));
        r.host.balances.put(a, 1.0);
        r.host.balances.put(b, 2.0);
        r.host.balances.put(c, 3.0);
        r.tracker.offered(true);
        r.ticks(1);
        r.millis.addAndGet(5L * 60L * 1000L + 1000L);
        r.ticks(200);
        check("the sweep asks the scheduler to list the ids", r.scheduler.queued.size() == 1);
        check("nothing is listed or looked up on the tick thread", r.host.idListings == 0 && r.host.nameLookups == 0
                && balances(r.drain(), "sweep").isEmpty());
        r.scheduler.queued.forEach(Runnable::run);
        r.scheduler.queued.clear();
        check("listing ids needs no name lookup", r.host.idListings == 1 && r.host.nameLookups == 0);
        r.ticks(5);
        List<EconomyEvent.Balance> swept = balances(r.drain(), "sweep");
        check("players are looked up one by one and those without a name are skipped", r.host.nameLookups == 3
                && swept.size() == 2);

        Rig stale = new Rig(config(30, 1));
        stale.online.clear();
        stale.scheduler.deferAsync = true;
        stale.host.everyone.add(new Known(a, "Anna"));
        stale.host.balances.put(a, 1.0);
        stale.tracker.offered(true);
        stale.ticks(1);
        stale.millis.addAndGet(5L * 60L * 1000L + 1000L);
        stale.ticks(200);
        stale.tracker.reset();
        stale.ticks(1);
        stale.scheduler.queued.forEach(Runnable::run);
        stale.ticks(300);
        check("a listing that finishes after a reset is dropped", balances(stale.drain(), "sweep").isEmpty());
    }

    private static void mergedCommands() {
        Rig r = started();
        r.tracker.onConsoleCommand("eco give Alex 100");
        int reads = r.host.calls;
        r.tracker.onConsoleCommand("eco give Alex 100");
        check("a second give on the same target merges without any read", r.host.calls == reads
                && r.tracker.pendingCommands() == 1);
        r.host.balances.put(ALEX, 700.0);
        r.ticks(2);
        List<EconomyEvent> events = r.drain();
        List<EconomyEvent.Admin> admins = of(events, EconomyEvent.Admin.class);
        check("two quick gives become one admin event of the sum", admins.size() == 1
                && admins.get(0).amount() == 200.0 && admins.get(0).action().equals("give"));
        check("a merged command has one after sample", balances(events, "after").size() == 1);

        Rig take = started();
        take.tracker.onConsoleCommand("eco give Alex 100");
        take.tracker.onConsoleCommand("eco take Alex 100");
        take.host.balances.put(ALEX, 600.0);
        take.ticks(2);
        admins = of(take.drain(), EconomyEvent.Admin.class);
        check("give then take: the second is ignored", admins.size() == 1 && admins.get(0).amount() == 100.0
                && admins.get(0).action().equals("give"));

        Rig other = started();
        other.tracker.onConsoleCommand("eco give Alex 100");
        other.tracker.onConsoleCommand("eco give Steve 100");
        other.tracker.onConsoleCommand("eco set Alex 5");
        other.host.balances.put(ALEX, 600.0);
        other.ticks(2);
        admins = of(other.drain(), EconomyEvent.Admin.class);
        check("another target or a set is ignored", admins.size() == 1 && admins.get(0).amount() == 100.0
                && admins.get(0).targetUuid().equals(ALEX.toString()));

        Rig sets = started();
        sets.tracker.onConsoleCommand("eco set Alex 5");
        sets.tracker.onConsoleCommand("eco set Alex 5");
        sets.host.balances.put(ALEX, 5.0);
        sets.ticks(2);
        admins = of(sets.drain(), EconomyEvent.Admin.class);
        check("set commands do not merge", admins.size() == 1 && admins.get(0).amount() == 5.0);

        Rig pay = started();
        pay.tracker.onPlayerCommand(STEVE, "Steve", "pay Alex 100");
        reads = pay.host.calls;
        pay.tracker.onPlayerCommand(STEVE, "Steve", "pay Alex 150");
        check("a second pay to the same target merges without any read", pay.host.calls == reads);
        pay.host.balances.put(STEVE, 750.0);
        pay.host.balances.put(ALEX, 750.0);
        pay.ticks(2);
        List<EconomyEvent.Transfer> transfers = of(pay.drain(), EconomyEvent.Transfer.class);
        check("two pays to the same target sum", transfers.size() == 1 && transfers.get(0).amount() == 250.0);

        Rig tax = started();
        tax.tracker.onPlayerCommand(STEVE, "Steve", "pay Alex 100");
        tax.tracker.onPlayerCommand(STEVE, "Steve", "pay Alex 100");
        tax.host.balances.put(STEVE, 790.0);
        tax.host.balances.put(ALEX, 700.0);
        tax.ticks(2);
        check("a merged pay with tax still confirms", of(tax.drain(), EconomyEvent.Transfer.class).size() == 1);

        Rig otherPay = started();
        otherPay.host.balances.put(NOTCH, 10.0);
        otherPay.host.names.put("notch", new Known(NOTCH, "Notch"));
        otherPay.tracker.onPlayerCommand(STEVE, "Steve", "pay Alex 100");
        otherPay.tracker.onPlayerCommand(STEVE, "Steve", "pay Notch 100");
        otherPay.tracker.onConsoleCommand("eco give Alex 1");
        otherPay.host.balances.put(STEVE, 900.0);
        otherPay.host.balances.put(ALEX, 600.0);
        otherPay.ticks(2);
        transfers = of(otherPay.drain(), EconomyEvent.Transfer.class);
        check("a pay to another target is ignored", transfers.size() == 1 && transfers.get(0).amount() == 100.0);

        Rig late = started();
        late.tracker.onConsoleCommand("eco give Alex 100");
        late.ticks(1);
        late.tracker.onConsoleCommand("eco give Alex 100");
        late.host.balances.put(ALEX, 700.0);
        late.ticks(1);
        check("a merge after a tick boundary delays the confirmation", late.buffer.isEmpty());
        late.ticks(1);
        check("the confirmation comes 2 ticks after the latest merge",
                of(late.drain(), EconomyEvent.Admin.class).size() == 1);

        Rig cap = started();
        for (int i = 0; i < 100; i++) {
            cap.tracker.onConsoleCommand("eco give Alex 1");
        }
        cap.host.balances.put(ALEX, 500.0 + 1.0 + EconomyTracker.MAX_MERGES);
        cap.ticks(2);
        admins = of(cap.drain(), EconomyEvent.Admin.class);
        check("a merge chain is capped", admins.size() == 1
                && admins.get(0).amount() == 1.0 + EconomyTracker.MAX_MERGES);
    }

    private static void adminMustMatchTheAmount() {
        Rig spam = started();
        for (int i = 0; i < 20; i++) {
            spam.tracker.onPlayerCommand(NOTCH, "Notch", "/eco give Alex 1000000");
        }
        spam.host.balances.put(ALEX, 505.0);
        spam.ticks(2);
        List<EconomyEvent> events = spam.drain();
        check("rejected merged gives plus an unrelated gain are not an admin event",
                of(events, EconomyEvent.Admin.class).isEmpty());
        check("the after sample is still sent", balances(events, "after").size() == 1);

        Rig partial = started();
        partial.tracker.onConsoleCommand("eco give Alex 100");
        partial.tracker.onConsoleCommand("eco give Alex 100");
        partial.host.balances.put(ALEX, 600.0);
        partial.ticks(2);
        events = partial.drain();
        check("two gives where only one applied are not reported", of(events, EconomyEvent.Admin.class).isEmpty()
                && balances(events, "after").size() == 1);

        Rig good = started();
        good.tracker.onConsoleCommand("eco give Alex 250");
        good.host.balances.put(ALEX, 750.0);
        good.ticks(2);
        List<EconomyEvent.Admin> admins = of(good.drain(), EconomyEvent.Admin.class);
        check("a give that matches is reported", admins.size() == 1 && admins.get(0).amount() == 250.0);

        Rig zero = started();
        zero.host.balances.put(ALEX, 30.0);
        zero.tracker.onConsoleCommand("eco take Alex 100");
        zero.host.balances.put(ALEX, 0.0);
        zero.ticks(2);
        admins = of(zero.drain(), EconomyEvent.Admin.class);
        check("a take capped at zero reports the money that moved", admins.size() == 1
                && admins.get(0).amount() == 30.0 && admins.get(0).action().equals("take"));

        Rig window = started();
        window.tracker.onConsoleCommand("eco give Alex 1");
        for (int i = 0; i < 19; i++) {
            window.ticks(1);
            window.tracker.onConsoleCommand("eco give Alex 1");
        }
        window.host.balances.put(ALEX, 520.0);
        check("a stream of commands does not keep the window open", window.buffer.isEmpty());
        window.ticks(1);
        admins = of(window.drain(), EconomyEvent.Admin.class);
        check("the due tick is at most 20 ticks after the first command", admins.size() == 1
                && admins.get(0).amount() == 20.0 && window.tracker.pendingCommands() == 0);
    }

    private static void oneCommandPerPlayer() {
        Rig r = started();
        r.tracker.onPlayerCommand(STEVE, "Steve", "pay Alex 250");
        int before = r.host.calls;
        r.host.balances.put(NOTCH, 10.0);
        r.host.names.put("notch", new Known(NOTCH, "Notch"));
        r.tracker.onPlayerCommand(STEVE, "Steve", "pay Notch 100");
        check("a second command while one is pending is ignored without any read", r.host.calls == before
                && r.tracker.pendingCommands() == 1);
        r.host.balances.put(STEVE, 750.0);
        r.host.balances.put(ALEX, 750.0);
        r.ticks(2);
        check("only the first command is reported", of(r.drain(), EconomyEvent.Transfer.class).size() == 1);
        r.tracker.onPlayerCommand(ALEX, "Alex", "pay Steve 10");
        r.tracker.onPlayerCommand(STEVE, "Steve", "pay Alex 10");
        check("different players each get one", r.tracker.pendingCommands() == 2);
        r.tracker.onConsoleCommand("eco give Alex 5");
        r.tracker.onConsoleCommand("eco give Steve 5");
        check("the console gets one as well", r.tracker.pendingCommands() == 3);
    }

    private static void slowModeSkipsCommands() {
        Rig r = new Rig(config(30, 0));
        r.online.clear();
        for (int i = 0; i < 60; i++) {
            UUID id = new UUID(0L, i + 1L);
            r.online.add(new RosterStore.Entry(id, "P" + i));
            r.host.balances.put(id, (double) i);
        }
        r.host.nanosPerCall = 6_000_000L;
        r.tracker.offered(true);
        r.ticks(200);
        check("a slow provider puts the tracker in slow mode", r.tracker.slow());
        r.drain();
        int before = r.host.calls;
        r.tracker.onPlayerCommand(STEVE, "Steve", "pay Alex 250");
        r.tracker.onConsoleCommand("eco give Alex 5");
        check("commands are not tracked in slow mode, and cost no reads", r.host.calls == before
                && r.tracker.pendingCommands() == 0);
    }

    private static void commandTimeCountsTowardTheBudget() {
        Rig r = started();
        r.host.nanosPerCall = 400_000L;
        r.ticks(599);
        r.tracker.onPlayerCommand(STEVE, "Steve", "pay Alex 250");
        int callsBefore = r.host.calls;
        // The handler read two balances (0.8 ms of the 1 ms budget). The poll of the two online players is due on
        // the next tick, and only one of its reads fits.
        r.ticks(1);
        check("the handler's reads count toward the next tick's budget", r.host.calls - callsBefore == 1);
        r.ticks(1);
        check("the confirmation still runs, and the poll goes on", r.host.calls - callsBefore >= 4);
    }

    private static void offeredCanBeWithdrawn() {
        Rig r = new Rig(config(30, 0));
        r.tracker.offered(true);
        r.ticks(1);
        check("collecting starts when the API offers the economy", !r.drain().isEmpty());
        r.tracker.offered(false);
        r.ticks(1);
        check("a ready without economy empties the buffer", r.buffer.isEmpty());
        r.online.add(new RosterStore.Entry(STEVE, "Steve"));
        r.tracker.onJoin(STEVE, "Steve");
        r.ticks(700);
        check("and nothing more is collected", r.buffer.isEmpty() && r.tracker.status().equals("waiting for the API"));
        r.tracker.offered(true);
        r.ticks(2);
        check("until it is offered again", !r.drain().isEmpty());
    }

    private static void providerFailures() {
        Rig r = new Rig(config(30, 0));
        r.tracker.offered(true);
        r.host.throwOnBalance = true;
        boolean threw = false;
        try {
            r.ticks(1);
            r.tracker.onJoin(STEVE, "Steve");
            r.tracker.onPlayerCommand(STEVE, "Steve", "pay Alex 5");
            r.ticks(5);
            r.tracker.onQuit(STEVE, "Steve");
        } catch (Throwable e) {
            threw = true;
        }
        check("a throwing provider never reaches the caller", !threw);
        check("a throwing provider is logged at most once in 10 minutes", r.warnings.get() == 1);
        check("nothing is sent while the provider throws", r.buffer.isEmpty());
        r.host.throwOnBalance = false;
        r.ticks(700);
        check("it recovers when the provider works again", !r.buffer.isEmpty());

        Rig bad = new Rig(config(30, 0));
        bad.host.balances.put(STEVE, Double.NaN);
        bad.host.balances.put(ALEX, 2e15);
        bad.tracker.offered(true);
        bad.ticks(1);
        check("balances the API would refuse are never sent", bad.buffer.isEmpty());
    }

    private static void slowProvider() {
        Rig r = new Rig(config(30, 24));
        r.online.clear();
        for (int i = 0; i < 60; i++) {
            UUID id = new UUID(0L, i + 1L);
            r.online.add(new RosterStore.Entry(id, "P" + i));
            r.host.balances.put(id, (double) i);
        }
        r.host.nanosPerCall = 6_000_000L;
        r.tracker.offered(true);
        r.ticks(200);
        check("a slow provider is detected", r.tracker.slow());
        check("the poll interval doubled", r.tracker.pollSeconds() >= 60);
        check("one warning only", r.warnings.get() == 1);
        r.ticks(2000);
        check("the interval stays at or under 10 minutes", r.tracker.pollSeconds() <= 600);
        check("work is spread over ticks (6 ms reads never run more than one per tick)",
                r.host.calls <= 200 + 2000 + 5);

        Rig fast = new Rig(config(30, 0));
        fast.host.nanosPerCall = 100_000L;
        fast.tracker.offered(true);
        fast.ticks(5000);
        check("a fast provider is not slowed", !fast.tracker.slow() && fast.tracker.pollSeconds() == 30);
    }

    private static void sweep() {
        Rig r = new Rig(config(30, 1));
        r.online.clear();
        r.host.balances.clear();
        for (int i = 0; i < 3; i++) {
            UUID id = new UUID(5L, i + 1L);
            r.host.everyone.add(new Known(id, "Old" + i));
            r.host.balances.put(id, 10.0 * (i + 1));
        }
        UUID closed = new UUID(5L, 99L);
        r.host.everyone.add(new Known(closed, "NoAccount"));
        r.host.noAccount.add(closed);
        UUID hidden = new UUID(5L, 98L);
        r.host.everyone.add(new Known(hidden, "Hidden"));
        r.host.balances.put(hidden, 3.0);
        r.optedOut.add(hidden);
        r.host.everyone.add(new Known(new UUID(5L, 97L), "bad name!"));
        r.tracker.offered(true);
        r.ticks(500);
        check("no sweep in the first 5 minutes", balances(r.drain(), "sweep").isEmpty());
        r.millis.addAndGet(5L * 60L * 1000L + 1000L);
        r.ticks(300);
        List<EconomyEvent.Balance> swept = balances(r.drain(), "sweep");
        check("the sweep sends known players that have an account and have not opted out", swept.size() == 3);
        check("the sweep checks the account first", r.host.accountChecks >= 4);
        r.ticks(2000);
        check("no second sweep inside sweep-hours", balances(r.drain(), "sweep").isEmpty());
        r.millis.addAndGet(61L * 60L * 1000L);
        r.ticks(300);
        check("another sweep after sweep-hours", balances(r.drain(), "sweep").size() == 3);

        Rig never = new Rig(config(30, 0));
        never.host.everyone.add(new Known(new UUID(6L, 1L), "Someone"));
        never.host.balances.put(new UUID(6L, 1L), 1.0);
        never.tracker.offered(true);
        never.millis.addAndGet(10L * 3600L * 1000L);
        never.ticks(500);
        check("sweep-hours 0 never sweeps", balances(never.drain(), "sweep").isEmpty());

        Rig busy = new Rig(config(30, 1));
        busy.online.clear();
        busy.host.everyone.add(new Known(new UUID(7L, 1L), "Late"));
        busy.host.balances.put(new UUID(7L, 1L), 1.0);
        busy.tracker.offered(true);
        busy.millis.addAndGet(6L * 60L * 1000L);
        for (int i = 0; i < EconomyBuffer.CAPACITY / 2 + 10; i++) {
            busy.buffer.add(new EconomyEvent.Balance(EconomyEvent.newEid(), busy.millis.get() / 1000L,
                    STEVE.toString(), "Steve", 1.0, "poll"));
        }
        busy.ticks(300);
        check("the sweep waits while the buffer is more than half full", balances(busy.drain(), "sweep").isEmpty());
    }

    private static OptionalDouble d(double v) {
        return OptionalDouble.of(v);
    }

    private static OptionalDouble none() {
        return OptionalDouble.empty();
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + name);
        if (!ok) {
            failures++;
        }
    }
}

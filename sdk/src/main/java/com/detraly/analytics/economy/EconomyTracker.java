package com.detraly.analytics.economy;

import com.detraly.analytics.economy.EconomyHost.Info;
import com.detraly.analytics.economy.EconomyHost.Known;
import com.detraly.analytics.events.RosterStore;
import com.detraly.analytics.platform.Scheduler;
import com.detraly.analytics.safety.Guard;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Watches an economy provider and turns what it sees into {@link EconomyEvent}s.
 *
 * <p>Vault has no events, so this class observes: it samples balances (join, quit, a poll of online players, a slow
 * sweep of everyone the server knows), and it recognises pay and admin commands and confirms them by reading the
 * balances before the command and again two ticks after. A transfer or admin action is reported only when the money
 * really moved.
 *
 * <p>Everything here runs on the main server thread, driven by {@link #tick()} once per game tick, and inside a
 * budget of one millisecond per tick. Only {@link #buffer()}, {@link #info()}, {@link #infoVersion()},
 * {@link #status()}, {@link #offered()} and {@link #reset()} may be used from other threads. The raw text of a
 * command is matched and dropped; it is never stored, logged or sent.
 */
public final class EconomyTracker {
    /** Main-thread time this class may use in one tick. */
    static final long BUDGET_NANOS = 1_000_000L;
    static final int MAX_PENDING_COMMANDS = 64;
    /** Stands for the console when it is the one who ran a command. */
    private static final UUID CONSOLE = new UUID(0L, 0L);
    static final long CONFIRM_DELAY_TICKS = 2L;
    /** How many further commands may be merged into one pending command. */
    static final int MAX_MERGES = 20;
    /** A merge never moves the due tick later than this many ticks after the first command of the chain. */
    static final long MAX_MERGE_WINDOW_TICKS = 20L;
    static final long INFO_CHECK_TICKS = 20L;
    static final long SWEEP_CHECK_TICKS = 100L;
    static final long FIRST_SWEEP_DELAY_MILLIS = 5L * 60L * 1000L;
    static final long ERROR_LOG_INTERVAL_MILLIS = 10L * 60L * 1000L;

    /** A provider whose reads take longer than this on average is slow. */
    static final double SLOW_AVERAGE_MILLIS = 5.0;
    static final double RECOVERED_AVERAGE_MILLIS = 2.5;
    static final int SLOW_WINDOW_CALLS = 50;
    static final int SLOW_MAX_POLL_SECONDS = 600;

    private final Logger logger;
    private final Scheduler scheduler;
    private final EconomyHost host;
    private final EconomyConfig config;
    private final EconomyBuffer buffer;
    private final Supplier<List<RosterStore.Entry>> onlinePlayers;
    private final Predicate<UUID> optedOut;
    private final SweepState sweepState;
    private final Supplier<String> fingerprint;
    private final LongSupplier millis;
    private final LongSupplier nanos;
    private final CommandPatterns payPatterns;
    private final CommandPatterns adminPatterns;
    private final long startMillis;

    private record Delayed(long due, long seq, Runnable run) {
    }

    private final PriorityQueue<Delayed> delayed = new PriorityQueue<>(
            (a, b) -> a.due() != b.due() ? Long.compare(a.due(), b.due()) : Long.compare(a.seq(), b.seq()));
    private final ArrayDeque<Runnable> urgent = new ArrayDeque<>();
    private final ArrayDeque<Runnable> work = new ArrayDeque<>();
    private final Map<UUID, String> online = new HashMap<>();
    private final Map<UUID, Double> known = new HashMap<>();

    private long tick;
    private long seq;
    private long nextPollTick;
    private int pollSeconds;
    private int pollRemaining;
    /** Who has a command waiting for its second balance read: at most one command per player. */
    private final Map<UUID, Pending> pendingActors = new HashMap<>();

    /**
     * A command waiting for its second balance read. Later commands of the same kind, by the same actor, on the same
     * target add to it instead of being dropped.
     */
    private static final class Pending {
        final boolean transfer;
        final UUID target;
        final String action;
        double amount;
        int merges;
        long dueTick;
        final long firstTick;

        Pending(boolean transfer, UUID target, String action, double amount, long dueTick) {
            this.transfer = transfer;
            this.target = target;
            this.action = action;
            this.amount = amount;
            this.dueTick = dueTick;
            this.firstTick = dueTick - CONFIRM_DELAY_TICKS;
        }
    }
    /** Time spent in command handlers since the last tick; it counts toward the next tick's budget. */
    private long carriedNanos;
    private long lastErrorLogMillis;

    private boolean slow;
    private boolean slowWarned;
    private long windowNanos;
    private int windowCalls;

    private boolean sweepActive;
    private boolean sweepLoaded;
    private ArrayDeque<UUID> sweepQueue = new ArrayDeque<>();
    private int sweepGeneration;
    private long lastSweepSeconds;
    private String loadedFingerprint;

    private Info currentInfo;

    private volatile Info publishedInfo;
    private volatile int infoVersion;
    private volatile boolean everOffered;
    private volatile boolean resetRequested;
    private volatile int tracked;
    private volatile SweepIds listedIds;

    /** The players the async listing found, with the sweep it was started for. */
    private record SweepIds(int generation, List<UUID> ids) {
    }

    public EconomyTracker(Logger logger, Scheduler scheduler, EconomyHost host, EconomyConfig config,
            EconomyBuffer buffer, Supplier<List<RosterStore.Entry>> onlinePlayers, Predicate<UUID> optedOut,
            SweepState sweepState, Supplier<String> fingerprint) {
        this(logger, scheduler, host, config, buffer, onlinePlayers, optedOut, sweepState, fingerprint,
                System::currentTimeMillis, System::nanoTime);
    }

    EconomyTracker(Logger logger, Scheduler scheduler, EconomyHost host, EconomyConfig config,
            EconomyBuffer buffer, Supplier<List<RosterStore.Entry>> onlinePlayers, Predicate<UUID> optedOut,
            SweepState sweepState, Supplier<String> fingerprint, LongSupplier millis, LongSupplier nanos) {
        this.logger = logger;
        this.scheduler = scheduler;
        this.host = host;
        this.config = config;
        this.buffer = buffer;
        this.onlinePlayers = onlinePlayers;
        this.optedOut = optedOut;
        this.sweepState = sweepState;
        this.fingerprint = fingerprint;
        this.millis = millis;
        this.nanos = nanos;
        this.startMillis = millis.getAsLong();
        this.pollSeconds = config.pollSeconds();
        List<String> rejected = new java.util.ArrayList<>();
        this.payPatterns = CommandPatterns.compile(config.payCommands(), false, rejected);
        this.adminPatterns = CommandPatterns.compile(config.adminCommands(), true, rejected);
        if (!rejected.isEmpty()) {
            logger.warning("Detraly ignored " + rejected.size()
                    + " economy command pattern(s) in config.yml that are not valid.");
        }
    }

    // ---- used from other threads ----

    public EconomyBuffer buffer() {
        return buffer;
    }

    /** The provider as last seen on the main thread, or null. */
    public Info info() {
        return publishedInfo;
    }

    /** Changes whenever {@link #info()} changes, so the sender knows to send it again. */
    public int infoVersion() {
        return infoVersion;
    }

    /**
     * Called on every {@code ready} from the API. When it offers the economy feature, start collecting. When it
     * does not (any more), stop collecting and drop what is waiting, as {@link #reset()} does.
     */
    public void offered(boolean offered) {
        if (offered) {
            everOffered = true;
        } else {
            reset();
        }
    }

    /** The server was unlinked or its token rejected: forget everything waiting and stop collecting. */
    public void reset() {
        everOffered = false;
        resetRequested = true;
        buffer.clear();
    }

    /** One line for {@code /detraly status}. */
    public String status() {
        if (!config.enabled()) {
            return "off";
        }
        if (!everOffered) {
            return "waiting for the API";
        }
        Info info = publishedInfo;
        if (info == null) {
            return "not found";
        }
        String name = EconomyMessages.text(info.provider(), 64);
        return (name.isEmpty() ? "economy" : name) + ", " + tracked + " players tracked";
    }

    // ---- main thread ----

    /** Call once per game tick. */
    public void tick() {
        tick++;
        if (resetRequested) {
            resetRequested = false;
            clearWork();
            known.clear();
            tracked = 0;
        }
        if (tick % INFO_CHECK_TICKS == 1L) {
            refreshInfo();
        }
        if (!active()) {
            if (hasWork()) {
                clearWork();
            }
            return;
        }
        schedulePoll();
        if (tick % SWEEP_CHECK_TICKS == 0L) {
            maybeStartSweep();
        }
        while (!delayed.isEmpty() && delayed.peek().due() <= tick) {
            urgent.addLast(delayed.poll().run());
        }
        runBudget();
    }

    public void onJoin(UUID id, String name) {
        if (id == null) {
            return;
        }
        online.put(id, name == null ? "" : name);
        if (!active()) {
            return;
        }
        schedule(1L, () -> {
            if (online.containsKey(id)) {
                sample(id, online.get(id), EconomyEvent.REASON_JOIN);
            }
        });
    }

    public void onQuit(UUID id, String name) {
        if (id == null) {
            return;
        }
        String joinedAs = online.get(id);
        if (active() && joinedAs != null) {
            sample(id, name != null ? name : joinedAs, EconomyEvent.REASON_QUIT);
        }
        online.remove(id);
        this.known.remove(id);
        tracked = this.known.size();
    }

    /** A player ran a command. {@code line} is matched and dropped. */
    public void onPlayerCommand(UUID actor, String actorName, String line) {
        handleCommand(actor, actorName, line);
    }

    /** The console ran a command. Only admin commands are looked for. */
    public void onConsoleCommand(String line) {
        handleCommand(null, null, line);
    }

    // ---- commands ----

    private void handleCommand(UUID actor, String actorName, String line) {
        // A slow provider is not asked anything extra: the next poll still sees the balance change.
        if (!active() || line == null || slow) {
            return;
        }
        Pending pending = pendingActors.get(actor == null ? CONSOLE : actor);
        if (pending == null && pendingActors.size() >= MAX_PENDING_COMMANDS) {
            return;
        }
        long start = nanos.getAsLong();
        try {
            matchCommand(actor, actorName, line, pending);
        } finally {
            carriedNanos += Math.max(0L, nanos.getAsLong() - start);
        }
    }

    private void matchCommand(UUID actor, String actorName, String line, Pending pending) {
        if (actor != null && (!EconomyMessages.validName(actorName) || isOptedOut(actor))) {
            return;
        }
        if (actor != null) {
            CommandPatterns.Match pay = payPatterns.match(line);
            if (pay != null) {
                startTransfer(actor, actorName, pay, pending);
                return;
            }
        }
        CommandPatterns.Match admin = adminPatterns.match(line);
        if (admin != null) {
            startAdmin(actor, actorName, admin, pending);
        }
    }

    private void startTransfer(UUID payer, String payerName, CommandPatterns.Match m, Pending pending) {
        Known target = resolveTarget(m.target());
        if (target == null || target.uuid().equals(payer) || !EconomyMessages.validName(target.name())
                || isOptedOut(target.uuid())) {
            return;
        }
        UUID receiver = target.uuid();
        String receiverName = target.name();
        if (pending != null) {
            // A second command while one waits: no balance reads, it only adds to the pending one.
            if (pending.transfer && pending.target.equals(receiver)) {
                merge(pending, m.amount());
            }
            return;
        }
        OptionalDouble payerBefore = readBalance(payer);
        if (payerBefore.isEmpty()) {
            return;
        }
        OptionalDouble receiverBefore = readBalance(receiver);
        Pending mine = new Pending(true, receiver, null, m.amount(), tick + CONFIRM_DELAY_TICKS);
        pendingActors.put(payer, mine);
        scheduleConfirm(mine, () -> {
            pendingActors.remove(payer);
            double amount = mine.amount;
            OptionalDouble payerAfter = readBalance(payer);
            OptionalDouble receiverAfter = readBalance(receiver);
            if (transferConfirmed(amount, payerBefore, payerAfter, receiverBefore, receiverAfter)) {
                buffer.add(new EconomyEvent.Transfer(EconomyEvent.newEid(), nowSeconds(), payer.toString(), payerName,
                        receiver.toString(), receiverName, amount, m.label()));
            }
            emitAfter(payer, payerName, payerAfter);
            emitAfter(receiver, receiverName, receiverAfter);
        });
    }

    private void startAdmin(UUID actor, String actorName, CommandPatterns.Match m, Pending pending) {
        Known target = resolveTarget(m.target());
        if (target == null || !EconomyMessages.validName(target.name()) || isOptedOut(target.uuid())) {
            return;
        }
        UUID id = target.uuid();
        String name = target.name();
        if (pending != null) {
            // Only give and take add up; anything else while a command waits is ignored.
            if (!pending.transfer && pending.target.equals(id) && pending.action.equals(m.action())
                    && (CommandPatterns.ACTION_GIVE.equals(m.action())
                            || CommandPatterns.ACTION_TAKE.equals(m.action()))) {
                merge(pending, m.amount());
            }
            return;
        }
        OptionalDouble before = readBalance(id);
        if (before.isEmpty()) {
            return;
        }
        UUID key = actor == null ? CONSOLE : actor;
        Pending mine = new Pending(false, id, m.action(), m.amount(), tick + CONFIRM_DELAY_TICKS);
        pendingActors.put(key, mine);
        scheduleConfirm(mine, () -> {
            pendingActors.remove(key);
            OptionalDouble after = readBalance(id);
            OptionalDouble moved = after.isPresent()
                    ? adminAmount(m.action(), mine.amount, before.getAsDouble(), after.getAsDouble())
                    : OptionalDouble.empty();
            if (moved.isPresent()) {
                buffer.add(new EconomyEvent.Admin(EconomyEvent.newEid(), nowSeconds(), m.action(), id.toString(),
                        name, moved.getAsDouble(), m.label(), actor == null ? null : actor.toString(),
                        actor == null ? null : actorName));
            }
            emitAfter(id, name, after);
        });
    }

    private void merge(Pending pending, double amount) {
        double sum = pending.amount + amount;
        if (pending.merges >= MAX_MERGES || !EconomyMessages.validBalance(sum)) {
            return;
        }
        pending.merges++;
        pending.amount = sum;
        // The economy plugin must have run the latest command before the second read.
        // The window is capped, so a stream of commands cannot keep it open.
        pending.dueTick = Math.min(Math.max(pending.dueTick, tick + CONFIRM_DELAY_TICKS),
                pending.firstTick + MAX_MERGE_WINDOW_TICKS);
    }

    /** Runs {@code run} once the pending command is due; a merge in a later tick moves the due tick on. */
    private void scheduleConfirm(Pending pending, Runnable run) {
        schedule(Math.max(0L, pending.dueTick - tick), new Runnable() {
            @Override
            public void run() {
                if (pending.dueTick > tick) {
                    schedule(pending.dueTick - tick, this);
                    return;
                }
                run.run();
            }
        });
    }

    /**
     * A transfer is real when the receiver gained the amount (within a small tolerance) and the payer lost at least
     * the amount minus the tolerance (a tax may take more). When the receiver cannot be read, the payer must have
     * lost the amount within the tolerance.
     */
    static boolean transferConfirmed(double amount, OptionalDouble payerBefore, OptionalDouble payerAfter,
            OptionalDouble receiverBefore, OptionalDouble receiverAfter) {
        if (payerBefore.isEmpty() || payerAfter.isEmpty()) {
            return false;
        }
        double tolerance = Math.max(0.01, amount * 0.001);
        double lost = payerBefore.getAsDouble() - payerAfter.getAsDouble();
        if (receiverBefore.isPresent() && receiverAfter.isPresent()) {
            double gained = receiverAfter.getAsDouble() - receiverBefore.getAsDouble();
            return Math.abs(gained - amount) <= tolerance && lost >= amount - tolerance;
        }
        return Math.abs(lost - amount) <= tolerance;
    }

    /**
     * What an admin action reports, or empty when it is not confirmed. Give and take must move the balance by the
     * typed amount (summed when commands were merged) within the same tolerance as a transfer, in the right
     * direction. A take may move less when it emptied the balance (the economy cannot take more than there is), and
     * then the money that really moved is reported. Set and reset: any change confirms, and the typed amount is
     * reported.
     */
    static OptionalDouble adminAmount(String action, double amount, double before, double after) {
        double change = after - before;
        double tolerance = Math.max(0.01, amount * 0.001);
        switch (action) {
            case CommandPatterns.ACTION_GIVE:
                return Math.abs(change - amount) <= tolerance ? OptionalDouble.of(amount) : OptionalDouble.empty();
            case CommandPatterns.ACTION_TAKE:
                if (Math.abs(change + amount) <= tolerance) {
                    return OptionalDouble.of(amount);
                }
                if (after <= tolerance && change < 0 && -change <= amount + tolerance) {
                    return OptionalDouble.of(-change);
                }
                return OptionalDouble.empty();
            case CommandPatterns.ACTION_SET:
            case CommandPatterns.ACTION_RESET:
                return Math.abs(change) > 1e-9 ? OptionalDouble.of(amount) : OptionalDouble.empty();
            default:
                return OptionalDouble.empty();
        }
    }

    private void emitAfter(UUID id, String name, OptionalDouble balance) {
        if (balance.isEmpty()) {
            return;
        }
        emit(id, name, balance.getAsDouble(), EconomyEvent.REASON_AFTER);
        if (online.containsKey(id)) {
            known.put(id, balance.getAsDouble());
            tracked = known.size();
        }
    }

    // ---- samples ----

    private void sample(UUID id, String name, String reason) {
        if (isOptedOut(id)) {
            return;
        }
        OptionalDouble b = readBalance(id);
        if (b.isEmpty()) {
            return;
        }
        emit(id, name, b.getAsDouble(), reason);
        if (online.containsKey(id) && !EconomyEvent.REASON_QUIT.equals(reason)) {
            known.put(id, b.getAsDouble());
            tracked = known.size();
        }
    }

    private void pollOne(UUID id) {
        String name = online.get(id);
        if (name == null || isOptedOut(id)) {
            return;
        }
        OptionalDouble b = readBalance(id);
        if (b.isEmpty()) {
            return;
        }
        Double previous = known.get(id);
        if (previous == null || Double.compare(previous, b.getAsDouble()) != 0) {
            known.put(id, b.getAsDouble());
            emit(id, name, b.getAsDouble(), EconomyEvent.REASON_POLL);
        }
        tracked = known.size();
    }

    private void emit(UUID id, String name, double balance, String reason) {
        if (!EconomyMessages.validName(name) || !EconomyMessages.validBalance(balance) || isOptedOut(id)) {
            return;
        }
        buffer.add(new EconomyEvent.Balance(EconomyEvent.newEid(), nowSeconds(), id.toString(), name, balance,
                reason));
    }

    private void schedulePoll() {
        if (tick < nextPollTick || pollRemaining > 0) {
            return;
        }
        nextPollTick = tick + pollSeconds * 20L;
        List<RosterStore.Entry> players = safe("reading the online players", onlinePlayers, List.<RosterStore.Entry>of());
        for (RosterStore.Entry e : players) {
            UUID id = e.uuid();
            online.putIfAbsent(id, e.name());
            if (isOptedOut(id)) {
                continue;
            }
            pollRemaining++;
            work.addLast(() -> {
                pollRemaining--;
                pollOne(id);
            });
        }
    }

    // ---- sweep ----

    private void maybeStartSweep() {
        if (sweepActive || slow || config.sweepHours() <= 0 || buffer.backpressure()
                || millis.getAsLong() - startMillis < FIRST_SWEEP_DELAY_MILLIS) {
            return;
        }
        String fp = fingerprint == null ? null : safe("reading the token fingerprint", fingerprint, null);
        if (fp == null) {
            return;
        }
        if (!fp.equals(loadedFingerprint)) {
            loadedFingerprint = fp;
            lastSweepSeconds = sweepState == null ? 0L : sweepState.lastSweepSeconds(fp);
        }
        if (nowSeconds() - lastSweepSeconds < config.sweepHours() * 3600L) {
            return;
        }
        sweepActive = true;
        sweepLoaded = false;
        sweepQueue = new ArrayDeque<>();
        listedIds = null;
        // Listing the players touches the disk, so it runs off the main thread. Only ids come back: a name can
        // cost a file read, so names are looked up one player at a time inside the tick budget.
        int generation = ++sweepGeneration;
        try {
            scheduler.async(() -> {
                List<UUID> ids = safe("listing the known players", host::knownPlayerIds, List.<UUID>of());
                listedIds = new SweepIds(generation, ids == null ? List.of() : ids);
            });
        } catch (RuntimeException e) {
            sweepActive = false;
            logError("listing the known players", e);
        }
    }

    private boolean sweepStep() {
        if (!sweepActive || slow || buffer.backpressure()) {
            return false;
        }
        if (!sweepLoaded) {
            SweepIds listed = listedIds;
            if (listed == null || listed.generation() != sweepGeneration) {
                return false;
            }
            listedIds = null;
            sweepQueue = new ArrayDeque<>(listed.ids());
            sweepLoaded = true;
            return true;
        }
        UUID id = sweepQueue.pollFirst();
        if (id == null) {
            finishSweep();
            return true;
        }
        if (isOptedOut(id)) {
            return true;
        }
        // One player per step: the name lookup can read a file, and the balance read can be slow.
        Known k = safe("looking up a known player", () -> host.knownPlayer(id), null);
        if (k == null || !EconomyMessages.validName(k.name())) {
            return true;
        }
        OptionalDouble b = readBalance(id);
        if (b.isPresent()) {
            emit(id, k.name(), b.getAsDouble(), EconomyEvent.REASON_SWEEP);
        }
        return true;
    }

    private void finishSweep() {
        sweepActive = false;
        sweepLoaded = false;
        lastSweepSeconds = nowSeconds();
        SweepState state = sweepState;
        String fp = loadedFingerprint;
        long when = lastSweepSeconds;
        if (state != null && fp != null) {
            try {
                scheduler.async(() -> {
                    try {
                        state.save(when, fp);
                    } catch (IOException | RuntimeException e) {
                        Guard.report(logger, "saving the economy state", e);
                    }
                });
            } catch (RuntimeException e) {
                Guard.report(logger, "saving the economy state", e);
            }
        }
    }

    // ---- running the work ----

    private void runBudget() {
        // Time the command handlers used since the last tick counts against this tick.
        long start = nanos.getAsLong() - carriedNanos;
        carriedNanos = 0L;
        while (true) {
            Runnable unit = urgent.pollFirst();
            if (unit == null) {
                if (nanos.getAsLong() - start >= BUDGET_NANOS) {
                    return;
                }
                unit = work.pollFirst();
            }
            if (unit != null) {
                try {
                    unit.run();
                } catch (Throwable e) {
                    logError("the economy tracker", e);
                }
            } else if (!sweepStepSafe()) {
                return;
            }
            if (nanos.getAsLong() - start >= BUDGET_NANOS) {
                return;
            }
        }
    }

    private boolean sweepStepSafe() {
        try {
            return sweepStep();
        } catch (Throwable e) {
            logError("the economy sweep", e);
            sweepActive = false;
            return false;
        }
    }

    private void schedule(long delayTicks, Runnable run) {
        delayed.add(new Delayed(tick + delayTicks, seq++, run));
    }

    private boolean active() {
        return config.enabled() && everOffered && currentInfo != null;
    }

    private boolean hasWork() {
        return !delayed.isEmpty() || !urgent.isEmpty() || !work.isEmpty() || sweepActive;
    }

    private void clearWork() {
        delayed.clear();
        urgent.clear();
        work.clear();
        pollRemaining = 0;
        pendingActors.clear();
        listedIds = null;
        sweepGeneration++;
        sweepActive = false;
        sweepLoaded = false;
        sweepQueue = new ArrayDeque<>();
    }

    private void refreshInfo() {
        Info now = safe("reading the economy provider", host::info, currentInfo);
        if (Objects.equals(now, currentInfo)) {
            return;
        }
        currentInfo = now;
        known.clear();
        tracked = 0;
        clearWork();
        nextPollTick = 0L;
        publishedInfo = now;
        infoVersion++;
    }

    // ---- provider calls ----

    private boolean isOptedOut(UUID id) {
        try {
            return optedOut.test(id);
        } catch (Throwable e) {
            return true;
        }
    }

    private Known resolveTarget(String name) {
        Known k = safe("looking up a player", () -> host.resolve(name), null);
        return k == null || k.uuid() == null ? null : k;
    }

    private OptionalDouble readBalance(UUID id) {
        boolean checkAccount = !online.containsKey(id);
        long start = nanos.getAsLong();
        OptionalDouble result = safe("reading a balance", () -> host.balance(id, checkAccount),
                OptionalDouble.empty());
        recordCall(nanos.getAsLong() - start);
        if (result == null || result.isEmpty() || !EconomyMessages.validBalance(result.getAsDouble())) {
            return OptionalDouble.empty();
        }
        return result;
    }

    private <T> T safe(String what, Supplier<T> action, T fallback) {
        try {
            return action.get();
        } catch (Throwable e) {
            logError(what, e);
            return fallback;
        }
    }

    private void logError(String what, Throwable e) {
        long now = millis.getAsLong();
        if (lastErrorLogMillis != 0L && now - lastErrorLogMillis < ERROR_LOG_INTERVAL_MILLIS) {
            return;
        }
        lastErrorLogMillis = now;
        Guard.report(logger, what, e);
    }

    private void recordCall(long elapsedNanos) {
        windowNanos += Math.max(0L, elapsedNanos);
        if (++windowCalls < SLOW_WINDOW_CALLS) {
            return;
        }
        double averageMillis = windowNanos / (double) windowCalls / 1_000_000.0;
        windowNanos = 0L;
        windowCalls = 0;
        if (averageMillis > SLOW_AVERAGE_MILLIS) {
            slow = true;
            int ceiling = Math.max(SLOW_MAX_POLL_SECONDS, config.pollSeconds());
            pollSeconds = Math.min(ceiling, pollSeconds * 2);
            if (!slowWarned) {
                slowWarned = true;
                logger.warning("The economy provider is slow (balance reads take over "
                        + (int) SLOW_AVERAGE_MILLIS + " ms on average), so Detraly reads balances less often "
                        + "and pauses its full sweep. The server is not affected.");
            }
        } else if (slow && averageMillis <= RECOVERED_AVERAGE_MILLIS) {
            pollSeconds = Math.max(config.pollSeconds(), pollSeconds / 2);
            if (pollSeconds == config.pollSeconds()) {
                slow = false;
            }
        }
    }

    private long nowSeconds() {
        return millis.getAsLong() / 1000L;
    }

    // ---- for checks ----

    boolean slow() {
        return slow;
    }

    int pollSeconds() {
        return pollSeconds;
    }

    int pendingCommands() {
        return pendingActors.size();
    }
}

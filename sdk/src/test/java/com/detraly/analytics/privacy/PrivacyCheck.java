package com.detraly.analytics.privacy;

import com.detraly.analytics.api.PrivacyApi;
import com.detraly.analytics.api.PrivacyApi.Result;
import com.detraly.analytics.command.CommandActor;
import com.detraly.analytics.command.Segment;
import com.detraly.analytics.platform.Scheduler;
import com.detraly.analytics.storage.TokenStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

public final class PrivacyCheck {
    private static final String TOKEN = "dtl_" + "A".repeat(43);
    private static int failures;

    private static final class FakeScheduler implements Scheduler {
        final Deque<Runnable> async = new ArrayDeque<>();
        final List<long[]> laterDelays = new ArrayList<>();
        final Deque<Runnable> later = new ArrayDeque<>();

        @Override public Task timer(Runnable task, long delayTicks, long periodTicks) { return () -> { }; }
        @Override public void later(Runnable task, long delayTicks) {
            laterDelays.add(new long[] {delayTicks});
            later.add(task);
        }
        @Override public void async(Runnable task) { async.add(task); }
        @Override public void sync(Runnable task) { task.run(); }

        void runAsync() {
            while (!async.isEmpty()) {
                async.poll().run();
            }
        }

        void runLater() {
            Runnable r = later.poll();
            if (r != null) {
                r.run();
            }
        }
    }

    private static final class FakeApi implements PrivacyApi {
        final Deque<Result> answers = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();
        Result fallback = Result.OK;

        @Override public Result setOptedOut(String token, UUID uuid, boolean optedOut) {
            calls.add(uuid + ":" + optedOut + ":" + token.equals(TOKEN));
            return answers.isEmpty() ? fallback : answers.poll();
        }
    }

    private static final class Actor implements CommandActor {
        final UUID id;
        final List<String> messages = new ArrayList<>();

        Actor(UUID id) { this.id = id; }

        @Override public boolean isConsole() { return false; }
        @Override public UUID playerId() { return id; }
        @Override public boolean isAdmin() { return false; }
        @Override public void send(Segment... parts) {
            StringBuilder sb = new StringBuilder();
            for (Segment s : parts) {
                sb.append(s.text());
            }
            messages.add(sb.toString());
        }
        String last() { return messages.isEmpty() ? "" : messages.get(messages.size() - 1); }
    }

    public static void main(String[] args) throws Exception {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        Path dir = Files.createTempDirectory("privacycheck");
        File folder = dir.toFile();
        Path file = dir.resolve("optouts.yml");

        storeChecks(logger, folder, file);
        serviceChecks(logger, folder);

        Files.deleteIfExists(file);
        Files.deleteIfExists(dir);
        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("PrivacyCheck OK");
    }

    private static void storeChecks(Logger logger, File folder, Path file) throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        Files.write(file, ("opted-out:\n- " + a + "\n- " + b + "\n").getBytes(StandardCharsets.UTF_8));
        OptOutStore old = new OptOutStore(folder, logger);
        old.load();
        expect(old.isOptedOut(a) && old.isOptedOut(b), "old file entries are opted out");
        expect(old.isPendingSync(a) && old.isPendingSync(b), "old file entries need one sync");

        Files.write(file, ("opted-out: [" + a + ", '" + b + "']\n").getBytes(StandardCharsets.UTF_8));
        old.load();
        expect(old.isOptedOut(a) && old.isOptedOut(b) && old.isPendingSync(a), "old inline list loads");

        old.markSynced(a);
        expect(!old.isPendingSync(a) && old.isPendingSync(b) && old.isOptedOut(a), "markSynced clears one flag");

        OptOutStore again = new OptOutStore(folder, logger);
        again.load();
        expect(again.isOptedOut(a) && again.isOptedOut(b), "saved file keeps both opted out");
        expect(!again.isPendingSync(a) && again.isPendingSync(b), "pending flags persist");
        String text = Files.readString(file);
        expect(text.contains("opted-out:") && text.contains("pending-sync:"), "file has both keys");
        expect(text.replace("opted-out:", "").replace("pending-sync:", "").replace("-", "")
                .replaceAll("[0-9a-f\\s]", "").isEmpty(), "file holds UUIDs only");

        again.optIn(b);
        OptOutStore third = new OptOutStore(folder, logger);
        third.load();
        expect(!third.isOptedOut(b) && !third.isPendingSync(b) && third.isOptedOut(a), "opt-in removes both");

        third.optOut(b);
        expect(third.isPendingSync(b), "new opt-out is pending");
        third.markSynced(a);
        third.optIn(a);
        third.markSynced(a);
        expect(!third.isOptedOut(a) && !third.isPendingSync(a), "markSynced after opt-in does nothing");
        expect(!third.isOptedOut(null), "null is not opted out");
    }

    private static TokenStore linkedTokens(File folder) {
        TokenStore tokens = new TokenStore(folder, Logger.getAnonymousLogger());
        tokens.save(TOKEN);
        return tokens;
    }

    private static void serviceChecks(Logger logger, File base) throws Exception {
        Path dir = Files.createTempDirectory("privacyservice");
        File folder = dir.toFile();
        TokenStore tokens = linkedTokens(folder);
        OptOutStore store = new OptOutStore(folder, logger);
        FakeScheduler sched = new FakeScheduler();
        FakeApi api = new FakeApi();
        PrivacyService svc = new PrivacyService(logger, sched, api, tokens, store);
        UUID id = UUID.randomUUID();
        Actor actor = new Actor(id);

        svc.optOut(actor);
        expect(store.isOptedOut(id), "opt-out is local before the API answers");
        expect(api.calls.isEmpty(), "API call is not made on the calling thread");
        sched.runAsync();
        expect(api.calls.size() == 1 && api.calls.get(0).equals(id + ":true:true"), "one opt-out call with token");
        expect(!store.isPendingSync(id), "synced after 200");
        expect(actor.last().contains("discards them from any other server") && !actor.last().contains("saved here"),
                "success message: " + actor.last());

        UUID id2 = UUID.randomUUID();
        Actor actor2 = new Actor(id2);
        api.answers.add(Result.NETWORK_ERROR);
        api.answers.add(Result.RETRY_LATER);
        svc.optOut(actor2);
        sched.runAsync();
        expect(store.isOptedOut(id2) && store.isPendingSync(id2), "failed opt-out stays pending");
        expect(actor2.last().contains("sync automatically"), "failure message: " + actor2.last());
        expect(sched.laterDelays.size() == 1, "retry scheduled");
        sched.runLater();
        sched.runAsync();
        expect(sched.laterDelays.size() == 2, "second retry scheduled with backoff");
        expect(sched.laterDelays.get(1)[0] > sched.laterDelays.get(0)[0], "backoff grows");
        sched.runLater();
        sched.runAsync();
        expect(!store.isPendingSync(id2), "retry succeeds and clears pending");
        expect(actor2.messages.size() == 1, "only one reply to the player");

        UUID id3 = UUID.randomUUID();
        api.fallback = Result.RETRY_LATER;
        svc.optOut(new Actor(id3));
        int guard = 0;
        while ((!sched.async.isEmpty() || !sched.later.isEmpty()) && guard++ < 20) {
            sched.runAsync();
            sched.runLater();
        }
        expect(guard < 20 && store.isPendingSync(id3), "retries are bounded and the flag stays");
        api.fallback = Result.OK;

        int before = api.calls.size();
        svc.syncOnJoin(UUID.randomUUID());
        sched.runAsync();
        expect(api.calls.size() == before, "join of a player not opted out sends nothing");
        svc.syncOnJoin(id3);
        svc.syncOnJoin(id3);
        sched.runAsync();
        expect(api.calls.size() == before + 1 && api.calls.get(before).equals(id3 + ":true:true"),
                "join of a pending player syncs once");
        expect(!store.isPendingSync(id3), "join sync clears pending");
        svc.syncOnJoin(id3);
        sched.runAsync();
        expect(api.calls.size() == before + 1, "synced player is not sent again on join");


        Actor inActor = new Actor(id3);
        api.answers.add(Result.FORBIDDEN);
        int c0 = api.calls.size();
        svc.optIn(inActor);
        sched.runAsync();
        expect(store.isOptedOut(id3), "unexpected 403 keeps the local opt-out");
        expect(api.calls.size() == c0 + 1 && api.calls.get(c0).equals(id3 + ":false:true"),
                "no recovery dance on 403: " + api.calls.subList(c0, api.calls.size()));
        expect(!inActor.last().contains("is removed") && inActor.last().contains("still opted out"),
                "failure message does not claim success: " + inActor.last());

        // opted out on another server only: no local entry, one opt-in call succeeds
        UUID remote = UUID.randomUUID();
        Actor remoteActor = new Actor(remote);
        svc.optIn(remoteActor);
        sched.runAsync();
        expect(remoteActor.last().contains("opted in") && !store.isOptedOut(remote),
                "opt-in without local entry: " + remoteActor.last());
        expect(remoteActor.last().contains("every server except servers where you ran /detraly optout yourself"),
                "opt-in copy describes the global effect: " + remoteActor.last());

        // opt-out typed while an opt-in is blocked in the API call: the opt-out must win
        UUID mid = UUID.randomUUID();
        Actor midActor = new Actor(mid);
        svc.optOut(midActor);
        sched.runAsync();
        int midBefore = midActor.messages.size();
        PrivacyApi realApi = api;
        final PrivacyService[] holder = new PrivacyService[1];
        final boolean[] injected = {false};
        PrivacyApi hook = (token, uuid, optedOut) -> {
            Result r = realApi.setOptedOut(token, uuid, optedOut);
            if (!optedOut && !injected[0]) {
                injected[0] = true;
                holder[0].optOut(midActor); // typed while the opt-in call is in flight
            }
            return r;
        };
        PrivacyService svc2 = new PrivacyService(logger, sched, hook, tokens, store);
        holder[0] = svc2;
        svc2.optIn(midActor);
        sched.runAsync();
        expect(store.isOptedOut(mid), "opt-out typed during an in-flight opt-in is not undone");
        expect(!store.isPendingSync(mid), "the newer opt-out synced to the API");
        expect(realApiLast(api, mid), "API saw opt-in then opt-out: " + api.calls);
        expect(midActor.messages.size() == midBefore + 1 && midActor.last().contains("opted out")
                && !midActor.last().contains("is removed"),
                "only the opt-out answers: " + midActor.messages);
        expect(svc2.slotCount() == 0, "idle slots are pruned: " + svc2.slotCount());

        // race: opt-out retry pending, then opt-in; the late retry must not send
        UUID racer = UUID.randomUUID();
        Actor racerActor = new Actor(racer);
        api.answers.add(Result.NETWORK_ERROR);
        svc.optOut(racerActor);
        sched.runAsync();
        expect(store.isPendingSync(racer) && !sched.later.isEmpty(), "retry pending before opt-in");
        svc.optIn(racerActor);
        sched.runAsync();
        expect(!store.isOptedOut(racer), "opt-in applied");
        int beforeLate = api.calls.size();
        while (!sched.later.isEmpty()) {
            sched.runLater();
            sched.runAsync();
        }
        expect(api.calls.size() == beforeLate, "late opt-out retry does not land after opt-in");

        // opt-in queued, then opt-out before it runs: the opt-in is dropped
        UUID quick = UUID.randomUUID();
        Actor quickActor = new Actor(quick);
        svc.optOut(quickActor);
        svc.optIn(quickActor);
        svc.optOut(quickActor);
        int beforeQuick = api.calls.size();
        sched.runAsync();
        expect(store.isOptedOut(quick), "newest command wins");
        expect(api.calls.size() == beforeQuick + 1 && api.calls.get(beforeQuick).equals(quick + ":true:true"),
                "only the newest command reaches the API: " + api.calls.subList(beforeQuick, api.calls.size()));

        // copy checks
        StringBuilder sb = new StringBuilder();
        for (Segment s : PrivacyService.optOutMessage(Result.OK)) {
            sb.append(s.text());
        }
        String out = sb.toString();
        expect(!out.contains("Nothing about you") && out.contains("console"), "opt-out copy does not overclaim: " + out);

        api.answers.add(Result.NETWORK_ERROR);
        svc.optIn(inActor);
        sched.runAsync();
        expect(store.isOptedOut(id3) && inActor.last().contains("Could not reach"),
                "network failure keeps opt-out: " + inActor.last());

        api.answers.add(Result.OK);
        svc.optIn(inActor);
        sched.runAsync();
        String call = api.calls.get(api.calls.size() - 1);
        expect(call.equals(id3 + ":false:true"), "opt-in call sends opted_out=false");
        expect(!store.isOptedOut(id3), "200 removes the local opt-out");
        expect(inActor.last().contains("opted in"), "success message: " + inActor.last());

        tokens.clear();
        svc.optIn(inActor);
        sched.runAsync();
        expect(inActor.last().contains("not linked"), "unlinked opt-in message: " + inActor.last());

        Files.deleteIfExists(dir.resolve("optouts.yml"));
        Files.deleteIfExists(dir.resolve("token.dat"));
        Files.deleteIfExists(dir);
    }

    private static boolean realApiLast(FakeApi api, UUID id) {
        int n = api.calls.size();
        return n >= 2 && api.calls.get(n - 2).equals(id + ":false:true") && api.calls.get(n - 1).equals(id + ":true:true");
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}

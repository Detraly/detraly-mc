package com.detraly.analytics.pulse;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class TickProfilerCheck {
    private static int failures;

    private static StackTraceElement f(String cls, String method) {
        return new StackTraceElement(cls, method, "X.java", 1);
    }

    private static String plugins(String cls) {
        if (cls.startsWith("com.example.worldedit.")) {
            return "WorldEdit";
        }
        if (cls.startsWith("com.acme.")) {
            return "Acme";
        }
        return null;
    }

    private static ProfileAttribution.Source classify(StackTraceElement... frames) {
        return ProfileAttribution.classify(frames, TickProfilerCheck::plugins);
    }

    private static void expect(String name, ProfileAttribution.Source got, String kind, String sourceName) {
        check(name + " -> " + got, got.kind().equals(kind) && got.name().equals(sourceName));
    }

    public static void main(String[] args) {

        check("idle park", classify(f("jdk.internal.misc.Unsafe", "park"),
                f("java.util.concurrent.locks.LockSupport", "parkNanos"),
                f("net.minecraft.server.MinecraftServer", "waitUntilNextTick"),
                f("net.minecraft.server.MinecraftServer", "runServer")) == ProfileAttribution.IDLE);
        check("idle sleep", classify(f("java.lang.Thread", "sleep0"), f("java.lang.Thread", "sleep"),
                f("net.minecraft.server.MinecraftServer", "runServer")) == ProfileAttribution.IDLE);
        check("idle wait", classify(f("java.lang.Object", "wait0"), f("java.lang.Object", "wait"))
                == ProfileAttribution.IDLE);
        check("idle managedBlock", classify(f("java.util.concurrent.locks.LockSupport", "parkNanos"),
                f("net.minecraft.util.thread.BlockableEventLoop", "managedBlock"),
                f("net.minecraft.server.MinecraftServer", "waitUntilNextTick")) == ProfileAttribution.IDLE);
        check("empty stack idle", classify() == ProfileAttribution.IDLE);

        expect("task inside managedBlock", classify(f("net.minecraft.world.entity.Mob", "tick"),
                f("net.minecraft.server.MinecraftServer", "managedBlock"),
                f("net.minecraft.server.MinecraftServer", "waitUntilNextTick")), "server", "entities");

        expect("plugin sleeping", classify(f("java.lang.Thread", "sleep"),
                f("com.example.worldedit.Copy", "run"), f("org.bukkit.craftbukkit.Task", "run")),
                "plugin", "WorldEdit");

        expect("plugin innermost", classify(f("net.minecraft.world.entity.Entity", "getX"),
                f("com.acme.Shop", "tick"), f("com.example.worldedit.Copy", "run")), "plugin", "Acme");
        expect("plugin over datapack", classify(f("com.acme.Shop", "tick"),
                f("net.minecraft.commands.functions.CommandFunction", "run")), "plugin", "Acme");

        expect("datapack", classify(f("net.minecraft.world.entity.Entity", "getX"),
                f("net.minecraft.commands.functions.PlainTextFunction", "instantiate"),
                f("net.minecraft.server.ServerFunctionManager", "tick")), "datapack", "functions");
        expect("datapack manager", classify(f("net.minecraft.server.ServerFunctionManager", "tick"),
                f("net.minecraft.server.MinecraftServer", "tickServer")), "datapack", "functions");

        expect("entities", classify(f("net.minecraft.world.entity.Mob", "tick"),
                f("net.minecraft.server.level.ServerLevel", "tick")), "server", "entities");
        expect("block entities", classify(f("net.minecraft.world.level.block.entity.HopperBlockEntity", "tick"),
                f("net.minecraft.server.level.ServerLevel", "tick")), "server", "block_entities");
        expect("chunks map", classify(f("net.minecraft.server.level.ChunkMap", "tick")), "server", "chunks");
        expect("chunks cache", classify(f("net.minecraft.server.level.ServerChunkCache", "tick")), "server", "chunks");
        expect("chunks package", classify(f("net.minecraft.world.level.chunk.LevelChunk", "x")), "server", "chunks");
        expect("network", classify(f("net.minecraft.network.Connection", "tick")), "server", "network");
        expect("packet listener", classify(f("net.minecraft.server.network.ServerGamePacketListenerImpl", "tick")),
                "server", "network");
        expect("world", classify(f("net.minecraft.world.level.Level", "tickBlockEntities")), "server", "world");
        expect("server level", classify(f("net.minecraft.server.level.ServerLevel", "tick")), "server", "world");
        expect("innermost area", classify(f("net.minecraft.network.Connection", "tick"),
                f("net.minecraft.world.entity.Mob", "tick")), "server", "network");

        expect("other", classify(f("net.minecraft.server.MinecraftServer", "tickServer"),
                f("net.minecraft.server.MinecraftServer", "runServer")), "server", "other");
        expect("jdk only", classify(f("java.util.HashMap", "get")), "server", "other");

        Set<String> asked = new HashSet<>();
        ProfileAttribution.classify(new StackTraceElement[] {f("java.util.HashMap", "get"),
            f("net.minecraft.world.entity.Mob", "tick"), f("io.netty.channel.X", "y"),
            f("org.bukkit.craftbukkit.Z", "z"), f("com.acme.Shop", "tick")}, cls -> {
                asked.add(cls);
                return null;
            });
        check("only plugin-shaped classes asked: " + asked, asked.equals(Set.of("com.acme.Shop")));

        messageChecks();

        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("TickProfilerCheck: all passed");
    }

    private static void messageChecks() {
        Map<ProfileAttribution.Source, Long> counts = new HashMap<>();
        counts.put(new ProfileAttribution.Source("plugin", "WorldEdit"), 120L);
        counts.put(new ProfileAttribution.Source("server", "entities"), 200L);
        counts.put(new ProfileAttribution.Source("datapack", "functions"), 10L);
        counts.put(new ProfileAttribution.Source("server", "other"), 100L);
        JSONObject m = build(60L, 1200L, 430L, counts);
        check("type", "profile".equals(m.getString("type")));
        check("window", m.getInt("window_seconds") == 60);
        check("samples", m.getInt("samples") == 1200 && m.getInt("busy_samples") == 430);
        JSONArray sources = m.getJSONArray("sources");
        check("4 sources", sources.length() == 4);
        check("sorted by samples", sources.getJSONObject(0).getString("name").equals("entities"));
        validate("simple", m);

        check("no samples -> null", build(60L, 0L, 0L, counts) == null);

        JSONObject clamped = build(3L, 500L, 900L, new HashMap<>());
        check("window min 10", clamped.getInt("window_seconds") == 10);
        check("busy <= samples", clamped.getInt("busy_samples") == 500);
        check("empty sources", clamped.getJSONArray("sources").length() == 0);
        check("window max 600", build(9999L, 5L, 5L, new HashMap<>()).getInt("window_seconds") == 600);

        Map<ProfileAttribution.Source, Long> many = new HashMap<>();
        long busy = 0L;
        for (int i = 0; i < 50; i++) {
            many.put(new ProfileAttribution.Source("plugin", "P" + i), 100L + i);
            busy += 100L + i;
        }
        many.put(new ProfileAttribution.Source("other", "other"), 7L);
        busy += 7L;
        JSONObject folded = build(60L, busy + 1000L, busy, many);
        validate("many", folded);
        JSONArray fs = folded.getJSONArray("sources");
        check("32 sources", fs.length() == 32);
        JSONObject last = fs.getJSONObject(31);
        check("other/other last", last.getString("kind").equals("other") && last.getString("name").equals("other"));
        long folded19 = 0L;
        for (int i = 0; i < 19; i++) {
            folded19 += 100L + i;
        }
        check("folded sum " + last.getLong("samples"), last.getLong("samples") == folded19 + 7L);
        check("sum equals busy", sum(fs) == busy);

        Map<ProfileAttribution.Source, Long> longNames = new HashMap<>();
        long busy2 = 0L;
        for (int i = 0; i < 40; i++) {
            longNames.put(new ProfileAttribution.Source("plugin", "N" + i + "x".repeat(120)), 50L + i);
            busy2 += 50L + i;
        }
        JSONObject fit = build(60L, busy2 * 2, busy2, longNames);
        check("fits 4096", fit.toString().getBytes(StandardCharsets.UTF_8).length <= 4096);
        validate("long names", fit);
        check("long names sum equals busy", sum(fit.getJSONArray("sources")) == busy2);

        Map<ProfileAttribution.Source, Long> over = new HashMap<>();
        over.put(new ProfileAttribution.Source("plugin", "A"), 90L);
        over.put(new ProfileAttribution.Source("plugin", "B"), 80L);
        validate("over", build(60L, 100L, 100L, over));

        spikeChecks();
    }

    private static JSONObject build(long window, long samples, long busy, Map<ProfileAttribution.Source, Long> counts) {
        Map<ProfileAttribution.Source, ProfileMessage.Count> mapped = new HashMap<>();
        counts.forEach((k, v) -> mapped.put(k, new ProfileMessage.Count(v, 0L)));
        return ProfileMessage.build(window, samples, busy, null, mapped);
    }

    private static ProfileMessage.Count c(long samples, long spikes) {
        return new ProfileMessage.Count(samples, spikes);
    }

    private static void spikeChecks() {

        Map<ProfileAttribution.Source, ProfileMessage.Count> m = new HashMap<>();
        m.put(new ProfileAttribution.Source("plugin", "Slow"), c(40L, 30L));
        m.put(new ProfileAttribution.Source("datapack", "functions"), c(60L, 5L));
        JSONObject none = ProfileMessage.build(60L, 200L, 100L, null, m);
        check("no spike field at top", !none.has("spike_samples"));
        check("no spike field in sources", !none.getJSONArray("sources").getJSONObject(0).has("spike_samples"));
        validate("no spikes", none);

        JSONObject with = ProfileMessage.build(60L, 200L, 100L, 35L, m);
        validate("spikes", with);
        check("top spike", with.getLong("spike_samples") == 35L);
        JSONArray ss = with.getJSONArray("sources");
        for (int i = 0; i < ss.length(); i++) {
            JSONObject s = ss.getJSONObject(i);
            long want = s.getString("name").equals("Slow") ? 30L : 5L;
            check("spikes of " + s.getString("name"), s.getLong("spike_samples") == want);
        }

        JSONObject zero = ProfileMessage.build(60L, 200L, 100L, 0L, m);
        validate("zero spikes", zero);
        check("zero spike field", zero.getLong("spike_samples") == 0L);
        check("zero source spike", zero.getJSONArray("sources").getJSONObject(0).getLong("spike_samples") == 0L);

        Map<ProfileAttribution.Source, ProfileMessage.Count> odd = new HashMap<>();
        odd.put(new ProfileAttribution.Source("plugin", "A"), c(50L, 90L));
        odd.put(new ProfileAttribution.Source("plugin", "B"), c(50L, 40L));
        validate("odd spikes", ProfileMessage.build(60L, 200L, 100L, 60L, odd));
        validate("top over busy", ProfileMessage.build(60L, 200L, 100L, 5000L, odd));

        Map<ProfileAttribution.Source, ProfileMessage.Count> many = new HashMap<>();
        long busy = 0L;
        long spikes = 0L;
        for (int i = 0; i < 50; i++) {
            many.put(new ProfileAttribution.Source("plugin", "P" + i), c(100L + i, 10L));
            busy += 100L + i;
            spikes += 10L;
        }
        many.put(new ProfileAttribution.Source("other", "other"), c(7L, 3L));
        busy += 7L;
        spikes += 3L;
        JSONObject folded = ProfileMessage.build(60L, busy + 500L, busy, spikes, many);
        validate("folded spikes", folded);
        JSONArray fs = folded.getJSONArray("sources");
        JSONObject last = fs.getJSONObject(fs.length() - 1);
        check("folded is other/other", last.getString("kind").equals("other"));
        check("folded spikes sum " + last.getLong("spike_samples"), last.getLong("spike_samples") == 19L * 10L + 3L);
        long total = 0L;
        for (int i = 0; i < fs.length(); i++) {
            total += fs.getJSONObject(i).getLong("spike_samples");
        }
        check("all spikes kept", total == spikes && folded.getLong("spike_samples") == spikes);

        Map<ProfileAttribution.Source, ProfileMessage.Count> longNames = new HashMap<>();
        long busy2 = 0L;
        for (int i = 0; i < 40; i++) {
            longNames.put(new ProfileAttribution.Source("plugin", "N" + i + "x".repeat(120)), c(50L + i, 2L));
            busy2 += 50L + i;
        }
        JSONObject fit = ProfileMessage.build(60L, busy2 * 2, busy2, 80L, longNames);
        validate("long names spikes", fit);
        JSONArray fits = fit.getJSONArray("sources");
        long fitTotal = 0L;
        for (int i = 0; i < fits.length(); i++) {
            fitTotal += fits.getJSONObject(i).getLong("spike_samples");
        }
        check("long names keep spikes", fitTotal == 80L);
    }

    private static long sum(JSONArray sources) {
        long total = 0L;
        for (int i = 0; i < sources.length(); i++) {
            total += sources.getJSONObject(i).getLong("samples");
        }
        return total;
    }

    private static void validate(String label, JSONObject m) {
        long samples = m.getLong("samples");
        long busy = m.getLong("busy_samples");
        check(label + " samples range", samples >= 1L && samples <= 1_000_000L);
        check(label + " busy range", busy >= 0L && busy <= samples);
        check(label + " window range", m.getInt("window_seconds") >= 10 && m.getInt("window_seconds") <= 600);
        boolean spikes = m.has("spike_samples");
        Set<String> keys = new HashSet<>(Set.of("type", "window_seconds", "samples", "busy_samples", "sources"));
        if (spikes) {
            keys.add("spike_samples");
            check(label + " spike range", m.getLong("spike_samples") >= 0L && m.getLong("spike_samples") <= busy);
        }
        check(label + " keys", m.keySet().equals(keys));
        JSONArray sources = m.getJSONArray("sources");
        check(label + " <= 32 sources", sources.length() <= 32);
        Set<String> seen = new HashSet<>();
        long total = 0L;
        long spikeSum = 0L;
        Set<String> serverNames = Set.of("entities", "block_entities", "chunks", "network", "world", "other");
        for (int i = 0; i < sources.length(); i++) {
            JSONObject s = sources.getJSONObject(i);
            String kind = s.getString("kind");
            String name = s.getString("name");
            long n = s.getLong("samples");
            Set<String> sourceKeys = new HashSet<>(Set.of("kind", "name", "samples"));
            if (spikes) {
                sourceKeys.add("spike_samples");
                long sp = s.getLong("spike_samples");
                check(label + " source spikes range", sp >= 0L && sp <= n);
                spikeSum += sp;
            }
            check(label + " source keys", s.keySet().equals(sourceKeys));
            check(label + " kind", Set.of("plugin", "server", "datapack", "other").contains(kind));
            check(label + " name length", name.length() >= 1 && name.length() <= 128);
            check(label + " samples <= busy", n >= 0L && n <= busy);
            check(label + " unique", seen.add(kind + "/" + name));
            if (kind.equals("server")) {
                check(label + " server name", serverNames.contains(name));
            }
            if (kind.equals("datapack")) {
                check(label + " datapack name", name.equals("functions"));
            }
            if (kind.equals("other")) {
                check(label + " other name", name.equals("other"));
            }
            total += n;
        }
        check(label + " sum <= busy", total <= busy);
        check(label + " spike sum <= top", !spikes || spikeSum <= m.getLong("spike_samples"));
        check(label + " size", m.toString().getBytes(StandardCharsets.UTF_8).length <= 4096);
    }

    private static void check(String name, boolean ok) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + name);
        }
    }
}

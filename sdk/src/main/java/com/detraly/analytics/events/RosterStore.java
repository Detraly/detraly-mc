package com.detraly.analytics.events;

import com.detraly.analytics.model.JoinLeaveEvent;
import com.detraly.analytics.storage.AtomicFiles;
import com.detraly.analytics.storage.JsonDepth;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class RosterStore {
    public static final String FILE_NAME = "roster.json";
    public static final int MAX_PLAYERS = 10_000;
    public static final long MAX_BYTES = 2L * 1024 * 1024;
    public static final long MAX_AGE_SECONDS = 23L * 60L * 60L;
    private static final long WRITE_WARN_INTERVAL_MILLIS = 10L * 60L * 1000L;

    private static final int VERSION = 1;
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    public record Entry(UUID uuid, String name) {
    }

    public record Snapshot(long timestampSeconds, boolean clean, List<Entry> players, boolean leavesSent) {
        public Snapshot(long timestampSeconds, boolean clean, List<Entry> players) {
            this(timestampSeconds, clean, players, false);
        }
    }

    private final File dataFolder;
    private final Logger logger;
    private boolean closed;
    private long lastWriteWarnMillis;
    private boolean writeWarned;

    public RosterStore(File dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
    }

    public synchronized boolean write(List<Entry> players, long nowSeconds, String fingerprint) {

        if (closed) {
            return false;
        }
        return store(false, false, nowSeconds, players, fingerprint);
    }

    public synchronized boolean markClean(long nowSeconds, String fingerprint) {
        closed = true;
        return store(true, false, nowSeconds, List.of(), fingerprint);
    }

    public synchronized boolean closeUnclean(List<Entry> players, long nowSeconds, String fingerprint,
            boolean leavesSent) {
        closed = true;
        return store(false, leavesSent, nowSeconds, players, fingerprint);
    }

    private boolean store(boolean clean, boolean leavesSent, long nowSeconds, List<Entry> players, String fingerprint) {
        try {
            JSONArray list = new JSONArray();
            if (players != null) {
                for (Entry entry : players) {
                    if (list.length() >= MAX_PLAYERS) {
                        break;
                    }
                    if (!validEntry(entry)) {
                        continue;
                    }
                    JSONObject o = new JSONObject();
                    o.put("uuid", entry.uuid().toString().toLowerCase(Locale.ROOT));
                    o.put("name", entry.name());
                    list.put(o);
                }
            }
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            if (fingerprint != null) {
                root.put("fp", fingerprint);
            }
            root.put("clean", clean);
            root.put("leaves_sent", leavesSent);
            root.put("timestamp", nowSeconds);
            root.put("players", list);
            AtomicFiles.write(path(), root.toString().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException | RuntimeException e) {
            warnWriteFailed();
            return false;
        }
    }

    public synchronized Optional<Snapshot> read(String fingerprint) {
        try {
            Path file = path();
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) {
                return unusable();
            }
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (JsonDepth.exceeds(text, JsonDepth.MAX_DEPTH)) {
                return unusable();
            }
            JSONObject root = new JSONObject(text);
            Object version = root.opt("version");
            if (!isWhole(version) || ((Number) version).longValue() != VERSION) {
                return unusable();
            }
            if (fingerprint == null || !fingerprint.equals(root.opt("fp"))) {
                logger.info("Ignored a saved Detraly roster that belongs to a different link.");
                return Optional.empty();
            }

            Object clean = root.opt("clean");
            if (!(clean instanceof Boolean)) {
                return unusable();
            }
            Object ts = root.opt("timestamp");
            if (!isWhole(ts)) {
                return unusable();
            }
            JSONArray array = root.optJSONArray("players");
            if (array == null) {
                return unusable();
            }
            List<Entry> out = new ArrayList<>();
            for (int i = 0; i < array.length() && out.size() < MAX_PLAYERS; i++) {
                JSONObject o = array.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                Object u = o.opt("uuid");
                Object n = o.opt("name");
                if (!(u instanceof String) || !(n instanceof String)) {
                    continue;
                }
                UUID uuid;
                try {
                    uuid = UUID.fromString((String) u);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                Entry entry = new Entry(uuid, (String) n);
                if (validEntry(entry)) {
                    out.add(entry);
                }
            }
            return Optional.of(new Snapshot(((Number) ts).longValue(), (Boolean) clean, List.copyOf(out),
                    Boolean.TRUE.equals(root.opt("leaves_sent"))));
        } catch (IOException | RuntimeException | StackOverflowError e) {

            return unusable();
        }
    }

    private static boolean isWhole(Object o) {
        return o instanceof Integer || o instanceof Long;
    }

    private Optional<Snapshot> unusable() {
        logger.warning("The saved Detraly roster file is not usable and was ignored.");
        return Optional.empty();
    }

    public static List<JoinLeaveEvent> crashLeaves(Snapshot snapshot, Set<UUID> onlineNow, long nowSeconds) {
        if (snapshot == null || snapshot.clean() || snapshot.players() == null
                || snapshot.timestampSeconds() < nowSeconds - MAX_AGE_SECONDS) {
            return List.of();
        }
        long stamp = Math.min(snapshot.timestampSeconds(), nowSeconds);
        Set<UUID> seen = new HashSet<>();
        List<JoinLeaveEvent> out = new ArrayList<>();
        for (Entry entry : snapshot.players()) {
            if (out.size() >= MAX_PLAYERS) {
                break;
            }
            if (!validEntry(entry) || !seen.add(entry.uuid())) {
                continue;
            }
            if (onlineNow != null && onlineNow.contains(entry.uuid())) {
                continue;
            }
            out.add(new JoinLeaveEvent(JoinLeaveEvent.Type.LEAVE, entry.name(), entry.uuid(), stamp, null));
        }
        return List.copyOf(out);
    }

    private static boolean validEntry(Entry e) {
        return e != null && e.uuid() != null && e.name() != null && NAME_PATTERN.matcher(e.name()).matches();
    }

    private void warnWriteFailed() {
        long now = System.currentTimeMillis();
        if (writeWarned && now - lastWriteWarnMillis < WRITE_WARN_INTERVAL_MILLIS) {
            return;
        }
        writeWarned = true;
        lastWriteWarnMillis = now;
        logger.warning("Could not save the Detraly roster file.");
    }

    public void deleteLeftovers() {
        AtomicFiles.deleteLeftoverTemps(dataFolder == null ? null : dataFolder.toPath(), FILE_NAME);
    }

    private Path path() {
        if (dataFolder == null) {
            throw new IllegalStateException("no data folder");
        }
        return new File(dataFolder, FILE_NAME).toPath();
    }
}

package com.detraly.analytics.events;

import com.detraly.analytics.model.JoinLeaveEvent;
import com.detraly.analytics.storage.AtomicFiles;
import com.detraly.analytics.storage.JsonDepth;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

public final class Outbox {
    public static final String FILE_NAME = "outbox.json";
    public static final int MAX_EVENTS = 5000;
    public static final long MAX_BYTES = 2L * 1024 * 1024;
    public static final long MAX_AGE_SECONDS = 23L * 60L * 60L;
    public static final long MAX_FUTURE_SECONDS = 120L;

    private static final int VERSION = 1;
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final Pattern UUID_TEXT = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final long WRAPPER_BYTES = 96;

    private final File dataFolder;
    private final Logger logger;

    public Outbox(File dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
    }

    public synchronized boolean save(List<JoinLeaveEvent> events, long nowSeconds, String fingerprint) {
        try {
            Path file = path();
            List<JSONObject> entries = new ArrayList<>();
            int offered = 0;
            if (events != null) {
                for (JoinLeaveEvent event : events) {
                    offered++;
                    if (event != null && valid(event.type(), event.username(), event.uuid(),
                            event.timestampSeconds(), nowSeconds)) {
                        entries.add(encode(event));
                    }
                }
            }
            int start = Math.max(0, entries.size() - MAX_EVENTS);
            long[] sizes = new long[entries.size()];
            long total = WRAPPER_BYTES;
            for (int i = start; i < entries.size(); i++) {
                sizes[i] = entries.get(i).toString().getBytes(StandardCharsets.UTF_8).length + 1L;
                total += sizes[i];
            }
            while (start < entries.size() && total > MAX_BYTES) {
                total -= sizes[start++];
            }
            if (fingerprint == null || start >= entries.size()) {
                Files.deleteIfExists(file);
                return true;
            }
            JSONArray array = new JSONArray();
            for (int i = start; i < entries.size(); i++) {
                array.put(entries.get(i));
            }
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            root.put("fp", fingerprint);
            root.put("events", array);
            AtomicFiles.write(file, root.toString().getBytes(StandardCharsets.UTF_8));
            int left = offered - array.length();
            logger.info("Saved " + array.length() + " Detraly events for the next start"
                    + (left > 0 ? " (" + left + " too old, too new or over the limit were left out)." : "."));
            return true;
        } catch (IOException | RuntimeException e) {
            logger.warning("Could not save the pending Detraly events.");
            return false;
        }
    }

    public synchronized List<JoinLeaveEvent> load(long nowSeconds, String fingerprint) {
        List<JoinLeaveEvent> result = new ArrayList<>();
        try {
            Path file = path();
            if (!Files.exists(file)) {
                return result;
            }
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) {
                logger.warning("The saved Detraly event file could not be read; ignoring it.");
                return result;
            }
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            if (JsonDepth.exceeds(text, JsonDepth.MAX_DEPTH)) {
                logger.warning("The saved Detraly event file could not be read; ignoring it.");
                return result;
            }
            JSONObject root = new JSONObject(text);
            Object version = root.opt("version");
            if (!(version instanceof Number number) || !isWhole(number) || number.longValue() != VERSION) {
                logger.warning("The saved Detraly event file could not be read; ignoring it.");
                return result;
            }
            JSONArray array = root.getJSONArray("events");
            if (fingerprint == null || !fingerprint.equals(root.opt("fp"))) {
                logger.info("Ignored " + array.length() + " saved Detraly event(s) that belong to a different link.");
                return result;
            }
            int skipped = 0;
            for (int i = 0; i < array.length() && result.size() < MAX_EVENTS; i++) {
                JoinLeaveEvent event = decode(array.opt(i), nowSeconds);
                if (event == null) {
                    skipped++;
                } else {
                    result.add(event);
                }
            }
            if (!result.isEmpty() || skipped > 0) {
                logger.info("Restored " + result.size() + " Detraly events saved at the last shutdown"
                        + (skipped > 0 ? " (" + skipped + " skipped)." : "."));
            }
            return result;
        } catch (IOException | RuntimeException | StackOverflowError e) {
            logger.warning("The saved Detraly event file could not be read; ignoring it.");
            return new ArrayList<>();
        }
    }

    public void deleteLeftovers() {
        AtomicFiles.deleteLeftoverTemps(dataFolder == null ? null : dataFolder.toPath(), FILE_NAME);
    }

    private Path path() {
        return new File(dataFolder, FILE_NAME).toPath();
    }

    private static JSONObject encode(JoinLeaveEvent event) {
        JSONObject o = new JSONObject();
        o.put("type", event.type().wire());
        o.put("username", event.username());
        o.put("uuid", event.uuid().toString().toLowerCase(Locale.ROOT));
        o.put("timestamp", event.timestampSeconds());
        return o;
    }

    private static JoinLeaveEvent decode(Object item, long now) {
        if (!(item instanceof JSONObject o)) {
            return null;
        }
        Object type = o.opt("type");
        Object name = o.opt("username");
        Object uuidText = o.opt("uuid");
        Object ts = o.opt("timestamp");
        if (!(type instanceof String) || !(name instanceof String) || !(uuidText instanceof String)
                || !(ts instanceof Number number) || !isWhole(number)) {
            return null;
        }
        JoinLeaveEvent.Type t;
        if ("join".equals(type)) {
            t = JoinLeaveEvent.Type.JOIN;
        } else if ("leave".equals(type)) {
            t = JoinLeaveEvent.Type.LEAVE;
        } else {
            return null;
        }
        if (!UUID_TEXT.matcher((String) uuidText).matches()) {
            return null;
        }
        UUID uuid = UUID.fromString((String) uuidText);
        long stamp = number.longValue();
        if (!valid(t, (String) name, uuid, stamp, now)) {
            return null;
        }
        return new JoinLeaveEvent(t, (String) name, uuid, stamp, null);
    }

    private static boolean isWhole(Number n) {
        return n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte
                || (n instanceof BigInteger b && b.bitLength() < 64);
    }

    private static boolean valid(JoinLeaveEvent.Type type, String name, UUID uuid, long ts, long now) {
        if (type == null || uuid == null || name == null || !USERNAME.matcher(name).matches()) {
            return false;
        }
        long min = now < Long.MIN_VALUE + MAX_AGE_SECONDS ? Long.MIN_VALUE : now - MAX_AGE_SECONDS;
        long max = now > Long.MAX_VALUE - MAX_FUTURE_SECONDS ? Long.MAX_VALUE : now + MAX_FUTURE_SECONDS;
        return ts >= min && ts <= max;
    }
}

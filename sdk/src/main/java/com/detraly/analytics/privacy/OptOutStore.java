package com.detraly.analytics.privacy;

import com.detraly.analytics.storage.AtomicFiles;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Players who opted out of Detraly. Only UUIDs are stored. "pending-sync" lists the opted-out players whose
 * choice has not yet been confirmed by the Detraly API. A file with no "pending-sync" key (the older format)
 * is loaded with every entry pending, so each is synced once.
 */
public final class OptOutStore {
    private static final String FILE_NAME = "optouts.yml";
    private static final String KEY = "opted-out";
    private static final String PENDING_KEY = "pending-sync";

    private final File file;
    private final Logger logger;
    private final Set<UUID> optedOut = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final Object saveLock = new Object();

    public OptOutStore(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, FILE_NAME);
        this.logger = logger;
    }

    public void load() {
        optedOut.clear();
        pending.clear();
        if (!file.isFile()) {
            return;
        }
        boolean sawPendingKey = false;
        try {
            Set<UUID> target = optedOut;
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String value = line.trim();
                int hash = value.indexOf(" #");
                if (hash >= 0) {
                    value = value.substring(0, hash).trim();
                }
                if (value.startsWith(KEY + ":")) {
                    target = optedOut;
                    addInline(value.substring(KEY.length() + 1).trim(), target);
                } else if (value.startsWith(PENDING_KEY + ":")) {
                    target = pending;
                    sawPendingKey = true;
                    addInline(value.substring(PENDING_KEY.length() + 1).trim(), target);
                } else if (value.startsWith("-")) {
                    addUuid(value.substring(1).trim(), target);
                }
            }
        } catch (IOException | RuntimeException e) {
            optedOut.clear();
            pending.clear();
            logger.warning("Could not read " + FILE_NAME + "; starting with no opt-outs.");
            return;
        }
        if (!sawPendingKey) {
            pending.addAll(optedOut);
        }
        pending.retainAll(optedOut);
    }

    private static void addInline(String value, Set<UUID> target) {
        if (value.startsWith("[") && value.endsWith("]")) {
            for (String item : value.substring(1, value.length() - 1).split(",")) {
                addUuid(item.trim(), target);
            }
        }
    }

    private static void addUuid(String value, Set<UUID> target) {
        String text = value;
        if (text.length() >= 2 && (text.charAt(0) == '\'' || text.charAt(0) == '"')
                && text.charAt(text.length() - 1) == text.charAt(0)) {
            text = text.substring(1, text.length() - 1);
        }
        try {
            target.add(UUID.fromString(text));
        } catch (IllegalArgumentException ignored) {

        }
    }

    public boolean isOptedOut(UUID uuid) {
        return uuid != null && optedOut.contains(uuid);
    }

    public boolean isPendingSync(UUID uuid) {
        return uuid != null && pending.contains(uuid);
    }

    /** Opts the player out on this server and marks the choice as not yet confirmed by the API. */
    public void optOut(UUID uuid) {
        boolean changed = optedOut.add(uuid);
        changed |= pending.add(uuid);
        if (changed) {
            save();
        }
    }

    /** Called once the API has accepted the opt-out. Does nothing if the player has since opted in. */
    public void markSynced(UUID uuid) {
        if (optedOut.contains(uuid) && pending.remove(uuid)) {
            save();
        }
    }

    public void optIn(UUID uuid) {
        boolean changed = optedOut.remove(uuid);
        changed |= pending.remove(uuid);
        if (changed) {
            save();
        }
    }

    private void save() {
        synchronized (saveLock) {
            StringBuilder out = new StringBuilder();
            appendList(out, KEY, optedOut);
            appendList(out, PENDING_KEY, pending);
            try {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                AtomicFiles.write(file.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                logger.warning("Could not save " + FILE_NAME + ".");
            }
        }
    }

    private static void appendList(StringBuilder out, String key, Set<UUID> set) {
        List<String> values = new ArrayList<>();
        for (UUID id : set) {
            values.add(id.toString());
        }
        Collections.sort(values);
        if (values.isEmpty()) {
            out.append(key).append(": []\n");
            return;
        }
        out.append(key).append(":\n");
        for (String v : values) {
            out.append("- ").append(v).append('\n');
        }
    }
}

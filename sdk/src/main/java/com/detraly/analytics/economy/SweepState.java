package com.detraly.analytics.economy;

import com.detraly.analytics.storage.AtomicFiles;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

/**
 * When the last full sweep finished, kept in the data folder so a restart does not sweep again at once. The time
 * counts only for the token it was made with: a server that is linked again is swept again.
 */
public final class SweepState {
    private static final String FILE_NAME = "economy-state.properties";

    private final File file;

    public SweepState(File dataFolder) {
        this.file = new File(dataFolder, FILE_NAME);
    }

    /** Unix seconds of the last sweep, or 0 when there is none for this fingerprint. */
    public long lastSweepSeconds(String fingerprint) {
        if (fingerprint == null || !file.isFile()) {
            return 0L;
        }
        try {
            Properties p = new Properties();
            p.load(new StringReader(Files.readString(file.toPath(), StandardCharsets.UTF_8)));
            if (!fingerprint.equals(p.getProperty("token"))) {
                return 0L;
            }
            return Math.max(0L, Long.parseLong(p.getProperty("last-sweep", "0").trim()));
        } catch (IOException | RuntimeException e) {
            return 0L;
        }
    }

    public void save(long seconds, String fingerprint) throws IOException {
        if (fingerprint == null) {
            return;
        }
        String text = "last-sweep=" + seconds + "\ntoken=" + fingerprint + "\n";
        AtomicFiles.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
}

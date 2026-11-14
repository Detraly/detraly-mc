package com.detraly.analytics.storage;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class TokenStoreCheck {
    private static int failures;
    private static final String GOOD = "dtl_" + "A".repeat(43);

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("tokencheck");
        List<String> logged = new ArrayList<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord r) { logged.add(r.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        File folder = dir.toFile();
        Path file = dir.resolve("token.dat");
        TokenStore store = new TokenStore(folder, logger);

        expect(store.load().isEmpty() && logged.isEmpty(), "missing file: empty and silent");

        Files.write(file, "garbage".getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 5; i++) {
            expect(store.load().isEmpty(), "bad file is refused");
        }
        expect(logged.size() == 1, "bad file warned once, got " + logged.size());

        Files.write(file, "other garbage".getBytes(StandardCharsets.UTF_8));
        store.load();
        store.load();
        expect(logged.size() == 2, "changed bad content warned once more, got " + logged.size());

        expect(store.save(GOOD) && store.load().orElse("").equals(GOOD), "save of a valid token works");
        store.clear();
        Files.write(file, "garbage".getBytes(StandardCharsets.UTF_8));
        store.load();
        expect(logged.size() == 3, "after save/clear the same bad content is reported again, got " + logged.size());

        Files.write(file, GOOD.getBytes(StandardCharsets.UTF_8));
        TokenStore fresh = new TokenStore(folder, logger);
        expect(fresh.load().orElse("").equals(GOOD) && logged.size() == 3, "valid file loads silently");
        for (String m : logged) {
            expect(!m.contains("dtl_") && !m.contains("garbage"), "log never holds file content");
        }

        Files.deleteIfExists(file);
        Files.deleteIfExists(dir);
        if (failures > 0) {
            System.exit(1);
        }
        System.out.println("TokenStoreCheck ok");
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}

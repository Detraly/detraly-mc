package com.detraly.analytics.command;

import com.detraly.analytics.api.GeolocationState;
import com.detraly.analytics.storage.TokenStore;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/** Checks the Economy line of {@code /detraly status}: its text and its tone. */
public final class EconomyStatusCheck {
    private static int failures;

    private static final class Admin implements CommandActor {
        final List<Segment[]> lines = new ArrayList<>();

        @Override public boolean isConsole() { return true; }
        @Override public UUID playerId() { return null; }
        @Override public boolean isAdmin() { return true; }
        @Override public void send(Segment... parts) { lines.add(parts); }

        Segment[] economyLine() {
            for (Segment[] line : lines) {
                if (line.length > 1 && line[1].text().equals("Economy: ")) {
                    return line;
                }
            }
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        File folder = Files.createTempDirectory("economystatuscheck").toFile();
        CommandCore commands = new CommandCore(logger, null, new TokenStore(folder, logger), new GeolocationState(),
                null);

        Segment[] line = status(commands);
        expect(line != null && line[2].text().equals("off") && line[2].tone() == Tone.MUTED, "default is off, muted");

        String unsupported = "not supported on Fabric yet. We only support Vault API at the moment.";
        commands.setEconomyStatus(() -> unsupported);
        line = status(commands);
        expect(line != null && line[2].text().equals(unsupported) && line[2].tone() == Tone.MUTED,
                "unsupported text is shown, muted");

        commands.setEconomyStatus(() -> "not found");
        expect(status(commands)[2].tone() == Tone.MUTED, "not found is muted");

        commands.setEconomyStatus(() -> "Essentials, 3 players tracked");
        expect(status(commands)[2].tone() == Tone.GOOD, "a working economy is good");

        folder.delete();
        if (failures > 0) {
            System.out.println(failures + " check(s) failed");
            System.exit(1);
        }
        System.out.println("EconomyStatusCheck OK");
    }

    private static Segment[] status(CommandCore commands) {
        Admin admin = new Admin();
        commands.execute(admin, new String[] {"status"});
        return admin.economyLine();
    }

    private static void expect(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }
}

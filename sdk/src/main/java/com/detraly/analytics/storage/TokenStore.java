package com.detraly.analytics.storage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public final class TokenStore {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("^dtl_[A-Za-z0-9_-]{43}$");
    private static final String FILE_NAME = "token.dat";

    private final File dataFolder;
    private final Logger logger;
    private String cached;

    private String reportedInvalid;

    public TokenStore(File dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
    }

    public synchronized Optional<String> load() {
        if (cached != null) {
            return Optional.of(cached);
        }
        Path file = tokenPath();
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
            if (!TOKEN_PATTERN.matcher(content).matches()) {
                if (!content.equals(reportedInvalid)) {
                    reportedInvalid = content;
                    logger.warning("The saved Detraly token file is not valid. Link the server again.");
                }
                return Optional.empty();
            }
            cached = content;
            reportedInvalid = null;
            return Optional.of(cached);
        } catch (IOException e) {
            logger.warning("Could not read the saved Detraly token file.");
            return Optional.empty();
        }
    }

    public synchronized boolean save(String token) {
        if (token == null || !TOKEN_PATTERN.matcher(token).matches()) {
            logger.warning("Refused to save a token with an unexpected format.");
            return false;
        }
        Path target = tokenPath();
        Path temp = null;
        try {
            Files.createDirectories(dataFolder.toPath());
            temp = Files.createTempFile(dataFolder.toPath(), "token", ".tmp");
            restrictToOwner(temp);
            Files.write(temp, token.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
            restrictToOwner(target);
            cached = token;
            reportedInvalid = null;
            return true;
        } catch (IOException | RuntimeException e) {
            logger.log(Level.WARNING, "Could not save the Detraly token file.");
            return false;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {

                }
            }
        }
    }

    public synchronized void clear() {
        cached = null;
        reportedInvalid = null;
        try {
            Files.deleteIfExists(tokenPath());
        } catch (IOException e) {
            logger.warning("Could not delete the Detraly token file.");
        }
    }

    public boolean hasToken() {
        return get().isPresent();
    }

    public Optional<String> get() {
        return load();
    }

    private Path tokenPath() {
        return new File(dataFolder, FILE_NAME).toPath();
    }

    private static void restrictToOwner(Path path) {
        try {
            if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
                return;
            }
            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
            if (view == null) {
                return;
            }
            UserPrincipal owner = view.getOwner();
            AclEntry entry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            view.setAcl(List.of(entry));
        } catch (IOException | RuntimeException ignored) {

        }
    }
}

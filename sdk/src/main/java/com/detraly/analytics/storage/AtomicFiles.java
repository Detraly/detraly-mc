package com.detraly.analytics.storage;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
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

public final class AtomicFiles {
    private static final long[] RETRY_DELAYS_MILLIS = {50L, 100L, 200L};

    private AtomicFiles() {
    }

    public static void write(Path target, byte[] data) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
        try {
            restrictToOwner(temp);
            Files.write(temp, data);
            move(temp, target);
            temp = null;
            restrictToOwner(target);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {

                }
            }
        }
    }

    private static void move(Path temp, Path target) throws IOException {
        FileSystemException last = null;
        for (int attempt = 0; attempt <= RETRY_DELAYS_MILLIS.length; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(RETRY_DELAYS_MILLIS[attempt - 1]);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (FileSystemException e) {
                last = e;
            }
        }
        throw last;
    }

    public static void deleteLeftoverTemps(Path dir, String baseName) {
        if (dir == null) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, baseName + "*.tmp")) {
            for (Path file : files) {
                try {
                    if (Files.isRegularFile(file)) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException | RuntimeException ignored) {

                }
            }
        } catch (IOException | RuntimeException ignored) {

        }
    }

    public static void restrictToOwner(Path path) {
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

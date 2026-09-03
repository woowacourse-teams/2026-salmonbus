package com.gustler.backend.migration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

public final class SecureFiles {

    private static final Set<PosixFilePermission> OWNER_DIRECTORY = EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> OWNER_FILE = EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE);

    private SecureFiles() {
    }

    public static void createPrivateDirectory(
        Path directory
    ) {
        try {
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    throw new MigrationException("PRIVATE_DIRECTORY_INVALID");
                }
            } else {
                Files.createDirectories(directory);
            }
            setPermissions(directory, OWNER_DIRECTORY);
        } catch (IOException e) {
            throw new MigrationException("PRIVATE_DIRECTORY_CREATE_FAILED", e);
        }
    }

    public static void writeNew(
        Path path,
        byte[] bytes
    ) {
        try {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                createPrivateDirectory(parent);
            }
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            setPermissions(path, OWNER_FILE);
        } catch (IOException e) {
            throw new MigrationException("PRIVATE_FILE_WRITE_FAILED", e);
        }
    }

    public static void requirePrivateRegularFile(
        Path path
    ) {
        try {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("PRIVATE_FILE_INVALID");
            }
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            if (!OWNER_FILE.equals(permissions) && !Set.of(PosixFilePermission.OWNER_READ).equals(permissions)) {
                throw new MigrationException("PRIVATE_FILE_PERMISSIONS_INVALID");
            }
        } catch (UnsupportedOperationException e) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("PRIVATE_FILE_INVALID", e);
            }
        } catch (IOException e) {
            throw new MigrationException("PRIVATE_FILE_INSPECTION_FAILED", e);
        }
    }

    public static void requirePrivateDirectory(
        Path path
    ) {
        try {
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("PRIVATE_DIRECTORY_INVALID");
            }
            Set<PosixFilePermission> permissions =
                Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            if (!OWNER_DIRECTORY.equals(permissions)) {
                throw new MigrationException("PRIVATE_DIRECTORY_PERMISSIONS_INVALID");
            }
        } catch (UnsupportedOperationException e) {
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new MigrationException("PRIVATE_DIRECTORY_INVALID", e);
            }
        } catch (IOException e) {
            throw new MigrationException("PRIVATE_DIRECTORY_INSPECTION_FAILED", e);
        }
    }

    public static void movePrivate(
        Path source,
        Path target
    ) {
        try {
            Files.move(source, target);
            setPermissions(target, OWNER_FILE);
        } catch (IOException e) {
            throw new MigrationException("PRIVATE_FILE_MOVE_FAILED", e);
        }
    }

    private static void setPermissions(
        Path path,
        Set<PosixFilePermission> permissions
    ) throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Windows 개발 환경에는 POSIX mode가 없다. 실제 source/EC2 경로는 Linux/macOS라 강제된다.
        }
    }
}

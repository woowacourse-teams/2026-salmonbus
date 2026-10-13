package com.gustler.devtools;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;

final class FileOps {
    private FileOps() {}

    static boolean exists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    static int attribute(Path path, String name) throws Exception {
        return ((Number) Files.getAttribute(path, "unix:" + name, LinkOption.NOFOLLOW_LINKS))
                .intValue();
    }

    static void mode(Path path, final int mode) throws Exception {
        Files.setAttribute(path, "unix:mode", mode, LinkOption.NOFOLLOW_LINKS);
    }

    static void owner(Path path, final int uid, final int gid) throws Exception {
        Files.setAttribute(path, "unix:uid", uid, LinkOption.NOFOLLOW_LINKS);
        Files.setAttribute(path, "unix:gid", gid, LinkOption.NOFOLLOW_LINKS);
    }

    static void protectedFile(Path path, String code) throws Exception {
        var attributes =
                Files.readAttributes(
                        path,
                        java.nio.file.attribute.BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
        Checks.require(
                attributes.isRegularFile()
                        && attribute(path, "uid") == 0
                        && (attribute(path, "mode") & 0022) == 0,
                code);
    }

    static String privateText(Path path) throws Exception {
        protectedFile(path, "PRIVATE_FILE_REQUIRED");
        Checks.require((attribute(path, "mode") & 0777) == 0600, "PRIVATE_FILE_REQUIRED");
        return Files.readString(path);
    }

    static void directory(Path path, final int mode) throws Exception {
        if (exists(path)) {
            Checks.require(
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                            && attribute(path, "uid") == 0
                            && (attribute(path, "mode") & 0022) == 0,
                    "DEV_DIRECTORY");
        } else {
            Files.createDirectory(
                    path,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
            mode(path, mode);
        }
    }

    static void writeNew(Path path, byte[] bytes, final int mode) throws Exception {
        try (var channel =
                FileChannel.open(
                        path,
                        java.util.Set.of(
                                StandardOpenOption.WRITE,
                                StandardOpenOption.CREATE_NEW,
                                LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")))) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        mode(path, mode);
    }

    static void sync(Path directory) throws Exception {
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    static void moveNew(Path from, Path to) throws Exception {
        Checks.require(!exists(to), "DESTINATION_EXISTS");
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        sync(to.getParent());
    }

    static void atomicFile(Path path, byte[] bytes, final int mode) throws Exception {
        Checks.require(!Files.isSymbolicLink(path), "FILE_SYMLINK");
        var temporary = Files.createTempDirectory(path.getParent(), ".dev-write-");
        try {
            var file = temporary.resolve("value");
            writeNew(file, bytes, mode);
            Files.move(
                    file,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            sync(path.getParent());
        } finally {
            removeTree(temporary);
        }
    }

    static String sha(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int length;
            while ((length = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, length);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String sha(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    static void removeTree(Path ownedTemporary) throws Exception {
        if (!exists(ownedTemporary)) {
            return;
        }
        try (var paths = Files.walk(ownedTemporary)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    static Lease lock(Path path) throws Exception {
        var channel =
                FileChannel.open(
                        path,
                        java.util.Set.of(
                                StandardOpenOption.READ,
                                StandardOpenOption.WRITE,
                                StandardOpenOption.CREATE,
                                LinkOption.NOFOLLOW_LINKS),
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        try {
            privateText(path);
            var lock = channel.tryLock();
            Checks.require(lock != null, "DEV_LOCK_BUSY");
            return new Lease(channel, lock);
        } catch (Exception error) {
            channel.close();
            throw error;
        }
    }

    record Lease(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }
}

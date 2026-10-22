package com.gustler.devtools;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Optional API login keys in api.env. Without them login stays off. AUTH_ENABLED=false leaves the
 * other values unchecked so they can be staged before login is turned on. The Apple key file lives
 * only in the apple directory of the dev configuration and is readable by the service group.
 */
final class LoginSettings {
    static final String APPLE_DIRECTORY = "apple";
    static final String APPLE_KEY_PREFIX = "/etc/salmonbus-dev/" + APPLE_DIRECTORY + "/";
    static final Set<String> KEYS =
            Set.of(
                    "AUTH_ENABLED",
                    "AUTH_PUBLIC_BASE_URL",
                    "AUTH_TOKEN_SECRET",
                    "AUTH_REFRESH_TOKEN_SECRET",
                    "AUTH_KAKAO_ENABLED",
                    "AUTH_KAKAO_CLIENT_ID",
                    "AUTH_KAKAO_CLIENT_SECRET",
                    "AUTH_KAKAO_ADMIN_KEY",
                    "AUTH_APPLE_ENABLED",
                    "AUTH_APPLE_CLIENT_ID",
                    "AUTH_APPLE_TEAM_ID",
                    "AUTH_APPLE_KEY_ID",
                    "AUTH_APPLE_PRIVATE_KEY_PATH");
    private static final String SECRET = "[0-9a-f]{64}";
    private static final String KEY_NAME = "[A-Za-z0-9][A-Za-z0-9_.-]*\\.p8";

    private LoginSettings() {}

    static void validate(Map<String, String> api) {
        if (api.keySet().stream().noneMatch(KEYS::contains)) {
            return;
        }
        Checks.require(flag(api.get("AUTH_ENABLED")), "DEV_AUTH_ENABLED");
        if (!enabled(api)) {
            return;
        }
        var base = api.get("AUTH_PUBLIC_BASE_URL");
        Checks.require(base != null, "DEV_AUTH_PUBLIC_BASE_URL");
        Environment.origin(base, "DEV_AUTH_PUBLIC_BASE_URL");
        var token = api.get("AUTH_TOKEN_SECRET");
        var refresh = api.get("AUTH_REFRESH_TOKEN_SECRET");
        Checks.require(
                token != null
                        && token.matches(SECRET)
                        && refresh != null
                        && refresh.matches(SECRET)
                        && !token.equals(refresh),
                "DEV_AUTH_TOKEN_SECRET");
        Checks.require(flag(api.getOrDefault("AUTH_KAKAO_ENABLED", "false")), "DEV_AUTH_KAKAO");
        if ("true".equals(api.get("AUTH_KAKAO_ENABLED"))) {
            Checks.require(
                    present(
                            api,
                            List.of(
                                    "AUTH_KAKAO_CLIENT_ID",
                                    "AUTH_KAKAO_CLIENT_SECRET",
                                    "AUTH_KAKAO_ADMIN_KEY")),
                    "DEV_AUTH_KAKAO");
        }
        Checks.require(flag(api.getOrDefault("AUTH_APPLE_ENABLED", "false")), "DEV_AUTH_APPLE");
        if (apple(api)) {
            var path = api.get("AUTH_APPLE_PRIVATE_KEY_PATH");
            Checks.require(
                    present(
                                    api,
                                    List.of(
                                            "AUTH_APPLE_CLIENT_ID",
                                            "AUTH_APPLE_TEAM_ID",
                                            "AUTH_APPLE_KEY_ID"))
                            && path != null
                            && path.startsWith(APPLE_KEY_PREFIX)
                            && path.substring(APPLE_KEY_PREFIX.length()).matches(KEY_NAME),
                    "DEV_AUTH_APPLE");
        }
    }

    /** Requires validated settings; checks the key that the service account reads at startup. */
    static void requireAppleKey(Path directory, Map<String, String> api, final int group)
            throws Exception {
        if (!apple(api)) {
            return;
        }
        var keys = directory.resolve(APPLE_DIRECTORY);
        keyDirectory(keys, group);
        keyFile(
                keys.resolve(
                        api.get("AUTH_APPLE_PRIVATE_KEY_PATH")
                                .substring(APPLE_KEY_PREFIX.length())),
                group);
    }

    /**
     * Requires an existing apple directory to hold only regular files, the shape that {@link
     * #preserve} can copy. Owner and mode are left to {@link #requireAppleKey}.
     */
    static void requireCopyable(Path directory) throws Exception {
        var keys = directory.resolve(APPLE_DIRECTORY);
        if (!FileOps.exists(keys)) {
            return;
        }
        Checks.require(
                Files.isDirectory(keys, LinkOption.NOFOLLOW_LINKS), "DEV_AUTH_APPLE_DIRECTORY");
        try (var files = Files.list(keys)) {
            Checks.require(
                    files.allMatch(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)),
                    "DEV_AUTH_APPLE_DIRECTORY");
        }
    }

    /**
     * Copies the apple directory into a replacement configuration with the same owner and mode,
     * whether or not login is on.
     */
    static void preserve(Path source, Path destination, SettingsPreparation.Writer writer)
            throws Exception {
        var from = source.resolve(APPLE_DIRECTORY);
        if (!FileOps.exists(from)) {
            return;
        }
        requireCopyable(source);
        var to = destination.resolve(APPLE_DIRECTORY);
        Files.createDirectory(
                to,
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        try (var files = Files.list(from)) {
            for (var file : files.sorted().toList()) {
                var copy = to.resolve(file.getFileName());
                writer.write(
                        copy, Files.readAllBytes(file), FileOps.attribute(file, "mode") & 0777);
                FileOps.owner(copy, FileOps.attribute(file, "uid"), FileOps.attribute(file, "gid"));
            }
        }
        FileOps.owner(to, FileOps.attribute(from, "uid"), FileOps.attribute(from, "gid"));
        FileOps.mode(to, FileOps.attribute(from, "mode") & 0777);
        FileOps.sync(to);
    }

    private static boolean enabled(Map<String, String> api) {
        return "true".equals(api.get("AUTH_ENABLED"));
    }

    private static boolean apple(Map<String, String> api) {
        return enabled(api) && "true".equals(api.get("AUTH_APPLE_ENABLED"));
    }

    private static boolean flag(String value) {
        return "true".equals(value) || "false".equals(value);
    }

    private static boolean present(Map<String, String> api, List<String> keys) {
        return keys.stream().allMatch(key -> api.get(key) != null && !api.get(key).isEmpty());
    }

    private static void keyDirectory(Path path, final int group) throws Exception {
        Checks.require(
                Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        && FileOps.attribute(path, "uid") == 0
                        && FileOps.attribute(path, "gid") == group
                        && (FileOps.attribute(path, "mode") & 07777) == 0750,
                "DEV_AUTH_APPLE_KEY");
    }

    private static void keyFile(Path path, final int group) throws Exception {
        Checks.require(
                path.getFileName().toString().matches(KEY_NAME) && FileOps.exists(path),
                "DEV_AUTH_APPLE_KEY");
        FileOps.protectedFile(path, "DEV_AUTH_APPLE_KEY");
        final int mode = FileOps.attribute(path, "mode");
        Checks.require(
                FileOps.attribute(path, "gid") == group
                        && (mode & 07137) == 0
                        && (mode & 0040) != 0,
                "DEV_AUTH_APPLE_KEY");
    }
}

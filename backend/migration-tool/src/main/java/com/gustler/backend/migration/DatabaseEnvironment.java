package com.gustler.backend.migration;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record DatabaseEnvironment(
    TargetKind targetKind,
    String jdbcUrl,
    String username,
    String password,
    String identitySha256
) {

    private static final Path ACADEMY_ENV = Path.of("/etc/salmonbus/worker.env");

    public enum TargetKind {
        LOCAL,
        ACADEMY,
    }

    public static DatabaseEnvironment load(
        Configuration configuration
    ) {
        TargetKind targetKind;
        try {
            targetKind = TargetKind.valueOf(configuration.required("target.kind").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new MigrationException("CONFIG_INVALID_TARGET_KIND", e);
        }
        Path path = configuration.requiredPath("database.env-file");
        if (targetKind == TargetKind.ACADEMY) {
            requireAcademyEnvironmentFile(path);
        } else {
            SecureFiles.requirePrivateRegularFile(path);
        }
        Map<String, String> values = readWithoutShell(path);
        String url = required(values, "DB_URL");
        String username = required(values, "DB_USERNAME");
        String password = required(values, "DB_PASSWORD");
        URI identity = jdbcIdentity(url);
        if (targetKind == TargetKind.LOCAL && !isLoopback(identity.getHost())) {
            throw new MigrationException("LOCAL_TARGET_MUST_BE_LOOPBACK");
        }
        String material = identity.getHost().toLowerCase(Locale.ROOT)
            + ":" + effectivePort(identity)
            + identity.getPath()
            + "\n" + username;
        return new DatabaseEnvironment(targetKind, url, username, password, Sha256.of(material));
    }

    private static void requireAcademyEnvironmentFile(
        Path path
    ) {
        if (!ACADEMY_ENV.equals(path)) {
            throw new MigrationException("ACADEMY_ENV_FILE_PATH_INVALID");
        }
        SecureFiles.requirePrivateRegularFile(path);
        try {
            UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
            if (!"root".equals(owner.getName())) {
                throw new MigrationException("ACADEMY_ENV_FILE_OWNER_INVALID");
            }
        } catch (IOException e) {
            throw new MigrationException("ACADEMY_ENV_FILE_OWNER_UNREADABLE", e);
        }
    }

    /** systemd EnvironmentFile의 단순 KEY=VALUE 부분만 읽고 shell expansion은 절대 하지 않는다. */
    private static Map<String, String> readWithoutShell(
        Path path
    ) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path);
        } catch (IOException e) {
            throw new MigrationException("DATABASE_ENV_FILE_READ_FAILED", e);
        }
        Map<String, String> values = new HashMap<>();
        for (String original : lines) {
            String line = original.endsWith("\r") ? original.substring(0, original.length() - 1) : original;
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator < 1) {
                throw new MigrationException("DATABASE_ENV_FILE_SYNTAX_INVALID");
            }
            String key = line.substring(0, separator).strip();
            String value = unquote(line.substring(separator + 1).strip());
            if (!key.matches("[A-Z][A-Z0-9_]*") || values.put(key, value) != null) {
                throw new MigrationException("DATABASE_ENV_FILE_SYNTAX_INVALID");
            }
        }
        return values;
    }

    private static String unquote(
        String value
    ) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String required(
        Map<String, String> values,
        String name
    ) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw new MigrationException("DATABASE_ENV_MISSING_" + name);
        }
        return value;
    }

    private static URI jdbcIdentity(
        String jdbcUrl
    ) {
        if (!jdbcUrl.startsWith("jdbc:postgresql://")) {
            throw new MigrationException("DATABASE_URL_INVALID");
        }
        try {
            URI uri = new URI(jdbcUrl.substring("jdbc:".length()));
            if (uri.getHost() == null || uri.getPath() == null || uri.getPath().length() < 2) {
                throw new MigrationException("DATABASE_URL_INVALID");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new MigrationException("DATABASE_URL_INVALID", e);
        }
    }

    private static int effectivePort(
        URI uri
    ) {
        return uri.getPort() < 0 ? 5432 : uri.getPort();
    }

    private static boolean isLoopback(
        String host
    ) {
        return "localhost".equalsIgnoreCase(host)
            || "127.0.0.1".equals(host)
            || "::1".equals(host)
            || "0:0:0:0:0:0:0:1".equals(host);
    }

    @Override
    public String toString() {
        return "DatabaseEnvironment[targetKind=" + targetKind + ", credentials=***]";
    }
}

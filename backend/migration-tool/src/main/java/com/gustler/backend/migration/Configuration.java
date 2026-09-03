package com.gustler.backend.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public final class Configuration {

    private final Properties values;

    private Configuration(
        Properties values
    ) {
        this.values = values;
    }

    public static Configuration load(
        Path path
    ) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
            throw new MigrationException("CONFIG_FILE_INVALID");
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        } catch (IOException e) {
            throw new MigrationException("CONFIG_FILE_READ_FAILED", e);
        }
        return new Configuration(properties);
    }

    public String required(
        String name
    ) {
        String value = values.getProperty(name);
        if (value == null || value.isBlank() || value.startsWith("<")) {
            throw new MigrationException("CONFIG_REQUIRED_" + codeOf(name));
        }
        return value.strip();
    }

    public String optional(
        String name
    ) {
        String value = values.getProperty(name);
        return value == null || value.isBlank() ? null : value.strip();
    }

    public Path requiredPath(
        String name
    ) {
        return Path.of(required(name)).toAbsolutePath().normalize();
    }

    public Instant requiredInstant(
        String name
    ) {
        try {
            return Instant.parse(required(name));
        } catch (DateTimeParseException e) {
            throw new MigrationException("CONFIG_INVALID_" + codeOf(name), e);
        }
    }

    public int integer(
        String name,
        int defaultValue,
        int minimum,
        int maximum
    ) {
        String value = optional(name);
        int parsed;
        try {
            parsed = value == null ? defaultValue : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new MigrationException("CONFIG_INVALID_" + codeOf(name), e);
        }
        if (parsed < minimum || parsed > maximum) {
            throw new MigrationException("CONFIG_OUT_OF_RANGE_" + codeOf(name));
        }
        return parsed;
    }

    public long longValue(
        String name,
        long defaultValue,
        long minimum
    ) {
        String value = optional(name);
        long parsed;
        try {
            parsed = value == null ? defaultValue : Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new MigrationException("CONFIG_INVALID_" + codeOf(name), e);
        }
        if (parsed < minimum) {
            throw new MigrationException("CONFIG_OUT_OF_RANGE_" + codeOf(name));
        }
        return parsed;
    }

    public List<String> commaSeparated(
        String name
    ) {
        List<String> entries = Arrays.stream(required(name).split(","))
            .map(String::strip)
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();
        if (entries.isEmpty()) {
            throw new MigrationException("CONFIG_REQUIRED_" + codeOf(name));
        }
        return entries;
    }

    private static String codeOf(
        String name
    ) {
        return name.replace('.', '_').replace('-', '_').toUpperCase();
    }
}

package com.gustler.backend.maintenance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
            throw new MaintenanceException("CONFIG_FILE_INVALID");
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        } catch (IOException e) {
            throw new MaintenanceException("CONFIG_FILE_READ_FAILED", e);
        }
        return new Configuration(properties);
    }

    public String required(
        String name
    ) {
        String value = values.getProperty(name);
        if (value == null || value.isBlank() || value.startsWith("<")) {
            throw new MaintenanceException("CONFIG_REQUIRED_" + codeOf(name));
        }
        return value.strip();
    }

    public Path requiredPath(
        String name
    ) {
        return Path.of(required(name)).toAbsolutePath().normalize();
    }

    private static String codeOf(
        String name
    ) {
        return name.replace('.', '_').replace('-', '_').toUpperCase();
    }
}

package com.gustler.backend.maintenance;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * 유지보수 설정 파일에서 필수 값과 경로를 읽는다.
 * 잘못된 설정은 공개할 수 있는 오류 코드로 변환한다.
 */
public final class Configuration {
    private final Properties values;

    private Configuration(
        Properties values
    ) {
        this.values = values;
    }

    /**
     * 일반 설정 파일을 읽는다. 심볼릭 링크이거나 파일이 없거나 읽기에 실패하면 유지보수 오류로 변환한다.
     */
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
        if (!isConfigured(value)) {
            throw new MaintenanceException("CONFIG_REQUIRED_" + codeOf(name));
        }
        return value.strip();
    }

    private static boolean isConfigured(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return !value.startsWith("<");
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

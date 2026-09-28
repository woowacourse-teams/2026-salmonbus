package com.gustler.backend.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class SchemaCheck {

    private SchemaCheck() {
    }

    public static Set<String> allowedValues(String migration, String column) {
        Matcher matcher = Pattern.compile("CHECK\\s*\\(\\s*" + Pattern.quote(column) + "\\s+IN\\s*\\(([^)]*)\\)\\s*\\)")
            .matcher(read(migration));
        if (!matcher.find()) {
            throw new IllegalStateException("%s 에서 %s 의 CHECK 를 못 찾았다".formatted(migration, column));
        }
        Set<String> values = Arrays.stream(matcher.group(1).split(","))
            .map(value -> value.strip().replace("'", ""))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (matcher.find()) {
            throw new IllegalStateException("%s 에 %s 의 CHECK 가 둘 이상이다".formatted(migration, column));
        }
        return values;
    }

    private static String read(String migration) {
        String path = "db/migration/" + migration;
        try (InputStream source = SchemaCheck.class.getClassLoader().getResourceAsStream(path)) {
            if (source == null) {
                throw new IllegalStateException("%s 를 클래스패스에서 못 찾았다".formatted(path));
            }
            return new String(source.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException cause) {
            throw new IllegalStateException("%s 를 읽지 못했다".formatted(path), cause);
        }
    }
}

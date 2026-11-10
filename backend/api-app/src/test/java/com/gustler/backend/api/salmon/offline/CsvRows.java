package com.gustler.backend.api.salmon.offline;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * PostgreSQL의 COPY CSV 출력과 같은 규칙으로 CSV 행을 읽는다. 따옴표 없는 빈 칸은 null이고 따옴표 안의 빈 칸은 빈 문자열이다.
 */
final class CsvRows implements AutoCloseable {
    private final PushbackReader reader;

    private CsvRows(PushbackReader reader) {
        this.reader = reader;
    }

    static CsvRows open(Path file) throws IOException {
        InputStream bytes = Files.newInputStream(file);
        if (file.getFileName().toString().endsWith(".gz")) {
            bytes = new GZIPInputStream(bytes);
        }
        return new CsvRows(new PushbackReader(
            new BufferedReader(new InputStreamReader(bytes, StandardCharsets.UTF_8))));
    }

    /** 다음 행을 돌려준다. 더 없으면 null이다. */
    List<String> next() throws IOException {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean insideQuotes = false;
        boolean started = false;
        while (true) {
            int read = reader.read();
            if (read == -1) {
                if (!started) {
                    return null;
                }
                fields.add(valueOf(field, quoted));
                return fields;
            }
            started = true;
            char character = (char) read;
            if (insideQuotes) {
                if (character != '"') {
                    field.append(character);
                    continue;
                }
                int following = reader.read();
                if (following == '"') {
                    field.append('"');
                    continue;
                }
                insideQuotes = false;
                if (following != -1) {
                    reader.unread(following);
                }
            } else if (character == '"') {
                insideQuotes = true;
                quoted = true;
            } else if (character == ',') {
                fields.add(valueOf(field, quoted));
                field.setLength(0);
                quoted = false;
            } else if (character == '\n') {
                fields.add(valueOf(field, quoted));
                return fields;
            } else if (character != '\r') {
                field.append(character);
            }
        }
    }

    private static String valueOf(StringBuilder field, boolean quoted) {
        return field.isEmpty() && !quoted ? null : field.toString();
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}

package com.gustler.backend.forecasting.infrastructure.statistics;

import com.gustler.backend.forecasting.infrastructure.statistics.JdbcStatisticsInputExtractor.Extracted;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** DB 읽기 트랜잭션이 끝난 뒤 호출한다. 실패한 디렉터리는 재사용하지 않는다. */
final class StatisticsExtractWriter {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private StatisticsExtractWriter() {
    }

    static Bundle write(Path parent, Extracted extracted, final long maxBytes) {
        if (!hasValidRequest(parent, extracted, maxBytes)) {
            throw new IllegalArgumentException("추출 자료와 작업 디렉터리, 파일 크기 한도가 필요하다");
        }
        StatisticsInputRows.verify(extracted.scope(), extracted.rows().iterator());
        try {
            Path directory = Files.createTempDirectory(parent, "statistics-extract-");
            Path rows = directory.resolve("rows.jsonl");
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            long written = 0;
            try (OutputStream output = Files.newOutputStream(rows, StandardOpenOption.CREATE_NEW)) {
                for (StatisticsInputRow row : extracted.rows()) {
                    byte[] bytes = (JSON.writeValueAsString(row) + "\n").getBytes(StandardCharsets.UTF_8);
                    if (bytes.length > maxBytes - written) {
                        throw new IllegalArgumentException("추출 파일 크기 한도를 초과했다");
                    }
                    output.write(bytes);
                    hash.update(bytes);
                    written += bytes.length;
                }
            }
            StatisticsInputFile file = new StatisticsInputFile("rows.jsonl", written, HexFormat.of()
                .formatHex(hash.digest()));
            StatisticsInputFiles.verify(directory, List.of(file));
            Manifest manifest = new Manifest("statistics-extract-v1", extracted.scope(),
                extracted.afterObservationId(), extracted.throughObservationId(), List.of(file));
            Files.writeString(directory.resolve("manifest.json"), JSON.writeValueAsString(manifest),
                StandardOpenOption.CREATE_NEW);
            return new Bundle(directory, manifest);
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("검증된 추출 파일을 만들지 못했다", exception);
        }
    }

    private static boolean hasValidRequest(Path parent, Extracted extracted, long maxBytes) {
        if (maxBytes < 1 || extracted == null) {
            return false;
        }
        if (parent == null || !Files.isDirectory(parent)) {
            return false;
        }
        return !Files.isSymbolicLink(parent);
    }

    record Manifest(String format, StatisticsInputScope scope, long afterObservationId,
        long throughObservationId, List<StatisticsInputFile> files) {
        /** 호출자가 가진 원본과 분리해 null·원소·순서를 보존한다. */
        Manifest {
            if (files != null) {
                files = Collections.unmodifiableList(new ArrayList<>(files));
            }
        }
    }
    record Bundle(Path directory, Manifest manifest) {
    }
}

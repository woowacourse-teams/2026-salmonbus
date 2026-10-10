package com.gustler.backend.forecasting.infrastructure.statistics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** DB 읽기 트랜잭션이 끝난 뒤 호출한다. 실패한 디렉터리는 재사용하지 않는다. */
final class StatisticsExtractWriter {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private StatisticsExtractWriter() { }

    static Bundle write(Path parent, JdbcStatisticsInputExtractor.Extracted extracted, long maxBytes) {
        if (maxBytes < 1 || extracted == null || parent == null || !Files.isDirectory(parent)
            || Files.isSymbolicLink(parent)) {
            throw new IllegalArgumentException("추출 자료와 작업 디렉터리, 파일 크기 한도가 필요하다");
        }
        StatisticsInputRows.verify(extracted.scope(), extracted.rows().iterator());
        try {
            Path directory = Files.createTempDirectory(parent, "statistics-extract-");
            Path rows = directory.resolve("rows.jsonl");
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            long written = 0;
            try (var output = Files.newOutputStream(rows, StandardOpenOption.CREATE_NEW)) {
                for (var row : extracted.rows()) {
                    byte[] bytes = (JSON.writeValueAsString(row) + "\n").getBytes(StandardCharsets.UTF_8);
                    if (bytes.length > maxBytes - written) {
                        throw new IllegalArgumentException("추출 파일 크기 한도를 초과했다");
                    }
                    output.write(bytes);
                    hash.update(bytes);
                    written += bytes.length;
                }
            }
            var file = new StatisticsInputFile("rows.jsonl", written, HexFormat.of().formatHex(hash.digest()));
            StatisticsInputFiles.verify(directory, List.of(file));
            var manifest = new Manifest("statistics-extract-v1", extracted.scope(),
                extracted.afterObservationId(), extracted.throughObservationId(), List.of(file));
            Files.writeString(directory.resolve("manifest.json"), JSON.writeValueAsString(manifest),
                StandardOpenOption.CREATE_NEW);
            return new Bundle(directory, manifest);
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("검증된 추출 파일을 만들지 못했다", exception);
        }
    }

    record Manifest(String format, StatisticsInputScope scope, long afterObservationId,
        long throughObservationId, List<StatisticsInputFile> files) { }
    record Bundle(Path directory, Manifest manifest) { }
}

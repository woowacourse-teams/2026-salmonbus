package com.gustler.backend.forecasting.infrastructure.statistics;

import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Key;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Row;
import com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader.Reference;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** DB 원본 JSON을 재직렬화하지 않고 보존한다. 가공된 통계 입력 파일과 다른 형식이다. */
final class EvaluationArchiveFiles {
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> COLUMNS = Set.of("vehicle_observation_id", "target_stop_order", "route_version_id",
        "scoring_state", "arrival_observation_id", "seats_on_arrival", "scored_at", "arrived_at",
        "arrival_route_version_id", "arrival_vehicle_id", "arrival_stop_order", "arrival_running_state",
        "arrival_remaining_seats", "arrival_seat_unknown_reason", "arrival_vehicle_trip_key", "arrival_quality_direction");

    private EvaluationArchiveFiles() { }

    static Encoded encode(EvaluationArchiveBatch batch, List<Row> rows, long maxBytes) {
        if (batch == null || rows == null || rows.size() != batch.rowCount()
            || maxBytes < 1 || maxBytes > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("묶음 건수와 파일 크기 한도가 올바르지 않다");
        }
        var ordered = rows.stream().sorted(Comparator.comparingLong((Row row) -> row.key().observationId())
            .thenComparingInt(row -> row.key().stopOrder())).toList();
        var data = new ByteArrayOutputStream();
        Set<Key> seen = new HashSet<>();
        for (Row row : ordered) {
            byte[] original = row.originalJson().getBytes(StandardCharsets.UTF_8);
            if (original.length > 64 * 1024 || data.size() + (long) original.length + 1 > maxBytes
                || row.originalJson().contains("\n") || row.originalJson().contains("\r")
                || !seen.add(row.key()) || !digest(original).equals(row.sha256())) {
                throw new IllegalArgumentException("원본의 크기·중복·검증값이 올바르지 않다");
            }
            var node = JSON.readTree(original);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("정산 원본은 JSON 객체여야 한다");
            }
            Set<String> names = new HashSet<>();
            node.properties().forEach(entry -> names.add(entry.getKey()));
            if (!names.equals(COLUMNS)
                || !node.get("vehicle_observation_id").isIntegralNumber()
                || !node.get("target_stop_order").isIntegralNumber()
                || !node.get("route_version_id").isIntegralNumber()
                || !node.get("vehicle_observation_id").canConvertToLong()
                || !node.get("target_stop_order").canConvertToInt()
                || !node.get("route_version_id").canConvertToLong()
                || node.get("vehicle_observation_id").asLong() != row.key().observationId()
                || node.get("target_stop_order").asInt() != row.key().stopOrder()
                || node.get("route_version_id").asLong() != batch.routeVersionId()
                || !Set.of("SETTLED", "SKIPPED", "LOST", "SEAT_MISSING").contains(node.get("scoring_state").asString())
                || !node.get("scored_at").isString()) {
                throw new IllegalArgumentException("원본 열·키·노선·완료 상태가 예약과 다르다");
            }
            data.writeBytes(original);
            data.write('\n');
        }
        byte[] bytes = data.toByteArray();
        var manifest = new Manifest("evaluation-archive-v1", batch.id(), batch.routeVersionId(),
            batch.qualityRevision(), rows.size(), "evaluations.jsonl", bytes.length, digest(bytes));
        return new Encoded(bytes, JSON.writeValueAsBytes(manifest));
    }

    static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static Manifest manifest(Reference reference, byte[] bytes, long maxBytes) {
        if (bytes.length > 16384 || !digest(bytes).equals(reference.manifestSha256())) {
            throw new IllegalArgumentException("S3 설명 파일이 DB의 검증 기록과 다르다");
        }
        Manifest manifest = JSON.readValue(bytes, Manifest.class);
        if (manifest == null || !"evaluation-archive-v1".equals(manifest.format())
            || !reference.batchId().equals(manifest.batchId()) || reference.routeVersionId() != manifest.routeVersionId()
            || reference.qualityRevision() != manifest.qualityRevision() || reference.rowCount() != manifest.rowCount()
            || !"evaluations.jsonl".equals(manifest.file()) || manifest.bytes() < 1 || manifest.bytes() > maxBytes
            || manifest.sha256() == null || !manifest.sha256().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("이관 설명 파일의 범위나 크기가 올바르지 않다");
        }
        return manifest;
    }

    static List<Row> decode(Reference reference, byte[] manifestBytes, byte[] data, long maxBytes) {
        Manifest manifest = manifest(reference, manifestBytes, maxBytes);
        if (data.length != manifest.bytes() || !digest(data).equals(manifest.sha256())) {
            throw new IllegalArgumentException("보존한 원본의 크기나 검증값이 다르다");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString();
        } catch (java.nio.charset.CharacterCodingException exception) {
            throw new IllegalArgumentException("이관 원본의 UTF-8이 올바르지 않다", exception);
        }
        String[] lines = text.split("\n", -1);
        if (lines.length != reference.rowCount() + 1 || !lines[lines.length - 1].isEmpty()) {
            throw new IllegalArgumentException("이관 원본의 건수가 설명 파일과 다르다");
        }
        List<Row> rows = new java.util.ArrayList<>();
        for (int i = 0; i < lines.length - 1; i++) {
            var node = JSON.readTree(lines[i]);
            if (node == null || !node.isObject() || !node.hasNonNull("vehicle_observation_id")
                || !node.hasNonNull("target_stop_order") || !node.get("vehicle_observation_id").isIntegralNumber()
                || !node.get("vehicle_observation_id").canConvertToLong() || !node.get("target_stop_order").isIntegralNumber()
                || !node.get("target_stop_order").canConvertToInt()) {
                throw new IllegalArgumentException("원본 키가 올바르지 않다");
            }
            rows.add(new Row(new Key(node.get("vehicle_observation_id").asLong(), node.get("target_stop_order").asInt()),
                lines[i], digest(lines[i].getBytes(StandardCharsets.UTF_8))));
        }
        // 같은 열·중복·정렬·노선 검사를 읽을 때도 적용한다. lease는 파일 내용에 포함되지 않는다.
        var batch = new EvaluationArchiveBatch(reference.batchId(), reference.routeVersionId(), reference.qualityRevision(),
            new UUID(0, 0), java.time.Instant.EPOCH, EvaluationArchiveBatch.State.RESERVED, reference.rowCount(), null);
        if (!java.util.Arrays.equals(encode(batch, rows, maxBytes).rows(), data)) {
            throw new IllegalArgumentException("원본 파일의 정렬이나 표현이 올바르지 않다");
        }
        return List.copyOf(rows);
    }

    record Encoded(byte[] rows, byte[] manifest) { }
    record Manifest(String format, UUID batchId, long routeVersionId, long qualityRevision,
        int rowCount, String file, long bytes, String sha256) { }
}

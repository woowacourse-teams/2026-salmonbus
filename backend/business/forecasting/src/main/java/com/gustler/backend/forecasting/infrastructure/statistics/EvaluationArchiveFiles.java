package com.gustler.backend.forecasting.infrastructure.statistics;

import com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader.Reference;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Key;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Row;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.State;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** DB 원본 JSON을 재직렬화하지 않고 보존한다. 가공된 통계 입력 파일과 다른 형식이다. */
final class EvaluationArchiveFiles {
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private static final Set<String> COLUMNS = Set.of("vehicle_observation_id", "target_stop_order", "route_version_id",
        "scoring_state", "arrival_observation_id", "seats_on_arrival", "scored_at", "arrived_at",
        "arrival_route_version_id", "arrival_vehicle_id", "arrival_stop_order", "arrival_running_state",
        "arrival_remaining_seats", "arrival_seat_unknown_reason", "arrival_vehicle_trip_key",
        "arrival_quality_direction");

    private EvaluationArchiveFiles() {
    }

    static Encoded encode(EvaluationArchiveBatch batch, List<Row> rows, final long maxBytes) {
        validateEncodingRequest(batch, rows, maxBytes);
        List<Row> ordered = rows.stream()
            .sorted(Comparator.comparingLong((Row row) -> row.key().observationId())
                .thenComparingInt(row -> row.key().stopOrder()))
            .toList();
        ByteArrayOutputStream rowsOutput = new ByteArrayOutputStream();
        Set<Key> seen = new HashSet<>();
        for (Row row : ordered) {
            byte[] original = row.originalJson().getBytes(StandardCharsets.UTF_8);
            validateArchiveRowBytes(row, original, rowsOutput.size(), maxBytes, seen);
            JsonNode node = readArchiveRowObject(original);
            validateArchiveRowFields(node, row, batch.routeVersionId());
            rowsOutput.writeBytes(original);
            rowsOutput.write('\n');
        }
        byte[] bytes = rowsOutput.toByteArray();
        Manifest manifest = new Manifest("evaluation-archive-v1", batch.id(), batch.routeVersionId(),
            batch.qualityRevision(), rows.size(), "evaluations.jsonl", bytes.length, digest(bytes));
        return new Encoded(bytes, JSON.writeValueAsBytes(manifest));
    }

    private static void validateEncodingRequest(EvaluationArchiveBatch batch, List<Row> rows, final long maxBytes) {
        if (!hasMatchingBatchRows(batch, rows) || !hasValidFileLimit(maxBytes)) {
            throw new IllegalArgumentException("묶음 건수와 파일 크기 한도가 올바르지 않다");
        }
    }

    private static boolean hasMatchingBatchRows(EvaluationArchiveBatch batch, List<Row> rows) {
        if (batch == null || rows == null) {
            return false;
        }
        return rows.size() == batch.rowCount();
    }

    private static boolean hasValidFileLimit(long maxBytes) {
        return maxBytes >= 1 && maxBytes <= 16 * 1024 * 1024;
    }

    private static void validateArchiveRowBytes(
        Row row,
        byte[] original,
        final int encodedBytes,
        final long maxBytes,
        Set<Key> seen
    ) {
        if (exceedsArchiveLimit(original, encodedBytes, maxBytes)) {
            throw invalidArchiveRow();
        }
        if (containsLineBreak(row.originalJson()) || !seen.add(row.key())) {
            throw invalidArchiveRow();
        }
        if (!digest(original).equals(row.sha256())) {
            throw invalidArchiveRow();
        }
    }

    private static boolean exceedsArchiveLimit(byte[] original, int encodedBytes, long maxBytes) {
        return original.length > 64 * 1024 || encodedBytes + (long) original.length + 1 > maxBytes;
    }

    private static boolean containsLineBreak(String originalJson) {
        return originalJson.contains("\n") || originalJson.contains("\r");
    }

    private static IllegalArgumentException invalidArchiveRow() {
        return new IllegalArgumentException("원본의 크기·중복·검증값이 올바르지 않다");
    }

    private static JsonNode readArchiveRowObject(byte[] original) {
        JsonNode node = JSON.readTree(original);
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("정산 원본은 JSON 객체여야 한다");
        }
        return node;
    }

    private static void validateArchiveRowFields(JsonNode node, Row row, final long routeVersionId) {
        Set<String> names = new HashSet<>();
        node.properties().forEach(entry -> names.add(entry.getKey()));
        if (!names.equals(COLUMNS) || !hasValidArchiveRowValues(node, row, routeVersionId)) {
            throw new IllegalArgumentException("원본 열·키·노선·완료 상태가 예약과 다르다");
        }
    }

    private static boolean hasValidArchiveRowValues(JsonNode node, Row row, final long routeVersionId) {
        return node.get("vehicle_observation_id").isIntegralNumber()
            && node.get("target_stop_order").isIntegralNumber()
            && node.get("route_version_id").isIntegralNumber()
            && node.get("vehicle_observation_id").canConvertToLong()
            && node.get("target_stop_order").canConvertToInt()
            && node.get("route_version_id").canConvertToLong()
            && node.get("vehicle_observation_id").asLong() == row.key().observationId()
            && node.get("target_stop_order").asInt() == row.key().stopOrder()
            && node.get("route_version_id").asLong() == routeVersionId
            && Set.of("SETTLED", "SKIPPED", "LOST", "SEAT_MISSING", "QUALITY_EXCLUDED")
            .contains(node.get("scoring_state").asString())
            && node.get("scored_at").isString();
    }

    static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static Manifest manifest(Reference reference, byte[] bytes, final long maxBytes) {
        validateManifestBytes(reference, bytes);
        Manifest manifest = JSON.readValue(bytes, Manifest.class);
        if (!matchesReference(manifest, reference, maxBytes)) {
            throw new IllegalArgumentException("이관 설명 파일의 범위나 크기가 올바르지 않다");
        }
        return manifest;
    }

    private static void validateManifestBytes(Reference reference, byte[] bytes) {
        if (bytes.length > 16384 || !digest(bytes).equals(reference.manifestSha256())) {
            throw new IllegalArgumentException("S3 설명 파일이 DB의 검증 기록과 다르다");
        }
    }

    private static boolean matchesReference(Manifest manifest, Reference reference, final long maxBytes) {
        return manifest != null
            && "evaluation-archive-v1".equals(manifest.format())
            && reference.batchId().equals(manifest.batchId())
            && reference.routeVersionId() == manifest.routeVersionId()
            && reference.qualityRevision() == manifest.qualityRevision()
            && reference.rowCount() == manifest.rowCount()
            && "evaluations.jsonl".equals(manifest.file())
            && manifest.bytes() >= 1
            && manifest.bytes() <= maxBytes
            && manifest.sha256() != null
            && manifest.sha256().matches("[0-9a-f]{64}");
    }

    static List<Row> decode(
        Reference reference,
        byte[] manifestBytes,
        byte[] encodedRows,
        final long maxBytes
    ) {
        Manifest manifest = manifest(reference, manifestBytes, maxBytes);
        validateEncodedRows(manifest, encodedRows);
        String text = decodeUtf8(encodedRows);
        String[] lines = text.split("\n", -1);
        validateRowCount(reference, lines);
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < lines.length - 1; i++) {
            JsonNode node = JSON.readTree(lines[i]);
            validateDecodedRowKey(node);
            rows.add(new Row(new Key(node.get("vehicle_observation_id")
                .asLong(), node.get("target_stop_order")
                .asInt()),
                lines[i], digest(lines[i].getBytes(StandardCharsets.UTF_8))));
        }
        // 같은 열·중복·정렬·노선 검사를 읽을 때도 적용한다. lease는 파일 내용에 포함되지 않는다.
        EvaluationArchiveBatch batch =
            new EvaluationArchiveBatch(reference.batchId(), reference.routeVersionId(), reference.qualityRevision(),
                new UUID(0, 0), Instant.EPOCH, State.RESERVED, reference.rowCount(), null);
        if (!Arrays.equals(encode(batch, rows, maxBytes).rows(), encodedRows)) {
            throw new IllegalArgumentException("원본 파일의 정렬이나 표현이 올바르지 않다");
        }
        return List.copyOf(rows);
    }

    private static void validateEncodedRows(Manifest manifest, byte[] encodedRows) {
        if (encodedRows.length != manifest.bytes() || !digest(encodedRows).equals(manifest.sha256())) {
            throw new IllegalArgumentException("보존한 원본의 크기나 검증값이 다르다");
        }
    }

    private static String decodeUtf8(byte[] encodedRows) {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(encodedRows)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("이관 원본의 UTF-8이 올바르지 않다", exception);
        }
    }

    private static void validateRowCount(Reference reference, String[] lines) {
        if (lines.length != reference.rowCount() + 1 || !lines[lines.length - 1].isEmpty()) {
            throw new IllegalArgumentException("이관 원본의 건수가 설명 파일과 다르다");
        }
    }

    private static void validateDecodedRowKey(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("원본 키가 올바르지 않다");
        }
        JsonNode observationId = node.get("vehicle_observation_id");
        JsonNode stopOrder = node.get("target_stop_order");
        if (!isLongNumber(observationId) || !isIntegerNumber(stopOrder)) {
            throw new IllegalArgumentException("원본 키가 올바르지 않다");
        }
    }

    private static boolean isLongNumber(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToLong();
    }

    private static boolean isIntegerNumber(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.canConvertToInt();
    }

    /** 인코더가 새로 만든 바이트 배열을 같은 패키지의 저장·검증 단계로 넘긴다. 전달 뒤 배열을 수정하지 않는다. */
    record Encoded(byte[] rows, byte[] manifest) {
    }
    record Manifest(String format, UUID batchId, long routeVersionId, long qualityRevision,
        int rowCount, String file, long bytes, String sha256) {
    }
}

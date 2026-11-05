package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.api.evaluation.EvaluationArchiveObjects;
import com.gustler.backend.forecasting.api.evaluation.LesEvaluationArchive;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** 파일 전송·해시 검증은 DB 트랜잭션 밖에서 조율하고, DB 확정은 저장 포트에 맡긴다. */
@Component
@ConditionalOnProperty(prefix = "forecast.archive", name = "enabled", havingValue = "true")
public class LesEvaluationArchiveService implements LesEvaluationArchive {
    private static final String BUCKET = "techcourse-project-2026";
    private static final String ROOT = "salmonbus-be/les-evaluation-archive/v1/";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long MAX_BYTES = 16 * 1024 * 1024;
    private final LesEvaluationArchiveStore store;
    private final EvaluationArchiveObjects objects;
    private final boolean deleteEnabled;
    private final int pageSize;

    public LesEvaluationArchiveService(
        LesEvaluationArchiveStore store,
        EvaluationArchiveObjects objects,
        @Value("${forecast.archive.delete-enabled:false}") final boolean deleteEnabled,
        @Value("${forecast.archive.page-size:500}") final int pageSize
    ) {
        if (pageSize < 1 || pageSize > 1000) {
            throw new IllegalArgumentException("Les 이관 페이지는 1~1000개여야 한다");
        }
        this.store = store;
        this.objects = objects;
        this.deleteEnabled = deleteEnabled;
        this.pageSize = pageSize;
    }

    @Override
    public void advance() {
        outsideTransaction();
        if (deleteEnabled) {
            Optional<String> verified = store.verifiedForPurge();
            if (verified.isPresent()) {
                String hash = verified.get();
                store.archive(readEnvelope(download(hash)), hash, true);
                return;
            }
        }
        List<LesEvaluationArchiveStore.Row> rows = store.candidates(pageSize);
        if (rows.isEmpty()) {
            return;
        }
        byte[] bytes = encode(rows);
        String hash = HexFormat.of().formatHex(sha256(bytes));
        storeAndVerify(hash, bytes);
        store.archive(rows, hash, deleteEnabled);
    }

    private byte[] encode(List<LesEvaluationArchiveStore.Row> rows) {
        ObjectNode envelope = JSON.createObjectNode().put("schema", "les-evaluation-archive-v1");
        ArrayNode entries = JSON.createArrayNode();
        for (LesEvaluationArchiveStore.Row row : rows) {
            ObjectNode entry = JSON.createObjectNode();
            entry.set("result", JSON.readTree(row.originalJson()));
            entry.set("evidence", JSON.readTree(row.evidenceJson()));
            entries.add(entry);
        }
        envelope.set("entries", entries);
        byte[] bytes = JSON.writeValueAsBytes(envelope);
        if (bytes.length > MAX_BYTES) {
            throw new IllegalStateException("Les 이관 원본이 파일 한도를 넘었다. 페이지 크기를 줄여야 한다");
        }
        return bytes;
    }

    @Override
    public int restore(
        final long observationId,
        final int stop,
        String target,
        String labelVersion,
        final long qualityRevision
    ) {
        outsideTransaction();
        LesEvaluationArchiveStore.Key wanted = new LesEvaluationArchiveStore.Key(observationId, stop, target,
            labelVersion, qualityRevision);
        String hash = store.objectHash(wanted);
        LesEvaluationArchiveStore.Row selected = null;
        for (LesEvaluationArchiveStore.Row row : readEnvelope(download(hash))) {
            if (wanted.equals(key(JSON.readTree(row.originalJson())))) {
                if (selected != null) {
                    throw new IllegalStateException("Les 이관 식별자가 중복됐다");
                }
                selected = row;
            }
        }
        if (selected == null) {
            throw new IllegalStateException("Les 이관 파일에 요청한 식별자가 없다");
        }
        return store.restore(wanted, hash, selected.originalJson());
    }

    private static List<LesEvaluationArchiveStore.Row> readEnvelope(byte[] bytes) {
        JsonNode envelope = JSON.readTree(bytes);
        if (!"les-evaluation-archive-v1".equals(envelope.path("schema").asString())
            || !envelope.path("entries").isArray()) {
            throw new IllegalStateException("Les 이관 형식이 다르다");
        }
        List<LesEvaluationArchiveStore.Row> rows = new ArrayList<>();
        for (JsonNode entry : envelope.get("entries")) {
            if (!entry.path("result").isObject() || !entry.path("evidence").isArray()) {
                throw new IllegalStateException("Les 이관 결과 또는 근거 형식이 다르다");
            }
            rows.add(
                new LesEvaluationArchiveStore.Row(entry.get("result").toString(), entry.get("evidence").toString()));
        }
        return List.copyOf(rows);
    }

    private void storeAndVerify(String hash, byte[] bytes) {
        Path temp = null;
        try {
            temp = Files.createTempFile("les-archive-", ".json");
            Files.write(temp, bytes);
            uploadOrVerifyExisting(hash, bytes, temp);
            requireIdenticalDownload(hash, bytes);
        } catch (IOException error) {
            throw new IllegalStateException("Les 이관 임시 파일 실패", error);
        } finally {
            remove(temp);
        }
    }

    private void uploadOrVerifyExisting(String hash, byte[] bytes, Path source) {
        try {
            objects.putIfAbsent(BUCKET, ROOT + hash + ".json", source,
                Base64.getEncoder().encodeToString(sha256(bytes)));
        } catch (RuntimeException writeFailure) {
            try {
                requireIdenticalDownload(hash, bytes);
            } catch (RuntimeException readFailure) {
                writeFailure.addSuppressed(readFailure);
                throw writeFailure;
            }
        }
    }

    private void requireIdenticalDownload(String hash, byte[] bytes) {
        if (!Arrays.equals(download(hash), bytes)) {
            throw new IllegalStateException("Les 이관 읽기 검증에 실패했다");
        }
    }

    private byte[] download(String hash) {
        Path temp = null;
        Path directory = null;
        try {
            directory = Files.createTempDirectory("les-archive-read-");
            temp = directory.resolve("result.json");
            objects.download(BUCKET, ROOT + hash + ".json", temp, MAX_BYTES);
            if (Files.size(temp) > MAX_BYTES) {
                throw new IllegalStateException("Les 이관 파일 크기 초과");
            }
            byte[] bytes = Files.readAllBytes(temp);
            if (!HexFormat.of().formatHex(sha256(bytes)).equals(hash)) {
                throw new IllegalStateException("Les 이관 체크섬 불일치");
            }
            return bytes;
        } catch (IOException error) {
            throw new IllegalStateException("Les 이관 읽기 실패", error);
        } finally {
            remove(temp);
            remove(directory);
        }
    }

    private static void remove(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException error) {
                throw new IllegalStateException("Les 임시 파일 정리 실패", error);
            }
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void outsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Les 객체 전송은 DB 트랜잭션 밖에서 실행해야 한다");
        }
    }

    private static LesEvaluationArchiveStore.Key key(JsonNode row) {
        return new LesEvaluationArchiveStore.Key(row.path("vehicle_observation_id").longValue(),
            row.path("target_stop_order").intValue(),
            row.path("target_kind").asString(), row.path("label_version").asString(),
            row.path("quality_revision").longValue());
    }
}

package com.gustler.backend.forecasting.infrastructure.les;

import com.gustler.backend.forecasting.application.les.LesFeatureUnavailableException;
import com.gustler.backend.forecasting.domain.model.les.LesHistoryCompactor;
import com.gustler.backend.forecasting.domain.model.les.LesObservation;
import com.gustler.backend.forecasting.domain.model.les.LesPassEvent;
import com.gustler.backend.forecasting.domain.model.les.LesVehiclePrefix;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 해시를 확인한 체크포인트를 읽고, 커서가 후퇴하지 않도록 잠근 뒤 파일을 원자적으로 교체한다. */
public final class LesHistorySeedReader {
    private static final ConcurrentHashMap<String, Object> WRITE_LOCKS = new ConcurrentHashMap<>();
    private final String directory;
    private final int maximumRows;

    public LesHistorySeedReader(String directory, final int maximumRows) {
        this.directory = directory;
        this.maximumRows = maximumRows;
    }

    /** 여섯 필드는 단일 체크포인트의 관측·누적 상태·사건과 확정 커서를 나타내는 저장 계약이다. */
    public record Seed(
        List<LesObservation> observations,
        long batchId,
        long rank,
        Instant through,
        Map<String, LesVehiclePrefix> prefix,
        List<LesPassEvent> events
    ) {
        public Seed {
            observations = List.copyOf(observations);
            prefix = Map.copyOf(prefix);
            events = List.copyOf(events);
        }

        public Seed(
            List<LesObservation> observations,
            final long batchId,
            final long rank,
            Instant through) {
            this(observations, batchId, rank, through, Map.of(), List.of());
        }

        private boolean isNewerThan(Seed other) {
            if (through.isAfter(other.through())) {
                return true;
            }
            return through.equals(other.through()) && batchId > other.batchId();
        }
    }

    public Seed read(final long routeVersionId) {
        if (directory.isBlank()) {
            return null;
        }
        Path file = Path.of(directory).resolve(routeVersionId + ".json");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            if (Files.size(file) > 256L * 1024 * 1024) {
                throw unavailable("LES_SEED_SIZE_LIMIT_EXCEEDED");
            }
            return LesHistorySeedCodec.decode(verifiedPayload(file), routeVersionId, maximumRows);
        } catch (IOException | NoSuchAlgorithmException | IllegalArgumentException failure) {
            throw unavailable("LES_SEED_INVALID: " + failure.getMessage());
        }
    }

    private static JsonNode verifiedPayload(Path file) throws IOException, NoSuchAlgorithmException {
        byte[] bytes = Files.readAllBytes(file);
        JsonNode root = new ObjectMapper().readTree(bytes);
        if (root.has("payload") && root.has("sha256")) {
            byte[] payload = root.path("payload").asText().getBytes(StandardCharsets.UTF_8);
            JsonNode parsed = new ObjectMapper().readTree(payload);
            verifyHash(payload, root.path("sha256").asText());
            return parsed;
        }
        verifyHash(bytes, Files.readString(Path.of(file + ".sha256")).trim());
        return root;
    }

    private static void verifyHash(byte[] bytes, String expected) throws NoSuchAlgorithmException {
        if (!digest(bytes).equals(expected)) {
            throw unavailable("LES_SEED_HASH_MISMATCH");
        }
    }

    /** 복구 입력의 완전성을 확인한 호출자가 사용한다. 체크섬과 내용은 하나의 파일로 교체된다. */
    public synchronized void write(final long routeVersionId, Seed seed) {
        if (directory.isBlank()) {
            return;
        }
        LesHistoryCompactor.History compact = LesHistoryCompactor.compact(
            new LesHistoryCompactor.History(seed.prefix(), seed.observations(), seed.events()), seed.through());
        Map<String, Object> payload = LesHistorySeedCodec.encode(routeVersionId, seed, compact);
        try {
            Path folder = Path.of(directory);
            Files.createDirectories(folder);
            Path target = folder.resolve(routeVersionId + ".json");
            Object lock = WRITE_LOCKS.computeIfAbsent(target.toAbsolutePath().normalize().toString(),
                key -> new Object());
            synchronized (lock) {
                writeWithFileLock(routeVersionId, seed, payload, folder, target);
            }
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw unavailable("LES_CHECKPOINT_WRITE_FAILED: " + failure.getMessage());
        }
    }

    private void writeWithFileLock(
        final long routeVersionId,
        Seed seed,
        Map<String, Object> payload,
        Path folder,
        Path target) throws IOException, NoSuchAlgorithmException {
        try (FileChannel channel = FileChannel.open(folder.resolve(routeVersionId + ".lock"),
            StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock ignored = channel.lock()) {
            Seed current = read(routeVersionId);
            if (current != null && current.isNewerThan(seed)) {
                return;
            }
            replaceAtomically(routeVersionId, payload, folder, target);
        }
    }

    private static void replaceAtomically(
        final long routeVersionId,
        Map<String, Object> payload,
        Path folder,
        Path target) throws IOException, NoSuchAlgorithmException {
        byte[] bytes = new ObjectMapper().writeValueAsBytes(payload);
        byte[] envelope = new ObjectMapper().writeValueAsBytes(Map.of(
            "sha256", digest(bytes), "payload", new String(bytes, StandardCharsets.UTF_8)));
        Path temporary = Files.createTempFile(folder, routeVersionId + "-", ".tmp");
        Files.write(temporary, envelope);
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String digest(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static LesFeatureUnavailableException unavailable(String reason) {
        return new LesFeatureUnavailableException(reason);
    }
}

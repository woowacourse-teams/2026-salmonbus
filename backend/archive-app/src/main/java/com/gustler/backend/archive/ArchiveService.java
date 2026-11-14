package com.gustler.backend.archive;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 노선 버전·한국 날짜 하나씩 운영 원본을 S3 로 내보내고, 다시 읽어 검증한 파일의 행만 DB 에서 지운다.
 *
 * <p>내보내기 전 작업은 노선 잠금 안에서 남은 일을 확인하며 이관 경계를 닫고, 노선 잠금 없이 최근 기록을 한 번 확인한 뒤 내보낸다.
 * 내보내기를 마친 작업({@code VERIFIED}, {@code PURGING})은 삭제부터 이어 간다. 삭제는 시도마다 남은 일을 한 번 다시 보고, 파일마다
 * 임대·경계·하차 이력 보존 조건만 본다. 노선 잠금을 제때 얻지 못하면({@code 55P03}) 같은 단계를 잠시 쉬고 세 번까지 다시 시도한다.
 */
@Component
@ConditionalOnProperty(prefix = "archive", name = "enabled", havingValue = "true")
public class ArchiveService {
    private static final Logger LOG = LoggerFactory.getLogger(ArchiveService.class);
    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private static final int LOCK_RETRIES = 3;
    private static final Duration DEFAULT_LOCK_RETRY_PAUSE = Duration.ofSeconds(1);
    private static final Duration MAX_PAUSE = Duration.ofMinutes(1);
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();
    private final ArchiveRepository repository;
    private final ArchiveObjects objects;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final String prefix;
    private final int pageSize;
    private final boolean deleteEnabled;
    private final Duration purgePause;
    private final Duration lockRetryPause;

    public ArchiveService(
        ArchiveRepository repository,
        ArchiveObjects objects,
        Clock clock,
        MeterRegistry metrics,
        String prefix,
        final int pageSize,
        final boolean deleteEnabled
    ) {
        this(repository, objects, clock, metrics, prefix, pageSize, deleteEnabled, Duration.ZERO,
            DEFAULT_LOCK_RETRY_PAUSE);
    }

    /**
     * @param purgePause 파일 삭제 사이에 쉬는 시간이다. 재배포 없이 처리량을 줄이는 데 쓴다. 0~1분만 받는다.
     * @param lockRetryPause 노선 잠금을 얻지 못한 단계를 다시 시도하기 전에 쉬는 시간이다. 0~1분만 받는다.
     */
    @Autowired
    public ArchiveService(
        ArchiveRepository repository,
        ArchiveObjects objects,
        Clock clock,
        MeterRegistry metrics,
        @Value("${archive.prefix}") String prefix,
        @Value("${archive.page-size:2000}") final int pageSize,
        @Value("${archive.delete-enabled:false}") final boolean deleteEnabled,
        @Value("${archive.purge-pause:0s}") Duration purgePause,
        @Value("${archive.lock-retry-pause:1s}") Duration lockRetryPause
    ) {
        if (!isValidDestination(prefix) || !isValidPageSize(pageSize)) {
            throw new IllegalArgumentException("새 S3 prefix와 1~10000행 페이지가 필요하다");
        }
        if (!isValidPause(purgePause) || !isValidPause(lockRetryPause)) {
            throw new IllegalArgumentException("삭제 사이와 잠금 재시도 전 쉬는 시간은 0~1분이어야 한다");
        }
        this.repository = repository;
        this.objects = objects;
        this.clock = clock;
        this.metrics = metrics;
        this.prefix = prefix;
        this.pageSize = pageSize;
        this.deleteEnabled = deleteEnabled;
        this.purgePause = purgePause;
        this.lockRetryPause = lockRetryPause;
        LOG.info("event=operational_archive_config enabled=true deleteEnabled={} pageSize={} purgePauseMs={}",
            deleteEnabled, pageSize, purgePause.toMillis());
    }

    private boolean isValidPause(Duration pause) {
        return !pause.isNegative() && pause.compareTo(MAX_PAUSE) <= 0;
    }

    private boolean isValidDestination(String prefix) {
        return prefix.matches("[a-zA-Z0-9/_=-]+/") && !prefix.startsWith("/") && !prefix.startsWith("salmonbus-be/");
    }

    private boolean isValidPageSize(final int pageSize) {
        return pageSize >= 1 && pageSize <= 10000;
    }

    @Scheduled(fixedDelayString = "${archive.interval:1s}")
    public synchronized void advance() {
        Optional<ArchiveRepository.Job> found = repository.claim(ArchiveRetention.cutoff(clock));
        if (found.isEmpty()) {
            return;
        }
        ArchiveRepository.Job job = found.get();
        final long started = System.nanoTime();
        try {
            process(job, started);
        } catch (ArchiveDeferral deferral) {
            defer(job, deferral.reason());
        } catch (RuntimeException failure) {
            recordFailure(job, failure);
        }
    }

    private void process(ArchiveRepository.Job job, final long started) {
        if (job.state().equals("EXPORTING")) {
            close(job);
            if (hasRecentRecords(job)) {
                return;
            }
            export(job);
        }
        verifyManifest(job);
        if (!deleteEnabled) {
            defer(job, "DELETE_DISABLED");
            return;
        }
        purgeVerifiedFiles(job);
        repository.finish(job);
        LOG.info("event=operational_archive status=DONE archiveId={} routeVersionId={} date={} durationMs={}",
            job.id(), job.version(), job.date(), (System.nanoTime() - started) / 1000000);
    }

    private void close(ArchiveRepository.Job job) {
        String reason = withLockRetry(job, "close", () -> repository.closeRange(job));
        if (!reason.equals("READY")) {
            throw new ArchiveDeferral(reason);
        }
    }

    /**
     * 닫은 날짜의 최근 기록을 노선 잠금 없이 한 번 확인한다. 있으면 보관 기준 시각이 바뀌는 다음 한국 자정까지 보류한다. 닫은 날짜의 행은
     * 바뀌지 않으므로 그 전에 다시 읽어도 답이 같다.
     */
    private boolean hasRecentRecords(ArchiveRepository.Job job) {
        final long started = System.nanoTime();
        String reason = repository.recentRecords(job);
        LOG.info("event=operational_archive status=RECENT_CHECKED archiveId={} routeVersionId={} date={} result={}"
            + " durationMs={}", job.id(), job.version(), job.date(), reason, (System.nanoTime() - started) / 1000000);
        metrics.timer("salmonbus_archive_stage", "stage", "recent_check").record(System.nanoTime() - started,
            TimeUnit.NANOSECONDS);
        if (reason.equals("READY")) {
            return false;
        }
        repository.deferUntil(job, reason, ArchiveRetention.nextCutoffChange(clock));
        recordDeferral(job, reason);
        return true;
    }

    private void defer(ArchiveRepository.Job job, String reason) {
        repository.defer(job, reason);
        recordDeferral(job, reason);
    }

    private void recordDeferral(ArchiveRepository.Job job, String reason) {
        metrics.counter("salmonbus_archive_jobs", "status", "deferred", "reason", reason).increment();
        LOG.info("event=operational_archive status=DEFERRED archiveId={} routeVersionId={} date={} reason={}",
            job.id(), job.version(), job.date(), reason);
    }

    /**
     * 노선 잠금을 잡는 한 단계를 실행한다. 잠금 대기 제한({@code 55P03})에 걸리면 쉬었다가 같은 단계를 세 번까지 다시 시도하고, 그래도
     * 못 얻으면 마지막 실패를 그대로 던진다. 다른 실패는 다시 시도하지 않는다. 단계는 다시 실행해도 결과가 같아야 한다.
     */
    private <T> T withLockRetry(ArchiveRepository.Job job, String stage, Supplier<T> step) {
        for (int retry = 0;; retry++) {
            try {
                return step.get();
            } catch (RuntimeException failure) {
                if (retry >= LOCK_RETRIES || !sqlState(failure).equals(LOCK_NOT_AVAILABLE)) {
                    throw failure;
                }
                metrics.counter("salmonbus_archive_lock_retries", "stage", stage).increment();
                LOG.info("event=operational_archive status=LOCK_RETRY archiveId={} routeVersionId={} date={} stage={}"
                    + " retry={}", job.id(), job.version(), job.date(), stage, retry + 1);
                pause(lockRetryPause);
            }
        }
    }

    private void pause(Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ARCHIVE_INTERRUPTED", interrupted);
        }
    }

    private void purgeVerifiedFiles(ArchiveRepository.Job job) {
        repository.requireDeletionIndexes();
        String reason = withLockRetry(job, "purge_readiness", () -> repository.purgeReadiness(job));
        if (!reason.equals("READY")) {
            throw new ArchiveDeferral(reason);
        }
        boolean first = true;
        for (ArchiveRepository.File file : repository.files(job)) {
            if (file.purged()) {
                continue;
            }
            if (!first) {
                pause(purgePause);
            }
            first = false;
            List<String> rows = readVerifiedRows(file);
            final long started = System.nanoTime();
            final int removed = withLockRetry(job, "purge", () -> repository.purge(job, file, rows));
            LOG.info(
                "event=operational_archive status=PURGED_FILE archiveId={} routeVersionId={} date={}"
                    + " fileNumber={} table={} deletedRows={} fileRows={} fileBytes={} dbDurationMs={}",
                job.id(), job.version(), job.date(), file.number(), file.table(), removed, file.rows(), file.bytes(),
                (System.nanoTime() - started) / 1000000);
            metrics.counter("salmonbus_archive_rows", "stage", "purged", "table", file.table()).increment(removed);
            metrics.timer("salmonbus_archive_stage", "stage", "purge").record(System.nanoTime() - started,
                TimeUnit.NANOSECONDS);
        }
    }

    private List<String> readVerifiedRows(ArchiveRepository.File file) {
        byte[] bytes = objects.read(file.key(), file.bytes());
        if (!ArchiveFiles.digest(bytes).equals(file.hash())) {
            throw new IllegalStateException("PURGE_FILE_HASH");
        }
        List<String> rows = ArchiveFiles.decode(bytes);
        if (rows.size() != file.rows()) {
            throw new IllegalStateException("PURGE_FILE_COUNT");
        }
        return rows;
    }

    private void recordFailure(ArchiveRepository.Job job, RuntimeException failure) {
        try {
            repository.defer(job, failureCode(failure));
        } catch (RuntimeException deferredFailure) {
            failure.addSuppressed(deferredFailure);
        }
        metrics.counter("salmonbus_archive_jobs", "status", "failed", "reason", failure.getClass().getSimpleName())
            .increment();
        LOG.warn(
            "event=operational_archive status=FAILED archiveId={} routeVersionId={} date={}"
                + " failureType={} reason={} sqlState={}",
            job.id(), job.version(), job.date(), failure.getClass().getSimpleName(), failureCode(failure), sqlState(failure));
    }

    private String failureCode(RuntimeException failure) {
        String message = failure.getMessage();
        if (hasSafeFailureCode(failure, message)) {
            return message;
        }
        return failure.getClass().getSimpleName();
    }

    private boolean hasSafeFailureCode(RuntimeException failure, String message) {
        return failure instanceof IllegalStateException && message != null && message.matches("[A-Za-z_]{1,80}");
    }

    private String sqlState(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 10; depth++) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            cause = cause.getCause();
        }
        return "NONE";
    }

    private String root(ArchiveRepository.Job job) {
        return prefix + "source_date=" + job.date() + "/route_version=" + job.version() + "/archive_id=" + job.id()
            + "/";
    }

    private void export(ArchiveRepository.Job job) {
        repository.resetExport(job);
        int number = 0;
        Set<Long> deployments = new TreeSet<>();
        for (ArchiveTable table : ArchiveTable.values()) {
            String cursor = "[]";
            while (true) {
                ArchiveRepository.Page page = repository.page(job, table, cursor, pageSize);
                if (page.rows().isEmpty()) {
                    break;
                }
                deployments.addAll(page.modelDeploymentIds());
                write(job, number++, table.table, page.rows());
                cursor = page.cursor();
            }
        }
        long cursor = 0;
        while (true) {
            ArchiveRepository.Page page = repository.evidence(job, cursor, pageSize);
            if (page.rows().isEmpty()) {
                break;
            }
            write(job, number++, "evidence", page.rows());
            cursor = Long.parseLong(page.cursor());
        }
        List<String> dimensions = repository.dimensions(job, JSON.writeValueAsString(deployments));
        if (!dimensions.isEmpty()) {
            write(job, number++, "dimensions", dimensions);
        }
        byte[] manifest = manifest(job);
        objects.putVerified(root(job) + "manifest-" + ArchiveFiles.digest(manifest) + ".json", manifest);
        repository.verified(job, ArchiveFiles.digest(manifest));
    }

    private void write(
        ArchiveRepository.Job job,
        final int number,
        String table,
        List<String> rows
    ) {
        final long started = System.nanoTime();
        byte[] bytes = ArchiveFiles.encode(rows);
        if (!ArchiveFiles.decode(bytes).equals(rows)) {
            throw new IllegalStateException("ENCODING_CHANGED_SOURCE");
        }
        String key = root(job) + String.format("%06d-%s-%s.jsonl.gz", number, table, ArchiveFiles.digest(bytes));
        objects.putVerified(key, bytes);
        repository.recordFile(job, new ArchiveRepository.File(number, table, key, ArchiveFiles.digest(bytes),
            rows.size(), bytes.length, false));
        metrics.counter("salmonbus_archive_rows", "stage", "verified", "table", table).increment(rows.size());
        metrics.counter("salmonbus_archive_bytes", "stage", "verified").increment(bytes.length);
        metrics.timer("salmonbus_archive_stage", "stage", "export").record(System.nanoTime() - started,
            TimeUnit.NANOSECONDS);
        LOG.info(
            "event=operational_archive status=VERIFIED_FILE archiveId={} routeVersionId={} date={} fileNumber={} table={}"
                + " rows={} bytes={} durationMs={}",
            job.id(), job.version(), job.date(), number, table, rows.size(), bytes.length,
            (System.nanoTime() - started) / 1000000);
    }

    private byte[] manifest(ArchiveRepository.Job job) {
        List<Object> files = new ArrayList<>();
        for (ArchiveRepository.File f : repository.files(job)) {
            files.add(Map.of("number", f.number(), "table", f.table(), "key", f.key(), "sha256", f.hash(), "rows",
                f.rows(), "bytes", f.bytes()));
        }
        return JSON
            .writeValueAsBytes(Map.of("format", "salmonbus-lake-v2", "archiveId", job.id().toString(), "routeVersionId",
                job.version(), "sourceDate", job.date().toString(), "cutoff", job.cutoff().toString(), "files", files));
    }

    private void verifyManifest(ArchiveRepository.Job job) {
        byte[] expected = manifest(job);
        if (!Arrays.equals(expected,
            objects.read(root(job) + "manifest-" + repository.manifestHash(job) + ".json", expected.length))) {
            throw new IllegalStateException("MANIFEST_CHANGED");
        }
    }
}

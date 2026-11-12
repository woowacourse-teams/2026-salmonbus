package com.gustler.backend.archive;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(prefix = "archive", name = "enabled", havingValue = "true")
public class ArchiveService {
    private static final Logger LOG = LoggerFactory.getLogger(ArchiveService.class);
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .build();
    private final ArchiveRepository repository;
    private final ArchiveObjects objects;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final String prefix;
    private final int pageSize;
    private final boolean deleteEnabled;

    public ArchiveService(
        ArchiveRepository repository,
        ArchiveObjects objects,
        Clock clock,
        MeterRegistry metrics,
        @Value("${archive.prefix}") String prefix,
        @Value("${archive.page-size:2000}") final int pageSize,
        @Value("${archive.delete-enabled:false}") final boolean deleteEnabled
    ) {
        if (!isValidDestination(prefix) || !isValidPageSize(pageSize)) {
            throw new IllegalArgumentException("새 S3 prefix와 1~10000행 페이지가 필요하다");
        }
        this.repository = repository;
        this.objects = objects;
        this.clock = clock;
        this.metrics = metrics;
        this.prefix = prefix;
        this.pageSize = pageSize;
        this.deleteEnabled = deleteEnabled;
        LOG.info("event=operational_archive_config enabled=true deleteEnabled={} pageSize={}", deleteEnabled, pageSize);
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
        } catch (RuntimeException failure) {
            recordFailure(job, failure);
        }
    }

    private void process(ArchiveRepository.Job job, final long started) {
        if (!isReady(job)) {
            return;
        }
        if (job.state().equals("EXPORTING")) {
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

    private boolean isReady(ArchiveRepository.Job job) {
        String readiness = repository.readiness(job);
        if (readiness.equals("READY")) {
            return true;
        }
        defer(job, readiness);
        return false;
    }

    private void defer(ArchiveRepository.Job job, String reason) {
        repository.defer(job, reason);
        metrics.counter("salmonbus_archive_jobs", "status", "deferred", "reason", reason).increment();
        LOG.info("event=operational_archive status=DEFERRED archiveId={} routeVersionId={} date={} reason={}",
            job.id(), job.version(), job.date(), reason);
    }

    private void purgeVerifiedFiles(ArchiveRepository.Job job) {
        repository.requireDeletionIndexes();
        for (ArchiveRepository.File file : repository.files(job)) {
            if (file.purged()) {
                continue;
            }
            List<String> rows = readVerifiedRows(file);
            final long started = System.nanoTime();
            final int removed = repository.purge(job, file, rows);
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
        repository.closeRange(job);
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

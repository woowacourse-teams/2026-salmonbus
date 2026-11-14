package com.gustler.backend.archive;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * SAL-434 가 지울 옛 테이블을 통째로, 기본 키 순서의 조각으로 나눠 S3 에 내보내고 다시 읽어 확인한 뒤 장부({@code daily_export})에
 * 조각마다 한 줄을 적는다. 줄을 지우거나 바꾸지 않는다. 옛 테이블을 지우는 것은 사람이 승인하고 실행하는
 * {@code sal434-03-drop-legacy-tables.sql} 뿐이고, 그 SQL 은 테이블마다 장부의 줄 수 합이 실제 줄 수와 같을 때만 진행한다.
 *
 * <p>테이블을 목록 순서로 하나씩 처리한다. 장부의 마지막 조각 {@code key_to} 다음 키부터 페이지를 읽어 파일을 올리고, 조각이
 * {@link LegacyExportSettings#segmentBytes()} 를 넘기거나 회차 상한(줄 수·시간)에 닿거나 테이블 끝에 닿으면 조각을 닫는다. 조각을
 * 닫을 때 manifest 를 올리고, manifest 와 파일을 다시 읽어 해시·줄 수를 확인한 뒤에만 장부에 적는다. 장부에 적지 못한 조각은 다음
 * 회차가 같은 키부터 다시 만든다. 이미 올라간 파일은 지우지 않는다.
 *
 * <p>끝났다는 판정: 마지막 조각 다음 키로 읽은 페이지가 비었고, 모든 조각의 키 범위(앞 조각 {@code key_to} 초과, 자기 {@code key_to}
 * 이하)를 다시 센 줄 수가 장부와 같으면 그 테이블을 끝났다고 본다. 범위가 서로 겹치지 않고 빈틈없이 키 공간을 덮으므로 이 판정은 장부
 * 합이 그 순간의 줄 수와 같다는 뜻이다. 다시 센 값이 다르면 이미 내보낸 범위에서 줄이 지워졌거나(적음) 커서 뒤에 들어왔다(많음).
 * 이때 장부를 고치지 않고 지표·로그로 알린다. 같은 키에서 값만 바뀐 줄은 줄 수로 알아보지 못한다.
 */
public class LegacyExportService {
    private static final Logger LOG = LoggerFactory.getLogger(LegacyExportService.class);
    private final LegacyExportRepository repository;
    private final ArchiveObjects objects;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final LegacyExportSettings settings;
    private final Pause pause;
    private final Map<String, TableState> states = new ConcurrentHashMap<>();
    private final AtomicLong rowsPerSecond = new AtomicLong();
    private final AtomicLong completeTables = new AtomicLong();

    /** 페이지 사이에 쉰다. 시험은 기록만 하는 구현을 넣는다. */
    @FunctionalInterface
    public interface Pause {
        /** 스레드를 재운다. 끼어들면 인터럽트 표시를 남기고 돌아온다. */
        Pause THREAD_SLEEP = duration -> {
            try {
                Thread.sleep(duration);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };

        void sleep(Duration duration);
    }

    public LegacyExportService(
        LegacyExportRepository repository,
        ArchiveObjects objects,
        Clock clock,
        MeterRegistry metrics,
        LegacyExportSettings settings,
        Pause pause
    ) {
        this.repository = repository;
        this.objects = objects;
        this.clock = clock;
        this.metrics = metrics;
        this.settings = settings;
        this.pause = pause;
        gauge("salmonbus_archive_legacy_rows_per_second", Tags.empty(), rowsPerSecond);
        gauge("salmonbus_archive_legacy_complete_tables", Tags.empty(), completeTables);
        LOG.info("event=legacy_export_config enabled=true prefix={} tables={} intervalMs={} pageSize={} pagePauseMs={}"
            + " segmentBytes={} maxRowsPerRun={} maxRunMs={} statementTimeoutMs={}", settings.prefix(),
            repository.tables().size(), settings.interval().toMillis(), settings.pageSize(),
            settings.pagePause().toMillis(), settings.segmentBytes(), settings.maxRowsPerRun(),
            settings.maxRunDuration().toMillis(), settings.statementTimeout().toMillis());
    }

    /** 회차가 멈춘 까닭이다. */
    public enum StopReason {
        /** 모든 테이블을 끝까지 돌았다. */
        NONE,
        /** 회차당 줄 수 상한에 닿았다. */
        ROW_LIMIT,
        /** 회차당 시간 상한에 닿았다. */
        TIME_LIMIT,
        /** 스레드가 인터럽트됐다. */
        INTERRUPTED
    }

    /** 테이블 하나의 이번 회차 결과다. */
    public enum TableStatus {
        /** 끝까지 내보냈고 모든 조각의 범위를 다시 센 값이 장부와 같다. */
        COMPLETE,
        /** 아직 내보내거나 다시 세는 중이다. */
        IN_PROGRESS,
        /** 다시 센 값이 장부와 다른 조각이 있다. */
        MISMATCH,
        /** 이번 회차에 실패했다. 다음 회차에 다시 한다. */
        FAILED,
        /** 테이블이 없다. */
        ABSENT
    }

    /** 처리한 회차의 요약이다. */
    public record RunReport(long rows, int segments, Map<String, TableStatus> tables, StopReason stoppedBy) {
        public RunReport {
            tables = Map.copyOf(tables);
        }
    }

    @Scheduled(fixedDelayString = "${archive.legacy-export.interval:1m}",
        initialDelayString = "${archive.legacy-export.initial-delay:1m}")
    public void scheduled() {
        try {
            run();
        } catch (RuntimeException failure) {
            metrics.counter("salmonbus_archive_legacy_failures", "table", "none", "reason",
                failure.getClass().getSimpleName()).increment();
            LOG.warn("event=legacy_export status=RUN_FAILED failureType={} sqlState={}",
                failure.getClass().getSimpleName(), sqlState(failure));
        }
    }

    /** 한 회차를 처리한다. 같은 프로세스 안에서는 한 번에 한 회차만 돈다. */
    public synchronized RunReport run() {
        final long started = System.nanoTime();
        Budget budget = new Budget(settings.maxRowsPerRun(), clock.instant().plus(settings.maxRunDuration()));
        Map<String, TableStatus> statuses = new LinkedHashMap<>();
        int segments = 0;
        for (String table : repository.tables()) {
            if (budget.exhausted()) {
                break;
            }
            TableRun result = processSafely(table, budget);
            statuses.put(table, result.status);
            segments += result.segments;
        }
        long complete = statuses.values().stream().filter(s -> s == TableStatus.COMPLETE).count();
        completeTables.set(complete);
        final long rows = settings.maxRowsPerRun() - budget.rowsLeft;
        final long millis = Math.max(1, (System.nanoTime() - started) / 1000000);
        rowsPerSecond.set(rows * 1000 / millis);
        LOG.info("event=legacy_export status=RUN rows={} segments={} completeTables={}/{} stoppedBy={} durationMs={}"
            + " rowsPerSecond={}", rows, segments, complete, repository.tables().size(), budget.stop, millis,
            rows * 1000 / millis);
        return new RunReport(rows, segments, statuses, budget.stop);
    }

    private final class Budget {
        long rowsLeft;
        final Instant deadline;
        StopReason stop = StopReason.NONE;

        Budget(final long rows, Instant deadline) {
            this.rowsLeft = rows;
            this.deadline = deadline;
        }

        boolean exhausted() {
            if (stop != StopReason.NONE) {
                return true;
            }
            if (Thread.currentThread().isInterrupted()) {
                stop = StopReason.INTERRUPTED;
            } else if (rowsLeft <= 0) {
                stop = StopReason.ROW_LIMIT;
            } else if (!clock.instant().isBefore(deadline)) {
                stop = StopReason.TIME_LIMIT;
            }
            return stop != StopReason.NONE;
        }
    }

    private record TableRun(TableStatus status, int segments) {
    }

    /** 테이블마다 프로세스 안에 두는 진행 상태와 지표다. 다시 세기의 진행은 기동할 때마다 처음부터 한다. */
    private final class TableState {
        final AtomicLong exportedRows = new AtomicLong();
        final AtomicLong segments = new AtomicLong();
        final AtomicLong complete = new AtomicLong();
        final AtomicLong mismatched = new AtomicLong();
        final List<String> auditedShas = new ArrayList<>();
        final List<Integer> mismatchedSegments = new ArrayList<>();
        boolean absentLogged;

        TableState(String table) {
            Tags tags = Tags.of("table", table);
            gauge("salmonbus_archive_legacy_exported_rows", tags, exportedRows);
            gauge("salmonbus_archive_legacy_segments", tags, segments);
            gauge("salmonbus_archive_legacy_complete", tags, complete);
            gauge("salmonbus_archive_legacy_mismatched_segments", tags, mismatched);
        }

        void ledger(List<LegacyExportRepository.Segment> ledger) {
            exportedRows.set(ledger.stream().mapToLong(LegacyExportRepository.Segment::rows).sum());
            segments.set(ledger.size());
            for (int i = 0; i < auditedShas.size(); i++) {
                if (i >= ledger.size() || !ledger.get(i).sha256().equals(auditedShas.get(i))) {
                    auditedShas.clear();
                    mismatchedSegments.clear();
                    mismatched.set(0);
                    break;
                }
            }
        }
    }

    private TableRun processSafely(String table, Budget budget) {
        TableState state = states.computeIfAbsent(table, TableState::new);
        try {
            return process(table, state, budget);
        } catch (RuntimeException failure) {
            state.complete.set(0);
            String reason = failureCode(failure);
            metrics.counter("salmonbus_archive_legacy_failures", "table", table, "reason", reason).increment();
            LOG.warn("event=legacy_export status=FAILED table={} reason={} failureType={} sqlState={}", table, reason,
                failure.getClass().getSimpleName(), sqlState(failure));
            return new TableRun(TableStatus.FAILED, 0);
        }
    }

    private TableRun process(String table, TableState state, Budget budget) {
        if (!repository.exists(table)) {
            state.complete.set(0);
            if (!state.absentLogged) {
                state.absentLogged = true;
                LOG.info("event=legacy_export status=ABSENT table={}", table);
            }
            return new TableRun(TableStatus.ABSENT, 0);
        }
        List<PartitionManifest.Column> columns = repository.columns(table);
        List<String> unsupported = LegacyExportRepository.unsupportedColumns(columns);
        if (!unsupported.isEmpty()) {
            LOG.warn("event=legacy_export status=UNSUPPORTED_COLUMNS table={} columns={}", table, unsupported);
            throw new IllegalStateException("UNSUPPORTED_COLUMN_TYPE");
        }
        List<String> keys = repository.primaryKey(table);
        List<LegacyExportRepository.Segment> ledger = new ArrayList<>(repository.segments(table));
        for (int i = 0; i < ledger.size(); i++) {
            if (ledger.get(i).number() != i) {
                throw new IllegalStateException("LEDGER_GAP");
            }
            if (ledger.get(i).verifiedAt() == null || ledger.get(i).keyTo() == null) {
                throw new IllegalStateException("LEDGER_UNVERIFIED");
            }
        }
        state.ledger(ledger);
        int written = 0;
        String cursor = ledger.isEmpty() ? null : ledger.getLast().keyTo();
        SegmentWriter writer = null;
        boolean reachedEnd = false;
        while (!budget.exhausted()) {
            final int limit = (int) Math.min(settings.pageSize(), budget.rowsLeft);
            final long pageStarted = System.nanoTime();
            LegacyExportRepository.Page page = repository.page(table, columns, keys, cursor, limit);
            metrics.timer("salmonbus_archive_stage", "stage", "legacy_page").record(System.nanoTime() - pageStarted,
                TimeUnit.NANOSECONDS);
            if (page.rows().isEmpty()) {
                reachedEnd = true;
                break;
            }
            if (writer == null) {
                writer = new SegmentWriter(table, ledger.size(), cursor, page.firstKey());
            }
            writer.add(page.rows());
            writer.keyTo = page.lastKey();
            cursor = page.lastKey();
            budget.rowsLeft -= page.rows().size();
            if (writer.plainBytes >= settings.segmentBytes()) {
                ledger.add(close(writer, columns, keys, state));
                written++;
                writer = null;
            }
            pause.sleep(settings.pagePause());
        }
        if (writer != null) {
            ledger.add(close(writer, columns, keys, state));
            written++;
        }
        state.ledger(ledger);
        if (!reachedEnd) {
            state.complete.set(0);
            return new TableRun(TableStatus.IN_PROGRESS, written);
        }
        return new TableRun(audit(table, columns, keys, ledger, state, budget), written);
    }

    /** 한 조각에 들어갈 줄을 평문 8MiB 안팎의 파일로 나눠 바로 올리고, 닫을 때 쓸 값을 모은다. */
    private final class SegmentWriter {
        final String table;
        final int number;
        final String after;
        final String keyFrom;
        final long started = System.nanoTime();
        final List<PartitionManifest.FileEntry> files = new ArrayList<>();
        final List<String> buffer = new ArrayList<>();
        String keyTo;
        long bufferBytes;
        long plainBytes;
        long gzipBytes;
        long rows;

        SegmentWriter(String table, final int number, String after, String keyFrom) {
            this.table = table;
            this.number = number;
            this.after = after;
            this.keyFrom = keyFrom;
        }

        void add(List<String> page) {
            for (String row : page) {
                final int lineBytes = row.getBytes(StandardCharsets.UTF_8).length + 1;
                if (!buffer.isEmpty() && bufferBytes + lineBytes > PartitionExportService.FILE_PLAIN_BYTES) {
                    flush();
                }
                buffer.add(row);
                bufferBytes += lineBytes;
                plainBytes += lineBytes;
                rows++;
            }
        }

        void flush() {
            if (buffer.isEmpty()) {
                return;
            }
            byte[] bytes = ArchiveFiles.encode(buffer);
            if (!ArchiveFiles.decode(bytes).equals(buffer)) {
                throw new IllegalStateException("ENCODING_CHANGED_SOURCE");
            }
            String sha = ArchiveFiles.digest(bytes);
            final int fileNumber = files.size();
            String key = root(table, number) + String.format("%06d-%s.jsonl.gz", fileNumber, sha);
            objects.putVerified(key, bytes);
            files.add(new PartitionManifest.FileEntry(fileNumber, key, sha, buffer.size(), bytes.length));
            gzipBytes += bytes.length;
            buffer.clear();
            bufferBytes = 0;
        }
    }

    private LegacyExportRepository.Segment close(SegmentWriter writer, List<PartitionManifest.Column> columns,
        List<String> keys, TableState state) {
        writer.flush();
        LegacySegmentManifest manifest = new LegacySegmentManifest(LegacySegmentManifest.FORMAT, writer.table,
            writer.number, writer.after, writer.keyFrom, writer.keyTo, writer.rows, keys, columns,
            clock.instant().toString(), writer.files);
        byte[] manifestBytes = manifest.toBytes();
        String sha = ArchiveFiles.digest(manifestBytes);
        String key = root(writer.table, writer.number) + "manifest-" + sha + ".json";
        objects.putVerified(key, manifestBytes);
        verifyStored(writer.table, writer.number, key, sha, writer.rows, writer.keyTo);
        LegacyExportRepository.Segment segment = new LegacyExportRepository.Segment(writer.number, writer.keyFrom,
            writer.keyTo, writer.rows, key, sha, Instant.parse(manifest.exportedAt()), clock.instant());
        if (repository.record(writer.table, segment, writer.after) != LegacyExportRepository.RecordResult.INSERTED) {
            throw new IllegalStateException("LEDGER_CHANGED");
        }
        final long nanos = System.nanoTime() - writer.started;
        metrics.timer("salmonbus_archive_stage", "stage", "legacy_segment").record(nanos, TimeUnit.NANOSECONDS);
        metrics.counter("salmonbus_archive_rows", "stage", "legacy_exported", "table", writer.table)
            .increment(writer.rows);
        metrics.counter("salmonbus_archive_bytes", "stage", "legacy_exported").increment(writer.gzipBytes);
        state.exportedRows.addAndGet(writer.rows);
        state.segments.incrementAndGet();
        LOG.info("event=legacy_export status=SEGMENT table={} segment={} rows={} files={} plainBytes={} bytes={}"
            + " keyFrom={} keyTo={} key={} durationMs={} ledgerRows={}", writer.table, writer.number, writer.rows,
            writer.files.size(), writer.plainBytes, writer.gzipBytes, writer.keyFrom, writer.keyTo, key,
            nanos / 1000000, state.exportedRows.get());
        return segment;
    }

    /** 조각의 manifest 와 파일을 지금 S3 에서 다시 읽어 확인한다. 어긋나면 사유 코드를 담은 예외를 던진다. */
    private void verifyStored(String table, final int number, String key, String sha, final long rows, String keyTo) {
        byte[] manifestBytes = objects.read(key, ArchiveFiles.MAX_BYTES);
        if (!ArchiveFiles.digest(manifestBytes).equals(sha)) {
            throw new IllegalStateException("MANIFEST_HASH_MISMATCH");
        }
        LegacySegmentManifest manifest = LegacySegmentManifest.parse(manifestBytes);
        if (!LegacySegmentManifest.FORMAT.equals(manifest.format()) || !table.equals(manifest.table())
            || manifest.segment() != number || manifest.rows() != rows || !keyTo.equals(manifest.keyTo())) {
            throw new IllegalStateException("MANIFEST_MISMATCH");
        }
        long stored = 0;
        for (PartitionManifest.FileEntry file : manifest.files()) {
            byte[] bytes = objects.read(file.key(), file.bytes());
            if (bytes.length != file.bytes() || !ArchiveFiles.digest(bytes).equals(file.sha256())) {
                throw new IllegalStateException("FILE_HASH_MISMATCH");
            }
            if (ArchiveFiles.decode(bytes).size() != file.rows()) {
                throw new IllegalStateException("FILE_ROW_COUNT_MISMATCH");
            }
            stored += file.rows();
        }
        if (stored != rows) {
            throw new IllegalStateException("MANIFEST_ROW_COUNT_MISMATCH");
        }
    }

    /**
     * 끝까지 내보낸 테이블의 조각 범위를 차례로 다시 세어 장부와 맞춘다. 회차 상한에 닿으면 멈추고 다음 회차가 이어 센다. 이미 맞춰 본
     * 조각은 장부의 해시가 그대로인 동안 다시 세지 않는다.
     */
    private TableStatus audit(String table, List<PartitionManifest.Column> columns, List<String> keys,
        List<LegacyExportRepository.Segment> ledger, TableState state, Budget budget) {
        final long started = System.nanoTime();
        while (state.auditedShas.size() < ledger.size()) {
            if (budget.exhausted()) {
                state.complete.set(0);
                return TableStatus.IN_PROGRESS;
            }
            final int index = state.auditedShas.size();
            LegacyExportRepository.Segment segment = ledger.get(index);
            String after = index == 0 ? null : ledger.get(index - 1).keyTo();
            final long current = repository.countRange(table, columns, keys, after, segment.keyTo());
            if (current != segment.rows()) {
                state.mismatchedSegments.add(index);
                state.mismatched.set(state.mismatchedSegments.size());
                String kind = current < segment.rows() ? "ROWS_REMOVED" : "ROWS_ADDED_BEHIND_CURSOR";
                metrics.counter("salmonbus_archive_legacy_failures", "table", table, "reason", kind).increment();
                LOG.warn("event=legacy_export status=AUDIT_MISMATCH table={} segment={} kind={} ledgerRows={}"
                    + " currentRows={} after={} keyTo={}", table, index, kind, segment.rows(), current, after,
                    segment.keyTo());
            }
            state.auditedShas.add(segment.sha256());
            pause.sleep(settings.pagePause());
        }
        metrics.timer("salmonbus_archive_stage", "stage", "legacy_audit").record(System.nanoTime() - started,
            TimeUnit.NANOSECONDS);
        if (!state.mismatchedSegments.isEmpty()) {
            state.complete.set(0);
            LOG.warn("event=legacy_export status=MISMATCH table={} ledgerRows={} segments={} mismatchedSegments={}",
                table, state.exportedRows.get(), ledger.size(), state.mismatchedSegments);
            return TableStatus.MISMATCH;
        }
        if (state.complete.getAndSet(1) == 0) {
            LOG.info("event=legacy_export status=COMPLETE table={} rows={} segments={}", table,
                state.exportedRows.get(), ledger.size());
        }
        return TableStatus.COMPLETE;
    }

    private void gauge(String name, Tags tags, AtomicLong value) {
        Gauge.builder(name, value, AtomicLong::get).tags(tags).strongReference(true).register(metrics);
    }

    private String root(String table, final int segment) {
        return settings.prefix() + table + String.format("/segment=%06d/", segment);
    }

    private static String failureCode(RuntimeException failure) {
        String message = failure.getMessage();
        if (failure instanceof IllegalStateException && message != null && message.matches("[A-Za-z_]{1,80}")) {
            return message;
        }
        return failure.getClass().getSimpleName();
    }

    private static String sqlState(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 10; depth++) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            cause = cause.getCause();
        }
        return "NONE";
    }
}

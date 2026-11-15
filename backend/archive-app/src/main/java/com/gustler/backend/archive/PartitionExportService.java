package com.gustler.backend.archive;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 예보 기록 테이블 다섯 개와 관측 테이블 두 개에서 닫힌 한국 날짜의 파티션을 통째로 S3 에 내보내고, 다시 읽어 확인한 뒤, 설정이
 * 허락하면 떼어 지운다.
 *
 * <p>대상은 {@code source_date <= 오늘(한국) - retentionDays} 인 날짜 파티션이고 오래된 날짜부터, 같은 날짜는 테이블 순서로 처리한다.
 * 기본 파티션은 대상이 아니며 줄 수만 지표로 낸다. 파티션 하나의 실패는 그 파티션만 다음 회차로 미루고 다른 파티션은 계속한다.
 *
 * <p>관측 테이블은 다음이 다르다.
 * <ul>
 * <li>파티션 테이블일 때만 다룬다. 일반 테이블이면 회차마다 {@code reason=NOT_PARTITIONED} 로 건너뛰었다고 알리고 아무것도 읽지
 * 않는다.</li>
 * <li>보관 날짜 수는 {@link #observationRetentionDays} 다. 다시 뜬 worker 와 채점이 다시 읽는 범위
 * ({@link #OBSERVATION_READ_WINDOW})를 덮지 못하는 설정이면 그 범위를 덮을 때까지 날짜를 건너뛴다.</li>
 * <li>날짜 파티션으로 바꾸기 전의 줄을 통째로 담은 첫 파티션({@code <table>_legacy})은 여러 날짜가 섞여 있다. 배치 첫 파티션의 모든
 * 시각이 관측 보관 경계 날짜 이하가 되면 그 파티션을 한 덩어리로, 날짜 파티션과 같은 형식·같은 확인 절차로 내보내고 지운다. 장부에는
 * ({@code table_name}, {@link PartitionExportRepository#INITIAL_DATE}, 0) 으로 적고 S3 키의 날짜도 그 날짜다. 관측 줄은 자기
 * 배치보다 먼저 들어올 수 없으므로 관측 첫 파티션의 줄도 그 배치들보다 늦지 않다. 그래서 관측 첫 파티션은 배치 첫 파티션이 준비되었거나
 * 그 장부가 확인을 마쳤을 때 대상이 된다.</li>
 * </ul>
 *
 * <p>파티션을 지우는 경로는 하나다. 다음을 모두 통과해야 {@link PartitionExportRepository#drop} 을 부른다.
 * <ol>
 * <li>{@code archive.partition-export.drop-enabled=true} 다.</li>
 * <li>장부에 그 (테이블, 날짜)의 줄이 있고 확인 시각이 있으며 지운 시각이 없다.</li>
 * <li>지금 S3 에서 manifest 를 다시 읽어 SHA-256 이 장부와 같고, manifest 의 모든 파일을 다시 읽어 해시·줄 수가 manifest 와 같으며,
 * 줄 수 합이 장부와 같다.</li>
 * <li>지우는 트랜잭션 안에서 새 줄을 막은 뒤 센 파티션의 줄 수가 장부와 같다.</li>
 * </ol>
 * {@code route} 행 잠금을 잡지 않는다.
 */
public class PartitionExportService {
    static final int FILE_PLAIN_BYTES = 8 * 1024 * 1024;
    /**
     * 관측 테이블을 다시 읽는 가장 긴 범위다. 다시 뜬 worker 는 관측을 최대 12시간 되감고, 채점은 최대 26시간 전 기록까지 다시 읽는다.
     * 둘 가운데 긴 쪽을 쓴다.
     */
    static final Duration OBSERVATION_READ_WINDOW = Duration.ofHours(26);
    private static final String BATCH_TABLE = PartitionExportRepository.OBSERVATION_TABLES.getFirst();
    private static final Logger LOG = LoggerFactory.getLogger(PartitionExportService.class);
    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private final PartitionExportRepository repository;
    private final ArchiveObjects objects;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final PartitionExportSettings settings;
    private final Map<String, AtomicLong> defaultRows = new ConcurrentHashMap<>();
    private final AtomicLong oldestPendingDays = new AtomicLong();
    private LocalDate initialNewest;

    public PartitionExportService(
        PartitionExportRepository repository,
        ArchiveObjects objects,
        Clock clock,
        MeterRegistry metrics,
        PartitionExportSettings settings
    ) {
        this.repository = repository;
        this.objects = objects;
        this.clock = clock;
        this.metrics = metrics;
        this.settings = settings;
        metrics.gauge("salmonbus_archive_partition_oldest_pending_days", oldestPendingDays);
        LOG.info("event=partition_export_config enabled=true dropEnabled={} retentionDays={}"
            + " observationRetentionDays={} prefix={} intervalMs={}", settings.dropEnabled(), settings.retentionDays(),
            observationRetentionDays(settings.retentionDays()), settings.prefix(), settings.interval().toMillis());
    }

    /**
     * 처리한 회차의 요약이다. {@code oldestPending} 은 대상인데 이번 회차에 끝내지 못한 가장 오래된 날짜다. 관측 첫 파티션은
     * {@code pending} 에는 세지만 날짜가 없으므로 {@code oldestPending} 에는 넣지 않는다.
     */
    public record RunReport(
        int exported,
        int dropped,
        int pending,
        LocalDate oldestPending
    ) {
    }

    /** 오늘(한국)에서 보관 날짜 수를 뺀 날짜다. 이 날짜까지의 파티션이 대상이다. */
    public static LocalDate lastEligibleDate(Clock clock, final int retentionDays) {
        return LocalDate.now(clock.withZone(ArchiveRetention.ZONE)).minusDays(retentionDays);
    }

    /**
     * 관측 테이블의 보관 날짜 수다. 날짜 d 의 파티션은 d+1 한국 자정까지 들어온 줄을 담으므로, 보관 날짜 수 n 이면 지우는 줄은 언제나
     * 지금보다 (n-1)일 이상 앞선다. 그래서 {@link #OBSERVATION_READ_WINDOW} 를 덮는 가장 작은 n 과 설정값 가운데 큰 값을 쓴다.
     */
    public static int observationRetentionDays(final int retentionDays) {
        final long windowDays = (OBSERVATION_READ_WINDOW.toHours() + 23) / 24;
        return Math.max(retentionDays, (int) windowDays + 1);
    }

    @Scheduled(fixedDelayString = "${archive.partition-export.interval:1h}",
        initialDelayString = "${archive.partition-export.initial-delay:1m}")
    public void scheduled() {
        try {
            run();
        } catch (RuntimeException failure) {
            metrics.counter("salmonbus_archive_partition_failures", "table", "none", "reason",
                failure.getClass().getSimpleName()).increment();
            LOG.warn("event=partition_export status=RUN_FAILED failureType={} sqlState={}",
                failure.getClass().getSimpleName(), sqlState(failure));
        }
    }

    /** 한 회차를 처리한다. 같은 프로세스 안에서는 한 번에 한 회차만 돈다. */
    public synchronized RunReport run() {
        final long started = System.nanoTime();
        LocalDate today = LocalDate.now(clock.withZone(ArchiveRetention.ZONE));
        LocalDate through = lastEligibleDate(clock, settings.retentionDays());
        LocalDate observationThrough = lastEligibleDate(clock, observationRetentionDays(settings.retentionDays()));
        List<String> observations = partitionedObservationTables();
        List<String> tables = new ArrayList<>(PartitionExportRepository.TABLES);
        tables.addAll(observations);
        recordDefaultRows(tables);
        List<String> unexpected = new ArrayList<>();
        List<PartitionExportRepository.Partition> partitions = new ArrayList<>(
            repository.datedPartitions(PartitionExportRepository.TABLES, through, unexpected));
        partitions.addAll(observationPartitions(observations, observationThrough, unexpected));
        partitions.sort(PartitionExportRepository.ORDER);
        recordUnexpectedBounds(unexpected);
        int exported = 0;
        int dropped = 0;
        int pending = 0;
        LocalDate oldest = null;
        for (PartitionExportRepository.Partition partition : partitions) {
            Outcome outcome = processSafely(partition);
            if (outcome.exported) {
                exported++;
            }
            if (outcome.dropped) {
                dropped++;
            }
            if (!outcome.done) {
                pending++;
                if (oldest == null && !partition.isInitial()) {
                    oldest = partition.date();
                }
            }
        }
        oldestPendingDays.set(pendingDays(oldest, today));
        LOG.info("event=partition_export status=RUN through={} partitions={} exported={} dropped={} pending={}"
            + " oldestPendingDate={} durationMs={}", through, partitions.size(), exported, dropped, pending,
            dateOrNone(oldest), (System.nanoTime() - started) / 1000000);
        return new RunReport(exported, dropped, pending, oldest);
    }

    /** 파티션 테이블이 된 관측 테이블을 낸다. 일반 테이블이면 건너뛰었다고 지표와 로그로 알린다. */
    private List<String> partitionedObservationTables() {
        List<String> partitioned = new ArrayList<>();
        for (String table : PartitionExportRepository.OBSERVATION_TABLES) {
            if (isPartitioned(table)) {
                partitioned.add(table);
                continue;
            }
            metrics.counter("salmonbus_archive_partition_deferrals", "table", table, "reason", "NOT_PARTITIONED")
                .increment();
            LOG.info("event=partition_export status=SKIPPED table={} reason=NOT_PARTITIONED", table);
        }
        return partitioned;
    }

    private boolean isPartitioned(String table) {
        try {
            return repository.isPartitioned(table);
        } catch (RuntimeException failure) {
            metrics.counter("salmonbus_archive_partition_failures", "table", table, "reason", "CATALOG_FAILED")
                .increment();
            LOG.warn("event=partition_export status=FAILED table={} reason=CATALOG_FAILED failureType={} sqlState={}",
                table, failure.getClass().getSimpleName(), sqlState(failure));
            return false;
        }
    }

    /**
     * 파티션 테이블이 된 관측 테이블의 대상 파티션이다. 준비된 첫 파티션과 {@code through} 이하의 날짜 파티션이다. 카탈로그를 읽지
     * 못하면 이번 회차에는 관측 테이블을 건너뛰고 예보 기록 테이블은 계속한다.
     */
    private List<PartitionExportRepository.Partition> observationPartitions(
        List<String> observations,
        LocalDate through,
        List<String> unexpected
    ) {
        if (observations.isEmpty()) {
            return List.of();
        }
        try {
            return listObservationPartitions(observations, through, unexpected);
        } catch (RuntimeException failure) {
            metrics.counter("salmonbus_archive_partition_failures", "table", BATCH_TABLE, "reason", "CATALOG_FAILED")
                .increment();
            LOG.warn("event=partition_export status=FAILED table={} reason=CATALOG_FAILED failureType={} sqlState={}",
                String.join(",", observations), failure.getClass().getSimpleName(), sqlState(failure));
            return List.of();
        }
    }

    private List<PartitionExportRepository.Partition> listObservationPartitions(
        List<String> observations,
        LocalDate through,
        List<String> unexpected
    ) {
        List<PartitionExportRepository.Partition> partitions = new ArrayList<>();
        List<PartitionExportRepository.Partition> initials = new ArrayList<>();
        for (String table : observations) {
            repository.initialPartition(table, unexpected).ifPresent(initials::add);
        }
        if (!initials.isEmpty() && initialRowsSettled(through)) {
            partitions.addAll(initials);
        }
        partitions.addAll(repository.datedPartitions(observations, through, unexpected));
        return partitions;
    }

    /**
     * 관측 첫 파티션을 지워도 되는 때인지 본다. 배치 첫 파티션이 붙어 있으면 그 줄의 가장 늦은 시각이 {@code through} 이하의 날짜일
     * 때 참이다. 이미 떼어 지웠으면 그 장부가 확인을 마쳤을 때 참이다. 가장 늦은 날짜를 기억해 두고 그 날짜가 {@code through} 를 넘는
     * 동안에는 다시 읽지 않는다. 읽기에 실패하면 거짓이다.
     */
    private boolean initialRowsSettled(LocalDate through) {
        PartitionExportRepository.Partition batches = PartitionExportRepository.Partition.initial(BATCH_TABLE);
        try {
            if (!repository.attached(batches)) {
                return repository.ledger(batches).filter(ledger -> ledger.verifiedAt() != null).isPresent();
            }
            if (initialNewest != null && initialNewest.isAfter(through)) {
                initialWaiting(through);
                return false;
            }
            Optional<LocalDate> newest = repository.newestInitialDate(BATCH_TABLE);
            if (newest.isEmpty()) {
                return true;
            }
            initialNewest = newest.get();
            if (initialNewest.isAfter(through)) {
                initialWaiting(through);
                return false;
            }
            return true;
        } catch (RuntimeException failure) {
            failed(batches, failureCode(failure), failure);
            return false;
        }
    }

    private void initialWaiting(LocalDate through) {
        LOG.info("event=partition_export status=SKIPPED table={} date={} reason=ROWS_TOO_RECENT newestDate={}"
            + " through={}", BATCH_TABLE, PartitionExportRepository.INITIAL_DATE, initialNewest, through);
    }

    private void recordUnexpectedBounds(List<String> unexpected) {
        for (String name : unexpected) {
            metrics.counter("salmonbus_archive_partition_failures", "table", "none", "reason", "UNEXPECTED_BOUND")
                .increment();
            LOG.warn("event=partition_export status=SKIPPED partition={} reason=UNEXPECTED_BOUND", name);
        }
    }

    private static long pendingDays(LocalDate oldest, LocalDate today) {
        if (oldest == null) {
            return 0;
        }
        return ChronoUnit.DAYS.between(oldest, today);
    }

    private static String dateOrNone(LocalDate date) {
        if (date == null) {
            return "none";
        }
        return date.toString();
    }

    private record Outcome(boolean done, boolean exported, boolean dropped) {
    }

    private Outcome processSafely(PartitionExportRepository.Partition partition) {
        try {
            return process(partition);
        } catch (RuntimeException failure) {
            if (sqlState(failure).equals(LOCK_NOT_AVAILABLE)) {
                deferred(partition, "LOCK_TIMEOUT");
                return new Outcome(false, false, false);
            }
            failed(partition, failureCode(failure), failure);
            return new Outcome(false, false, false);
        }
    }

    private Outcome process(PartitionExportRepository.Partition partition) {
        Optional<PartitionExportRepository.Ledger> found = repository.ledger(partition);
        if (found.isEmpty()) {
            return dropIfEnabled(partition, export(partition), true);
        }
        PartitionExportRepository.Ledger ledger = found.get();
        if (ledger.droppedAt() != null) {
            if (repository.attached(partition)) {
                throw new IllegalStateException("LEDGER_DROPPED_PARTITION_PRESENT");
            }
            return new Outcome(true, false, false);
        }
        return dropIfEnabled(partition, verifiedLedger(partition, ledger), false);
    }

    /** 확인 시각이 비어 있으면 S3 를 다시 읽어 확인하고 장부에 확인 시각을 적은 장부 줄을 낸다. */
    private PartitionExportRepository.Ledger verifiedLedger(
        PartitionExportRepository.Partition partition,
        PartitionExportRepository.Ledger ledger
    ) {
        if (ledger.verifiedAt() != null) {
            return ledger;
        }
        verifyStored(partition, ledger);
        Instant verifiedAt = clock.instant();
        if (!repository.markVerified(partition, ledger.sha256(), verifiedAt)) {
            throw new IllegalStateException("LEDGER_CHANGED");
        }
        return new PartitionExportRepository.Ledger(ledger.key(), ledger.rows(), ledger.sha256(),
            ledger.exportedAt(), verifiedAt, null);
    }

    private Outcome dropIfEnabled(
        PartitionExportRepository.Partition partition,
        PartitionExportRepository.Ledger ledger,
        final boolean exported
    ) {
        if (!settings.dropEnabled()) {
            return new Outcome(true, exported, false);
        }
        return drop(partition, ledger, exported);
    }

    private PartitionExportRepository.Ledger export(PartitionExportRepository.Partition partition) {
        final long started = System.nanoTime();
        List<PartitionManifest.Column> columns = repository.columns(partition.table());
        List<String> keys = repository.orderKey(partition.table());
        List<PartitionManifest.FileEntry> files = new ArrayList<>();
        List<String> buffer = new ArrayList<>();
        long bufferBytes = 0;
        long total = 0;
        long bytes = 0;
        String cursor = null;
        while (true) {
            PartitionExportRepository.Page page = repository.page(partition, columns, keys, cursor,
                settings.pageSize());
            if (page.rows().isEmpty()) {
                break;
            }
            for (String row : page.rows()) {
                final int lineBytes = row.getBytes(StandardCharsets.UTF_8).length + 1;
                if (!buffer.isEmpty() && bufferBytes + lineBytes > FILE_PLAIN_BYTES) {
                    bytes += writeFile(partition, files, buffer);
                    buffer.clear();
                    bufferBytes = 0;
                }
                buffer.add(row);
                bufferBytes += lineBytes;
                total++;
            }
            cursor = page.cursor();
        }
        if (!buffer.isEmpty()) {
            bytes += writeFile(partition, files, buffer);
        }
        PartitionManifest manifest = new PartitionManifest(PartitionManifest.FORMAT, partition.table(),
            partition.date().toString(), partition.name(), total, keys, columns, clock.instant().toString(), files);
        byte[] manifestBytes = manifest.toBytes();
        String sha = ArchiveFiles.digest(manifestBytes);
        String key = root(partition) + "manifest-" + sha + ".json";
        objects.putVerified(key, manifestBytes);
        PartitionExportRepository.Ledger exported = new PartitionExportRepository.Ledger(key, total, sha,
            Instant.parse(manifest.exportedAt()), null, null);
        final long exportNanos = System.nanoTime() - started;
        metrics.timer("salmonbus_archive_stage", "stage", "partition_export").record(exportNanos,
            TimeUnit.NANOSECONDS);
        LOG.info("event=partition_export status=EXPORTED table={} date={} rows={} files={} bytes={} key={}"
            + " durationMs={}", partition.table(), partition.date(), total, files.size(), bytes, key,
            exportNanos / 1000000);
        verifyStored(partition, exported);
        PartitionExportRepository.Ledger verified = new PartitionExportRepository.Ledger(key, total, sha,
            exported.exportedAt(), clock.instant(), null);
        return recordVerified(partition, verified, manifest, bytes);
    }

    /**
     * 확인한 내보내기를 장부에 적는다. 다른 실행이 먼저 적었으면 그 줄이 같은 내용일 때만 그 줄을 내고, 다르면
     * {@code EXPORT_CONTENT_MISMATCH} 로 실패한다.
     */
    private PartitionExportRepository.Ledger recordVerified(
        PartitionExportRepository.Partition partition,
        PartitionExportRepository.Ledger verified,
        PartitionManifest manifest,
        final long bytes
    ) {
        PartitionExportRepository.LedgerWrite written = repository.recordVerified(partition, verified);
        if (written.inserted()) {
            metrics.counter("salmonbus_archive_partitions", "stage", "exported", "table", partition.table())
                .increment();
            metrics.counter("salmonbus_archive_rows", "stage", "partition_exported", "table", partition.table())
                .increment(verified.rows());
            metrics.counter("salmonbus_archive_bytes", "stage", "partition_exported").increment(bytes);
            return verified;
        }
        PartitionExportRepository.Ledger other = written.ledger();
        if (other.sha256().equals(verified.sha256()) || sameContent(other, manifest)) {
            LOG.info("event=partition_export status=ALREADY_EXPORTED table={} date={} key={}", partition.table(),
                partition.date(), other.key());
            return other;
        }
        throw new IllegalStateException("EXPORT_CONTENT_MISMATCH");
    }

    private boolean sameContent(PartitionExportRepository.Ledger other, PartitionManifest mine) {
        byte[] bytes = objects.read(other.key(), ArchiveFiles.MAX_BYTES);
        return ArchiveFiles.digest(bytes).equals(other.sha256()) && PartitionManifest.parse(bytes).sameContent(mine);
    }

    private long writeFile(
        PartitionExportRepository.Partition partition,
        List<PartitionManifest.FileEntry> files,
        List<String> rows
    ) {
        byte[] bytes = ArchiveFiles.encode(rows);
        if (!ArchiveFiles.decode(bytes).equals(rows)) {
            throw new IllegalStateException("ENCODING_CHANGED_SOURCE");
        }
        String sha = ArchiveFiles.digest(bytes);
        final int number = files.size();
        String key = root(partition) + String.format("%06d-%s.jsonl.gz", number, sha);
        objects.putVerified(key, bytes);
        files.add(new PartitionManifest.FileEntry(number, key, sha, rows.size(), bytes.length));
        return bytes.length;
    }

    private String root(PartitionExportRepository.Partition partition) {
        return settings.prefix() + partition.table() + "/source_date=" + partition.date() + "/";
    }

    /**
     * 장부가 가리키는 manifest 와 그 파일을 지금 S3 에서 다시 읽어 확인한다. 어긋나면 사유 코드를 담은
     * {@link IllegalStateException} 을 던진다.
     */
    private void verifyStored(PartitionExportRepository.Partition partition, PartitionExportRepository.Ledger ledger) {
        final long started = System.nanoTime();
        byte[] manifestBytes = objects.read(ledger.key(), ArchiveFiles.MAX_BYTES);
        if (!ArchiveFiles.digest(manifestBytes).equals(ledger.sha256())) {
            throw new IllegalStateException("MANIFEST_HASH_MISMATCH");
        }
        PartitionManifest manifest = PartitionManifest.parse(manifestBytes);
        if (differsFromLedger(manifest, partition, ledger)) {
            throw new IllegalStateException("MANIFEST_MISMATCH");
        }
        final long rows = storedRows(manifest);
        if (rows != ledger.rows()) {
            throw new IllegalStateException("MANIFEST_ROW_COUNT_MISMATCH");
        }
        final long nanos = System.nanoTime() - started;
        metrics.timer("salmonbus_archive_stage", "stage", "partition_verify").record(nanos, TimeUnit.NANOSECONDS);
        LOG.info("event=partition_export status=VERIFIED table={} date={} rows={} files={} durationMs={}",
            partition.table(), partition.date(), rows, manifest.files().size(), nanos / 1000000);
    }

    private static boolean differsFromLedger(
        PartitionManifest manifest,
        PartitionExportRepository.Partition partition,
        PartitionExportRepository.Ledger ledger
    ) {
        return !PartitionManifest.FORMAT.equals(manifest.format()) || !partition.table().equals(manifest.table())
            || !partition.date().toString().equals(manifest.sourceDate()) || manifest.rows() != ledger.rows();
    }

    /** manifest 의 파일을 지금 S3 에서 다시 읽어 해시·줄 수를 확인하고 줄 수 합을 낸다. */
    private long storedRows(PartitionManifest manifest) {
        long rows = 0;
        for (PartitionManifest.FileEntry file : manifest.files()) {
            byte[] bytes = objects.read(file.key(), file.bytes());
            if (bytes.length != file.bytes() || !ArchiveFiles.digest(bytes).equals(file.sha256())) {
                throw new IllegalStateException("FILE_HASH_MISMATCH");
            }
            if (ArchiveFiles.decode(bytes).size() != file.rows()) {
                throw new IllegalStateException("FILE_ROW_COUNT_MISMATCH");
            }
            rows += file.rows();
        }
        return rows;
    }

    private Outcome drop(
        PartitionExportRepository.Partition partition,
        PartitionExportRepository.Ledger ledger,
        final boolean exported
    ) {
        verifyStored(partition, ledger);
        final long current = repository.count(partition);
        if (current != ledger.rows()) {
            rowCountChanged(partition, ledger, current);
            return new Outcome(false, exported, false);
        }
        final long started = System.nanoTime();
        PartitionExportRepository.DropResult result = repository.drop(partition, ledger);
        switch (result) {
            case DROPPED -> {
                recordDropped(partition, ledger, System.nanoTime() - started);
                return new Outcome(true, exported, true);
            }
            case ROW_COUNT_CHANGED -> {
                rowCountChanged(partition, ledger, -1);
                return new Outcome(false, exported, false);
            }
            case ALREADY_DROPPED -> {
                LOG.info("event=partition_export status=SKIPPED table={} date={} reason=ALREADY_DROPPED",
                    partition.table(), partition.date());
                return new Outcome(true, exported, false);
            }
            case BUSY -> {
                deferred(partition, "BUSY");
                return new Outcome(false, exported, false);
            }
            default -> {
                failed(partition, result.name(), null);
                return new Outcome(false, exported, false);
            }
        }
    }

    private void recordDropped(
        PartitionExportRepository.Partition partition,
        PartitionExportRepository.Ledger ledger,
        final long nanos
    ) {
        metrics.counter("salmonbus_archive_partitions", "stage", "dropped", "table", partition.table())
            .increment();
        metrics.counter("salmonbus_archive_rows", "stage", "partition_dropped", "table", partition.table())
            .increment(ledger.rows());
        metrics.timer("salmonbus_archive_stage", "stage", "partition_drop").record(nanos, TimeUnit.NANOSECONDS);
        LOG.info("event=partition_export status=DROPPED table={} date={} partition={} rows={} durationMs={}",
            partition.table(), partition.date(), partition.name(), ledger.rows(), nanos / 1000000);
    }

    /** {@code current} 가 음수면 지우는 트랜잭션 안에서 센 값이라 로그에 {@code locked} 로 남긴다. */
    private void rowCountChanged(
        PartitionExportRepository.Partition partition,
        PartitionExportRepository.Ledger ledger,
        final long current
    ) {
        metrics.counter("salmonbus_archive_partition_failures", "table", partition.table(), "reason",
            "ROW_COUNT_CHANGED").increment();
        LOG.warn("event=partition_export status=DROP_REFUSED table={} date={} reason=ROW_COUNT_CHANGED ledgerRows={}"
            + " currentRows={}", partition.table(), partition.date(), ledger.rows(), currentRowsLabel(current));
    }

    private static String currentRowsLabel(final long current) {
        if (current < 0) {
            return "locked";
        }
        return Long.toString(current);
    }

    private void deferred(PartitionExportRepository.Partition partition, String reason) {
        metrics.counter("salmonbus_archive_partition_deferrals", "table", partition.table(), "reason", reason)
            .increment();
        LOG.info("event=partition_export status=DEFERRED table={} date={} reason={}", partition.table(),
            partition.date(), reason);
    }

    private void failed(PartitionExportRepository.Partition partition, String reason, RuntimeException failure) {
        metrics.counter("salmonbus_archive_partition_failures", "table", partition.table(), "reason", reason)
            .increment();
        LOG.warn("event=partition_export status=FAILED table={} date={} reason={} failureType={} sqlState={}",
            partition.table(), partition.date(), reason, failureType(failure), failureSqlState(failure));
    }

    private static String failureType(RuntimeException failure) {
        if (failure == null) {
            return "none";
        }
        return failure.getClass().getSimpleName();
    }

    private static String failureSqlState(RuntimeException failure) {
        if (failure == null) {
            return "NONE";
        }
        return sqlState(failure);
    }

    private void recordDefaultRows(List<String> tables) {
        for (String table : tables) {
            try {
                final long rows = repository.defaultRows(table);
                defaultRows.computeIfAbsent(table, name -> metrics.gauge("salmonbus_archive_partition_default_rows",
                    Tags.of("table", name), new AtomicLong())).set(rows);
                if (rows > 0) {
                    LOG.warn("event=partition_export status=DEFAULT_ROWS table={} rows={}", table, rows);
                }
            } catch (RuntimeException failure) {
                LOG.warn("event=partition_export status=DEFAULT_COUNT_FAILED table={} failureType={} sqlState={}",
                    table, failure.getClass().getSimpleName(), sqlState(failure));
            }
        }
    }

    private static String failureCode(RuntimeException failure) {
        String message = failure.getMessage();
        if (hasSafeFailureCode(failure, message)) {
            return message;
        }
        return failure.getClass().getSimpleName();
    }

    private static boolean hasSafeFailureCode(RuntimeException failure, String message) {
        return failure instanceof IllegalStateException && message != null && message.matches("[A-Za-z_]{1,80}");
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

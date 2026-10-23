package com.gustler.backend.forecasting.application.statistics;

import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.application.statistics.ArchivedDemandStatisticsStore.Cell;
import com.gustler.backend.forecasting.application.statistics.ArchivedDemandStatisticsStore.Missing;
import com.gustler.backend.forecasting.application.statistics.ArchivedDemandStatisticsStore.Sample;
import com.gustler.backend.forecasting.application.statistics.ArchivedDemandStatisticsStore.Total;
import com.gustler.backend.forecasting.application.statistics.DemandStatisticsRebuildStore.BatchPosition;
import com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader.Key;
import com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader.Reference;
import com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader.Row;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRebuild;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRebuild.Phase;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRebuild.ScanWindow;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRebuildRepository;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRunRepository;
import com.gustler.backend.forecasting.domain.statistics.RebuildScope;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 재집계의 한 페이지를 준비하고 외부 파일을 읽은 뒤 같은 진행 위치에만 반영한다. */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ArchivedDemandStatisticsRebuilder {
    private final DemandStatisticsRebuildRepository rebuilds;
    private final DemandStatisticsRebuildRequestStore requests;
    private final DemandStatisticsRebuildStore pages;
    private final DemandStatisticsStore limits;
    private final DemandStatisticsRunRepository runs;
    private final RouteDataQualityAccess quality;
    private final ArchivedDemandStatisticsStore archives;
    private final ObjectProvider<StatisticsArchiveReader> readers;
    private final TransactionTemplate transaction;

    public ArchivedDemandStatisticsRebuilder(
        DemandStatisticsRebuildRepository rebuilds,
        DemandStatisticsRebuildRequestStore requests,
        DemandStatisticsRebuildStore pages,
        DemandStatisticsStore limits,
        DemandStatisticsRunRepository runs,
        RouteDataQualityAccess quality,
        ArchivedDemandStatisticsStore archives,
        ObjectProvider<StatisticsArchiveReader> readers,
        PlatformTransactionManager transactions
    ) {
        this.rebuilds = rebuilds;
        this.requests = requests;
        this.pages = pages;
        this.limits = limits;
        this.runs = runs;
        this.quality = quality;
        this.archives = archives;
        this.readers = readers;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(2);
    }

    /** empty면 기존 짧은 DB 단계로 진행한다. false면 입력 변경으로 다음 호출에서 다시 준비한다. */
    public Optional<Boolean> advance(final long routeVersionId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("외부 재집계를 기존 DB 트랜잭션 안에서 실행하지 않는다");
        }
        if (!archives.hasArchive(routeVersionId)) {
            return Optional.empty();
        }
        Prepared prepared = transaction.execute(status -> prepare(routeVersionId));
        if (prepared == null) {
            return Optional.empty();
        }
        StatisticsArchiveReader reader = readers.getIfAvailable();
        if (reader == null) {
            throw new IllegalStateException("이관 원본을 읽는 저장소가 구성되지 않았다");
        }

        Map<Key, Row> selected = new LinkedHashMap<>();
        long bytes = 0;
        for (Reference reference : prepared.missing()
            .stream()
            .map(Missing::reference)
            .distinct()
            .toList()) {
            List<Row> rows = reader.read(reference);
            for (Missing missing : prepared.missing()) {
                if (!missing.reference().equals(reference)) {
                    continue;
                }
                List<Row> matches = rows.stream()
                    .filter(row -> row.key().equals(missing.key()))
                    .toList();
                if (matches.size() != 1 || !matches.getFirst().sha256().equals(missing.originalSha256())) {
                    throw new IllegalStateException("이관 파일에 예약한 정산 원본이 없거나 달라졌다");
                }
                Row row = matches.getFirst();
                bytes = Math.addExact(bytes, row.originalJson().getBytes(StandardCharsets.UTF_8).length);
                if (bytes > 8 * 1024 * 1024 || selected.putIfAbsent(row.key(), row) != null) {
                    throw new IllegalStateException("한 단계의 원본 크기를 넘거나 키가 중복됐다");
                }
            }
        }
        return Optional.of(
            Boolean.TRUE.equals(transaction.execute(status -> apply(prepared, List.copyOf(selected.values())))));
    }

    private Prepared prepare(final long route) {
        limits.limitStatementTime();
        final long revision = quality.lock(route);
        Optional<RebuildScope> scope = requests.next(route);
        if (scope.isEmpty() || investigationPending(route, scope.get())) {
            return null;
        }
        Optional<DemandStatisticsRebuild> found = rebuilds.find(route, scope.get());
        Optional<UUID> request = requests.current(route, scope.get());
        if (found.isEmpty() || request.isEmpty()) {
            return null;
        }
        DemandStatisticsRebuild rebuild = found.get();
        if (!rebuild.serves(request.get(), revision) || rebuild.phase() != Phase.SCAN) {
            return null;
        }
        Snapshot snapshot = Snapshot.of(rebuild);
        Optional<List<Long>> page = prepareObservationPage(route, rebuild);
        if (page.isEmpty()) {
            return null;
        }
        List<Long> ids = page.orElseThrow();
        List<Missing> missing = archives.missing(route, ids);
        if (missing.isEmpty()) {
            return null;
        }
        return new Prepared(snapshot, rebuild, revision, List.copyOf(ids), List.copyOf(missing));
    }

    private boolean apply(Prepared prepared, List<Row> rows) {
        limits.limitStatementTime();
        DemandStatisticsRebuild rebuild = prepared.opened();
        final long route = rebuild.routeVersionId();
        final long revision = quality.lock(route);
        if (revision != prepared.currentQuality() || investigationPending(route, rebuild.scope())) {
            return false;
        }
        if (!requests.next(route).equals(Optional.of(rebuild.scope()))
                || !requests.current(route, rebuild.scope()).equals(Optional.of(rebuild.requestId()))) {
            return false;
        }
        Optional<DemandStatisticsRebuild> current = rebuilds.find(route, rebuild.scope());
        if (current.isEmpty() || !prepared.snapshot().equals(Snapshot.of(current.get()))) {
            return false;
        }
        if (!archives.missing(route, prepared.observations()).equals(prepared.missing())) {
            return false;
        }

        Map<Cell, Total> totals = new LinkedHashMap<>();
        for (Sample sample : archives.samples(rebuild, prepared.observations(), rows)) {
            totals.compute(sample.cell(), (cell, previous) -> mergedTotal(cell, previous, sample));
        }
        archives.add(rebuild, new ArrayList<>(totals.values()));
        advanceScan(rebuild, prepared.observations());
        rebuilds.save(rebuild);
        runs.find(route).ifPresent(run -> {
            run.markStale();
            runs.save(run);
        });
        return true;
    }

    private boolean investigationPending(final long route, RebuildScope scope) {
        if (scope.isWholeRoute()) {
            return quality.anyInvestigationPending(route);
        }
        return quality.investigationPending(route, scope.vehicleId());
    }

    private record Prepared(Snapshot snapshot, DemandStatisticsRebuild opened, long currentQuality,
        List<Long> observations, List<Missing> missing) {
    }

    private record Snapshot(long route, RebuildScope scope, UUID request, long quality, Instant until, long inputUntil,
        long observationUntil, long cursor, Phase phase, ScanWindow scan) {
        static Snapshot of(DemandStatisticsRebuild rebuild) {
            return new Snapshot(rebuild.routeVersionId(), rebuild.scope(), rebuild.requestId(),
                rebuild.qualityRevision(),
                rebuild.dataUntil(), rebuild.inputUntilId(), rebuild.observationUntilId(), rebuild.cursorId(),
                rebuild.phase(),
                rebuild.scan().orElse(null));
        }
    }

    /**
     * 빈 Optional은 다음 그룹 자체의 부재이고, Optional 안의 빈 목록은 유효한 빈 페이지다. 새 그룹은 행 조회 전에 상태에 연다.
     */
    private Optional<List<Long>> prepareObservationPage(final long route, DemandStatisticsRebuild rebuild) {
        if (rebuild.scansByObservationId()) {
            return Optional.of(pages.observationPage(
                rebuild.cursorId(), rebuild.observationUntilId(), DemandStatisticsRebuild.PAGE_SIZE));
        }
        ScanWindow window = rebuild.scan().orElseThrow();
        if (!window.hasOpenGroup()) {
            Optional<BatchPosition> end = pages.nextGroupEnd(route, window, DemandStatisticsRebuild.BATCH_GROUP_SIZE);
            if (end.isEmpty()) {
                return Optional.empty();
            }
            rebuild.groupOpened(end.get().at(), end.get().id());
            window = rebuild.scan().orElseThrow();
        }
        return Optional.of(pages.groupObservationPage(route, window, rebuild.scope(),
            rebuild.cursorId(), rebuild.observationUntilId(),
            DemandStatisticsRebuild.BATCH_GROUP_SIZE, DemandStatisticsRebuild.PAGE_SIZE));
    }

    private static Total mergedTotal(Cell cell, Total previous, Sample sample) {
        if (previous == null) {
            return new Total(cell, 1, sample.arrivalSeats(), sample.netBoarding());
        }
        return previous.plus(sample);
    }

    private static void advanceScan(DemandStatisticsRebuild rebuild, List<Long> observations) {
        if (rebuild.scansByObservationId()) {
            rebuild.scannedObservations(observations);
            return;
        }
        rebuild.scannedGroup(observations);
    }
}

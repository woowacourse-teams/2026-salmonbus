package com.gustler.backend.forecasting.application.quality;

import com.gustler.backend.forecasting.api.quality.GetTripQualityStatus;
import com.gustler.backend.forecasting.api.quality.PreviewTripQuality;
import com.gustler.backend.forecasting.api.quality.ProcessTripQualityChunk;
import com.gustler.backend.forecasting.api.quality.TripQualityChunkResult;
import com.gustler.backend.forecasting.api.quality.TripQualityPreview;
import com.gustler.backend.forecasting.api.quality.TripQualityStatus;
import com.gustler.backend.forecasting.application.quality.TripQualityMaintenanceStore.Batch;
import com.gustler.backend.forecasting.application.quality.TripQualityMaintenanceStore.Signal;
import com.gustler.backend.forecasting.domain.quality.QualityObservationBatch;
import com.gustler.backend.forecasting.domain.quality.TripQualityDiscovery;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 품질 조사의 미리보기·제한된 재조사·진행 상태 조회를 제공한다.
 * 일괄 처리 대신 재개 가능한 커서를 저장한다.
 */
@Service
public class TripQualityMaintenanceService implements PreviewTripQuality, ProcessTripQualityChunk,
    GetTripQualityStatus {
    private final TripQualityMaintenanceStore maintenance;

    private final TripQualityInvestigationService investigations;
    private final RouteDataQualityAccess quality;
    private final TripQualityStore store;
    private final RouteDataQualityChanges changes;
    private WorkerQualityTransactions memory;

    public TripQualityMaintenanceService(
        TripQualityMaintenanceStore maintenance,
        TripQualityInvestigationService investigations,
        RouteDataQualityAccess quality,
        TripQualityStore store,
        RouteDataQualityChanges changes
    ) {
        this.maintenance = maintenance;
        this.investigations = investigations;
        this.quality = quality;
        this.store = store;
        this.changes = changes;
    }

    @Autowired(required = false)
    public void useQualityMemory(WorkerQualityTransactions memory) {
        this.memory = memory;
    }

    @Override
    @Transactional(readOnly = true)
    public TripQualityPreview preview(final long version, Instant until) {
        store.applyTimeBudget();
        return maintenance.preview(version, until);
    }

    @Override
    @Transactional(readOnly = false)
    public TripQualityChunkResult applyChunk(final long version, Instant until, final int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("한 번에 1~100개 관측 묶음만 처리할 수 있다");
        }
        if (memory != null) {
            memory.changing();
        }
        store.applyTimeBudget();
        quality.lock(version);
        final long seconds = store.readRoute(version).maximumGap().toSeconds();
        Optional<TripQualityDiscovery> existing = maintenance.discovery(version);
        TripQualityDiscovery discovery =
            existing.orElseGet(() -> new TripQualityDiscovery(Instant.EPOCH, 0, until, seconds, false));
        discovery.verifyResume(until, seconds);
        if (existing.isEmpty()) {
            maintenance.startDiscovery(version, discovery);
            changes.routeChanged(version);
        }
        int processed = 0;
        if (!discovery.completed()) {
            List<Batch> batches = maintenance.discoveryPage(version, discovery, limit);
            if (!batches.isEmpty()) {
                Map<Long, Instant> batchTimes = batches.stream()
                    .collect(Collectors.toMap(
                        Batch::id, Batch::observedAt));
                List<Signal> signals = maintenance.anomalies(batches.stream()
                    .map(Batch::id)
                    .toList());
                LinkedHashMap<String, Signal> first = new LinkedHashMap<String, Signal>();
                for (Signal signal : signals) {
                    first.putIfAbsent(signal.row().vehicleId(), signal);
                }
                for (Signal signal : first.values()) {
                    investigations.observationsStored(new QualityObservationBatch(signal.batchId(), version,
                        batchTimes.get(signal.batchId()), List.of(signal.row())));
                }
            }
            processed = batches.size();
            advanceDiscovery(discovery, batches, processed, limit);
            maintenance.saveDiscovery(version, discovery);
            if (discovery.completed()) {
                changes.routeChanged(version);
            }
        }
        Optional<String> pending = maintenance.nextPendingVehicle(version);
        pending.ifPresent(vehicle -> investigations.investigateLocked(version, vehicle));
        final boolean investigating = maintenance.hasPendingVehicles(version);
        return new TripQualityChunkResult(processed, discovery.completed(), discovery.completed() && !investigating);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TripQualityStatus> status(final long version) {
        store.applyTimeBudget();
        return maintenance.status(version);
    }

    private static void advanceDiscovery(
        TripQualityDiscovery discovery,
        List<Batch> batches,
        final int processed,
        final int limit
    ) {
        if (batches.isEmpty()) {
            discovery.advance(discovery.cursorAt(), discovery.cursorBatchId(), processed, limit);
            return;
        }
        Batch last = batches.getLast();
        discovery.advance(last.observedAt(), last.id(), processed, limit);
    }
}

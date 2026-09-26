package com.gustler.backend.forecasting.application.quality;

import com.gustler.backend.forecasting.api.quality.GetTripQualityStatus;
import com.gustler.backend.forecasting.api.quality.PreviewTripQuality;
import com.gustler.backend.forecasting.api.quality.ProcessTripQualityChunk;
import com.gustler.backend.forecasting.api.quality.TripQualityChunkResult;
import com.gustler.backend.forecasting.api.quality.TripQualityPreview;
import com.gustler.backend.forecasting.api.quality.TripQualityStatus;
import com.gustler.backend.forecasting.domain.quality.QualityObservationBatch;
import com.gustler.backend.forecasting.domain.quality.TripQualityDiscovery;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TripQualityMaintenanceService implements PreviewTripQuality, ProcessTripQualityChunk, GetTripQualityStatus {
    private final TripQualityMaintenanceStore maintenance;
    private final TripQualityInvestigationService investigations;
    private final RouteDataQualityAccess quality;
    private final TripQualityStore store;

    public TripQualityMaintenanceService(final TripQualityMaintenanceStore maintenance,
        final TripQualityInvestigationService investigations, final RouteDataQualityAccess quality, final TripQualityStore store) {
        this.maintenance = maintenance;
        this.investigations = investigations;
        this.quality = quality;
        this.store = store;
    }

    @Override
    @Transactional(readOnly = true)
    public TripQualityPreview preview(final long version, final Instant until) {
        validateRange(version, until);
        store.applyTimeBudget();
        return maintenance.preview(version, until);
    }

    @Override
    @Transactional
    public TripQualityChunkResult applyChunk(final long version, final Instant until, final int limit) {
        validateRange(version, until);
        if (limit < 1 || limit > 100) { throw new IllegalArgumentException("한 번에 1~100개 관측 묶음만 처리할 수 있다"); }
        store.applyTimeBudget();
        quality.lock(version);
        final long seconds = store.readRoute(version).maximumGap().toSeconds();
        final var existing = maintenance.discovery(version);
        final var discovery = existing.orElseGet(() -> new TripQualityDiscovery(Instant.EPOCH, 0, until, seconds, false));
        discovery.verifyResume(until, seconds);
        if (existing.isEmpty()) {
            maintenance.startDiscovery(version, discovery);
            quality.invalidate(version);
        }
        int processed = 0;
        if (!discovery.completed()) {
            final var batches = maintenance.discoveryPage(version, discovery, limit);
            if (!batches.isEmpty()) {
                final var batchTimes = batches.stream().collect(Collectors.toMap(
                    TripQualityMaintenanceStore.Batch::id, TripQualityMaintenanceStore.Batch::observedAt));
                final var signals = maintenance.anomalies(batches.stream().map(TripQualityMaintenanceStore.Batch::id).toList());
                final var first = new LinkedHashMap<String, TripQualityMaintenanceStore.Signal>();
                for (final var signal : signals) { first.putIfAbsent(signal.row().vehicleId(), signal); }
                for (final var signal : first.values()) {
                    investigations.observationsStored(new QualityObservationBatch(signal.batchId(), version,
                        batchTimes.get(signal.batchId()), List.of(signal.row())));
                }
            }
            processed = batches.size();
            final var last = batches.isEmpty() ? null : batches.getLast();
            discovery.advance(last == null ? discovery.cursorAt() : last.observedAt(),
                last == null ? discovery.cursorBatchId() : last.id(), processed, limit);
            maintenance.saveDiscovery(version, discovery);
            if (discovery.completed()) { quality.invalidate(version); }
        }
        final var pending = maintenance.nextPendingVehicle(version);
        final boolean advanced = pending.isPresent() && investigations.investigateLocked(version, pending.get());
        final boolean investigating = maintenance.hasPendingVehicles(version);
        return new TripQualityChunkResult(processed, discovery.completed(), discovery.completed() && !investigating,
            discovery.completed() && investigating && !advanced);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TripQualityStatus> status(final long version) {
        if (version <= 0) { throw new IllegalArgumentException("노선 버전 ID는 양수여야 한다"); }
        store.applyTimeBudget();
        return maintenance.status(version);
    }

    private static void validateRange(final long version, final Instant until) {
        if (version <= 0 || until == null) { throw new IllegalArgumentException("노선 버전과 조사 종료 시각이 필요하다"); }
    }
}

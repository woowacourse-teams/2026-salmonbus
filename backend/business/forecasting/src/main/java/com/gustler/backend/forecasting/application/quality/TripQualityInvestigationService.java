package com.gustler.backend.forecasting.application.quality;

import com.gustler.backend.forecasting.domain.quality.QualityObservationBatch;
import com.gustler.backend.forecasting.domain.quality.TripQualityInvestigation;
import com.gustler.backend.forecasting.domain.quality.TripQualityInvestigation.Phase;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TripQualityInvestigationService {
    private final TripQualityStore store;
    private final RouteDataQualityAccess quality;
    private final RouteDataQualityChanges changes;

    public TripQualityInvestigationService(final TripQualityStore store, final RouteDataQualityAccess quality,
        final RouteDataQualityChanges changes) {
        this.store = store;
        this.quality = quality;
        this.changes = changes;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void observationsStored(final QualityObservationBatch batch) {
        final var anomalies = batch.firstAnomalies();
        if (anomalies.isEmpty()) { return; }
        // 조사 완료와 새 이상 관측 저장을 직렬화한다. 조사 중의 동일 차량은 새 요청을 만들지 않는다.
        quality.lock(batch.routeVersionId());
        final var active = store.activeVehicleIds(batch.routeVersionId());
        final var maximumGap = store.maximumObservationGap(batch.routeVersionId()).orElse(null);
        final List<String> started = new ArrayList<>();
        for (final var anomaly : anomalies) {
            if (active.contains(anomaly.vehicleId())) { continue; }
            store.saveStart(TripQualityInvestigation.start(batch, anomaly, maximumGap));
            started.add(anomaly.vehicleId());
        }
        changes.vehiclesChanged(batch.routeVersionId(), started);
    }

    @Transactional(timeout = 2)
    public boolean investigateNext() {
        final var pending = store.nextPending();
        if (pending.isEmpty()) { return false; }
        store.applyTimeBudget();
        investigateLocked(pending.get().routeVersionId(), pending.get().vehicleId());
        return true;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void investigateLocked(final long version, final String vehicle) {
        quality.lock(version);
        final var pending = store.findPending(version, vehicle);
        if (pending.isEmpty()) { return; }
        final var investigation = pending.get();
        final var route = store.readRoute(version);
        final boolean backwards = investigation.phase() == Phase.SEARCH_START;
        final var rows = store.readPage(version, vehicle, investigation.cursorAt(), investigation.cursorBatchId(),
            backwards, investigation.includeCursor());
        if (backwards) {
            investigation.searchStart(route, rows,
                store.observations(investigation.anchorObservationId(), investigation.boundaryCandidateObservationId()));
        } else {
            final var previous = investigation.previousObservationId() == null ? null
                : store.previous(investigation.previousObservationId());
            final var assessments = investigation.replay(route, rows, previous);
            store.saveAssessments(version, assessments);
        }
        store.save(investigation);
        if (investigation.completed()) {
            changes.vehiclesChanged(version, List.of(vehicle));
        }
    }
}

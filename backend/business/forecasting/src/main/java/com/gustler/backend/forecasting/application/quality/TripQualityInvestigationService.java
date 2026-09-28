package com.gustler.backend.forecasting.application.quality;

import com.gustler.backend.forecasting.domain.quality.QualityObservationBatch;
import com.gustler.backend.forecasting.domain.quality.TripQualityInvestigation;
import com.gustler.backend.forecasting.domain.quality.TripQualityInvestigation.Phase;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TripQualityInvestigationService {
    private final TripQualityStore store;
    private final RouteDataQualityAccess quality;
    private final DemandStatisticsRebuildTrigger statistics;

    public TripQualityInvestigationService(final TripQualityStore store, final RouteDataQualityAccess quality,
        final DemandStatisticsRebuildTrigger statistics) {
        this.store = store;
        this.quality = quality;
        this.statistics = statistics;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void observationsStored(final QualityObservationBatch batch) {
        final var anomalies = batch.firstAnomalies();
        if (anomalies.isEmpty()) { return; }
        quality.lock(batch.routeVersionId());
        final var active = store.activeVehicleIds(batch.routeVersionId());
        final var maximumGap = store.maximumObservationGap(batch.routeVersionId()).orElse(null);
        boolean changed = false;
        for (final var anomaly : anomalies) {
            if (active.contains(anomaly.vehicleId())) { continue; }
            store.saveStart(TripQualityInvestigation.start(batch, anomaly, maximumGap));
            statistics.requestVehicle(batch.routeVersionId(), anomaly.vehicleId());
            changed = true;
        }
        if (changed) {
            quality.invalidate(batch.routeVersionId());
        }
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
            quality.invalidate(version);
            statistics.requestVehicle(version, vehicle);
        }
    }
}

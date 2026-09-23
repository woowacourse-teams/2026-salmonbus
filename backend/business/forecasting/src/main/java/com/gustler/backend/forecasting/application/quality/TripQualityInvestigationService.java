package com.gustler.backend.forecasting.application.quality;

import com.gustler.backend.forecasting.domain.quality.QualityObservationBatch;
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
    private final QualityInputRetention retention;

    public TripQualityInvestigationService(final TripQualityStore store, final RouteDataQualityAccess quality,
        final QualityInputRetention retention) {
        this.store = store;
        this.quality = quality;
        this.retention = retention;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void observationsStored(final QualityObservationBatch batch) {
        if (batch.anomalies().isEmpty()) { return; }
        quality.lock(batch.routeVersionId());
        final var evidenceIds = store.registerAnomalies(batch);
        if (!evidenceIds.isEmpty()) {
            retention.confirmObservations(evidenceIds);
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
    public boolean investigateLocked(final long version, final String vehicle) {
        quality.lock(version);
        final var pending = store.findPending(version, vehicle);
        if (pending.isEmpty()) { return false; }
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
            final List<Long> evidence = new ArrayList<>();
            for (final var assessment : assessments) {
                if (assessment.startsTrip() || assessment.excludesExistingTrip()) { evidence.add(assessment.observation().id()); }
            }
            if (investigation.previousObservationId() != null) { evidence.add(investigation.previousObservationId()); }
            retention.confirmObservations(evidence);
            store.saveAssessments(version, assessments);
        }
        final List<Long> retained = new ArrayList<>();
        retained.add(investigation.anchorObservationId());
        if (investigation.boundaryCandidateObservationId() != null) { retained.add(investigation.boundaryCandidateObservationId()); }
        retention.confirmObservations(retained);
        store.save(investigation);
        if (investigation.completed()) { quality.invalidate(version); }
        return !rows.isEmpty() || investigation.completed() || backwards;
    }
}

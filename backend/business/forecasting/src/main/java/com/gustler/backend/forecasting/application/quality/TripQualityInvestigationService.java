package com.gustler.backend.forecasting.application.quality;

import com.gustler.backend.forecasting.domain.quality.QualityObservationBatch;
import com.gustler.backend.forecasting.domain.quality.TripQualityInvestigation;
import com.gustler.backend.forecasting.domain.quality.TripQualityInvestigation.Phase;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
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
        final var anomalies = batch.firstAnomalies();
        if (anomalies.isEmpty()) { return; }
        quality.lock(batch.routeVersionId());
        final var existing = store.findForVehicles(batch.routeVersionId(),
                anomalies.stream().map(QualityObservationBatch.Row::vehicleId).toList()).stream()
            .collect(Collectors.toMap(TripQualityInvestigation::vehicleId, Function.identity()));
        final var maximumGap = store.maximumObservationGap(batch.routeVersionId()).orElse(null);
        final List<Long> evidenceIds = new ArrayList<>();
        for (final var anomaly : anomalies) {
            final var previous = existing.get(anomaly.vehicleId());
            final var started = previous == null
                ? Optional.of(TripQualityInvestigation.start(batch, anomaly, maximumGap))
                : previous.restart(batch, anomaly, maximumGap);
            if (started.isEmpty()) { continue; }
            store.saveStart(started.get());
            evidenceIds.add(started.get().evidenceObservationId());
        }
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

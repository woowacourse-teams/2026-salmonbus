package com.gustler.backend.forecasting.domain.quality;

import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Previous;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Route;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Status;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 시작점 탐색과 순방향 재판정의 커서를 별도로 보존하는 차량별 조사. */
public final class TripQualityInvestigation {
    public static final int BATCH_LIMIT = 32;
    public enum Phase { SEARCH_START, REPLAY, DONE }
    public record BatchObservations(long batch, Instant at, OneWayTripClassifier.Observation observation) { }
    private final long routeVersionId;
    private final String vehicleId;
    private final Instant evidenceAt;
    private final Duration maximumGap;
    private Instant cursorAt;
    private long cursorBatchId;
    private Phase phase;
    private long anchorObservationId;
    private Long previousObservationId;
    private boolean includeCursor;
    private boolean canRelease;
    private Long boundaryCandidateObservationId;

    public TripQualityInvestigation(final long routeVersionId, final String vehicleId, final Instant cursorAt,
        final long cursorBatchId, final Instant evidenceAt, final Phase phase, final long anchorObservationId,
        final Long previousObservationId, final boolean includeCursor, final boolean canRelease,
        final Duration maximumGap, final Long boundaryCandidateObservationId) {
        this.routeVersionId = routeVersionId;
        this.vehicleId = vehicleId;
        this.cursorAt = cursorAt;
        this.cursorBatchId = cursorBatchId;
        this.evidenceAt = evidenceAt;
        this.phase = phase;
        this.anchorObservationId = anchorObservationId;
        this.previousObservationId = previousObservationId;
        this.includeCursor = includeCursor;
        this.canRelease = canRelease;
        this.maximumGap = maximumGap == null ? OneWayTripClassifier.DEFAULT_MAXIMUM_GAP : maximumGap;
        this.boundaryCandidateObservationId = boundaryCandidateObservationId;
    }

    public void searchStart(final Route configured, final List<BatchObservations> page,
        final Map<Long, BatchObservations> retained) {
        requirePhase(Phase.SEARCH_START);
        final Map<Long, BatchObservations> observations = new HashMap<>(retained);
        final BatchObservations anchor = observations.get(anchorObservationId);
        if (anchor == null) { throw new IllegalStateException("조사 시작 관측이 없다"); }
        final BatchObservations candidate = observations.get(boundaryCandidateObservationId);
        final ReverseBoundarySearch search = new ReverseBoundarySearch(route(configured), anchor.observation(),
            candidate == null ? null : candidate.observation());
        for (final BatchObservations row : page) {
            if (search.boundaryConfirmed()) { break; }
            if (row.observation() != null) {
                observations.put(row.observation().id(), row);
                search.inspect(row.observation());
            }
        }
        final boolean found = search.boundaryConfirmed() || page.size() < BATCH_LIMIT;
        final BatchObservations nextAnchor = observations.get(found ? search.replayStart().id() : search.anchor().id());
        final BatchObservations nextCursor = found ? nextAnchor : page.getLast();
        cursorAt = nextCursor.at();
        cursorBatchId = nextCursor.batch();
        anchorObservationId = nextAnchor.observation().id();
        boundaryCandidateObservationId = found || search.candidate() == null ? null : search.candidate().id();
        includeCursor = found;
        phase = found ? Phase.REPLAY : Phase.SEARCH_START;
    }

    public List<OneWayTripAssessment> replay(final Route configured, final List<BatchObservations> page,
        final Previous retainedPrevious) {
        requirePhase(Phase.REPLAY);
        final List<OneWayTripAssessment> assessments = new ArrayList<>();
        Previous previous = retainedPrevious;
        for (final BatchObservations row : page) {
            if (row.observation() == null) { continue; }
            final OneWayTripAssessment assessment = OneWayTripAssessment.assess(route(configured), previous, row.observation());
            assessments.add(assessment);
            previous = assessment.asPrevious();
            canRelease = row.at().isAfter(evidenceAt) && assessment.decision().status() == Status.ELIGIBLE;
        }
        previousObservationId = previous == null ? null : previous.observation().id();
        if (!page.isEmpty()) {
            cursorAt = page.getLast().at();
            cursorBatchId = page.getLast().batch();
        }
        includeCursor = false;
        if (page.size() < BATCH_LIMIT && canRelease) { phase = Phase.DONE; }
        return List.copyOf(assessments);
    }

    private Route route(final Route configured) {
        return new Route(configured.firstStop(), configured.lastStop(), configured.turnStop(), maximumGap);
    }
    private void requirePhase(final Phase expected) {
        if (phase != expected) { throw new IllegalStateException("조사 단계가 맞지 않는다: " + phase); }
    }
    public long routeVersionId() { return routeVersionId; }
    public String vehicleId() { return vehicleId; }
    public Instant cursorAt() { return cursorAt; }
    public long cursorBatchId() { return cursorBatchId; }
    public Phase phase() { return phase; }
    public long anchorObservationId() { return anchorObservationId; }
    public Long previousObservationId() { return previousObservationId; }
    public boolean includeCursor() { return includeCursor; }
    public boolean canRelease() { return canRelease; }
    public Long boundaryCandidateObservationId() { return boundaryCandidateObservationId; }
    public boolean completed() { return phase == Phase.DONE; }
}

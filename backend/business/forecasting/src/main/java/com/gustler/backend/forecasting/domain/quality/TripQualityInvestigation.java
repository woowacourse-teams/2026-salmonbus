package com.gustler.backend.forecasting.domain.quality;

import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Observation;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Previous;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Route;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Status;
import com.gustler.backend.forecasting.domain.quality.QualityObservationBatch.Row;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 시작점 탐색과 순방향 재판정의 커서를 별도로 보존하는 차량별 조사. */
public final class TripQualityInvestigation {
    public static final int BATCH_LIMIT = 32;
    public enum Phase {
        SEARCH_START, REPLAY, DONE
    }
    public record BatchObservations(long batch, Instant at, Observation observation) {
    }
    private final long routeVersionId;
    private final String vehicleId;
    private final Instant evidenceAt;
    private final long evidenceObservationId;
    private final Duration maximumGap;
    private Instant cursorAt;
    private long cursorBatchId;
    private Phase phase;
    private long anchorObservationId;
    private Long previousObservationId;
    private boolean includeCursor;
    private boolean canRelease;
    private boolean completed;
    private Long boundaryCandidateObservationId;

    public TripQualityInvestigation(
        final long routeVersionId,
        String vehicleId,
        Instant cursorAt,
        final long cursorBatchId,
        Instant evidenceAt,
        final long evidenceObservationId,
        Phase phase,
        final long anchorObservationId,
        Long previousObservationId,
        final boolean includeCursor,
        final boolean canRelease,
        Duration maximumGap,
        Long boundaryCandidateObservationId,
        final boolean completed
    ) {
        this.routeVersionId = routeVersionId;
        this.vehicleId = vehicleId;
        this.cursorAt = cursorAt;
        this.cursorBatchId = cursorBatchId;
        this.evidenceAt = evidenceAt;
        this.evidenceObservationId = evidenceObservationId;
        this.phase = phase;
        this.anchorObservationId = anchorObservationId;
        this.previousObservationId = previousObservationId;
        this.includeCursor = includeCursor;
        this.canRelease = canRelease;
        this.completed = completed;
        this.maximumGap = Objects.requireNonNullElse(maximumGap, OneWayTripClassifier.DEFAULT_MAXIMUM_GAP);
        this.boundaryCandidateObservationId = boundaryCandidateObservationId;
    }

    /**
     * 차량을 식별할 수 있는 이상 관측으로 역방향 조사를 시작한다. 조사 대상이 아닌 관측은 예외로 거부한다.
     */
    public static TripQualityInvestigation start(QualityObservationBatch batch,
        Row anomaly, Duration maximumGap) {
        if (!OneWayTripAssessment.requiresInvestigation(anomaly.vehicleId(), anomaly.remainingSeats())) {
            throw new IllegalArgumentException("조사는 차량을 식별할 수 있는 이상 관측으로 시작해야 한다");
        }
        return new TripQualityInvestigation(batch.routeVersionId(), anomaly.vehicleId(), batch.observedAt(),
            batch.batchId(), batch.observedAt(), anomaly.observationId(), Phase.SEARCH_START, anomaly.observationId(),
            null, false, false, maximumGap, null, false);
    }

    /**
     * SEARCH_START 단계의 역방향 페이지로 편도 경계를 찾는다. 앵커·커서를 함께 이동하고 경계가 확정되면 재생으로 전환한다.
     */
    public void searchStart(Route configured, List<BatchObservations> page,
        Map<Long, BatchObservations> retained) {
        requirePhase(Phase.SEARCH_START);
        Map<Long, BatchObservations> observations = new HashMap<>(retained);
        BatchObservations anchor = observations.get(anchorObservationId);
        BatchObservations candidate = observations.get(boundaryCandidateObservationId);
        ReverseBoundarySearch search = new ReverseBoundarySearch(route(configured), anchor.observation(),
            observationOf(candidate));
        for (BatchObservations row : page) {
            if (search.boundaryConfirmed()) {
                break;
            }
            if (row.observation() != null) {
                observations.put(row.observation().id(), row);
                search.inspect(row.observation());
            }
        }
        final boolean found = search.boundaryConfirmed() || page.size() < BATCH_LIMIT;
        BatchObservations nextAnchor = observations.get(nextAnchorId(search, found));
        BatchObservations nextCursor = nextCursor(nextAnchor, page, found);
        cursorAt = nextCursor.at();
        cursorBatchId = nextCursor.batch();
        anchorObservationId = nextAnchor.observation().id();
        boundaryCandidateObservationId = candidateIdAfterSearch(search, found);
        includeCursor = found;
        phase = phaseAfterSearch(found);
    }

    /**
     * 관측 순서대로 편도 품질을 다시 판정하고 재생 커서를 이동한다. 빈 관측은 건너뛰며 판정 결과는 수정 불가 목록이다.
     */
    public List<OneWayTripAssessment> replay(Route configured, List<BatchObservations> page,
        Previous retainedPrevious) {
        List<OneWayTripAssessment> assessments = new ArrayList<>();
        Previous previous = retainedPrevious;
        for (BatchObservations row : page) {
            if (row.observation() == null) {
                continue;
            }
            OneWayTripAssessment assessment =
                OneWayTripAssessment.assess(route(configured), previous, row.observation());
            assessments.add(assessment);
            previous = assessment.asPrevious();
            canRelease = row.at().isAfter(evidenceAt) && assessment.decision().status() == Status.ELIGIBLE;
        }
        previousObservationId = observationIdOf(previous);
        if (!page.isEmpty()) {
            cursorAt = page.getLast().at();
            cursorBatchId = page.getLast().batch();
        }
        includeCursor = false;
        advanceReplayPhase(page.size());
        return List.copyOf(assessments);
    }

    private Route route(Route configured) {
        return new Route(configured.firstStop(), configured.lastStop(), configured.turnStop(), maximumGap);
    }

    private void requirePhase(Phase expected) {
        if (phase != expected) {
            throw new IllegalStateException("조사 단계가 맞지 않는다: " + phase);
        }
    }

    public long routeVersionId() {
        return routeVersionId;
    }

    public String vehicleId() {
        return vehicleId;
    }

    public Instant evidenceAt() {
        return evidenceAt;
    }

    public long evidenceObservationId() {
        return evidenceObservationId;
    }

    public Duration maximumGap() {
        return maximumGap;
    }

    public Instant cursorAt() {
        return cursorAt;
    }

    public long cursorBatchId() {
        return cursorBatchId;
    }

    public Phase phase() {
        return phase;
    }

    public long anchorObservationId() {
        return anchorObservationId;
    }

    public Long previousObservationId() {
        return previousObservationId;
    }

    public boolean includeCursor() {
        return includeCursor;
    }

    public boolean canRelease() {
        return canRelease;
    }

    public Long boundaryCandidateObservationId() {
        return boundaryCandidateObservationId;
    }

    public boolean completed() {
        return completed;
    }

    private static Observation observationOf(BatchObservations row) {
        if (row == null) {
            return null;
        }
        return row.observation();
    }

    private static long nextAnchorId(ReverseBoundarySearch search, final boolean found) {
        if (found) {
            return search.replayStart().id();
        }
        return search.anchor().id();
    }

    private static BatchObservations nextCursor(
        BatchObservations nextAnchor,
        List<BatchObservations> page,
        final boolean found
    ) {
        if (found) {
            return nextAnchor;
        }
        return page.getLast();
    }

    private static Long candidateIdAfterSearch(ReverseBoundarySearch search, final boolean found) {
        if (found || search.candidate() == null) {
            return null;
        }
        return search.candidate().id();
    }

    private static Phase phaseAfterSearch(final boolean found) {
        if (found) {
            return Phase.REPLAY;
        }
        return Phase.SEARCH_START;
    }

    private static Long observationIdOf(Previous previous) {
        if (previous == null) {
            return null;
        }
        return previous.observation().id();
    }

    private void advanceReplayPhase(final int pageSize) {
        if (pageSize < BATCH_LIMIT && canRelease) {
            phase = Phase.DONE;
            completed = true;
            return;
        }
        phase = Phase.REPLAY;
    }
}

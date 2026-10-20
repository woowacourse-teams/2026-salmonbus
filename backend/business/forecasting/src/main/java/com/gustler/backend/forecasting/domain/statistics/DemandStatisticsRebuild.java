package com.gustler.backend.forecasting.domain.statistics;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 하나의 노선·차량 범위 재집계 요청과 품질 판본에 속한 처리 커서를 관리한다.
 * 단계 전이는 도메인 행위로만 진행한다.
 */
public final class DemandStatisticsRebuild {
    public static final int PAGE_SIZE = 128;
    public static final int BATCH_GROUP_SIZE = 32;
    public enum Phase {
        SCAN, CLEAR, COPY, ACK, CLEAN
    }

    public record ScanWindow(long batchUntilId, Instant afterAt, long afterBatchId, Instant groupEndAt,
        long groupEndId) {
        public static ScanWindow startingAt(final long batchUntilId) {
            return new ScanWindow(batchUntilId, null, 0, null, 0);
        }

        public boolean hasOpenGroup() {
            return groupEndAt != null;
        }

        public ScanWindow openGroup(Instant endAt, final long endBatchId) {
            return new ScanWindow(batchUntilId, afterAt, afterBatchId, endAt, endBatchId);
        }

        public ScanWindow closeGroup() {
            return new ScanWindow(batchUntilId, groupEndAt, groupEndId, null, 0);
        }

        public Optional<Instant> after() {
            return Optional.ofNullable(afterAt);
        }
    }

    private final long routeVersionId;
    private final RebuildScope scope;
    private final UUID requestId;
    private final long qualityRevision;
    private final Instant dataUntil;
    private final long inputUntilId;
    private final long observationUntilId;
    private long cursorId;
    private Phase phase;
    private ScanWindow scan;

    public DemandStatisticsRebuild(
        final long routeVersionId,
        RebuildScope scope,
        UUID requestId,
        final long qualityRevision,
        Instant dataUntil,
        final long inputUntilId,
        final long observationUntilId,
        final long cursorId,
        Phase phase,
        ScanWindow scan
    ) {
        this.routeVersionId = routeVersionId;
        this.scope = Objects.requireNonNull(scope, "정정 범위가 필요하다");
        this.requestId = Objects.requireNonNull(requestId, "정정 요청 식별자가 필요하다");
        this.qualityRevision = qualityRevision;
        this.dataUntil = Objects.requireNonNull(dataUntil, "자료 기준 시각이 필요하다");
        this.inputUntilId = inputUntilId;
        this.observationUntilId = observationUntilId;
        this.cursorId = cursorId;
        this.phase = Objects.requireNonNull(phase, "정정 단계가 필요하다");
        this.scan = scan;
    }

    /**
     * 요청·품질 판본과 입력 상한을 고정한 SCAN 상태를 만든다. 진행 중 새로 들어온 입력은 이 실행에 섞지 않는다.
     */
    public static DemandStatisticsRebuild start(
        final long routeVersionId,
        RebuildScope scope,
        UUID requestId,
        final long qualityRevision,
        Instant dataUntil,
        final long inputUntilId,
        final long observationUntilId,
        final long batchUntilId
    ) {
        return new DemandStatisticsRebuild(routeVersionId, scope, requestId, qualityRevision, dataUntil, inputUntilId,
            observationUntilId, 0, Phase.SCAN, ScanWindow.startingAt(batchUntilId));
    }

    // 차량별 변경은 해당 요청 UUID로 판단한다. 전체 계산은 모든 차량의 품질 변경에 영향을 받는다.
    public boolean serves(UUID currentRequestId, final long currentRevision) {
        return requestId.equals(currentRequestId) && (!scope.isWholeRoute() || qualityRevision == currentRevision);
    }

    public boolean scansByObservationId() {
        return scan == null;
    }

    /**
     * SCAN 페이지의 끝을 반영한다. 짧은 페이지이면 현재 합계를 정리하는 단계로 넘어간다.
     */
    public void scannedObservations(List<Long> observationIds) {
        requirePhase(Phase.SCAN);
        if (observationIds.size() < PAGE_SIZE) {
            move(Phase.CLEAR, 0);
            return;
        }
        move(Phase.SCAN, observationIds.getLast());
    }

    public void groupOpened(Instant endAt, final long endBatchId) {
        requirePhase(Phase.SCAN);
        scan = requireScan().openGroup(endAt, endBatchId);
    }

    public void noGroupLeft() {
        requirePhase(Phase.SCAN);
        move(Phase.CLEAR, 0);
    }

    /**
     * SCAN 단계에서 그룹 페이지의 끝 커서를 이동한다. 짧은 페이지이면 현재 그룹을 닫는다.
     */
    public void scannedGroup(List<Long> observationIds) {
        requirePhase(Phase.SCAN);
        if (observationIds.size() < PAGE_SIZE) {
            scan = requireScan().closeGroup();
            move(Phase.SCAN, 0);
            return;
        }
        move(Phase.SCAN, observationIds.getLast());
    }

    public void cleared(final int removed) {
        requirePhase(Phase.CLEAR);
        if (removed == 0) {
            move(Phase.COPY, 0);
        }
    }

    public void copied(final int copied, final long lastTotalId) {
        requirePhase(Phase.COPY);
        if (copied < PAGE_SIZE) {
            move(Phase.ACK, 0);
            return;
        }
        move(Phase.COPY, lastTotalId);
    }

    public void acknowledged(final int visited, final long lastSampleId) {
        requirePhase(Phase.ACK);
        if (visited < PAGE_SIZE) {
            move(Phase.CLEAN, 0);
            return;
        }
        move(Phase.ACK, lastSampleId);
    }

    public boolean cleanedUp(final int removed) {
        requirePhase(Phase.CLEAN);
        return removed == 0;
    }

    private ScanWindow requireScan() {
        if (scan == null) {
            throw new IllegalStateException("관측 ID 순서로 시작한 정정에는 묶음 범위가 없다");
        }
        return scan;
    }

    private void move(Phase next, final long nextCursorId) {
        phase = next;
        cursorId = nextCursorId;
    }

    private void requirePhase(Phase expected) {
        if (phase != expected) {
            throw new IllegalStateException("통계 정정 단계가 %s가 아니다: %s".formatted(expected, phase));
        }
    }

    public long routeVersionId() {
        return routeVersionId;
    }

    public RebuildScope scope() {
        return scope;
    }

    public UUID requestId() {
        return requestId;
    }

    public long qualityRevision() {
        return qualityRevision;
    }

    public Instant dataUntil() {
        return dataUntil;
    }

    public long inputUntilId() {
        return inputUntilId;
    }

    public long observationUntilId() {
        return observationUntilId;
    }

    public long cursorId() {
        return cursorId;
    }

    public Phase phase() {
        return phase;
    }

    public Optional<ScanWindow> scan() {
        return Optional.ofNullable(scan);
    }
}

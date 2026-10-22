package com.gustler.backend.forecasting.application.statistics;

import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRebuild;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRebuild.ScanWindow;
import com.gustler.backend.forecasting.domain.statistics.RebuildScope;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 재집계의 유한한 관측 페이지와 중간 합계 정리·복사·확인 작업을 제공한다.
 */
public interface DemandStatisticsRebuildStore {
    record BatchPosition(Instant at, long id) {
    }

    record Page(int size, long lastId) {
    }

    /**
     * 이전 재구축의 중간 합계를 최대 limit건 정리한다. 새 입력 상한을 고정하기 전에 호출한다.
     */
    int clearLeftoverTotals(long routeVersionId, RebuildScope scope, int limit);

    /**
     * 재구축 입력의 고정 상한으로 사용할 현재 마지막 관측 식별자를 읽는다.
     */
    long lastObservationId();

    /**
     * 재구축 그룹 탐색의 고정 상한으로 사용할 현재 마지막 수집 묶음 식별자를 읽는다.
     */
    long lastBatchId();

    /**
     * 식별자 커서 이후부터 고정 관측 상한까지 최대 limit건의 입력 식별자를 읽는다.
     */
    List<Long> observationPage(long afterObservationId, long observationUntilId, int limit);

    /**
     * 현재 탐색 창 뒤의 그룹 끝을 찾는다. 빈 값은 더 진행할 그룹이 없음을 뜻한다.
     */
    Optional<BatchPosition> nextGroupEnd(long routeVersionId, ScanWindow window, int groupSize);

    /**
     * 고정된 그룹 창과 관측 상한 안에서 처리할 식별자를 최대 limit건 읽는다. 빈 결과도 유효한 그룹 페이지일 수 있다.
     */
    List<Long> groupObservationPage(
        long routeVersionId,
        ScanWindow window,
        RebuildScope scope,
        long afterObservationId,
        long observationUntilId,
        int groupSize,
        int limit
    );

    /**
     * 선택된 관측으로 요청별 중간 합계를 누적한다. 운영 합계 교체와 분리된 재구축 공간에 저장한다.
     */
    void addRebuildTotals(DemandStatisticsRebuild rebuild, List<Long> observationIds);

    /**
     * 선택한 노선·차량 범위의 현재 합계를 최대 limit건 제거한다. 다음 처리 여부를 삭제 건수로 판단한다.
     */
    int clearCurrentTotals(long routeVersionId, RebuildScope scope, int limit);

    /**
     * 완성된 요청의 중간 합계를 커서 이후부터 현재 합계로 옮기고 진행 정보를 반환한다.
     */
    Page copyToCurrentTotals(UUID requestId, long afterTotalId, int limit);

    /**
     * 고정 입력 상한과 시각까지의 처리 표본을 페이지 단위로 정리하고 다음 커서를 반환한다.
     */
    Page acknowledgeSamples(
        long routeVersionId,
        RebuildScope scope,
        long afterSampleId,
        long inputUntilId,
        Instant dataUntil,
        int limit
    );

    /**
     * 요청별 중간 합계를 최대 limit건 제거하고 실제 삭제 건수를 반환한다.
     */
    int cleanRebuildTotals(UUID requestId, int limit);
}

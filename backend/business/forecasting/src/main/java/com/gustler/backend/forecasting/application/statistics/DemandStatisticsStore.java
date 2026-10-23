package com.gustler.backend.forecasting.application.statistics;

import com.gustler.backend.forecasting.domain.statistics.DailyStopDemand;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsBaseline;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRun.FoldCursor;
import com.gustler.backend.forecasting.domain.statistics.DemandStatisticsRun.ReduceCursor;
import com.gustler.backend.forecasting.domain.statistics.StopDemandCellTotals;
import com.gustler.backend.forecasting.domain.statistics.VehicleHourlyDemand;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 통계 처리 단계의 입력 페이지·중간 합계·발행 입력을 제공하는 저장 경계다.
 */
public interface DemandStatisticsStore {
    record FoldRow(VehicleHourlyDemand total, Integer capacity) {
    }

    /**
     * 현재 DB 작업에 적용할 문장 실행 시간 제한을 설정한다. 외부 호출의 timeout과는 별개다.
     */
    void limitStatementTime();

    /** 쓰기·행 잠금 없이, 재집계할 일이 없고 갱신 주기가 남은 완료 통계의 기준 시각을 읽는다. */
    Optional<Instant> idleDataUntil(long routeVersionId, Instant completedAfter);

    /**
     * 현재 통계의 초기화 여부와 처리 시각 상한을 함께 읽는다.
     */
    DemandStatisticsBaseline baseline(long routeVersionId);

    /**
     * 현재 통계의 초기화 여부와 데이터 상한을 함께 확정한다. 통계 교체와 같은 트랜잭션에서 호출한다.
     */
    void recordBaseline(long routeVersionId, boolean initialized, Instant dataUntil);

    /**
     * 고정 입력 상한과 현재 차량·입력 커서 뒤의 다음 차량을 찾는다. 대상이 없으면 빈 값이다.
     */
    Optional<String> nextAccumulationVehicle(
        long routeVersionId,
        long inputUntilId,
        String vehicleCursor,
        long inputCursor
    );

    /**
     * 차량·시간별 증분을 현재 합계에 누적한다. 처리 표본 제거와 같은 트랜잭션에서 호출한다.
     */
    void addToCurrentTotals(long routeVersionId, List<VehicleHourlyDemand> increments);

    /**
     * 노선 통계에서 누적 처리할 차량을 등록한다. 조회 전용 연산이 아니다.
     */
    void registerVehicle(long routeVersionId, String vehicleId);

    /**
     * 노선의 이전 중간 결과를 최대 limit건 정리하고 삭제 건수를 반환한다.
     */
    int clearStagePage(long routeVersionId, int limit);

    /**
     * 지정 시각·입력 상한으로 통계 계산에 사용할 용량을 단계 저장소에 고정한다.
     */
    void stageCapacities(long routeVersionId, Instant dataUntil, long inputUntilId);

    /**
     * 접기 커서 이후의 합계 행을 최대 limit건 읽는다. 입력 Optional은 기존 커서 부재 계약을 유지한다.
     */
    List<FoldRow> foldPage(long routeVersionId, Optional<FoldCursor> after, int limit);

    /**
     * 일별 중간 결과를 단계 저장소에 반영한다. 최종 통계 발행과 분리해 사용한다.
     */
    void addToDayStage(long routeVersionId, List<DailyStopDemand> days);

    /**
     * 축약 커서 이후의 일별 통계를 최대 limit건 읽는다. 커서가 없으면 첫 페이지부터 시작한다.
     */
    List<DailyStopDemand> reducePage(long routeVersionId, Optional<ReduceCursor> after, int limit);

    /**
     * 셀별 중간 합계를 운영 통계와 분리된 단계 저장소에 반영한다.
     */
    void addToCellStage(long routeVersionId, List<StopDemandCellTotals> cells);

    /**
     * 발행할 셀별 중간 합계를 읽는다. 아직 운영 통계로 교체하지 않는다.
     */
    List<StopDemandCellTotals> stagedCells(long routeVersionId);
}

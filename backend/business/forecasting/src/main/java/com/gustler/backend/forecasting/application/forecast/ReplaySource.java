package com.gustler.backend.forecasting.application.forecast;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 복구에 쓸 확정 배치를 관측과 같이 읽는 포트다.
 */
public interface ReplaySource {

    /**
     * 통계의 노선 표시 이름과 같은 모든 노선 버전에서, 처리 시작 시각이 {@code from} 이상이고 (처리 시작 시각, 배치 번호)가
     * ({@code through}, {@code throughBatchId}) 이하인 확정 배치를 그 순서로 반환한다. 배치의 줄은 저장된 모든 행이며 차량 번호 순이다.
     * 조회 범위 안 버전의 정류장 구성이 통계와 다르거나 행 수가 한도를 넘으면 사유와 같이 실패를 반환한다.
     */
    Read read(
        AlightingAwareRouteStatistics statistics,
        Instant from,
        Instant through,
        long throughBatchId
    );

    /**
     * 읽은 배치 목록 또는 실패 사유다. 실패면 배치 목록은 비어 있다.
     *
     * @param unforecastRuns 읽은 배치 가운데 확정한 통과가 저장되지 않은 배치. 키는 관측 배치 번호다. 입력 부족으로
     *     끝난 예보 실행의 배치다. 이런 배치는 이력에는 들어가지만 그 배치가 확정한 통과가
     *     저장되지 않았다
     */
    record Read(
        List<ForecastBatch> batches,
        String failureReason,
        Map<Long, UnforecastRun> unforecastRuns
    ) {

        public Read {
            batches = List.copyOf(batches);
            unforecastRuns = Map.copyOf(unforecastRuns);
        }

        public static Read of(List<ForecastBatch> batches) {
            return new Read(batches, null, Map.of());
        }

        public static Read of(
            List<ForecastBatch> batches,
            Map<Long, UnforecastRun> unforecastRuns
        ) {
            return new Read(batches, null, unforecastRuns);
        }

        public static Read failed(String reason) {
            return new Read(List.of(), reason, Map.of());
        }

        public boolean succeeded() {
            return failureReason == null;
        }
    }

    /**
     * 확정한 통과가 저장되지 않은 배치 하나다. 예보 실행이 입력 부족({@code INPUT_UNAVAILABLE})으로 끝난
     * 배치가 확정한 통과를 저장할 때 쓸 값을 담는다.
     *
     * @param sourceDate 배치의 파티션 기준 날짜
     * @param routeId 배치의 노선
     */
    record UnforecastRun(LocalDate sourceDate, long routeId) {

    }
}

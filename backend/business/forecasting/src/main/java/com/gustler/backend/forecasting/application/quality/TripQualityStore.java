package com.gustler.backend.forecasting.application.quality;

import com.gustler.backend.forecasting.domain.quality.OneWayTripAssessment;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Previous;
import com.gustler.backend.forecasting.domain.quality.OneWayTripClassifier.Route;
import com.gustler.backend.forecasting.domain.quality.TripQualityInvestigation.BatchObservations;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 제한된 관측 배치와 이전 편도 근거를 읽고 판정 변경을 저장하는 품질 조사 경계다.
 */
public interface TripQualityStore {
    /**
     * 노선 관측에서 허용할 최대 시간 간격을 읽는다. 설정이 없으면 빈 값이다.
     */
    Optional<Duration> maximumObservationGap(long routeVersionId);

    /**
     * 조사할 노선 판본의 편도·정류장·좌석 판정 규칙을 읽는다.
     */
    Route readRoute(long routeVersionId);

    /**
     * 시각·묶음 커서를 기준으로 정방향 또는 역방향 조사 페이지를 읽는다. include는 경계 커서 포함 여부다.
     */
    List<BatchObservations> readPage(
        long version,
        String vehicle,
        Instant at,
        long batch,
        boolean backwards,
        boolean include
    );

    /**
     * 조사 앵커와 선택적 경계 후보를 식별자로 복원한다. 없는 후보와 실제 관측을 구별한다.
     */
    Map<Long, BatchObservations> observations(long anchor, Long candidate);

    /**
     * 지정 관측 앞의 품질 문맥을 읽는다. 이전 관측 부재는 Previous의 기존 부재 계약으로 표현한다.
     */
    Previous previous(long observationId);

    /**
     * 재생한 편도 판정과 관측 적격성을 저장한다. 조사 진행 상태와 같은 트랜잭션에서 호출한다.
     */
    void saveAssessments(long version, List<OneWayTripAssessment> assessments);

    /**
     * 현재 조사 DB 작업에 사용할 문장 실행 시간 예산을 적용한다.
     */
    void applyTimeBudget();
}

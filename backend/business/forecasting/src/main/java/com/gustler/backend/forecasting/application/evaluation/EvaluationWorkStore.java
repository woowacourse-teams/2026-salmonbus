package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 조회는 잠금 없이 수행할 수 있다. 등록·종료는 호출자가 확보한 노선 품질 잠금과 트랜잭션이 필요하다. */
public interface EvaluationWorkStore {
    /**
     * 발행된 관측의 평가 작업을 등록한다. 예측 발행과 같은 트랜잭션에서 호출한다.
     */
    void register(long version, List<Long> observationIds);

    /**
     * 평가 작업이 남은 노선 판본을 읽는다. 작업이 없으면 빈 목록이다.
     */
    List<Long> routes();

    /**
     * 해당 노선 작업 페이지의 고정 상한으로 사용할 마지막 관측 식별자를 읽는다.
     */
    long lastObservationId(long version);

    /**
     * 커서 이후부터 고정 상한까지 최대 limit건의 관측 식별자를 읽는다.
     */
    List<Long> page(
        long version,
        long afterObservationId,
        long throughObservationId,
        int limit
    );

    /**
     * 지정 관측별 현재 작업 판본을 읽는다. 계산 결과를 커밋하기 전 변경 감지에 사용한다.
     */
    Map<Long, Long> revisions(long version, List<Long> observationIds);

    /**
     * 지정 관측의 저장된 작업과 미완료 예측을 복원한다. 예측이 없는 상태도 복원 결과에서 구별한다.
     */
    List<Restored> restore(long version, List<Long> observationIds);

    /**
     * 선택 관측 중 품질상 제외된 미완료 작업을 최대 limit건 닫고 닫힌 키를 반환한다.
     */
    List<Key> closeExcluded(long version, List<Long> observationIds, int limit);

    /**
     * 선택 관측에서 평가가 끝난 대상 키를 읽는다. 아직 끝나지 않은 작업은 결과에 포함하지 않는다.
     */
    List<Key> completed(long version, List<Long> observationIds);

    /**
     * 끝난 평가 작업의 관측을 해제하고 해제된 식별자를 반환한다.
     */
    List<Long> releaseCompleted(long version, List<Long> observationIds);

    record Key(long observationId, int targetStopOrder) {
    }

    /** 미완료 키를 나타낸다. 평가 정보가 없으면 완료가 아니라 보류다. */
    record Restored(Key key, Optional<PendingForecast> forecast) {
    }
}

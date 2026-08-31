package com.gustler.backend.collector.persistence.jpa;

import com.gustler.backend.collector.CollectedObservations;
import com.gustler.backend.collector.ForecastAlreadyCompletedException;
import com.gustler.backend.collector.ObservationAttempt;
import com.gustler.backend.collector.ObservationBatchConclusion;
import com.gustler.backend.collector.ObservationBatchFailureCode;
import com.gustler.backend.collector.ObservationBatchOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 조회 쪽도 이 표를 매핑한다. 같은 물리 컬럼에 논리명이 둘이면 Hibernate 가
 * DuplicateMappingException 을 내고 앱이 안 뜬다. 그래서 전 필드에 @Column(name) 을 명시한다.
 *
 * <p>http_status 는 매핑하지 않는다. GbisLocationSource 가 오류 응답의 상태 코드를 삼키고
 * GbisLocationResult 가 그것을 들고 나오지 않아 채울 값이 없다. 열은 NULL 허용으로 이미 있다.
 *
 * <p>completed_at 도 매핑하지 않는다. 무엇을 완료로 볼지는 예보까지 끝났는지를 찍는
 * forecast_completed_at 과 같이 정해야 뜻이 선다.
 */
@Entity(name = "CollectorObservationBatch")
@Table(name = "observation_batch")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ObservationBatchJpaEntity {

    private static final int FIRST_ATTEMPT = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "route_version_id")
    private Long routeVersionId;

    @Column(name = "scheduled_at")
    private OffsetDateTime scheduledAt;

    @Column(name = "attempt_number")
    private Integer attemptNumber;

    @Column(name = "attempt_key")
    private String attemptKey;

    @Column(name = "requested_at")
    private OffsetDateTime requestedAt;

    @Column(name = "response_received_at")
    private OffsetDateTime responseReceivedAt;

    /**
     * processor 가 이 판으로 예보를 끝냈을 때 찍는다. 수집은 이 값을 쓰지 않고 읽기만 한다.
     * 예보가 끝난 판을 다시 열면 안 되는지 판별하는 데만 본다.
     */
    @Column(name = "forecast_completed_at", insertable = false, updatable = false)
    private OffsetDateTime forecastCompletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome")
    private ObservationBatchOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code")
    private ObservationBatchFailureCode failureCode;

    @Column(name = "result_code")
    private Integer resultCode;

    @Column(name = "provider_rows")
    private Integer providerRows;

    @Column(name = "stored_rows")
    private Integer storedRows;

    @Column(name = "excluded_rows")
    private Integer excludedRows;

    @Column(name = "normalization_version")
    private String normalizationVersion;

    public ObservationBatchJpaEntity(
        ObservationAttempt attempt,
        ObservationBatchOutcome outcome,
        ObservationBatchFailureCode failureCode
    ) {
        this.routeVersionId = attempt.routeVersionId();
        this.scheduledAt = attempt.scheduledAt();
        this.attemptKey = attempt.attemptKey();
        this.attemptNumber = FIRST_ATTEMPT;
        this.outcome = outcome;
        this.failureCode = failureCode;
        this.normalizationVersion = CollectedObservations.CURRENT_NORMALIZATION_VERSION;
    }

    /**
     * 같은 계획을 다시 부른다. 시도 횟수만 올리고 나머지는 처음 상태로 되돌린다.
     * 묶음은 계획 하나에 한 행이라 지난 시도의 값이 남아 있으면 이번 판의 사실이 아니게 된다.
     *
     * <p>예보가 이미 끝난 판은 거절한다. 되돌릴 것이 수집 밖으로 번지기 때문이다.
     * forecast_completed_at 을 그대로 두면 예보 대기 조회가 이 판을 건너뛰고 조회 쪽은 지난 완료 시각을
     * 이번 예보의 것으로 읽는다. 지우면 이미 나간 seat_forecast 행까지 같이 정리해야 하는데,
     * 그건 수집이 건드릴 영역이 아니다. 애초에 예보가 끝난 뒤 같은 계획을 다시 부를 일이 없다.
     */
    public void reopen(
        ObservationBatchOutcome newOutcome,
        ObservationBatchFailureCode newFailureCode
    ) {
        requireForecastNotCompleted();
        this.attemptNumber = attemptNumber + 1;
        this.outcome = newOutcome;
        this.failureCode = newFailureCode;
        this.requestedAt = null;
        this.responseReceivedAt = null;
        this.resultCode = null;
        this.providerRows = null;
        this.storedRows = null;
        this.excludedRows = null;
    }

    private void requireForecastNotCompleted() {
        if (forecastCompletedAt != null) {
            throw new ForecastAlreadyCompletedException(id, forecastCompletedAt);
        }
    }

    public void markDispatching(
        OffsetDateTime dispatchedAt
    ) {
        this.outcome = ObservationBatchOutcome.DISPATCHING;
        this.requestedAt = dispatchedAt;
    }

    public void abandonBeforeSend() {
        this.outcome = ObservationBatchOutcome.ABANDONED_BEFORE_SEND;
    }

    public void conclude(
        ObservationBatchConclusion conclusion,
        OffsetDateTime receivedAt
    ) {
        this.outcome = conclusion.outcome();
        this.failureCode = conclusion.failureCode();
        this.resultCode = conclusion.upstreamResultCode();
        this.responseReceivedAt = receivedAt;
    }

    /** 상류가 준 행 수와 우리가 쌓은 행 수와 뺀 행 수. 상류가 정상으로 답한 판에만 있다. */
    public void countRows(
        CollectedObservations collected
    ) {
        this.providerRows = collected.providerRows();
        this.storedRows = collected.storableRows().size();
        this.excludedRows = collected.excludedRows().size();
    }
}

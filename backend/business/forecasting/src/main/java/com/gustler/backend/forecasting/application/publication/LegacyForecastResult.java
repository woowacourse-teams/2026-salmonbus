package com.gustler.backend.forecasting.application.publication;

import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.domain.publication.SeatForecast;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 옛 발행 경로가 확정 판본 배치 하나를 처리한 결과다. 그림자 실행이 같은 입력으로 새 경로를 돌리고 이 결과와 대조하는 데 쓴다.
 *
 * <p>착수 시각({@code generatedAt})은 옛 경로가 이 처리에서 찍은 값이다. 예측 목록은 커밋한 발행의 목록 그대로이며
 * {@link Kind#PUBLISHED}일 때만 담는다(빈 목록일 수 있다). 사유는 {@link Kind#FAILED}일 때 예외 메시지다.
 *
 * @param batch 처리한 배치
 * @param stops 배치 노선 판본의 정류장
 * @param runtime 처리에 쓴 활성 배포와 모델
 * @param statistics 모델이 이 노선에 쓰는 확정 판본 통계
 * @param generatedAt 옛 경로의 착수 시각
 * @param kind 옛 경로가 낸 결과의 종류
 * @param reason 예보하지 못한 사유. {@link Kind#FAILED}가 아니면 비어 있을 수 있다
 * @param predictions 커밋한 예측 목록. 바꿀 수 없는 목록이어야 한다
 */
public record LegacyForecastResult(
    PendingForecastBatch batch,
    RouteStops stops,
    RuntimeSnapshot runtime,
    AlightingAwareRouteStatistics statistics,
    Instant generatedAt,
    Kind kind,
    String reason,
    List<SeatForecast> predictions
) {
    public LegacyForecastResult {
        Objects.requireNonNull(batch);
        Objects.requireNonNull(stops);
        Objects.requireNonNull(runtime);
        Objects.requireNonNull(statistics);
        Objects.requireNonNull(generatedAt);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(predictions);
        if (kind != Kind.PUBLISHED && !predictions.isEmpty()) {
            throw new IllegalArgumentException("예측 목록은 발행한 결과에만 담는다: " + kind);
        }
    }

    /** 옛 경로가 낸 결과의 종류다. */
    public enum Kind {
        /** 발행을 커밋했다. 예측이 없으면 빈 발행이다. */
        PUBLISHED,
        /** 입력 확정이 실패해 빈 발행을 커밋했다. 빈 응답이거나 수신 뒤 90초를 넘긴 배치다. */
        NOT_CONFIRMED,
        /** 출퇴근 범위 밖이라 빈 발행을 커밋했다. */
        OUT_OF_SCOPE,
        /** 입력을 만들지 못해 발행하지 않았다. 옛 경로는 같은 배치를 다시 처리한다. */
        FAILED
    }
}

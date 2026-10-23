package com.gustler.backend.forecasting.domain.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.Lost;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.NotArrivedYet;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.SeatMissing;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.Settled;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel.Skipped;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 예보 하나와 후속 관측으로 평가 결과를 정한다. DB에 접근하지 않는다.
 *
 * <p><b>평가에는 통과 순번이 대상 순번과 같은 관측을 사용한다.</b> 해당하는 경우는 세 가지다.
 * 정류장 출발(운행 상태 2, 순번 S) · 교차로 통과(운행 상태 0, 순번 S) ·
 * 다음 정류장 도착(운행 상태 1, 순번 S+1). 적재가 통과 순번을 낼 때 운행 상태가 1이면 순번에서
 * 하나를 빼므로 셋 다 통과 순번이 S가 된다. 라벨로 사용하지 않는 관측(대상 정류장에 도착 중,
 * 운행 상태 1 · 순번 S)은 통과 순번이 S − 1이므로 제외된다.
 *
 * <p>도착이 아니라 <b>출발</b>을 재는 이유는 도착 시점의 0석이 만차가 아니기 때문이다.
 * 하차하면 잔여석이 늘어난다. 실측 만석 비율은 도착 시점 3.02퍼센트, 출발 이후 11.95퍼센트로 네 배 차이가 난다.
 *
 * <p><b>평가 대기 시간에는 상한을 둔다.</b> 운행을 끝낸 차량처럼 후속 관측에서 대상 순번을
 * 확인할 수 없으면 예보가 계속 미평가 상태로 남는다. 이런 행이 쌓여 평가 대상 조회를 채우면
 * 이후 예보를 처리할 수 없다. {@link #LONGEST_WAIT_FOR_ARRIVAL}이 지나면 추적 중단으로 확정한다.
 *
 * <p>운행 방향이 바뀌거나 사용 불가 관측을 만나면 결과 연결을 끊는다.
 * 정상 관측은 사전 편도 판정 없이 사용한다. 기존의 90초 관측 공백과
 * 순번 되돌림 규칙도 유지한다. 90초는 편도 구분 정책값이 아니다.
 */
public final class ArrivalLabelResolver {
    /**
     * 라벨로 인정하는 연속 관측 간격. 이보다 벌어지면 같은 여정인지 판단할 수 없다.
     *
     * <p>수집 간격은 첨두 15초 · 낮 20초로 이 제한보다 짧다. 심야 600초 구간에서는 이 제한을 넘어
     * 라벨이 거의 남지 않는다.
     */
    private static final Duration LONGEST_LABEL_GAP = Duration.ofSeconds(90);

    /**
     * 도착을 기다리는 한도. 이 한도를 지나도 대상 순번에 도달하지 않으면 해당 여정은 끝난 것으로 본다.
     *
     * <p>예보 거리 12정류장의 중앙 소요 시간이 26.5분이라 정상 운행이면 그 안에 도달한다. 지연과 심야 배차를 감안해
     * 네 배 넘게 잡았다. 한도가 짧으면 늦게 도착한 차량의 라벨을 버리고 길면 미평가 행이 오래 쌓인다.
     */
    private static final Duration LONGEST_WAIT_FOR_ARRIVAL = Duration.ofHours(2);

    private ArrivalLabelResolver() {
    }

    /**
     * @param laterObservations 같은 차량의 관측 후보. 시각 오름차순이어야 하며 계산 시각 이전과 같은 시각은 제외한다
     * @param now 지금 시각. 기다림을 언제 그만둘지 재는 데만 쓴다
     */
    public static ArrivalLabel resolve(
        PendingForecast forecast,
        List<ArrivalCandidate> laterObservations,
        Instant now
    ) {
        if (!forecast.hasVehicleId()) {
            return new Lost();
        }
        int passedStopOrder = forecast.passedStopOrder();
        Instant previousObservedAt = forecast.observedAt();
        for (ArrivalCandidate candidate : laterObservations) {
            if (!candidate.observedAt().isAfter(forecast.generatedAt())) {
                continue;
            }
            if (!candidate.qualityAssessed()) {
                if (waitedTooLong(forecast, now)) {
                    return new Lost();
                }
                return new NotArrivedYet();
            }
            if (forecast.qualityDirection() != null
                && !forecast.qualityDirection().equals(candidate.qualityDirection())) {
                return new Lost();
            }
            if (isGapTooLong(previousObservedAt, candidate.observedAt())) {
                return new Lost();
            }
            if (candidate.passedStopOrder() < passedStopOrder) {
                return new Lost();
            }
            if (candidate.passedStopOrder() > forecast.targetStopOrder()) {
                return new Skipped();
            }
            if (candidate.passedStopOrder() == forecast.targetStopOrder()) {
                return labelOf(candidate);
            }
            passedStopOrder = candidate.passedStopOrder();
            previousObservedAt = candidate.observedAt();
        }
        if (waitedTooLong(forecast, now)) {
            return new Lost();
        }
        return new NotArrivedYet();
    }

    private static boolean waitedTooLong(
        PendingForecast forecast,
        Instant now
    ) {
        return Duration.between(forecast.observedAt(), now).compareTo(LONGEST_WAIT_FOR_ARRIVAL) > 0;
    }

    private static boolean isGapTooLong(
        Instant earlier,
        Instant later
    ) {
        return Duration.between(earlier, later).compareTo(LONGEST_LABEL_GAP) > 0;
    }

    private static ArrivalLabel labelOf(
        ArrivalCandidate arrival
    ) {
        if (!arrival.hasKnownSeats()) {
            return new SeatMissing(arrival.observationId());
        }
        return new Settled(arrival.observationId(), arrival.remainingSeats());
    }
}

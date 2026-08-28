package com.gustler.backend.processor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 예보 한 줄과 그 뒤 관측을 받아 라벨과 회수 상태를 정한다. DB 를 안 탄다.
 *
 * <p><b>라벨로 인정하는 관측은 통과 순번이 대상 순번과 같은 것 하나다.</b> 티켓은 셋으로 적었다.
 * 정류장 출발(운행 상태 2, 순번 S) · 교차로 통과(운행 상태 0, 순번 S) ·
 * 다음 정류장 도착(운행 상태 1, 순번 S+1). 적재가 통과 순번을 낼 때 운행 상태가 1이면 순번에서
 * 하나를 빼므로 셋 다 통과 순번이 S 가 된다. 라벨로 안 쓰는 자리(대상 정류장에 도착 중,
 * 운행 상태 1 · 순번 S)는 통과 순번이 S − 1 이라 저절로 빠진다.
 *
 * <p>도착이 아니라 <b>출발</b>을 재는 이유는 도착 시점의 0석이 만차가 아니기 때문이다.
 * 하차하면 자리가 생긴다. 실측으로도 도착 시점 만석 3.02퍼센트 대 출발 이후 11.95퍼센트로 네 배 벌어진다.
 *
 * <p>여정은 여정 키로 안 잇는다. 적재가 그 열을 아직 안 채워서 늘 비어 있고, 티켓대로 하면
 * 모든 예보 행이 끊김으로 닫힌다. 대신 같은 차량으로 잇고 순번 되돌림과 관측 공백으로 끊는다.
 * 궤적 조립이 쓰는 규칙과 같다. 여정 키가 채워지면 이 판정을 다시 봐야 한다.
 */
public final class ArrivalLabelResolver {

    /**
     * 라벨로 인정하는 연속 관측 간격. 이보다 벌어지면 같은 여정인지 믿을 수 없다.
     *
     * <p>수집이 첨두 15초 · 낮 20초라 여유가 크다. 심야 600초 구간에서는 이 문턱을 넘어
     * 라벨이 거의 안 남는다.
     */
    private static final Duration LONGEST_LABEL_GAP = Duration.ofSeconds(90);

    private ArrivalLabelResolver() {
    }

    /**
     * @param laterObservations 예보를 낸 관측보다 뒤인 같은 차량의 관측. 시각 오름차순이어야 한다
     */
    public static ArrivalLabel resolve(
        PendingForecast forecast,
        List<ArrivalCandidate> laterObservations
    ) {
        if (!forecast.hasVehicleId()) {
            return new ArrivalLabel.Lost();
        }
        int passedStopOrder = forecast.passedStopOrder();
        Instant previousObservedAt = forecast.observedAt();
        for (ArrivalCandidate candidate : laterObservations) {
            if (isGapTooLong(previousObservedAt, candidate.observedAt())) {
                return new ArrivalLabel.Lost();
            }
            if (candidate.passedStopOrder() < passedStopOrder) {
                return new ArrivalLabel.Lost();
            }
            if (candidate.passedStopOrder() > forecast.targetStopOrder()) {
                return new ArrivalLabel.Skipped();
            }
            if (candidate.passedStopOrder() == forecast.targetStopOrder()) {
                return labelOf(candidate);
            }
            passedStopOrder = candidate.passedStopOrder();
            previousObservedAt = candidate.observedAt();
        }
        return new ArrivalLabel.NotArrivedYet();
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
            return new ArrivalLabel.SeatMissing(arrival.observationId());
        }
        return new ArrivalLabel.Settled(arrival.observationId(), arrival.remainingSeats());
    }
}

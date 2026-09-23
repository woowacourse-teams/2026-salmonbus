package com.gustler.backend.forecasting.domain.evaluation;

/**
 * 예보 대상 차량의 후속 관측을 판정한 결과. 아직 평가할 수 없는 대기 상태도 포함한다.
 *
 * <p>결과를 확인하지 못한 이유를 구분한다. 예측이 틀린 경우와 평가할 자료가 없는 경우를
 * 같은 결과로 처리하지 않기 위해서다.
 * 확정된 평가를 저장할 때는 대기 상태를 허용하지 않는 {@link EvaluationResult}로 변환한다.
 */
public sealed interface ArrivalLabel {

    ScoringState scoringState();

    /** 대상 정류장을 지난 관측에서 잔여석을 확인했다. 0석이면 만석으로 평가한다. */
    record Settled(
        long arrivalObservationId,
        int seatsOnArrival
    ) implements ArrivalLabel {

        public Settled {
            if (seatsOnArrival < 0) {
                throw new IllegalArgumentException("도착 잔여석은 0석 이상이다: " + seatsOnArrival);
            }
        }

        @Override
        public ScoringState scoringState() {
            return ScoringState.SETTLED;
        }
    }

    /** 도착 관측은 있지만 잔여석을 알 수 없다. */
    record SeatMissing(
        long arrivalObservationId
    ) implements ArrivalLabel {

        @Override
        public ScoringState scoringState() {
            return ScoringState.SEAT_MISSING;
        }
    }

    /** 대상 정류장 순번을 건너뛰어 해당 정류장의 관측이 없다. */
    record Skipped() implements ArrivalLabel {

        @Override
        public ScoringState scoringState() {
            return ScoringState.SKIPPED;
        }
    }

    /** 대상 정류장에 도달하기 전에 추적이 끊겼다. 관측 공백·순번 되돌림·차량 ID 부재가 해당한다. */
    record Lost() implements ArrivalLabel {

        @Override
        public ScoringState scoringState() {
            return ScoringState.LOST;
        }
    }

    /** 아직 대상 정류장에 도달하지 않아 다음 평가 회차에서 다시 확인한다. */
    record NotArrivedYet() implements ArrivalLabel {

        @Override
        public ScoringState scoringState() {
            return ScoringState.PENDING;
        }
    }
}

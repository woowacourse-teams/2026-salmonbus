package com.gustler.backend.processor;

/**
 * 예보를 낸 차량이 대상 정류장에 실제로 닿았을 때 회수한 결과.
 *
 * <p>못 회수한 이유를 한 값으로 뭉개지 않는다. 뭉개면 모델이 못 맞힌 것과
 * 자료가 없는 것이 채점표에서 같아 보인다.
 */
public sealed interface ArrivalLabel {

    ScoringState scoringState();

    /** 대상 정류장를 지난 관측을 찾았고 잔여석도 안다. 0석이면 만석이 채점의 라벨이다. */
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

    /** 도착 관측은 찾았는데 그 관측이 잔여석을 모른다. */
    record SeatMissing(
        long arrivalObservationId
    ) implements ArrivalLabel {

        @Override
        public ScoringState scoringState() {
            return ScoringState.SEAT_MISSING;
        }
    }

    /** 여정이 대상 순번을 그냥 지나쳤다. 그 자리 관측이 없다. */
    record Skipped() implements ArrivalLabel {

        @Override
        public ScoringState scoringState() {
            return ScoringState.SKIPPED;
        }
    }

    /** 대상 순번에 닿기 전에 여정이 끊겼다. 관측 공백 · 순번 되돌림 · 차량 아이디 없음이 여기로 온다. */
    record Lost() implements ArrivalLabel {

        @Override
        public ScoringState scoringState() {
            return ScoringState.LOST;
        }
    }

    /** 아직 대상 정류장에 안 닿았다. 다음 회차에 다시 본다. */
    record NotArrivedYet() implements ArrivalLabel {

        @Override
        public ScoringState scoringState() {
            return ScoringState.PENDING;
        }
    }
}

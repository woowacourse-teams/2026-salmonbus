package com.gustler.backend.api.boarding.domain;

import java.util.List;

/**
 * 기준 방문에서 같은 노선의 다른 방문으로 이동해 탈 수 있는 후보 목록. 가장 빠른 이동 방법의 시간 오름차순이다.
 */
public record BoardingOptions(String routeId, long referenceVersionId, StopVisit origin, List<Option> options) {
    public BoardingOptions {
        options = List.copyOf(options);
    }

    /**
     * 후보 방문 하나와 그곳까지 가는 방법들. 방법은 이동 시간 오름차순이고 적어도 하나 있다.
     * {@code sequenceOffset}이 음수면 같은 회차에서 버스가 기준보다 먼저 지난다.
     */
    public record Option(StopVisit stop, int sequenceOffset, StopRelation relation, List<Access> accesses) {
        public Option {
            accesses = List.copyOf(accesses);
            if (accesses.isEmpty()) {
                throw new IllegalArgumentException("후보에는 이동 방법이 적어도 하나 있어야 한다");
            }
        }

        public int fastestSeconds() {
            return accesses.getFirst().durationSeconds();
        }
    }

    /**
     * 이동 방법 하나. 구간을 이어 붙인 것이다.
     *
     * @param durationSeconds 출발부터 탑승 정류장 도착까지 전체 시간(초). 기다리는 시간이 있는 방식이면 그 시간도 들어가
     *                        구간 시간의 합보다 클 수 있다
     */
    public record Access(int durationSeconds, List<Leg> legs) {
        public Access {
            legs = List.copyOf(legs);
            if (durationSeconds < 0 || legs.isEmpty()) {
                throw new IllegalArgumentException("이동 방법은 0초 이상이고 구간이 적어도 하나 있어야 한다");
            }
        }

        /**
         * 기다리는 시간 없이 구간만 이어지는 이동 방법. 전체 시간은 구간 시간의 합이다.
         */
        public static Access of(List<Leg> legs) {
            return new Access(legs.stream().mapToInt(Leg::durationSeconds).sum(), legs);
        }
    }

    /**
     * 이동 구간 하나. {@code distanceMeters}는 수단에 따라 모를 수 있어 null을 허용한다. {@code source}는 값을 만든 출처다.
     */
    public record Leg(AccessMode mode, int durationSeconds, Integer distanceMeters, String source) {
    }
}

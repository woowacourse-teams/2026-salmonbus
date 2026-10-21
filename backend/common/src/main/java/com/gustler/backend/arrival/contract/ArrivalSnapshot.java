package com.gustler.backend.arrival.contract;

import com.gustler.backend.arrival.contract.ArrivalSnapshot.RouteArrival;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Worker와 조회 API가 공유하는 불변 저장 형식. 생성 시 형식만 검증한다.
 * 갱신·만료 판단과 ETA 계산은 이 계약을 사용하는 각 업무 계층이 담당한다.
 */
public record ArrivalSnapshot(
    UUID id,
    String stationId,
    Instant receivedAt,
    Instant refreshAfterAt,
    Instant staleAt,
    String sourceQueryTime,
    List<RouteArrival> routes
) {
    public static final String PROVIDER = "GBIS";
    public static final String OPERATION = "ARRIVAL_LIST_V2";
    public static final String NORMALIZATION_VERSION = "gbis-arrival-v1";

    public ArrivalSnapshot {
        Objects.requireNonNull(id);
        requireStation(stationId);
        Objects.requireNonNull(receivedAt);
        if (!hasValidTimeOrder(receivedAt, refreshAfterAt, staleAt)) {
            throw new IllegalArgumentException("수신·갱신·만료시각의 순서가 올바르지 않다");
        }
        routes = List.copyOf(routes);
    }

    private static boolean hasValidTimeOrder(Instant receivedAt, Instant refreshAfterAt, Instant staleAt) {
        if (refreshAfterAt == null || staleAt == null) {
            return false;
        }
        if (refreshAfterAt.isBefore(receivedAt)) {
            return false;
        }
        return staleAt.isAfter(refreshAfterAt);
    }

    private static void requireStation(String value) {
        if (value == null || !value.matches("[0-9]{9}")) {
            throw new IllegalArgumentException("stationId는 9자리 숫자여야 한다");
        }
    }

    public record RouteArrival(String routeId, String name, int sourceStopOrder, Integer routeType,
        String destinationId, String destinationName, String operatingFlag,
        List<Prediction> arrivals) {
        public RouteArrival {
            if (!hasValidVisitIdentifier(routeId, sourceStopOrder)) {
                throw new IllegalArgumentException("노선과 대상 방문의 식별자가 필요하다");
            }
            arrivals = List.copyOf(arrivals);
        }

        private static boolean hasValidVisitIdentifier(String routeId, int sourceStopOrder) {
            if (routeId == null || !routeId.matches("[0-9]{9}")) {
                return false;
            }
            return sourceStopOrder >= 1;
        }
    }

    public record Prediction(String vehicleId, int ordinal, Integer sourceRemainingSeconds,
        Integer sourceRemainingMinutes, Integer remainingStops,
        Integer remainingSeats, String seatUnknownReason, Integer congestion,
        Integer vehicleType, Boolean taglessSupported) {
        public Prediction {
            if (!hasValidOrdinal(ordinal)) {
                throw invalidPrediction();
            }
            if (!hasArrivalTime(sourceRemainingSeconds, sourceRemainingMinutes)) {
                throw invalidPrediction();
            }
            if (hasNegativeValue(sourceRemainingSeconds) || hasNegativeValue(sourceRemainingMinutes)) {
                throw invalidPrediction();
            }
            if (hasNegativeValue(remainingStops) || hasNegativeValue(remainingSeats)) {
                throw invalidPrediction();
            }
            if (!hasConsistentSeatAvailability(remainingSeats, seatUnknownReason)) {
                throw invalidPrediction();
            }
        }

        private static boolean hasValidOrdinal(int ordinal) {
            return ordinal >= 1 && ordinal <= 2;
        }

        private static boolean hasArrivalTime(Integer seconds, Integer minutes) {
            return seconds != null || minutes != null;
        }

        private static boolean hasNegativeValue(Integer value) {
            return value != null && value < 0;
        }

        private static boolean hasConsistentSeatAvailability(Integer remainingSeats, String seatUnknownReason) {
            return (remainingSeats == null) != (seatUnknownReason == null);
        }

        private static IllegalArgumentException invalidPrediction() {
            return new IllegalArgumentException("도착 예측의 값 또는 미제공 사유가 올바르지 않다");
        }
    }
}

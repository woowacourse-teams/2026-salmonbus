package com.gustler.backend.arrival;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 한 정류장에서 정상 수신한 전체 노선 응답. 요청마다 수신시각이나 유효기간을 바꾸지 않는다. */
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
        if (refreshAfterAt == null || staleAt == null || refreshAfterAt.isBefore(receivedAt)
            || !staleAt.isAfter(refreshAfterAt)) {
            throw new IllegalArgumentException("수신·갱신·만료시각의 순서가 올바르지 않다");
        }
        routes = List.copyOf(routes);
    }

    public boolean usableAt(Instant now) { return now.isBefore(staleAt); }
    public boolean refreshDueAt(Instant now) { return !now.isBefore(refreshAfterAt); }

    public static void requireStation(String value) {
        if (value == null || !value.matches("[0-9]{9}")) {
            throw new IllegalArgumentException("stationId는 9자리 숫자여야 한다");
        }
    }

    public record RouteArrival(String routeId, String name, int sourceStopOrder, Integer routeType,
                               String destinationId, String destinationName, String operatingFlag,
                               List<Prediction> arrivals) {
        public RouteArrival {
            if (routeId == null || !routeId.matches("[0-9]{9}") || sourceStopOrder < 1) {
                throw new IllegalArgumentException("노선과 대상 방문의 식별자가 필요하다");
            }
            arrivals = List.copyOf(arrivals);
        }
    }

    public record Prediction(String vehicleId, int ordinal, Integer sourceRemainingSeconds,
                             Integer sourceRemainingMinutes, Integer remainingStops,
                             Integer remainingSeats, String seatUnknownReason, Integer congestion,
                             Integer vehicleType, Boolean taglessSupported) {
        public Prediction {
            if (ordinal < 1 || ordinal > 2 || (sourceRemainingSeconds == null && sourceRemainingMinutes == null)
                || (sourceRemainingSeconds != null && sourceRemainingSeconds < 0)
                || (sourceRemainingMinutes != null && sourceRemainingMinutes < 0)
                || (remainingStops != null && remainingStops < 0)
                || (remainingSeats != null && remainingSeats < 0)
                || ((remainingSeats == null) == (seatUnknownReason == null))) {
                throw new IllegalArgumentException("도착 예측의 값 또는 미제공 사유가 올바르지 않다");
            }
        }

        public Instant expectedAt(Instant base) {
            return base.plusSeconds(sourceRemainingSeconds != null
                ? sourceRemainingSeconds : Math.multiplyExact(sourceRemainingMinutes.longValue(), 60));
        }

        public String resolution() { return sourceRemainingSeconds == null ? "MINUTES" : "SECONDS"; }
    }
}

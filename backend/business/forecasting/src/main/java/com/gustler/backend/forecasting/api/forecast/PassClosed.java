package com.gustler.backend.forecasting.api.forecast;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 버스가 정류장 하나를 지나 통과가 확정됐다는 이벤트다. 열 하나하나가 {@code stop_pass}의 열과 같다.
 *
 * <p>키는 (파티션 기준 날짜, 노선, 운행 시작 관측, 정류장)이고 같은 이벤트를 두 번 받아도 결과가 같다. 정산 단계가 관측 테이블을 다시 읽지
 * 않도록 정답 계산과 짝짓기에 필요한 관측 번호, 좌석, 시각을 모두 싣는다. 관측의 시각은 처리 시작 시각과 수신 시각을 같이 싣는다.
 * {@code sourceDate}는 닫은 배치의 예약 시각으로 {@code SourceDates.of}가 정한 날짜여야 한다.
 *
 * @param sourceDate 닫은 배치 예약 시각의 한국 날짜
 * @param routeId 노선
 * @param tripStartObservationId 운행 시작 관측
 * @param stopOrder 지난 정류장 순번
 * @param routeVersionId 닫은 관측의 노선 버전
 * @param vehicleId 차량
 * @param chainStartObservationId 확정 연결의 시작 관측
 * @param departureDate 운행 출발 날짜
 * @param arrivalObservationId 정류장 도착 전 관측
 * @param arrivalSeats 도착 전 관측의 좌석
 * @param arrivalConfirmedAt 도착 전 관측의 처리 시작 시각
 * @param arrivalReceivedAt 도착 전 관측의 수신 시각
 * @param firstObservationId 정류장 구간의 첫 관측
 * @param firstConfirmedAt 첫 관측의 처리 시작 시각
 * @param lastObservationId 정류장 구간의 마지막 관측
 * @param lastSeats 마지막 관측의 좌석
 * @param lastConfirmedAt 마지막 관측의 처리 시작 시각
 * @param lastReceivedAt 마지막 관측의 수신 시각
 * @param reachedKnownSeatAt 목표에 정확히 도달한 좌석 있는 관측 가운데 가장 늦은 시각. 없으면 비어 있다
 * @param closureObservationId 통과를 확정한 관측
 * @param closureBatchId 통과를 확정한 배치
 * @param closedAt 통과 확정 시각
 */
public record PassClosed(
    LocalDate sourceDate,
    long routeId,
    long tripStartObservationId,
    int stopOrder,
    long routeVersionId,
    String vehicleId,
    long chainStartObservationId,
    LocalDate departureDate,
    long arrivalObservationId,
    int arrivalSeats,
    Instant arrivalConfirmedAt,
    Instant arrivalReceivedAt,
    long firstObservationId,
    Instant firstConfirmedAt,
    long lastObservationId,
    int lastSeats,
    Instant lastConfirmedAt,
    Instant lastReceivedAt,
    Instant reachedKnownSeatAt,
    long closureObservationId,
    long closureBatchId,
    Instant closedAt
) {

    public PassClosed {
        Objects.requireNonNull(sourceDate, "닫은 배치의 날짜가 필요하다");
        Objects.requireNonNull(departureDate, "출발 날짜가 필요하다");
        if (vehicleId == null || vehicleId.isBlank()) {
            throw new IllegalArgumentException("차량 번호가 필요하다");
        }
        if (arrivalSeats < 0 || lastSeats < 0) {
            throw new IllegalArgumentException("좌석은 0 이상이어야 한다: %d, %d".formatted(arrivalSeats, lastSeats));
        }
        Objects.requireNonNull(arrivalConfirmedAt, "도착 전 관측의 처리 시작 시각이 필요하다");
        Objects.requireNonNull(arrivalReceivedAt, "도착 전 관측의 수신 시각이 필요하다");
        Objects.requireNonNull(firstConfirmedAt, "첫 관측의 처리 시작 시각이 필요하다");
        Objects.requireNonNull(lastConfirmedAt, "마지막 관측의 처리 시작 시각이 필요하다");
        Objects.requireNonNull(lastReceivedAt, "마지막 관측의 수신 시각이 필요하다");
        Objects.requireNonNull(closedAt, "닫힌 시각이 필요하다");
    }
}

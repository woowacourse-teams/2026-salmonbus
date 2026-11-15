package com.gustler.backend.forecasting.api.pipeline;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 예보 단계가 배치 하나를 처리했다는 사건이다. {@code forecast_run} 한 줄과 {@code forecast_record} 여러 줄로 저장된다.
 *
 * <p>예보를 만들지 않은 배치(늦음·범위 밖·입력 부족·빈 발행)도 이 사건을 낸다. 버스별 예측은 {@link ForecastOutcome#PUBLISHED}일
 * 때만 싣고, 그때는 한 대 이상이어야 한다. 착수 시각({@code confirmedAt})은 예보 단계가 한 번 찍은 값이며 뒤 단계와 재처리는
 * 이 값을 그대로 쓴다. {@code sourceDate}는 배치 예약 시각으로 {@code SourceDates.of}가 정한 날짜여야 한다.
 *
 * @param sourceDate 배치 예약 시각의 한국 날짜
 * @param observationBatchId 처리한 관측 배치
 * @param routeId 노선
 * @param routeVersionId 배치가 속한 노선 판본
 * @param receivedAt 배치 응답 수신 시각
 * @param confirmedAt 예보 착수 시각
 * @param modelDeploymentId 예측을 낸 모델 배포. 모델을 고르기 전에 끝난 처리면 없다
 * @param outcome 처리 결과
 * @param vehicles 버스별 예측. 배치 안 순서를 유지한다
 */
public record ForecastPublished(
    LocalDate sourceDate,
    long observationBatchId,
    long routeId,
    long routeVersionId,
    Instant receivedAt,
    Instant confirmedAt,
    Long modelDeploymentId,
    ForecastOutcome outcome,
    List<VehicleForecast> vehicles
) {
    public ForecastPublished {
        Objects.requireNonNull(sourceDate, "배치 날짜가 필요하다");
        Objects.requireNonNull(receivedAt, "수신 시각이 필요하다");
        Objects.requireNonNull(confirmedAt, "착수 시각이 필요하다");
        Objects.requireNonNull(outcome, "처리 결과가 필요하다");
        vehicles = List.copyOf(Objects.requireNonNull(vehicles, "버스 목록이 필요하다"));
        if (outcome.carriesForecasts() == vehicles.isEmpty()) {
            throw new IllegalArgumentException("버스별 예측은 발행한 결과에만, 한 대 이상 실어야 한다: " + outcome);
        }
        if (outcome.carriesForecasts() && modelDeploymentId == null) {
            throw new IllegalArgumentException("발행한 예측에는 모델 배포가 필요하다");
        }
        Set<Long> observations = new HashSet<>();
        for (VehicleForecast vehicle : vehicles) {
            if (!observations.add(vehicle.vehicleObservationId())) {
                throw new IllegalArgumentException("한 배치에 같은 관측의 예측이 둘 있다: " + vehicle.vehicleObservationId());
            }
        }
    }

    /** 이 사건에 담긴 (관측, 목표 정류장) 예측의 수. {@code forecast_run.prediction_count}에 저장된다. */
    public int predictionCount() {
        return vehicles.stream()
            .mapToInt(VehicleForecast::targetCount)
            .sum();
    }

    /**
     * 버스 한 대의 예측 묶음이다. 목표 정류장·기대 좌석·만석 확률은 같은 순서·같은 길이의 배열이다.
     *
     * <p>배열은 만들 때와 읽을 때 복사하므로 바깥에서 바꿀 수 없다. 같음 비교는 배열 내용으로 하며 실수는 비트 단위로 비교한다.
     *
     * @param vehicleObservationId 예측의 기준 관측
     * @param sourceRowNumber 배치 응답 안의 줄 번호
     * @param vehicleId 차량
     * @param passedStopOrder 기준 관측에서 지난 정류장 순번
     * @param tripStartObservationId 운행 시작 관측
     * @param chainStartObservationId 확정 연결의 시작 관측
     * @param down 하행이면 참
     * @param capacity 차량 정원
     * @param extended 시간 확장 모델로 낸 예측이면 참
     * @param targetStopOrders 목표 정류장 순번. 겹치지 않는다
     * @param expectedSeats 목표 정류장별 기대 좌석
     * @param fullProbabilities 목표 정류장별 만석 확률
     */
    public record VehicleForecast(
        long vehicleObservationId,
        int sourceRowNumber,
        String vehicleId,
        int passedStopOrder,
        long tripStartObservationId,
        long chainStartObservationId,
        boolean down,
        int capacity,
        boolean extended,
        int[] targetStopOrders,
        double[] expectedSeats,
        double[] fullProbabilities
    ) {
        public VehicleForecast {
            if (vehicleId == null || vehicleId.isBlank()) {
                throw new IllegalArgumentException("차량 번호가 필요하다");
            }
            if (capacity <= 0) {
                throw new IllegalArgumentException("정원은 1 이상이어야 한다: " + capacity);
            }
            targetStopOrders = Objects.requireNonNull(targetStopOrders, "목표 정류장이 필요하다").clone();
            expectedSeats = Objects.requireNonNull(expectedSeats, "기대 좌석이 필요하다").clone();
            fullProbabilities = Objects.requireNonNull(fullProbabilities, "만석 확률이 필요하다").clone();
            if (targetStopOrders.length == 0) {
                throw new IllegalArgumentException("목표 정류장이 하나 이상 필요하다");
            }
            if (expectedSeats.length != targetStopOrders.length
                || fullProbabilities.length != targetStopOrders.length) {
                throw new IllegalArgumentException("목표 정류장·기대 좌석·만석 확률의 길이가 다르다: %d, %d, %d"
                    .formatted(targetStopOrders.length, expectedSeats.length, fullProbabilities.length));
            }
            if (Arrays.stream(targetStopOrders).distinct().count() != targetStopOrders.length) {
                throw new IllegalArgumentException("목표 정류장이 겹친다: " + Arrays.toString(targetStopOrders));
            }
        }

        /** 목표 정류장의 수. */
        public int targetCount() {
            return targetStopOrders.length;
        }

        @Override
        public int[] targetStopOrders() {
            return targetStopOrders.clone();
        }

        @Override
        public double[] expectedSeats() {
            return expectedSeats.clone();
        }

        @Override
        public double[] fullProbabilities() {
            return fullProbabilities.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof VehicleForecast that)) {
                return false;
            }
            return vehicleObservationId == that.vehicleObservationId && sourceRowNumber == that.sourceRowNumber
                && passedStopOrder == that.passedStopOrder && tripStartObservationId == that.tripStartObservationId
                && chainStartObservationId == that.chainStartObservationId && down == that.down
                && capacity == that.capacity && extended == that.extended && vehicleId.equals(that.vehicleId)
                && Arrays.equals(targetStopOrders, that.targetStopOrders)
                && Arrays.equals(expectedSeats, that.expectedSeats)
                && Arrays.equals(fullProbabilities, that.fullProbabilities);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(vehicleObservationId, sourceRowNumber, vehicleId, passedStopOrder,
                tripStartObservationId, chainStartObservationId, down, capacity, extended);
            result = 31 * result + Arrays.hashCode(targetStopOrders);
            result = 31 * result + Arrays.hashCode(expectedSeats);
            return 31 * result + Arrays.hashCode(fullProbabilities);
        }

        @Override
        public String toString() {
            return "VehicleForecast[vehicleObservationId=%d, sourceRowNumber=%d, vehicleId=%s, passedStopOrder=%d, "
                .formatted(vehicleObservationId, sourceRowNumber, vehicleId, passedStopOrder)
                + "tripStartObservationId=%d, chainStartObservationId=%d, down=%s, capacity=%d, extended=%s, "
                    .formatted(tripStartObservationId, chainStartObservationId, down, capacity, extended)
                + "targetStopOrders=%s, expectedSeats=%s, fullProbabilities=%s]".formatted(
                    Arrays.toString(targetStopOrders), Arrays.toString(expectedSeats),
                    Arrays.toString(fullProbabilities));
        }
    }
}

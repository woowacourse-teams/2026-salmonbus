package com.gustler.backend.forecasting.domain.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * 예보 한 건의 입력.
 *
 * <p>특징 벡터의 생성 방식은 검사하지 않는다. 생성된 특징값과 계수 선택에 쓰는 노선·거리,
 * 중심 좌석 계산에 쓰는 잔여석·정원을 받는다.
 *
 * <p>DB 를 읽지 않고 당일 성적도 이미 범위를 제한한 값으로 받는다. 예보 시각보다 뒤에 도착한 예보를
 * 제외하는 일은 이 값을 생성하는 쪽이 맡는다.
 *
 * <p>특징 벡터는 입력·반환 시 복사한다. 동등성·해시·문자열은 배열 내용과 나머지 구성요소를 기준으로 한다.
 *
 * @param featureVector 특징 벡터. 길이는 번들이 선언한 특징 개수와 같아야 한다
 * @param modelRoute 계수 묶음이 쓰는 노선 이름
 * @param stopsAhead 예보 대상까지의 정류장 수
 * @param currentSeats 현재 관측한 잔여석
 * @param capacity 해당 차량에서 관측한 최대 잔여석
 * @param sameDayOutcomes 당일 성적. 없으면 null 이고 그때는 보정하지 않는다
 */
public record SeatDistributionInput(
    double[] featureVector,
    String modelRoute,
    int stopsAhead,
    int currentSeats,
    int capacity,
    SameDayFullOutcomes sameDayOutcomes
) {
    public SeatDistributionInput {
        if (featureVector == null || featureVector.length == 0) {
            throw new IllegalArgumentException("특징 벡터가 있어야 한다");
        }
        for (final double feature : featureVector) {
            if (!Double.isFinite(feature)) {
                throw new IllegalArgumentException("특징 값은 유한해야 한다: " + feature);
            }
        }
        if (modelRoute == null || modelRoute.isBlank()) {
            throw new IllegalArgumentException("계수를 고르려면 노선 이름이 있어야 한다");
        }
        if (currentSeats < 0 || currentSeats > SeatGrid.LARGEST_SEATS) {
            throw new SeatRangeException("currentSeats", currentSeats, 0, SeatGrid.LARGEST_SEATS);
        }
        if (capacity < 1 || capacity > SeatGrid.LARGEST_SEATS) {
            throw new SeatRangeException("capacity", capacity, 1, SeatGrid.LARGEST_SEATS);
        }
        featureVector = featureVector.clone();
    }

    /** 검증한 특징 벡터를 외부에서 변경할 수 없도록 복사해 반환한다. */
    @Override
    public double[] featureVector() {
        return featureVector.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SeatDistributionInput input)) {
            return false;
        }
        return Arrays.equals(featureVector, input.featureVector)
            && Objects.equals(modelRoute, input.modelRoute)
            && stopsAhead == input.stopsAhead
            && currentSeats == input.currentSeats
            && capacity == input.capacity
            && Objects.equals(sameDayOutcomes, input.sameDayOutcomes);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(featureVector);
        result = 31 * result + Objects.hashCode(modelRoute);
        result = 31 * result + Integer.hashCode(stopsAhead);
        result = 31 * result + Integer.hashCode(currentSeats);
        result = 31 * result + Integer.hashCode(capacity);
        return 31 * result + Objects.hashCode(sameDayOutcomes);
    }

    @Override
    public String toString() {
        return ("SeatDistributionInput[featureVector=%s, modelRoute=%s, stopsAhead=%d, "
            + "currentSeats=%d, capacity=%d, sameDayOutcomes=%s]")
            .formatted(Arrays.toString(featureVector), modelRoute, stopsAhead, currentSeats, capacity, sameDayOutcomes);
    }
}

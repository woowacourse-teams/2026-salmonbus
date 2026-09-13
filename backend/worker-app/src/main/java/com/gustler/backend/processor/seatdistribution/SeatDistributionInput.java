package com.gustler.backend.processor.seatdistribution;

/**
 * 예보 한 건의 입력.
 *
 * <p>특징 벡터가 어떻게 만들어지는지는 여기서 안 본다. 만들어진 값과, 계수를 고르는 데 쓰는
 * 노선·거리와, 중심 좌석을 잡는 데 쓰는 잔여석·정원만 받는다.
 *
 * <p>DB 를 안 읽는다. 당일 성적도 이미 잘린 값으로 받는다. 예보 시각보다 뒤에 도착한 것을
 * 걸러 내는 것은 이 값을 만드는 쪽의 몫이다.
 *
 * @param featureVector 특징 벡터. 길이는 번들이 선언한 특징 개수와 같아야 한다
 * @param modelRoute 계수 묶음이 쓰는 노선 이름
 * @param stopsAhead 몇 정류장 앞을 예보하는가
 * @param currentSeats 지금 보이는 잔여석
 * @param capacity 그 차량이 보여 준 최대 잔여석
 * @param sameDayOutcomes 당일 성적. 없으면 null 이고 그때는 보정을 안 한다
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
            throw new IllegalArgumentException(
                "잔여석은 0석부터 %d석까지다: %d".formatted(SeatGrid.LARGEST_SEATS, currentSeats));
        }
        if (capacity < 1 || capacity > SeatGrid.LARGEST_SEATS) {
            throw new IllegalArgumentException(
                "정원은 1석부터 %d석까지다: %d".formatted(SeatGrid.LARGEST_SEATS, capacity));
        }
        featureVector = featureVector.clone();
    }
}

package com.gustler.backend.processor;

/**
 * 정류장이 노선의 어디쯤인가. 기점에 가까울수록 0에 가깝고 마지막 정류장이 1이다.
 *
 * <p>순번을 그대로 쓰지 않는 이유는 판본마다 정류장 수가 달라서다. 60개 판본의 30번 정류장과
 * 40개 판본의 30번 정류장은 순번이 같아도 노선에서 서 있는 자리가 다르다.
 *
 * <p>설계행렬은 이 값을 여덟 열로 펴서 쓰는데 펴는 규칙이 공개 문서에 없다. 그래서 여기서는
 * 나누는 데까지만 한다.
 *
 * <p>A18 문서는 이 층을 {@code L_spline} 이라고 적었다. 순번을 0에서 1 사이로 정규화하는
 * 층이라는 뜻이다.
 */
public final class StopPositionOnRoute {

    private StopPositionOnRoute() {
    }

    public static double of(
        final int stopOrder,
        RouteStops stops
    ) {
        return (double) stopOrder / stops.largestStopOrder();
    }
}

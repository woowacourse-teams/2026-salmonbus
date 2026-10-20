package com.gustler.backend.forecasting.domain.model;


/**
 * 예보 재료를 번들의 입력 규칙으로 펴서 좌석 분포 계산에 넘긴다.
 *
 * <p>예보 경로와 계수 계산 사이를 잇는 자리다. 재료에서 열을 만드는 것은 설계행렬이 하고,
 * 열에서 확률을 내는 것은 예측기가 한다. 여기는 그 둘을 붙이고 노선 이름을 옮기기만 한다.
 *
 * <p><b>DB 를 안 읽는다.</b> 어느 노선인지도 재료 안의 정류장 목록에서 꺼낸다. 그래서 과거 시점
 * 재료를 손으로 만들어 넣으면 그때의 예보가 그대로 다시 나온다.
 *
 * <p>오늘 이미 도착이 확인된 예보들의 성적도 그대로 넘긴다. 없으면 {@code null} 이고 그때는
 * 만석 확률을 안 옮긴다. 자르는 것은 그 값을 읽는 쪽이 하고 여기서는 옮기기만 한다.
 */
public final class SeatDistributionForecastModel implements SeatForecastModel {
    private final SeatDistributionPredictor predictor;
    private final ForecastFeatureContract features;
    private final ForecastRouteReference reference;

    public SeatDistributionForecastModel(
        SeatDistributionPredictor predictor
    ) {
        this(predictor, ForecastFeatureContract.LEGACY, null);
    }

    public SeatDistributionForecastModel(SeatDistributionPredictor predictor,
        ForecastFeatureContract features, ForecastRouteReference reference) {
        this.predictor = java.util.Objects.requireNonNull(predictor);
        this.features = java.util.Objects.requireNonNull(features);
        this.reference = features != ForecastFeatureContract.LEGACY
            ? java.util.Objects.requireNonNull(reference, "조건 모델에는 학습 정류장 기준이 필요하다") : reference;
    }

    @Override
    public boolean supportsRoute(RouteStops stops) {
        return features == ForecastFeatureContract.LEGACY || reference.matches(stops);
    }

    @Override
    public SeatForecastResult predict(
        SeatForecastInput input
    ) {
        if (features != ForecastFeatureContract.LEGACY) {
            reference.requireMatches(input.stops());
        }
        return predictor.predict(new SeatDistributionInput(
            features.vectorOf(input),
            ModelRoute.of(input.stops().sourceRouteId()),
            input.target().distance().stopCount(),
            input.target().remainingSeats(),
            input.maximumSeatsEverObserved(),
            input.sameDayFullOutcomes()));
    }
}

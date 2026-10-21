package com.gustler.backend.forecasting.domain.model;

import java.util.Objects;

/**
 * 예보 입력을 번들의 입력 규칙에 맞게 변환해 좌석 분포 계산에 전달한다.
 *
 * <p>예보 경로와 계수 계산을 연결한다. 설계행렬은 입력에서 열을 만들고 예측기는 열에서 확률을 계산한다.
 * 이 객체는 두 단계를 연결하고 노선 이름을 변환한다.
 *
 * <p>DB 를 읽지 않고 입력의 정류장 목록에서 노선을 확인한다. 과거 시점의 입력을 구성해 전달하면
 * 그때의 예보를 같은 경로로 재현한다.
 *
 * <p>오늘 이미 도착이 확인된 예보들의 성적도 그대로 전달한다. 없으면 {@code null} 이고 그때는
 * 만석 확률을 보정하지 않는다. 집계 범위는 그 값을 읽는 쪽이 제한하며 여기서는 그대로 전달한다.
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
        this.predictor = Objects.requireNonNull(predictor);
        this.features = Objects.requireNonNull(features);
        this.reference = referenceOf(features, reference);
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
            ModelRoute.of(input.sourceRouteId()),
            input.stopsToTarget(),
            input.targetRemainingSeats(),
            input.maximumSeatsEverObserved(),
            input.sameDayFullOutcomes()));
    }

    private static ForecastRouteReference referenceOf(
        ForecastFeatureContract features,
        ForecastRouteReference reference
    ) {
        if (features != ForecastFeatureContract.LEGACY) {
            return Objects.requireNonNull(reference, "조건 모델에는 학습 정류장 기준이 필요하다");
        }
        return reference;
    }
}

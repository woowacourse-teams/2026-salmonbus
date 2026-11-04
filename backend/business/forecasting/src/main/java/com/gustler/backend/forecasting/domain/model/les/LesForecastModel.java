package com.gustler.backend.forecasting.domain.model.les;

import com.gustler.backend.forecasting.domain.model.ModelRoute;
import com.gustler.backend.forecasting.domain.model.RouteDirection;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.SeatForecastInput;
import com.gustler.backend.forecasting.domain.model.SeatForecastModel;
import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 레스의 고정 통계, 트리, 정산 파라미터를 하나의 불변 배포로 선택한다. */
public final class LesForecastModel implements SeatForecastModel {
    private final LesSeatPredictor predictor;
    private final Map<String, LesRouteStatistics> statistics;
    private final Map<String, Map<Integer, String>> evidence;
    private final String bundleDigest;

    public LesForecastModel(
        LesSeatPredictor predictor,
        Map<String, LesRouteStatistics> statistics,
        Map<String, Map<Integer, String>> evidence,
        String bundleDigest) {
        this.predictor = Objects.requireNonNull(predictor);
        this.statistics = Map.copyOf(statistics);
        this.evidence = evidence.entrySet().stream().collect(Collectors.toUnmodifiableMap(
            Map.Entry::getKey, entry -> Map.copyOf(entry.getValue())));
        this.bundleDigest = bundleDigest;
    }

    @Override
    public boolean supportsRoute(RouteStops stops) {
        if (!ModelRoute.covers(stops.sourceRouteId())) {
            return false;
        }
        LesRouteStatistics stats = statistics.get(ModelRoute.of(stops.sourceRouteId()));
        if (stats == null || stops.stops().size() != stats.stops().size()) {
            return false;
        }
        return stops.stops().stream()
            .allMatch(stop -> stats.stops().stream().anyMatch(reference -> matchesStop(reference, stop)));
    }

    private boolean matchesStop(LesRouteStatistics.Stop reference, RouteStop stop) {
        return reference.order() == stop.stopOrder()
            && reference.id().equals(stop.stopId())
            && reference.boardingAllowed() == stop.boardingAllowed()
            && matchesDirection(reference, stop);
    }

    private boolean matchesDirection(LesRouteStatistics.Stop reference, RouteStop stop) {
        if (reference.down()) {
            return stop.direction() == RouteDirection.DOWN;
        }
        return stop.direction() == RouteDirection.UP;
    }

    public LesRouteStatistics statistics(RouteStops stops) {
        if (!supportsRoute(stops)) {
            throw new IllegalArgumentException("레스 번들과 정류장 판본이 다르다");
        }
        return statistics.get(ModelRoute.of(stops.sourceRouteId()));
    }

    public String evidence(String route, final int targetStopOrder) {
        return evidence.get(route).get(targetStopOrder);
    }

    public String bundleDigest() {
        return bundleDigest;
    }

    public SeatForecastResult predict(LesFeatureVector vector) {
        LesSeatPredictor.Prediction prediction = predictor.predict(vector.seat88(), vector.probability80());
        return SeatForecastResult.les(prediction.afterAlightingSeats(), prediction.departureFullProbability());
    }

    @Override
    public SeatForecastResult predict(SeatForecastInput input) {
        throw new IllegalArgumentException("레스 모델에는 88/80열 시점별 입력이 필요하다");
    }
}

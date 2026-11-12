package com.gustler.backend.forecasting.domain.model.alighting;

import com.gustler.backend.forecasting.domain.model.ModelRoute;
import com.gustler.backend.forecasting.domain.model.RouteDirection;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 하차 반영의 고정 통계, 트리, 정산 파라미터를 하나의 불변 배포로 선택한다. */
public final class AlightingAwareSeatForecastModel {
    private final AlightingAwareSeatPredictor predictor;
    private final Map<String, AlightingAwareRouteStatistics> statistics;
    private final Map<String, Map<Integer, String>> evidence;
    private final String bundleDigest;
    private final Map<String, ExtendedAlightingPredictor> extended;

    public AlightingAwareSeatForecastModel(
        AlightingAwareSeatPredictor predictor,
        Map<String, AlightingAwareRouteStatistics> statistics,
        Map<String, Map<Integer, String>> evidence,
        String bundleDigest
    ) {
        this(predictor, statistics, evidence, bundleDigest, Map.of());
    }

    public AlightingAwareSeatForecastModel(
        AlightingAwareSeatPredictor predictor,
        Map<String, AlightingAwareRouteStatistics> statistics,
        Map<String, Map<Integer, String>> evidence,
        String bundleDigest,
        Map<String, ExtendedAlightingPredictor> extended
    ) {
        this.predictor = Objects.requireNonNull(predictor);
        this.statistics = Map.copyOf(statistics);
        this.evidence = evidence.entrySet().stream().collect(Collectors.toUnmodifiableMap(
            Map.Entry::getKey, entry -> Map.copyOf(entry.getValue())));
        this.bundleDigest = bundleDigest;
        this.extended = Map.copyOf(extended);
        if (!this.extended.isEmpty() && !this.extended.keySet().equals(this.statistics.keySet())) {
            throw new IllegalArgumentException("시간 확장 모델과 통계의 노선 목록이 다르다");
        }
    }

    public boolean supportsRoute(RouteStops stops) {
        if (!ModelRoute.covers(stops.sourceRouteId())) {
            return false;
        }
        AlightingAwareRouteStatistics stats = statistics.get(ModelRoute.of(stops.sourceRouteId()));
        if (stats == null || stops.stops().size() != stats.stops().size()) {
            return false;
        }
        return stops.stops().stream()
            .allMatch(stop -> stats.stops().stream().anyMatch(reference -> matchesStop(reference, stop)));
    }

    private boolean matchesStop(AlightingAwareRouteStatistics.Stop reference, RouteStop stop) {
        return reference.order() == stop.stopOrder()
            && reference.id().equals(stop.stopId())
            && reference.boardingAllowed() == stop.boardingAllowed()
            && matchesDirection(reference, stop);
    }

    private boolean matchesDirection(AlightingAwareRouteStatistics.Stop reference, RouteStop stop) {
        if (reference.down()) {
            return stop.direction() == RouteDirection.DOWN;
        }
        return stop.direction() == RouteDirection.UP;
    }

    public AlightingAwareRouteStatistics statistics(RouteStops stops) {
        if (!supportsRoute(stops)) {
            throw new IllegalArgumentException("하차 반영 좌석 번들과 정류장 판본이 다르다");
        }
        return statistics.get(ModelRoute.of(stops.sourceRouteId()));
    }

    public String evidence(String route, final int targetStopOrder) {
        return evidence.get(route).get(targetStopOrder);
    }

    public String bundleDigest() {
        return bundleDigest;
    }

    public boolean supportsExtendedHours() {
        return !extended.isEmpty();
    }

    public SeatForecastResult predictExtended(String route, double[] inputs) {
        return extended.get(route).predict(inputs);
    }

    public SeatForecastResult predict(AlightingAwareFeatureVector vector) {
        AlightingAwareSeatPredictor.Prediction prediction = predictor.predict(vector.seat88(), vector.probability80());
        return SeatForecastResult.alighting(prediction.afterAlightingSeats(), prediction.departureFullProbability());
    }

}

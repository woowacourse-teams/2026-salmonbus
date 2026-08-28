package com.gustler.backend.processor;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 아직 라벨을 못 채운 예보를 집어 그 차량의 뒤 관측으로 닫는다.
 *
 * <p>예보 배치와 다른 시계에서 돈다. 한 배치에 묶으면 회수 한 번 돌 때마다 예보가 멈춘다.
 *
 * <p>한 차량의 뒤 관측을 예보마다 다시 읽지 않는다. 같은 판에서 낸 예보 열두 줄이 같은 차량을
 * 가리키므로 차량으로 묶어 한 번만 읽고 예보마다 자기 시각 뒤만 잘라 쓴다.
 */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ArrivalLabelJob {

    private final RouteVersionRepository routeVersionRepository;
    private final SeatForecastRepository seatForecastRepository;
    private final ArrivalObservationRepository arrivalObservationRepository;
    private final ForecastProperties properties;
    private final Clock clock;

    public ArrivalLabelJob(
        RouteVersionRepository routeVersionRepository,
        SeatForecastRepository seatForecastRepository,
        ArrivalObservationRepository arrivalObservationRepository,
        ForecastProperties properties,
        Clock clock
    ) {
        this.routeVersionRepository = routeVersionRepository;
        this.seatForecastRepository = seatForecastRepository;
        this.arrivalObservationRepository = arrivalObservationRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 한 회차를 한 transaction 으로 닫는다.
     *
     * <p>노선이 둘이고 한 회차에 보는 예보가 수백 줄이라 오래 잡고 있지 않는다.
     */
    @Scheduled(fixedDelayString = "${forecast.settlement-interval}")
    @Transactional
    public void settleArrivalLabels() {
        List<ForecastSettlement> settlements = new ArrayList<>();
        for (Long routeVersionId : routeVersionRepository.findActiveVersionIds()) {
            settlements.addAll(settlementsOf(routeVersionId));
        }
        seatForecastRepository.settle(settlements);
    }

    private List<ForecastSettlement> settlementsOf(
        final long routeVersionId
    ) {
        List<PendingForecast> pending =
            seatForecastRepository.findPending(routeVersionId, properties.pendingLimit());
        List<ForecastSettlement> settlements = new ArrayList<>();
        for (Map.Entry<String, List<PendingForecast>> byVehicle : groupByVehicle(pending).entrySet()) {
            settlements.addAll(settlementsOf(routeVersionId, byVehicle.getKey(), byVehicle.getValue()));
        }
        return settlements;
    }

    /**
     * 차량 아이디가 없는 예보는 도착을 찾을 길이 없다. 그래도 판정은 도메인에 맡긴다.
     * 여기서 미리 걸러 내면 같은 규칙이 두 곳에 생긴다.
     */
    private Map<String, List<PendingForecast>> groupByVehicle(
        List<PendingForecast> pending
    ) {
        Map<String, List<PendingForecast>> byVehicle = new LinkedHashMap<>();
        for (PendingForecast forecast : pending) {
            byVehicle.computeIfAbsent(forecast.vehicleId(), vehicleId -> new ArrayList<>()).add(forecast);
        }
        return byVehicle;
    }

    private List<ForecastSettlement> settlementsOf(
        final long routeVersionId,
        String vehicleId,
        List<PendingForecast> forecasts
    ) {
        List<ArrivalCandidate> candidates = readCandidates(routeVersionId, vehicleId, forecasts);
        List<ForecastSettlement> settlements = new ArrayList<>();
        for (PendingForecast forecast : forecasts) {
            ArrivalLabel label = ArrivalLabelResolver.resolve(forecast, observedAfter(candidates, forecast));
            if (!(label instanceof ArrivalLabel.NotArrivedYet)) {
                settlements.add(new ForecastSettlement(
                    forecast.vehicleObservationId(), forecast.targetStopOrder(), label, clock.instant()));
            }
        }
        return settlements;
    }

    private List<ArrivalCandidate> readCandidates(
        final long routeVersionId,
        String vehicleId,
        List<PendingForecast> forecasts
    ) {
        if (vehicleId == null) {
            return List.of();
        }
        return arrivalObservationRepository.findAfter(
            routeVersionId, vehicleId, earliestObservedAt(forecasts), properties.arrivalLimit());
    }

    private static Instant earliestObservedAt(
        List<PendingForecast> forecasts
    ) {
        Instant earliest = forecasts.getFirst().observedAt();
        for (PendingForecast forecast : forecasts) {
            if (forecast.observedAt().isBefore(earliest)) {
                earliest = forecast.observedAt();
            }
        }
        return earliest;
    }

    private static List<ArrivalCandidate> observedAfter(
        List<ArrivalCandidate> candidates,
        PendingForecast forecast
    ) {
        List<ArrivalCandidate> later = new ArrayList<>();
        for (ArrivalCandidate candidate : candidates) {
            if (candidate.observedAt().isAfter(forecast.observedAt())) {
                later.add(candidate);
            }
        }
        return later;
    }
}

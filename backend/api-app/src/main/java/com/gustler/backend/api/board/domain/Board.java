package com.gustler.backend.api.board.domain;

import com.gustler.backend.api.route.domain.RouteStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

public record Board(
    BoardRoute route,
    OffsetDateTime observedAt,
    OffsetDateTime staleAt,
    ForecastModel model,
    int vehiclesInService,
    List<StopState> stops
) {

    public Board {
        Objects.requireNonNull(route, "route는 null일 수 없습니다.");
        Objects.requireNonNull(observedAt, "observedAt은 null일 수 없습니다.");
        Objects.requireNonNull(staleAt, "staleAt은 null일 수 없습니다.");
        Objects.requireNonNull(model, "model은 null일 수 없습니다.");
        Objects.requireNonNull(stops, "stops는 null일 수 없습니다.");
        stops = List.copyOf(stops);
        if (route.status() == RouteStatus.PREPARING && hasAvailableForecast(stops)) {
            throw new IllegalArgumentException("예보를 준비 중인 노선의 보드에는 좌석 예보가 있을 수 없습니다.");
        }
    }

    private static boolean hasAvailableForecast(
        List<StopState> stops
    ) {
        return stops.stream()
            .flatMap(stop -> stop.approachingVehicles().stream())
            .anyMatch(vehicle -> vehicle.forecast() instanceof VehicleForecast.Available);
    }
}

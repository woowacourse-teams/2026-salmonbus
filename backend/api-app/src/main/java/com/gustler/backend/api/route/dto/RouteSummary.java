package com.gustler.backend.api.route.dto;

import com.gustler.backend.api.route.domain.Route;
import com.gustler.backend.api.route.domain.RouteStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Objects;

@Schema(requiredProperties = {"id", "displayName", "startStopName", "endStopName", "status"})
public record RouteSummary(
    @Schema(description = "GBIS 노선 ID. 9자리 숫자 문자열", example = "123456789")
    String id,
    @Schema(description = "화면에 표시할 노선 이름")
    String displayName,
    @Schema(description = "기점 이름")
    String startStopName,
    @Schema(description = "종점 이름")
    String endStopName,
    @Schema(description = "FORECAST_READY는 현재 노선 판본의 예보가 준비됨, PREPARING은 아직 준비되지 않음")
    RouteStatus status
) {

    public RouteSummary {
        Objects.requireNonNull(id, "id는 null일 수 없습니다.");
        Objects.requireNonNull(displayName, "displayName은 null일 수 없습니다.");
        Objects.requireNonNull(startStopName, "startStopName은 null일 수 없습니다.");
        Objects.requireNonNull(endStopName, "endStopName은 null일 수 없습니다.");
        Objects.requireNonNull(status, "status는 null일 수 없습니다.");
    }

    public static RouteSummary from(
        Route route,
        RouteStatus status
    ) {
        return new RouteSummary(
            route.id(),
            route.displayName(),
            route.startStopName(),
            route.endStopName(),
            status
        );
    }
}

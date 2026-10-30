package com.gustler.backend.api.route.dto;

import com.gustler.backend.api.route.application.RouteOverview;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;

/**
 * 현재 공개 노선 목록을 응답 형식으로 변환한 값이다.
 */
@Schema(requiredProperties = {"routes"})
public record RouteListResponse(
    @Schema(description = "현재 판본이 있는 노선 목록. 빈 배열도 정상 응답")
    List<RouteSummary> routes
) {
    public RouteListResponse {
        Objects.requireNonNull(routes, "routes는 null일 수 없습니다.");
        routes = List.copyOf(routes);
    }

    public static RouteListResponse from(RouteOverview overview) {
        List<RouteSummary> routes =
            overview.routes().stream()
                .map(
                    currentRoute ->
                        new RouteSummary(
                            currentRoute.route().id(),
                            currentRoute.route().displayName(),
                            currentRoute.route().startStopName(),
                            currentRoute.route().endStopName(),
                            currentRoute.status(),
                            currentRoute.serviceNotice()))
                .toList();

        return new RouteListResponse(routes);
    }
}

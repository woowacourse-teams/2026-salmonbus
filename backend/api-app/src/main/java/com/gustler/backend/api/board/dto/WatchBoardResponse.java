package com.gustler.backend.api.board.dto;

import com.gustler.backend.api.board.application.WatchBoardOverview;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.vehicle.dto.VehicleResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(requiredProperties = {"route", "observedAt", "staleAt", "model", "vehiclesInService", "stops", "vehicles"})
public record WatchBoardResponse(
    @Schema(description = "노선과 현재 판본의 방향 정보")
    BoardRouteResponse route,
    @Schema(description = "응답 전체가 기준으로 사용한 관측 시각. 정류장 예보와 차량 위치가 모두 이 관측에서 나온다")
    OffsetDateTime observedAt,
    @Schema(description = "응답 전체의 만료 시각. 관측 후 5분, 관측 시각이 01~03시대면 10분")
    OffsetDateTime staleAt,
    @Schema(description = "정류장 예보에 연결된 모델 정보")
    ModelInfoResponse model,
    @Schema(description = "기준 관측에 기록된 운행 차량 수. 위치를 읽지 못한 차량이 있으면 vehicles의 길이보다 클 수 있다", minimum = "0")
    int vehiclesInService,
    @Schema(description = "노선의 정류장 목록과 정류장별 도착 예보. 접근 차량이 없는 정류장도 포함")
    List<StopStateResponse> stops,
    @Schema(description = "기준 관측과 같은 관측의 차량 위치. seat는 관측 시점의 실측 잔여석이며 정류장 도착 시점 예보는 "
        + "stops[].approachingVehicles[].forecast에 있다. direction은 현재 정류장이 속한 방향이라 회차점을 출발한 "
        + "차량의 예보는 DOWN 정류장에 붙는다")
    List<VehicleResponse> vehicles
) {

    public static WatchBoardResponse from(
        WatchBoardOverview overview
    ) {
        Board board = overview.board();
        return new WatchBoardResponse(
            BoardRouteResponse.from(board.route()),
            board.observedAt(),
            board.staleAt(),
            ModelInfoResponse.from(board.model()),
            board.vehiclesInService(),
            board.stops().stream()
                .map(StopStateResponse::from)
                .toList(),
            overview.vehicles().stream()
                .map(VehicleResponse::from)
                .toList()
        );
    }
}

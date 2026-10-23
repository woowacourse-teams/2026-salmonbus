package com.gustler.backend.api.board.dto;

import com.gustler.backend.api.board.domain.Board;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 같은 관측과 모델을 기준으로 만든 탑승판의 공개 응답이다.
 * 정류장과 접근 차량 목록을 응답 이후 바꿀 수 없게 보관한다.
 */
@Schema(requiredProperties = {"route", "observedAt", "staleAt", "model", "vehiclesInService", "stops"})
public record BoardResponse(
    @Schema(description = "노선과 현재 판본의 방향 정보")
    BoardRouteResponse route,
    @Schema(description = "보드가 기준으로 사용한 관측 시각. ISO-8601 오프셋 포함")
    OffsetDateTime observedAt,
    @Schema(description = "보드 기준 관측의 만료 시각. 관측 후 5분, 관측 시각이 01~03시대면 10분")
    OffsetDateTime staleAt,
    @Schema(description = "보드 예보에 연결된 모델 정보")
    ModelInfoResponse model,
    @Schema(description = "현재 기준 관측에 포함된 운행 차량 수", minimum = "0")
    int vehiclesInService,
    @Schema(description = "노선의 정류장 목록. 접근 차량이 없는 정류장도 포함")
    List<StopStateResponse> stops
) {
    /** 원본 참조와 분리하고 기존 null·원소·순서를 보존한다. */
    public BoardResponse {
        if (stops != null) {
            stops = Collections.unmodifiableList(new ArrayList<>(stops));
        }
    }

    public static BoardResponse from(
        Board board
    ) {
        return new BoardResponse(
            BoardRouteResponse.from(board.route()),
            board.observedAt(),
            board.staleAt(),
            ModelInfoResponse.from(board.model()),
            board.vehiclesInService(),
            board.stops()
                .stream()
                .map(StopStateResponse::from)
                .toList()
        );
    }
}

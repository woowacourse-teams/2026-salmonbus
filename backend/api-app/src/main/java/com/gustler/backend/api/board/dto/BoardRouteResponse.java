package com.gustler.backend.api.board.dto;

import com.gustler.backend.api.board.domain.BoardRoute;
import com.gustler.backend.api.route.domain.RouteStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 현재 노선 판본과 방향·시간표를 공개 탑승판 형식으로 전달한다.
 * 참조 판본은 문자열 계약을 유지한다.
 */
@Schema(requiredProperties = {"id", "displayName", "startStopName", "endStopName", "status",
    "turnSequence", "directions", "referenceVersionId"})
public record BoardRouteResponse(
    @Schema(description = "GBIS 노선 ID. 9자리 숫자 문자열")
    String id,
    @Schema(description = "화면에 표시할 노선 이름")
    String displayName,
    @Schema(description = "기점 이름")
    String startStopName,
    @Schema(description = "종점 이름")
    String endStopName,
    @Schema(description = "현재 판본의 예보 준비 상태")
    RouteStatus status,
    @Schema(description = "회차 정류장 순번. 회차점이 없으면 null", nullable = true)
    Integer turnSequence,
    @Schema(description = "상행/하행 방향 이름과 운행 시간")
    List<DirectionInfoResponse> directions,
    @Schema(description = "이 응답이 기준으로 삼은 노선 판본 ID")
    String referenceVersionId,
    @Schema(description = "GBIS 노선 기본정보에 등록된 요일별 최소·최대 배차간격(분). 방향 구분이 없고 실제 도착 간격이나 "
        + "평균이 아니다. GBIS가 맞지 않는 값을 보내면 그 요일은 마지막으로 받은 정상 값을 유지한다. "
        + "오늘이 공휴일인지는 판정하지 않는다. 이 필드가 없는 응답은 정보 없음으로 본다")
    RouteHeadwaysResponse headways
) {
    /** 원본 참조와 분리하고 기존 null·원소·순서를 보존한다. */
    public BoardRouteResponse {
        if (directions != null) {
            directions = Collections.unmodifiableList(new ArrayList<>(directions));
        }
    }

    static BoardRouteResponse from(
        BoardRoute route
    ) {
        return new BoardRouteResponse(
            route.id(),
            route.displayName(),
            route.startStopName(),
            route.endStopName(),
            route.status(),
            route.turnSequence(),
            route.directions()
                .stream()
                .map(DirectionInfoResponse::from)
                .toList(),
            route.referenceVersionId(),
            RouteHeadwaysResponse.from(route.headways())
        );
    }
}

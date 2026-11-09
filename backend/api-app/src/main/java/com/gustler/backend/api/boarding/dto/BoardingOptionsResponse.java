package com.gustler.backend.api.boarding.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.gustler.backend.api.boarding.domain.BoardingOptions;
import com.gustler.backend.api.boarding.domain.StopVisit;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 탑승 후보 응답. 후보는 정류장 방문 하나이고 이동 방법은 배열이다.
 * 이동 수단·출처·관계 값은 앞으로 늘어나므로 문자열로 내보내고, 모르는 값은 건너뛰도록 안내한다.
 */
@Schema(name = "BoardingOptionsResponse",
    requiredProperties = {"routeId", "referenceVersionId", "origin", "options"})
public record BoardingOptionsResponse(
    @Schema(description = "9자리 GBIS 노선 ID", example = "204000057")
    String routeId,
    @Schema(description = "현재 노선 판본. 양의 64비트 정수의 문자열 표현", example = "7")
    String referenceVersionId,
    @Schema(description = "기준 방문")
    BoardingStop origin,
    @Schema(description = "후보 정류장 방문. accesses[0].durationSeconds 오름차순, 같으면 stopSequence 오름차순. 없으면 빈 배열")
    List<BoardingOption> options
) {
    public static BoardingOptionsResponse from(BoardingOptions result) {
        return new BoardingOptionsResponse(result.routeId(), Long.toString(result.referenceVersionId()),
            BoardingStop.from(result.origin()), result.options().stream().map(BoardingOption::from).toList());
    }

    @Schema(name = "BoardingStop",
        requiredProperties = {"stationId", "stopSequence", "name", "direction", "boardingAllowed"})
    public record BoardingStop(
        @Schema(description = "정류장 ID")
        String stationId,
        @Schema(description = "현재 노선 판본의 방문 순번")
        int stopSequence,
        @Schema(description = "정류장 이름")
        String name,
        @Schema(description = "노선 진행 방향(UP 또는 DOWN). 기준 정류장과의 관계는 relation으로 판단한다")
        String direction,
        @Schema(description = "이 정류장에서 승차 가능 여부")
        boolean boardingAllowed
    ) {
        static BoardingStop from(StopVisit visit) {
            return new BoardingStop(visit.stationId(), visit.stopSequence(), visit.name(), visit.direction(),
                visit.boardingAllowed());
        }
    }

    @Schema(name = "BoardingOption", requiredProperties = {"stop", "sequenceOffset", "relation", "accesses"})
    public record BoardingOption(
        BoardingStop stop,
        @Schema(description = "후보 순번 − 기준 순번")
        int sequenceOffset,
        @Schema(description = "기준과의 관계. BEFORE·AFTER(같은 방향에서 버스가 기준보다 먼저·나중에 지남), "
            + "OPPOSITE(방향이 다름), SAME_STATION(같은 정류장의 다른 방문). 값이 늘어날 수 있으며 모르는 값의 후보는 건너뛴다",
            example = "OPPOSITE")
        String relation,
        @Schema(description = "그 정류장까지 가는 방법. durationSeconds 오름차순이고 적어도 하나 있다")
        List<BoardingAccess> accesses
    ) {
        static BoardingOption from(BoardingOptions.Option option) {
            return new BoardingOption(BoardingStop.from(option.stop()), option.sequenceOffset(),
                option.relation().name(), option.accesses().stream().map(BoardingAccess::from).toList());
        }
    }

    @Schema(name = "BoardingAccess", requiredProperties = {"durationSeconds", "legs"})
    public record BoardingAccess(
        @Schema(description = "출발부터 탑승 정류장 도착까지 전체 시간(초). 기다리는 시간이 있는 수단이면 legs 시간의 합보다 클 수 있다")
        int durationSeconds,
        @Schema(description = "이동 구간. 지금은 WALK 한 구간")
        List<BoardingLeg> legs
    ) {
        static BoardingAccess from(BoardingOptions.Access access) {
            return new BoardingAccess(access.durationSeconds(), access.legs().stream().map(BoardingLeg::from).toList());
        }
    }

    @JsonInclude(Include.NON_NULL)
    @Schema(name = "BoardingLeg", requiredProperties = {"mode", "durationSeconds", "source"})
    public record BoardingLeg(
        @Schema(description = "구간 수단. 지금은 WALK. 값이 늘어날 수 있으며 모르는 값의 이동 방법은 건너뛴다", example = "WALK")
        String mode,
        @Schema(description = "구간 이동 시간(초)")
        int durationSeconds,
        @Schema(description = "구간 이동 거리(m). 도보는 실제 보행 경로 거리이며 직선거리가 아니다. 수단에 따라 없을 수 있다")
        Integer distanceMeters,
        @Schema(description = "값의 출처. PEDESTRIAN_GRAPH는 OSM 보행 그래프 정적 계산. 값이 늘어날 수 있다",
            example = "PEDESTRIAN_GRAPH")
        String source
    ) {
        static BoardingLeg from(BoardingOptions.Leg leg) {
            return new BoardingLeg(leg.mode().name(), leg.durationSeconds(), leg.distanceMeters(), leg.source());
        }
    }
}

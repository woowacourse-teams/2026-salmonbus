package com.gustler.backend.api.arrival.dto;

import com.gustler.backend.api.arrival.application.StationArrivalsOverview;
import com.gustler.backend.api.arrival.domain.ArrivalPrediction;
import com.gustler.backend.api.arrival.domain.ArrivalVisit;
import com.gustler.backend.api.arrival.domain.RouteArrivals;
import com.gustler.backend.arrival.contract.ArrivalRefresh;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(name = "StationArrivalsResponse", requiredProperties = {"stationId", "generatedAt", "status", "reason", "snapshot", "refresh", "routes"},
    additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record StationArrivalsResponse(
    @Schema(description = "9자리 정류장 ID", pattern = "^[0-9]{9}$")
    String stationId,
    @Schema(description = "이번 응답 생성 시각. 캐시의 Date/Age도 함께 반영")
    Instant generatedAt,
    @Schema(description = "READY는 유효한 ETA가 있음. NO_PREDICTION은 예측 부재. UNAVAILABLE은 미수집·만료 등으로 제공 불가", allowableValues = {"READY", "NO_PREDICTION", "UNAVAILABLE"})
    String status,
    @Schema(description = "제공 불가 사유. NOT_COLLECTED, STALE, PREDICTION_ELAPSED, NO_VERIFIED_ROUTE_MATCH 등. 정상이면 null", nullable = true)
    String reason,
    @Schema(description = "GBIS 응답 수신 결과. 최초 수집 전에는 null", nullable = true)
    Snapshot snapshot,
    @Schema(description = "데이터 상태와 독립적인 갱신 작업 상태")
    Refresh refresh,
    @Schema(description = "선택 필터에 맞는 노선·방문별 도착정보")
    List<Route> routes
) {
    public StationArrivalsResponse { routes = List.copyOf(routes); }

    public static StationArrivalsResponse from(StationArrivalsOverview overview) {
        return new StationArrivalsResponse(overview.stationId(), overview.generatedAt(), overview.status().name(),
            overview.reason(), snapshotOf(overview.snapshot()), refreshOf(overview.refresh()),
            overview.routes().stream().map(StationArrivalsResponse::routeOf).toList());
    }

    private static Snapshot snapshotOf(ArrivalSnapshot snapshot) {
        return snapshot == null ? null : new Snapshot(snapshot.id(), snapshot.receivedAt(), null,
            snapshot.sourceQueryTime(), snapshot.refreshAfterAt(), snapshot.staleAt());
    }

    private static Refresh refreshOf(ArrivalRefresh value) {
        String status = switch (value.status()) {
            case ACCEPTED, IN_PROGRESS -> "RUNNING";
            case DEFERRED -> "NOT_DUE".equals(value.reason()) ? "IDLE" : "BLOCKED";
            case IDLE -> "IDLE";
            case UNAVAILABLE -> "FAILED";
        };
        return new Refresh(status, value.reason(), value.suggestedRefreshAt());
    }

    private static Route routeOf(RouteArrivals route) {
        ArrivalVisit visit = route.targetVisit();
        TargetVisit target = new TargetVisit(visit == null ? null : Long.toString(visit.referenceVersionId()),
            visit == null ? null : visit.stopSequence(), route.sourceStopOrder(),
            visit == null ? "UNVERIFIED" : "MATCHED", visit == null ? null : visit.direction());
        return new Route(route.routeId(), route.name(), target, route.status().name(), route.reason(),
            route.arrivals().stream().map(StationArrivalsResponse::vehicleOf).toList());
    }

    private static Vehicle vehicleOf(ArrivalPrediction prediction) {
        var source = prediction.source();
        Eta eta = new Eta(prediction.available() ? "AVAILABLE" : "UNAVAILABLE",
            prediction.available() ? null : "PREDICTION_ELAPSED", prediction.resolution(),
            prediction.estimateBaseAt(), "RECEIVED_AT_APPROXIMATION", prediction.expectedArrivalAt(),
            prediction.remainingSeconds());
        return new Vehicle(source.vehicleId(), source.ordinal(), source.remainingStops(), eta,
            new Seat(source.remainingSeats() == null ? "UNKNOWN" : "EXACT", source.remainingSeats(),
                source.seatUnknownReason()), source.congestion(), source.vehicleType(), source.taglessSupported());
    }
    @Schema(name = "ArrivalSnapshot", requiredProperties = {"snapshotId", "receivedAt", "sourceObservedAt", "sourceQueryTime", "refreshAfterAt", "staleAt"},
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Snapshot(
        @Schema(description = "수신 결과 식별자")
        UUID snapshotId,
        @Schema(description = "서버가 GBIS 응답을 받은 시각")
        Instant receivedAt,
        @Schema(description = "실제 원천 차량 관측시각은 알 수 없어 현재 null. receivedAt으로 대신 채우지 않음", nullable = true)
        Instant sourceObservedAt,
        @Schema(description = "원천의 정보요청시간 문자열. 실제 차량 관측시각이나 ETA 산출시각이 아님", nullable = true)
        String sourceQueryTime,
        @Schema(description = "수신 후 40초. 이후 조회에서 갱신을 요청할 수 있는 시각")
        Instant refreshAfterAt,
        @Schema(description = "수신 후 60초. 이 시각부터 수신 결과를 사용하지 않음")
        Instant staleAt
    ) { }
    @Schema(name = "ArrivalRefresh", requiredProperties = {"status", "reason", "suggestedRefreshAt"},
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Refresh(
        @Schema(description = "IDLE, RUNNING, BLOCKED, FAILED. 실패·제한 중에도 기존 ETA는 유효할 수 있음", allowableValues = {"IDLE", "RUNNING", "BLOCKED", "FAILED"})
        String status,
        @Schema(description = "COLLECTING, STARTING, RATE_LIMITED, DAILY_QUOTA_EXHAUSTED, REFRESH_DISABLED 등. 없으면 null", nullable = true)
        String reason,
        @Schema(description = "다음 재조회 권장 시각. 수집 중에는 보통 1초 뒤이며 고정 주기 대신 이 값을 사용")
        Instant suggestedRefreshAt
    ) { }
    @Schema(name = "ArrivalTargetVisit", requiredProperties = {"referenceVersionId", "stopSequence", "sourceStopOrder", "matchStatus", "direction"},
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record TargetVisit(
        @Schema(description = "대응한 노선 판본. 확인되지 않으면 null이며 문자열로 유지", nullable = true)
        String referenceVersionId,
        @Schema(description = "대상 정류장의 방문 순번. 확인되지 않으면 null", nullable = true, minimum = "1")
        Integer stopSequence,
        @Schema(description = "GBIS가 보고한 대상 정류장 순번. 차량의 현재 위치가 아님", minimum = "1")
        int sourceStopOrder,
        @Schema(description = "MATCHED는 현재 판본과 대응 확인, UNVERIFIED는 미확인", allowableValues = {"MATCHED", "UNVERIFIED"})
        String matchStatus,
        @Schema(description = "대상 방문의 UP/DOWN 방향. 미확인이면 null", nullable = true, allowableValues = {"UP", "DOWN"})
        String direction
    ) { }
    @Schema(name = "ArrivalRoute", requiredProperties = {"routeId", "name", "targetVisit", "status", "reason", "arrivals"},
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Route(
        @Schema(description = "노선 ID")
        String routeId,
        @Schema(description = "원천의 노선 번호 또는 표시명. 미제공이면 null", nullable = true)
        String name,
        @Schema(description = "ETA의 대상 정류장 방문")
        TargetVisit targetVisit,
        @Schema(description = "이 노선·방문에 대한 예측 상태. 각 차량의 eta.status도 확인", allowableValues = {"READY", "NO_PREDICTION", "UNAVAILABLE"})
        String status,
        @Schema(description = "제공 불가 사유. 정상이면 null", nullable = true)
        String reason,
        @Schema(description = "GBIS가 제공한 첫째·둘째 도착 예정 차량. 최대 2대이며 중간 슬롯이 없을 수 있음")
        List<Vehicle> arrivals
    ) {
        public Route { arrivals = List.copyOf(arrivals); }
    }
    @Schema(name = "ArrivalVehicle", requiredProperties = {"vehicleId", "ordinal", "remainingStops", "eta", "currentSeat", "congestionCode", "vehicleTypeCode", "taglessSupported"},
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Vehicle(
        @Schema(description = "원천 차량 ID. 미제공이면 null이며 ordinal을 차량 ID로 사용하지 않음", nullable = true)
        String vehicleId,
        @Schema(description = "첫째 또는 둘째 도착 예정 차량 슬롯", minimum = "1", maximum = "2")
        int ordinal,
        @Schema(description = "대상 정류장까지 남은 정류장 수. 미제공이면 null", nullable = true, minimum = "0")
        Integer remainingStops,
        @Schema(description = "선택한 정류장까지의 예상 도착정보. 실제 도착·차량 좌표가 아님")
        Eta eta,
        @Schema(description = "수신된 현재 잔여 좌석. 승차 시점 좌석 예보와 별개")
        Seat currentSeat,
        @Schema(description = "혼잡도 코드 1~4. 미제공이면 null", nullable = true, minimum = "1", maximum = "4")
        Integer congestionCode,
        @Schema(description = "원천 차량 유형 코드. 미제공이면 null", nullable = true)
        Integer vehicleTypeCode,
        @Schema(description = "태그리스 지원 여부. 미제공이면 null", nullable = true)
        Boolean taglessSupported
    ) { }
    @Schema(name = "ArrivalEta", requiredProperties = {"status", "reason", "resolution", "estimateBaseAt", "baseKind", "expectedArrivalAt", "remainingSeconds"},
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Eta(
        @Schema(description = "AVAILABLE 또는 UNAVAILABLE. 예상 시각 경과 시 실제 도착으로 확정하지 않음", allowableValues = {"AVAILABLE", "UNAVAILABLE"})
        String status,
        @Schema(description = "예상 시각이 지났으면 PREDICTION_ELAPSED, 유효하면 null", nullable = true)
        String reason,
        @Schema(description = "원천 예측 단위. 초 우선, 초 미제공 시 분 단위 사용", allowableValues = {"SECONDS", "MINUTES"})
        String resolution,
        @Schema(description = "예측에 사용한 로컬 수신시각")
        Instant estimateBaseAt,
        @Schema(description = "수신시각을 기준으로 해석한 근사이며 원천 ETA 산출시각이 아님", allowableValues = "RECEIVED_AT_APPROXIMATION")
        String baseKind,
        @Schema(description = "예상 도착시각. 프론트 카운트다운 기준이며 경과해도 실제 도착으로 확정하지 않음")
        Instant expectedArrivalAt,
        @Schema(description = "응답 생성 시점의 남은 초. 예상 시각 경과 시 null. snapshot.staleAt 이후에도 사용하지 않음", nullable = true, minimum = "0")
        Long remainingSeconds
    ) { }
    @Schema(name = "ArrivalSeat", requiredProperties = {"kind", "remaining", "reason"},
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    public record Seat(
        @Schema(description = "EXACT는 좌석 수 제공, UNKNOWN은 미제공", allowableValues = {"EXACT", "UNKNOWN"})
        String kind,
        @Schema(description = "현재 잔여 좌석 수. 0은 제공된 값이며 UNKNOWN의 null과 구분", nullable = true, minimum = "0")
        Integer remaining,
        @Schema(description = "미제공 사유. NOT_REPORTED 또는 UNSUPPORTED_ROUTE_TYPE 등이며 EXACT이면 null", nullable = true)
        String reason
    ) { }
}

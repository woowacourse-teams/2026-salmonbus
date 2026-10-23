package com.gustler.backend.api.vehicle.dto;

import com.gustler.backend.api.vehicle.application.LiveVehicleOverview;
import com.gustler.backend.api.vehicle.domain.VehicleObservationState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * 차량 응답이 기준으로 삼은 관측의 상태와 유효 시각을 공개 필드로 전달한다.
 */
@Schema(requiredProperties = {"state", "observedAt", "staleAt"})
public record ObservationResponse(
    @Schema(description = "VEHICLES_PRESENT, NO_VEHICLES_OBSERVED 또는 UNKNOWN. 정상 빈 응답을 운행 종료로 단정하지 않음")
    VehicleObservationState state,
    @Schema(description = "마지막 정상 관측 시각. 정상 관측 이력이 없으면 null", nullable = true)
    OffsetDateTime observedAt,
    @Schema(description = "마지막 정상 관측의 만료 시각. 이력이 없으면 null", nullable = true)
    OffsetDateTime staleAt
) {
    public ObservationResponse {
        Objects.requireNonNull(state, "state는 null일 수 없습니다.");
    }

    public static ObservationResponse from(LiveVehicleOverview overview) {
        return new ObservationResponse(
            overview.state(),
            overview.observedAt(),
            overview.staleAt()
        );
    }
}

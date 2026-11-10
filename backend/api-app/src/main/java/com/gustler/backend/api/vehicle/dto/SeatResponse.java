package com.gustler.backend.api.vehicle.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gustler.backend.api.vehicle.domain.VehicleSeat;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.util.Objects;

/**
 * 현재 좌석의 EXACT·UNKNOWN 형태를 공개 JSON 계약으로 전송한다. 0석과 null은 다른 의미다.
 */
@Schema(description = "EXACT는 remaining, UNKNOWN은 reason을 포함. 두 변형의 다른 필드는 생략", oneOf = { SeatResponse.Exact.class,
        SeatResponse.Unknown.class }, discriminatorProperty = "kind", discriminatorMapping = {
                @DiscriminatorMapping(value = "EXACT", schema = SeatResponse.Exact.class),
                @DiscriminatorMapping(value = "UNKNOWN", schema = SeatResponse.Unknown.class) })
public sealed interface SeatResponse permits SeatResponse.Exact, SeatResponse.Unknown {
    static SeatResponse from(VehicleSeat seat) {
        if (seat instanceof VehicleSeat.Exact exact) {
            return new SeatResponse.Exact(exact.remaining());
        }
        return new SeatResponse.Unknown((VehicleSeat.Unknown) seat);
    }

    enum Kind {
        EXACT, UNKNOWN,;
    }

    @Schema(name = "ExactSeat", title = "EXACT 좌석", requiredProperties = { "kind", "remaining" })
    record Exact(@Schema(description = "잔여 좌석 수", minimum = "0") int remaining) implements SeatResponse {
        @JsonProperty("kind")
        @Schema(description = "EXACT", allowableValues = "EXACT", requiredMode = RequiredMode.REQUIRED)
        public Kind kind() {
            return Kind.EXACT;
        }
    }

    @Schema(name = "UnknownSeat", title = "UNKNOWN 좌석", requiredProperties = { "kind", "reason" })
    record Unknown(@Schema(description = "좌석 수 미제공 사유. NOT_REPORTED=미보고, "
        + "REPORTED_UNKNOWN=상류 미확인 또는 수집 단계에서 보류한 좌석값") VehicleSeat.Unknown reason) implements SeatResponse {
        public Unknown {
            Objects.requireNonNull(reason, "UNKNOWN 응답에는 미제공 사유가 필요합니다.");
        }

        @JsonProperty("kind")
        @Schema(description = "UNKNOWN", allowableValues = "UNKNOWN", requiredMode = RequiredMode.REQUIRED)
        public Kind kind() {
            return Kind.UNKNOWN;
        }
    }
}

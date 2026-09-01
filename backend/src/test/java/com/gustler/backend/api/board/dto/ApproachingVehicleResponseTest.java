package com.gustler.backend.api.board.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.board.domain.ApproachingVehicle;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ApproachingVehicleResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void 좌석_확률이_있는_차량은_kind가_FORECAST로_나간다() {
        Map<String, Object> actual = serialize(
            new ApproachingVehicle.Forecast("A", 3, 0.8d, 12.4)
        );

        assertThat(actual).containsEntry("kind", "FORECAST");
    }

    @Test
    void 좌석을_모르는_차량은_kind가_UNKNOWN으로_나간다() {
        Map<String, Object> actual = serialize(new ApproachingVehicle.SeatUnknown("A", 3));

        assertThat(actual).containsEntry("kind", "UNKNOWN");
    }

    @Test
    void 좌석을_모르는_차량은_좌석_확률을_뺀_세_가지만_낸다() {
        Map<String, Object> actual = serialize(new ApproachingVehicle.SeatUnknown("A", 3));

        assertThat(actual).containsOnlyKeys("kind", "vehicleId", "horizonStops");
    }

    @Test
    void 좌석을_아는_차량은_빈자리_확률을_그대로_낸다() {
        Map<String, Object> actual = serialize(
            new ApproachingVehicle.Forecast("A", 3, 0.996d, 12.4)
        );

        assertThat(actual).containsEntry("seatAvailableProbability", 0.996d);
    }

    @Test
    void 기대_잔여석이_없는_차량은_기대_잔여석_키를_생략한다() {
        Map<String, Object> actual = serialize(
            new ApproachingVehicle.Forecast("A", 3, 0.8d, null)
        );

        assertThat(actual).doesNotContainKey("expectedSeats");
    }

    @Test
    void 차량_id를_모르는_관측도_좌석을_모른다고_낸다() {
        Map<String, Object> actual = serialize(new ApproachingVehicle.SeatUnknown(null, 1));

        assertThat(actual).containsEntry("vehicleId", null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> serialize(
        ApproachingVehicle vehicle
    ) {
        return objectMapper.readValue(
            objectMapper.writeValueAsString(ApproachingVehicleResponse.from(vehicle)),
            Map.class
        );
    }
}

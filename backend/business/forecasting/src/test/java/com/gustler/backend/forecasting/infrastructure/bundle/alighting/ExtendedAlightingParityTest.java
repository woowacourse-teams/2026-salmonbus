package com.gustler.backend.forecasting.infrastructure.bundle.alighting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.VehicleStopTarget;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingTimeCell;
import com.gustler.backend.forecasting.domain.model.alighting.ExtendedAlightingPredictor;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

class ExtendedAlightingParityTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Test
    void Python과_동일한_시간대_경계와_정산_구간을_사용한다() throws IOException {
        JsonNode fixture = fixture();
        List<String> holidays = fixture.get("holidays").valueStream().map(JsonNode::asText).toList();
        for (JsonNode row : fixture.get("cells")) {
            var at = OffsetDateTime.parse(row.get("at").asText()).atZoneSameInstant(SEOUL);
            AlightingTimeCell cell = AlightingTimeCell.of(at, holidays.contains(at.toLocalDate().toString()));
            assertThat(cell.key()).isEqualTo(row.get("cell").asText());
            assertThat(cell.settlementBin(at)).isEqualTo(row.get("bin").asInt());
        }
    }

    @Test
    void 실제_목표_정류장에서_만든_여섯_입력과_두_예측이_Python과_같다() throws IOException {
        JsonNode fixture = fixture();
        JsonNode origin = fixture.get("origin");
        var at = OffsetDateTime.parse(origin.get("at").asText()).toInstant();
        int passed = origin.get("passed").asInt();
        int seats = origin.get("seats").asInt();
        int capacity = origin.get("capacity").asInt();
        var observation = new AlightingAwareObservation(1, 1, 1, at, "bus", passed, passed, 2, seats, 0);
        var snapshot = new AlightingAwareFeatureSnapshot(1, observation, capacity, null, List.of(observation), List.of());
        var vehicle = new ObservedVehicle("bus", 1, passed, at, seats, null);
        var target = new VehicleStopTarget(vehicle, new RouteStop(1, origin.get("stop").asInt(), "stop", true));
        double[] inputs = ExtendedAlightingPredictor.inputs(snapshot, target, true);
        assertThat(inputs).containsExactly(fixture.get("inputs").valueStream().mapToDouble(JsonNode::asDouble).toArray());

        var predictor = new ExtendedAlightingPredictor(AlightingAwareInferenceBundle.model(fixture.get("seats")),
            AlightingAwareInferenceBundle.model(fixture.get("full")));
        var result = predictor.predict(inputs);
        assertThat(result.expectedSeats()).isEqualTo(fixture.get("expected_seats").asDouble());
        assertThat(result.fullChance()).isCloseTo(fixture.get("expected_full").asDouble(), within(1e-12));
    }

    private JsonNode fixture() throws IOException {
        try (var input = getClass().getResourceAsStream("/alighting/extended-parity.json")) {
            return JsonMapper.builder().build().readTree(input);
        }
    }
}

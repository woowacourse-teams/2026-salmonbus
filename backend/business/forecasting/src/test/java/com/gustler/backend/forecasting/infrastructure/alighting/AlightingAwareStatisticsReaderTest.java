package com.gustler.backend.forecasting.infrastructure.alighting;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AlightingAwareStatisticsReaderTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String STATISTICS = """
        {
          "route": "100", "min_per_stop": 2,
          "route_metadata": [
            {"order": 2, "id": "B", "name": "역", "direction": "DOWN", "boardingAllowed": true},
            {"order": 1, "id": "A", "name": "차고지", "direction": "UP", "boardingAllowed": true}
          ],
          "tables": {
            "lam": [%s], "lam_base": [], "net_tab": [], "full_tab": [], "dep_tab": [], "stop_net_base": [],
            "target_band": [
              {"stop": 2, "band": "morning", "values": [4, 5, 6]},
              {"stop": 2, "band": "morning", "values": [7, null, 9]}
            ]
          }
        }
        """;
    private static final String RATE = """
        {"key": [2, "work", 12], "value": 3.5}
        """;

    @Test
    void 정류장_순서와_승차율을_읽고_겹치는_목표_구간은_마지막_세_값을_쓴다() {
        // given
        JsonNode statistics = JSON.readTree(STATISTICS.formatted(RATE));

        // when
        AlightingAwareRouteStatistics route = AlightingAwareStatisticsReader.read(statistics, Set.of());

        // then
        assertSoftly(softly -> {
            softly.assertThat(route.stops()).extracting(AlightingAwareRouteStatistics.Stop::id)
                .containsExactly("B", "A");
            softly.assertThat(route.rate(2, "work", 360)).isEqualTo(3.5);
            softly.assertThat(route.targetMeans(2, "morning")).containsExactly(7.0, Double.NaN, 9.0);
        });
    }

    @Test
    void 같은_정류장과_시간의_승차율이_두_번_들어오면_거절한다() {
        // given
        JsonNode statistics = JSON.readTree(STATISTICS.formatted(RATE + "," + RATE));

        // when, then
        assertThatThrownBy(() -> AlightingAwareStatisticsReader.read(statistics, Set.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Duplicate AlightingAware cell");
    }
}

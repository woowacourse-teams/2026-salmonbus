package com.gustler.backend.api.station.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StationTileResponseTest {
    @Test
    void 원본_목록이_변해도_응답과_중첩된_노선_목록은_유지된다() {
        // given
        List<String> routeIds = new ArrayList<>(List.of("234000882"));
        StationTileResponse.Station station = new StationTileResponse.Station(
            "206000532", "판교역", new StationTileResponse.Point(127.11, 37.39),
            "07489", "성남", null, routeIds
        );
        List<StationTileResponse.Station> stations = new ArrayList<>(List.of(station));
        StationTileResponse response = new StationTileResponse(
            new StationTileResponse.Tile(14, 13976, 6354), "READY", "GBIS", "WGS84",
            stations, LocalDate.of(2026, 10, 20), null
        );

        // when
        stations.clear();
        routeIds.clear();

        // then
        assertThat(response.stations()).hasSize(1);
        assertSoftly(softly -> {
            softly.assertThat(response.stations()).containsExactly(station);
            softly.assertThat(response.stations().getFirst().salmonRouteIds()).containsExactly("234000882");
            softly.assertThatThrownBy(() -> response.stations().clear())
                .isInstanceOf(UnsupportedOperationException.class);
            softly.assertThatThrownBy(() -> response.stations().getFirst().salmonRouteIds().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        });
    }
}

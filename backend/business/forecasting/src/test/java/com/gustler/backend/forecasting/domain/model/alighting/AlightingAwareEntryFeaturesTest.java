package com.gustler.backend.forecasting.domain.model.alighting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AlightingAwareEntryFeaturesTest {

    @ParameterizedTest
    @CsvSource({
        "30, 1, 2", "40, 2, 2", "50, 3, 2", "60, 0, 0"
    })
    void 연속_경유_뒤_세_정류장까지_순위와_직전_경유_수를_기록한다(final int target, final int rank, final int viaRun) {
        // given
        AlightingAwareRouteStatistics route = route(
                stop(60, "종점", false),
                stop(50, "도서관", false),
                stop(40, "시장", false),
                stop(30, "병원", false),
                stop(20, "터널(경유)", false),
                stop(10, "교차로(경유)", false)
        );

        // when
        double[] features = AlightingAwareEntryFeatures.calculate(route, target, 10);

        // then
        assertThat(features).isEqualTo(new double[] { rank, viaRun, 0, 1 });
    }

    @Test
    void 목표_앞뒤의_경유는_같은_방향에서_찾고_출발과_목표_사이의_경유는_양방향에서_센다() {
        // given
        AlightingAwareRouteStatistics route = route(
                stop(1, "교차로(경유)", true),
                stop(2, "터널(경유)", false),
                stop(3, "시장", false),
                stop(4, "병원", true),
                stop(5, "다리(경유)", false),
                stop(6, "종점", false)
        );

        // when
        double[] betweenViaStops = AlightingAwareEntryFeatures.calculate(route, 3, 0);
        double[] oppositeDirection = AlightingAwareEntryFeatures.calculate(route, 4, 0);
        double[] afterNextVia = AlightingAwareEntryFeatures.calculate(route, 6, 0);

        // then
        assertSoftly(softly -> {
            softly.assertThat(betweenViaStops).isEqualTo(new double[] { 0, 0, 1, 2 });
            softly.assertThat(oppositeDirection).isEqualTo(new double[] { 1, 1, 0, 2 });
            softly.assertThat(afterNextVia).isEqualTo(new double[] { 1, 1, 0, 3 });
        });
    }

    @Test
    void 목표가_경유이거나_목록에_없어도_출발과_목표_사이의_경유_수는_남는다() {
        // given
        AlightingAwareRouteStatistics route = route(stop(1, "시장", false), stop(2, "터널(경유)", false),
                stop(5, "다리(경유)", false));

        // when
        double[] viaTarget = AlightingAwareEntryFeatures.calculate(route, 5, 1);
        double[] missingTarget = AlightingAwareEntryFeatures.calculate(route, 4, 1);
        double[] beforeOrigin = AlightingAwareEntryFeatures.calculate(route, 1, 5);

        // then
        assertSoftly(softly -> {
            softly.assertThat(viaTarget).isEqualTo(new double[] { 0, 0, 0, 1 });
            softly.assertThat(missingTarget).isEqualTo(new double[] { 0, 0, 0, 1 });
            softly.assertThat(beforeOrigin).isEqualTo(new double[] { 0, 0, 0, 0 });
        });
    }

    private static AlightingAwareRouteStatistics.Stop stop(final int order, String name, final boolean down) {
        return new AlightingAwareRouteStatistics.Stop(
                order,
                Integer.toString(order),
                name,
                down,
                true
        );
    }

    private static AlightingAwareRouteStatistics route(AlightingAwareRouteStatistics.Stop... stops) {
        Map<String, Map<String, Double>> tables = Map.ofEntries(
                Map.entry("lam", Map.of()),
                Map.entry("lam_base", Map.of()),
                Map.entry("net_tab", Map.of()),
                Map.entry("full_tab", Map.of()),
                Map.entry("dep_tab", Map.of()),
                Map.entry("stop_net_base", Map.of())
        );
        return new AlightingAwareRouteStatistics(
                "3330",
                List.of(stops),
                2.0,
                tables,
                Map.of(),
                Set.of()
        );
    }
}

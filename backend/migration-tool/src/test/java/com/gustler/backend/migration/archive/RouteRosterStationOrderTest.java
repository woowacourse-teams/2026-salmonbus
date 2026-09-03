package com.gustler.backend.migration.archive;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.migration.CanonicalJson;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * manifest 는 stations 배열 순서를 그대로 적는다. 예전에는 {@code Map.copyOf} 로 담아
 * JVM 마다 다른 순회 순서가 파일에 새겨졌고(ImmutableCollections 의 SALT 는 기동마다 무작위),
 * 빌드한 JVM 과 검증하는 JVM 이 다르면 archive-verify 가 round-trip 에서 깨졌다.
 * 같은 JVM 안에서는 재현되지 않으므로 "정렬되어 직렬화되는가" 로 못 박는다.
 */
class RouteRosterStationOrderTest {

    private static final int STATION_COUNT = 60;

    @Test
    void serializesStationsByStopOrderNoMatterHowTheInputMapWasOrdered() {
        RouteRoster ascending = roster(orders(false));
        RouteRoster shuffled = roster(orders(true));

        assertThat(stopOrders(ascending)).isEqualTo(expectedOrders());
        assertThat(stopOrders(shuffled)).isEqualTo(expectedOrders());
    }

    @Test
    void serializesTheSameBytesForTheSameRosterAndSurvivesAManifestRoundTrip() {
        byte[] ascending = CanonicalJson.bytesOf(roster(orders(false)).toMap());
        byte[] shuffled = CanonicalJson.bytesOf(roster(orders(true)).toMap());

        assertThat(shuffled).isEqualTo(ascending);

        JsonNode node = CanonicalJson.parse(ascending, "ROUTE_ROSTER_JSON_INVALID");
        RouteRoster reread = RouteRoster.fromManifest(node);

        assertThat(CanonicalJson.bytesOf(reread.toMap())).isEqualTo(ascending);
        assertThat(reread.rosterSha256()).isEqualTo(roster(orders(false)).rosterSha256());
    }

    private static List<Integer> expectedOrders() {
        List<Integer> expected = new ArrayList<>();
        for (int order = 1; order <= STATION_COUNT; order++) {
            expected.add(order);
        }
        return expected;
    }

    /** 오름차순, 또는 결정적으로 뒤섞은 순서. 뒤섞은 쪽은 절대 오름차순이 되지 않는다. */
    private static List<Integer> orders(
        boolean shuffle
    ) {
        List<Integer> orders = expectedOrders();
        if (!shuffle) {
            return orders;
        }
        List<Integer> mixed = new ArrayList<>();
        for (int index = orders.size() - 1; index >= 0; index--) {
            mixed.add(orders.get(index));
        }
        return mixed;
    }

    private static RouteRoster roster(
        List<Integer> insertionOrder
    ) {
        Map<Integer, RouteRoster.Station> stations = new LinkedHashMap<>();
        for (int order : insertionOrder) {
            stations.put(order, new RouteRoster.Station(order, stopId(order), true));
        }
        return new RouteRoster(
            "gbis-2026-08-19", LocalDate.parse("2026-01-01"), null,
            "3330", "204000057", 43, stations, null);
    }

    private static String stopId(
        int order
    ) {
        return "205%06d".formatted(order);
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> stopOrders(
        RouteRoster roster
    ) {
        List<Map<String, Object>> stations = (List<Map<String, Object>>) roster.toMap().get("stations");
        return stations.stream().map(station -> (Integer) station.get("stopOrder")).toList();
    }
}

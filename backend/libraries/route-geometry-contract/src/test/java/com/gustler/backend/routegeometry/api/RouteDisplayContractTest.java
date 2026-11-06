package com.gustler.backend.routegeometry.api;

import static com.gustler.backend.routegeometry.api.RouteDisplaySamples.*;

import static org.assertj.core.api.Assertions.*;

import com.gustler.backend.routegeometry.api.RouteDisplayInput.Point;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

class RouteDisplayContractTest {
    @Test
    void python_struct_독립_골든과_바이트가_일치한다() {
        assertThat(input().digest())
                .isEqualTo("6abce8005707fefc839dd167618c63d734693168970beb336ca0615a1959bdc4");
    }

    @Test
    void 형상_정류장_회차_미세변경도_무시하지_않는다() {
        var source = input();
        assertThat(
                        new RouteDisplayInput(
                                        source.routeId(), null, source.points(), source.visits())
                                .digest())
                .isNotEqualTo(source.digest());
        var points = new ArrayList<>(source.points());
        points.set(1, new Point(127.010000001, 37.01));
        assertThat(new RouteDisplayInput(source.routeId(), 2, points, source.visits()).digest())
                .isNotEqualTo(source.digest());
        var visits = new ArrayList<>(source.visits());
        visits.set(
                0,
                new RouteDisplayInput.Visit(
                        "206000637", 1, "UP", false, source.points().getFirst()));
        assertThat(new RouteDisplayInput(source.routeId(), 2, source.points(), visits).digest())
                .isNotEqualTo(source.digest());
    }

    @Test
    void 반복정류장_방문과_양방향_연결을_그대로_보존한다() {
        var document = manual(input(), 0.0001);
        assertThatCode(() -> document.validateFor(input())).doesNotThrowAnyException();
        assertThat(document.connections()).containsKeys("1", "2", "3");
        assertThat(document.visits().getFirst().displayPoint())
                .isEqualTo(document.visits().getLast().displayPoint());
    }

    @Test
    void 다른원본과_누락연결과_선분불일치를_거부한다() {
        var source = input();
        var document = manual(source, 0);
        var changed =
                new RouteDisplayInput(source.routeId(), null, source.points(), source.visits());
        assertThatThrownBy(() -> document.validateFor(changed))
                .isInstanceOf(IllegalArgumentException.class);
        var connections = new HashMap<>(document.connections());
        connections.remove("2");
        assertThatThrownBy(() -> copy(document, connections).validateFor(source))
                .isInstanceOf(IllegalArgumentException.class);
        connections.put("2", new ManualRouteDisplay.Connection(new Point(128, 38), 0, 1));
        assertThatThrownBy(() -> copy(document, connections).validateFor(source))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 연결_역행과_없는방문과_preview를_거부한다() {
        var source = input();
        var document = manual(source, 0);
        var connections = new HashMap<>(document.connections());
        connections.put("1", connections.get("2"));
        connections.put("2", document.connections().get("1"));
        final var reversed = connections;
        assertThatThrownBy(() -> copy(document, reversed).validateFor(source))
                .isInstanceOf(IllegalArgumentException.class);
        connections = new HashMap<>(document.connections());
        connections.put("4", connections.get("3"));
        final var extra = connections;
        assertThatThrownBy(() -> copy(document, extra).validateFor(source))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new ManualRouteDisplay(
                                        document.format(),
                                        "UNREVIEWED_LOCAL_PREVIEW",
                                        document.algorithm(),
                                        document.routeId(),
                                        document.inputDigest(),
                                        document.displayPaths(),
                                        document.connections(),
                                        document.visits(),
                                        document.review()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 좌표없는_방문에_표시점을_발명하지_않는다() {
        var source = input();
        var visits = new ArrayList<>(source.visits());
        visits.set(1, new RouteDisplayInput.Visit("206000638", 2, "UP", true, null));
        var partial = new RouteDisplayInput(source.routeId(), 2, source.points(), visits);
        assertThatCode(() -> manual(partial, 0).validateFor(partial)).doesNotThrowAnyException();
    }

    @Test
    void 비승차방문은_표시핀없이_정확한_선분연결을_보존한다() {
        var base = input();
        var sourceVisits = new ArrayList<>(base.visits());
        var original = sourceVisits.get(1);
        sourceVisits.set(1, new RouteDisplayInput.Visit(original.stationId(), original.sequence(),
                original.direction(), false, original.sourcePoint()));
        var source = new RouteDisplayInput(base.routeId(), base.turnSequence(), base.points(), sourceVisits);
        var document = withoutDisplayPoint(manual(source, 0), 1);
        assertThatCode(() -> document.validateFor(source)).doesNotThrowAnyException();
        var connections = new HashMap<>(document.connections());
        connections.remove("2");
        assertThatThrownBy(() -> copy(document, connections).validateFor(source))
                .isInstanceOf(IllegalArgumentException.class);
        connections.put("2", new ManualRouteDisplay.Connection(new Point(128, 38), 0, 1));
        assertThatThrownBy(() -> copy(document, connections).validateFor(source))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 승차방문은_연결점이있어도_표시핀을_생략할수없다() {
        var source = input();
        assertThatThrownBy(() -> withoutDisplayPoint(manual(source, 0), 1).validateFor(source))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ManualRouteDisplay withoutDisplayPoint(ManualRouteDisplay document, int index) {
        var visits = new ArrayList<>(document.visits());
        var visit = visits.get(index);
        visits.set(index, new ManualRouteDisplay.Visit(visit.stationId(), visit.sequence(),
                visit.direction(), visit.boardingAllowed(), visit.sourcePoint(), null));
        return new ManualRouteDisplay(document.format(), document.status(), document.algorithm(),
                document.routeId(), document.inputDigest(), document.displayPaths(),
                document.connections(), visits, document.review());
    }

    private ManualRouteDisplay copy(
            ManualRouteDisplay document,
            java.util.Map<String, ManualRouteDisplay.Connection> connections) {
        return new ManualRouteDisplay(
                document.format(),
                document.status(),
                document.algorithm(),
                document.routeId(),
                document.inputDigest(),
                document.displayPaths(),
                connections,
                document.visits(),
                document.review());
    }

    @Test
    void 캐시_수명_갱신과_만료는_불변_생성입력_판본을_변경한다() {
        var until = java.time.Instant.EPOCH.plusSeconds(3600);
        var typed =
                List.of(
                        new RouteShapeEvidence.TypedPoint(
                                input().points().getFirst(), 2, "UP", true));
        var evidence =
                new RouteShapeEvidence(
                        "GBIS_API",
                        List.of(
                                new RouteShapeEvidence.Source(
                                        "GG_BMS_NODES", "AVAILABLE", "a".repeat(64), until)),
                        typed);
        var renewed =
                new RouteShapeEvidence(
                        "GBIS_API",
                        List.of(
                                new RouteShapeEvidence.Source(
                                        "GG_BMS_NODES",
                                        "AVAILABLE",
                                        "a".repeat(64),
                                        until.plusSeconds(60))),
                        typed);
        assertThat(evidence.at(java.time.Instant.EPOCH).revision())
                .isNotEqualTo(renewed.at(java.time.Instant.EPOCH).revision());
        var expired = evidence.at(until);
        assertThat(expired.sources().getFirst().status()).isEqualTo("STALE");
        assertThat(expired.webPoints().getFirst().nodeConfirmed()).isFalse();
        assertThat(expired.revision()).isNotEqualTo(evidence.revision());
    }
}

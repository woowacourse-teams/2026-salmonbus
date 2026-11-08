package com.gustler.backend.api.salmon.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.arrival.application.ArrivalReadRepository;
import com.gustler.backend.api.arrival.domain.ArrivalVisit;
import com.gustler.backend.api.salmon.application.ArrivalStopSource.CoverageRule;
import com.gustler.backend.api.salmon.domain.ActionFixture;
import com.gustler.backend.api.salmon.domain.ActionModel.*;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.Prediction;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.RouteArrival;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class ArrivalStopSourceTest {
    private static final Instant RECEIVED = Instant.parse("2026-11-08T09:00:00Z");
    private static final Instant NOW = RECEIVED.plusSeconds(30);
    private static final String PUBLIC_ROUTE = "204000057";
    private static final String SOURCE_ROUTE = "224000057";
    private static final String STATION = "228000001";

    @Test
    void 일치한_방문의_예측을_현재_시각_기준_오프셋_항목으로_만든다() {
        StopList list = source(snapshotOf(RECEIVED.plusSeconds(600), prediction("bus-1", 1, 270, null)),
            visits(), CoverageRule.NONE).arrivalsAt(PUBLIC_ROUTE, STATION, 5, NOW);

        Queried queried = (Queried) list;
        assertThat(queried.entries()).hasSize(1);
        assertThat(queried.entries().get(0).arrival()).isEqualTo(new Observed<>(new Offset(270_000 - 30_000)));
        assertThat(queried.coverage()).isEqualTo(Coverage.COVERAGE_UNKNOWN);
    }

    @Test
    void 오프셋은_호출자가_준_판정_시각을_기준으로_하고_호출_시각에_따라_바뀌지_않는다() {
        ArrivalStopSource source = source(snapshotOf(RECEIVED.plusSeconds(600), prediction("bus-1", 1, 270, null)),
            visits(), CoverageRule.NONE);

        Field<Offset> atNow = ((Queried) source.arrivalsAt(PUBLIC_ROUTE, STATION, 5, NOW)).entries().get(0).arrival();
        Field<Offset> later = ((Queried) source.arrivalsAt(PUBLIC_ROUTE, STATION, 5, NOW.plusSeconds(60)))
            .entries().get(0).arrival();
        Field<Offset> again = ((Queried) source.arrivalsAt(PUBLIC_ROUTE, STATION, 5, NOW)).entries().get(0).arrival();

        assertThat(atNow).isEqualTo(new Observed<>(new Offset(240_000)));
        assertThat(later).isEqualTo(new Observed<>(new Offset(180_000)));
        assertThat(again).isEqualTo(atNow);
    }

    @Test
    void 분_해상도_예측은_양자화_상계를_붙이고_도착_시각은_파생으로_만들지_않는다() {
        StopList list = source(snapshotOf(RECEIVED.plusSeconds(600), prediction("bus-1", 1, null, 4)),
            visits(), CoverageRule.NONE).arrivalsAt(PUBLIC_ROUTE, STATION, 5, NOW);

        Field<Offset> arrival = ((Queried) list).entries().get(0).arrival();
        assertThat(arrival).isInstanceOf(Observed.class);
        assertThat(arrival.knownBound()).contains(new Span(60_000));
        assertThat(arrival.isDerived()).isFalse();
    }

    @Test
    void 차량_ID가_비어_있어도_항목은_남고_항목_ID에_차량_ID를_지어내지_않는다() {
        StopList list = source(snapshotOf(RECEIVED.plusSeconds(600), prediction(null, 1, 270, null),
            prediction("", 2, 540, null)), visits(), CoverageRule.NONE).arrivalsAt(PUBLIC_ROUTE, STATION, 5, NOW);

        List<StopEntry> entries = ((Queried) list).entries();
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(StopEntry::id).allMatch(id -> id.endsWith(":1") || id.endsWith(":2"));
    }

    @Test
    void 저장된_스냅샷이_없으면_제공_안_됨이다() {
        StopList list = source(null, visits(), CoverageRule.NONE).arrivalsAt(PUBLIC_ROUTE, STATION, 5, NOW);

        assertThat(list).isEqualTo(new Unavailable(MissingReason.NOT_PROVIDED));
    }

    @Test
    void 저장소_조회가_실패하면_조회_실패다() {
        ArrivalReadRepository failing = new ArrivalReadRepository() {
            @Override
            public List<ArrivalVisit> visits(String stationId) {
                throw new DataAccessResourceFailureException("connection refused");
            }

            @Override
            public Optional<ArrivalSnapshot> latest(String stationId) {
                return Optional.of(snapshotOf(RECEIVED.plusSeconds(600), prediction("bus-1", 1, 270, null)));
            }
        };
        StopList list = new ArrivalStopSource(failing, CoverageRule.NONE, ActionFixture.config(15, 8))
            .arrivalsAt(PUBLIC_ROUTE, STATION, 5, NOW);

        assertThat(list).isEqualTo(new Unavailable(MissingReason.QUERY_FAILED));
    }

    @Test
    void 만료된_스냅샷은_STALE이다() {
        StopList list = source(snapshotOf(RECEIVED.plusSeconds(600), prediction("bus-1", 1, 270, null)),
            visits(), CoverageRule.NONE).arrivalsAt(PUBLIC_ROUTE, STATION, 5, RECEIVED.plusSeconds(601));

        assertThat(list).isEqualTo(new Unavailable(MissingReason.STALE));
    }

    @Test
    void 방문이_검증되지_않으면_순번_미검증이다() {
        StopList list = source(snapshotOf(RECEIVED.plusSeconds(600), prediction("bus-1", 1, 270, null)),
            visits(), CoverageRule.NONE).arrivalsAt(PUBLIC_ROUTE, STATION, 99, NOW);

        assertThat(list).isEqualTo(new Unavailable(MissingReason.UNVERIFIED_SEQUENCE));
    }

    @Test
    void 스냅샷에_해당_노선_예측이_없으면_덮임을_모르는_빈_목록이다() {
        StopList list = source(snapshotOf(RECEIVED.plusSeconds(600)), visits(), CoverageRule.NONE)
            .arrivalsAt(PUBLIC_ROUTE, STATION, 5, NOW);

        assertThat(list).isEqualTo(new Queried(List.of(), Coverage.COVERAGE_UNKNOWN));
    }

    @Test
    void 덮임_규칙이_꺼져_있으면_둘째가_창_밖이어도_덮임을_모른다() {
        StopList list = source(snapshotOf(RECEIVED.plusSeconds(600), prediction("a", 1, 300, null),
            prediction("b", 2, 1500, null)), visits(), CoverageRule.NONE).arrivalsAt(PUBLIC_ROUTE, STATION, 5, NOW);

        assertThat(((Queried) list).coverage()).isEqualTo(Coverage.COVERAGE_UNKNOWN);
    }

    @Test
    void 덮임_규칙을_켜면_둘째_도착이_창_밖일_때_창을_덮는다() {
        ArrivalStopSource source = source(snapshotOf(RECEIVED.plusSeconds(600), prediction("a", 1, 300, null),
            prediction("b", 2, 1500, null)), visits(), CoverageRule.SECOND_BEYOND_WINDOW);

        assertThat(((Queried) source.arrivalsAt(PUBLIC_ROUTE, STATION, 5, NOW)).coverage())
            .isEqualTo(Coverage.COVERS_WINDOW);
    }

    @Test
    void 덮임_규칙을_켜도_둘째_도착이_창_안이면_덮임을_모른다() {
        ArrivalStopSource source = source(snapshotOf(RECEIVED.plusSeconds(600), prediction("a", 1, 300, null),
            prediction("b", 2, 600, null)), visits(), CoverageRule.SECOND_BEYOND_WINDOW);

        assertThat(((Queried) source.arrivalsAt(PUBLIC_ROUTE, STATION, 5, NOW)).coverage())
            .isEqualTo(Coverage.COVERAGE_UNKNOWN);
    }

    @Test
    void 덮임_규칙을_켜도_예측이_한_대뿐이면_덮임을_모른다() {
        ArrivalStopSource source = source(snapshotOf(RECEIVED.plusSeconds(600), prediction("a", 1, 300, null)),
            visits(), CoverageRule.SECOND_BEYOND_WINDOW);

        assertThat(((Queried) source.arrivalsAt(PUBLIC_ROUTE, STATION, 5, NOW)).coverage())
            .isEqualTo(Coverage.COVERAGE_UNKNOWN);
    }

    private static ArrivalStopSource source(ArrivalSnapshot snapshot, List<ArrivalVisit> visits, CoverageRule rule) {
        ArrivalReadRepository repository = new ArrivalReadRepository() {
            @Override
            public List<ArrivalVisit> visits(String stationId) {
                return visits;
            }

            @Override
            public Optional<ArrivalSnapshot> latest(String stationId) {
                return Optional.ofNullable(snapshot);
            }
        };
        return new ArrivalStopSource(repository, rule, ActionFixture.config(15, 8));
    }

    private static List<ArrivalVisit> visits() {
        return List.of(new ArrivalVisit(PUBLIC_ROUTE, SOURCE_ROUTE, 7, 5, "UP", RECEIVED.minusSeconds(3600)));
    }

    private static ArrivalSnapshot snapshotOf(Instant staleAt, Prediction... predictions) {
        RouteArrival route = new RouteArrival(SOURCE_ROUTE, "9300", 5, null, null, null, null, List.of(predictions));
        List<RouteArrival> routes = predictions.length == 0 ? List.of() : List.of(route);
        return new ArrivalSnapshot(UUID.fromString("00000000-0000-0000-0000-000000000001"), STATION, RECEIVED,
            RECEIVED.plusSeconds(60), staleAt, "query", routes);
    }

    private static Prediction prediction(String vehicleId, int ordinal, Integer seconds, Integer minutes) {
        return new Prediction(vehicleId, ordinal, seconds, minutes, 3, 10, null, null, null, null);
    }
}

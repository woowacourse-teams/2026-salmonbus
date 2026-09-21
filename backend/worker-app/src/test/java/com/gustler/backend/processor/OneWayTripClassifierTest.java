package com.gustler.backend.processor;

import static com.gustler.backend.processor.OneWayTripClassifier.classify;
import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.processor.OneWayTripClassifier.Boundary;
import com.gustler.backend.processor.OneWayTripClassifier.Decision;
import com.gustler.backend.processor.OneWayTripClassifier.Observation;
import com.gustler.backend.processor.OneWayTripClassifier.Previous;
import com.gustler.backend.processor.OneWayTripClassifier.Route;
import com.gustler.backend.processor.OneWayTripClassifier.Start;
import com.gustler.backend.processor.OneWayTripClassifier.Status;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OneWayTripClassifierTest {
    private static final Instant T = Instant.parse("2026-09-21T00:00:00Z");
    // 합성 입력의 60초는 운영 권장 기준이 아니다.
    private static final Route ROUTE = new Route(1, 20, 10, Duration.ofSeconds(60));
    private static final Route GBIS_ONLY = new Route(1, 20, 10, null);
    private Observation row(long id, int stop, int state, int seats) {
        return new Observation(id, "bus", T.plusSeconds(id), stop, state, seats);
    }
    private Previous previous(Observation row, long trip, Status status) {
        return new Previous(row, trip, status);
    }

    @Test
    void 회차지_도착_후_출발하면_새_편도를_ELIGIBLE로_판정한다() {
        // given
        var before = previous(row(1, 10, 1, 40), 1, Status.EXCLUDED);
        var current = row(2, 10, 2, 44);

        // when
        var actual = classify(ROUTE, before, current);

        // then
        assertThat(actual).isEqualTo(new Decision(2, Status.ELIGIBLE, Boundary.DEPARTURE));
    }

    @Test
    void 회차지에_도착해도_이전_편도의_EXCLUDED_상태를_유지한다() {
        // given
        var before = previous(row(1, 9, 2, 40), 1, Status.EXCLUDED);
        var current = row(2, 10, 1, 44);

        // when
        var actual = classify(ROUTE, before, current);

        // then
        assertThat(actual.tripId()).isEqualTo(1);
        assertThat(actual.status()).isEqualTo(Status.EXCLUDED);
    }

    @Test
    void 같은_회차지의_출발_관측이_연속되면_같은_편도_ID를_반환한다() {
        // given
        var before = previous(row(1, 10, 2, 44), 1, Status.ELIGIBLE);
        var current = row(2, 10, 2, 44);

        // when
        var actual = classify(ROUTE, before, current);

        // then
        assertThat(actual.tripId()).isEqualTo(1);
    }

    @Test
    void 회차지_출발_후_같은_순번의_교차로통과_관측은_같은_편도_ID를_반환한다() {
        // given
        var before = previous(row(1, 10, 2, 44), 1, Status.ELIGIBLE);
        var current = row(2, 10, 0, 43);

        // when
        var actual = classify(ROUTE, before, current);

        // then
        assertThat(actual.tripId()).isEqualTo(1);
    }

    @Test
    void 직전_관측_없이_기점과_회차지가_아닌_정류소에서_관측되면_BOUNDARY_UNCONFIRMED를_반환한다() {
        // given
        var current = row(1, 5, 2, 40);

        // when
        var actual = classify(ROUTE, null, current);

        // then
        assertThat(actual.status()).isEqualTo(Status.BOUNDARY_UNCONFIRMED);
    }

    @Test
    void 경계_미확정_편도에서_순번이_증가해도_BOUNDARY_UNCONFIRMED를_유지한다() {
        // given
        var before = previous(row(1, 5, 2, 40), 1, Status.BOUNDARY_UNCONFIRMED);
        var current = row(2, 6, 2, 42);

        // when
        var actual = classify(ROUTE, before, current);

        // then
        assertThat(actual.status()).isEqualTo(Status.BOUNDARY_UNCONFIRMED);
        assertThat(actual.tripId()).isEqualTo(1);
    }

    @Test
    void 관측_간격이_설정값_이내이고_회차지_이전에서_이후로_이동하면_새_편도를_시작한다() {
        // given
        var before = previous(row(1, 9, 2, 40), 1, Status.EXCLUDED);
        var current = row(2, 11, 0, 44);

        // when
        var actual = classify(ROUTE, before, current);

        // then
        assertThat(actual).isEqualTo(new Decision(2, Status.ELIGIBLE, Boundary.DIRECTION_CHANGE));
    }

    @Test
    void 같은_편도의_잔여석이_70이면_ELIGIBLE이고_71이면_EXCLUDED다() {
        // given
        var before = previous(row(1, 5, 2, 40), 1, Status.ELIGIBLE);
        var atMaximum = row(2, 6, 2, 70);
        var aboveMaximum = row(2, 6, 2, 71);

        // when
        var allowed = classify(ROUTE, before, atMaximum);
        var excluded = classify(ROUTE, before, aboveMaximum);

        // then
        assertThat(allowed.status()).isEqualTo(Status.ELIGIBLE);
        assertThat(excluded).isEqualTo(new Decision(1, Status.EXCLUDED, Boundary.CONTINUATION));
    }

    @Test
    void 같은_편도에서_잔여석이_2에서_41로_증가해도_ELIGIBLE을_유지한다() {
        // given
        var before = previous(row(1, 5, 2, 2), 1, Status.ELIGIBLE);
        var current = row(2, 6, 2, 41);

        // when
        var actual = classify(ROUTE, before, current);

        // then
        assertThat(actual.status()).isEqualTo(Status.ELIGIBLE);
    }

    @Test
    void 제외된_편도의_잔여석이_82에서_40으로_감소해도_EXCLUDED를_유지한다() {
        // given
        var before = previous(row(1, 5, 2, 82), 1, Status.EXCLUDED);
        var current = row(2, 6, 2, 40);

        // when
        var actual = classify(ROUTE, before, current);

        // then
        assertThat(actual.status()).isEqualTo(Status.EXCLUDED);
    }

    @Test
    void 기점에서_출발해도_잔여석이_82이면_새_편도를_EXCLUDED로_판정한다() {
        // given
        var current = row(1, 1, 2, 82);

        // when
        var actual = classify(ROUTE, null, current);

        // then
        assertThat(actual.status()).isEqualTo(Status.EXCLUDED);
    }

    @Test
    void 관측_간격이_설정값을_초과하면_순번이_증가해도_BOUNDARY_UNCONFIRMED를_반환한다() {
        // given
        var before = previous(row(1, 5, 2, 40), 1, Status.EXCLUDED);
        var current = row(100, 6, 2, 44);

        // when
        var actual = classify(ROUTE, before, current);

        // then
        assertThat(actual).isEqualTo(new Decision(100, Status.BOUNDARY_UNCONFIRMED, Boundary.UNCONFIRMED));
    }

    @Test
    void 최대_관측_간격이_미설정이고_같은_방향으로_순번이_증가하면_이전_편도에_연결한다() {
        // given
        var before = previous(row(1, 5, 2, 40), 1, Status.ELIGIBLE);
        var current = row(2, 6, 2, 42);

        // when
        var actual = classify(GBIS_ONLY, before, current);

        // then
        assertThat(actual).isEqualTo(new Decision(1, Status.ELIGIBLE, Boundary.CONTINUATION));
    }

    @Test
    void 같은_방향에서_정류소_순번이_감소하면_BOUNDARY_UNCONFIRMED를_반환한다() {
        // given
        var before = previous(row(1, 6, 2, 40), 1, Status.ELIGIBLE);
        var current = row(2, 5, 2, 42);

        // when
        var actual = classify(ROUTE, before, current);

        // then
        assertThat(actual.status()).isEqualTo(Status.BOUNDARY_UNCONFIRMED);
    }

    @Test
    void 복귀_방향의_차량이_기점에_도착하면_이전_편도_ID와_ELIGIBLE을_유지한다() {
        // given
        var before = previous(row(1, 20, 2, 40), 1, Status.ELIGIBLE);
        var current = row(2, 1, 1, 42);

        // when
        var actual = classify(ROUTE, before, current);

        // then
        assertThat(actual.tripId()).isEqualTo(1);
        assertThat(actual.status()).isEqualTo(Status.ELIGIBLE);
    }

    @Test
    void 차량_ID가_없으면_기점_출발이어도_BOUNDARY_UNCONFIRMED를_반환한다() {
        // given
        var current = new Observation(1, null, T, 1, 2, 44);

        // when
        var actual = classify(ROUTE, null, current);

        // then
        assertThat(actual.status()).isEqualTo(Status.BOUNDARY_UNCONFIRMED);
    }

    @Test
    void 회차지_출발_후_교차로통과와_출발_상태가_반복되어도_같은_편도의_EXCLUDED를_유지한다() {
        // given
        var before = new Previous(row(2, 10, 0, 82), 1, Status.EXCLUDED, new Start(10, 2));
        var current = row(3, 10, 2, 44);

        // when
        var actual = classify(ROUTE, before, current);

        // then
        assertThat(actual.tripId()).isEqualTo(1);
        assertThat(actual.status()).isEqualTo(Status.EXCLUDED);
    }

    @Test
    void 최대_관측_간격이_미설정이어도_기점_출발은_새_편도를_ELIGIBLE로_판정한다() {
        // given
        var current = row(1, 1, 2, 44);

        // when
        var actual = classify(GBIS_ONLY, null, current);

        // then
        assertThat(actual).isEqualTo(new Decision(1, Status.ELIGIBLE, Boundary.DEPARTURE));
    }

    @Test
    void 회차지_출발_후_같은_순번에_도착_상태가_다시_나타나도_같은_편도_ID를_반환한다() {
        // given
        var before = new Previous(row(2, 10, 0, 82), 1, Status.EXCLUDED, new Start(10, 2));
        var current = row(3, 10, 1, 44);

        // when
        var actual = classify(ROUTE, before, current);

        // then
        assertThat(actual.tripId()).isEqualTo(1);
    }

    @Test
    void 최대_관측_간격이_미설정이면_72석을_발견한_편도는_회차지_도착까지_제외하고_다음_출발은_ELIGIBLE이다() {
        // given
        var departure = row(1, 1, 2, 2);
        var increased = row(2, 5, 2, 41);
        var aboveRange = row(3, 9, 2, 72);
        var arrival = row(4, 10, 1, 44);
        var nextDeparture = row(5, 10, 2, 44);

        // when
        var start = classify(GBIS_ONLY, null, departure);
        var increase = classify(GBIS_ONLY, previous(departure, start.tripId(), start.status()), increased);
        var excluded = classify(GBIS_ONLY, previous(increased, increase.tripId(), increase.status()), aboveRange);
        var arrived = classify(GBIS_ONLY, previous(aboveRange, excluded.tripId(), excluded.status()), arrival);
        var nextTrip = classify(GBIS_ONLY, previous(arrival, arrived.tripId(), arrived.status()), nextDeparture);

        // then
        assertThat(increase).isEqualTo(new Decision(1, Status.ELIGIBLE, Boundary.CONTINUATION));
        assertThat(excluded).isEqualTo(new Decision(1, Status.EXCLUDED, Boundary.CONTINUATION));
        assertThat(arrived).isEqualTo(new Decision(1, Status.EXCLUDED, Boundary.CONTINUATION));
        assertThat(nextTrip).isEqualTo(new Decision(5, Status.ELIGIBLE, Boundary.DEPARTURE));
    }

    @Test
    void 최대_관측_간격이_미설정이어도_회차지_이전에서_이후로_이동하면_새_편도를_시작한다() {
        // given
        var before = previous(row(1, 9, 2, 82), 1, Status.EXCLUDED);
        var current = row(2, 11, 0, 44);

        // when
        var actual = classify(GBIS_ONLY, before, current);

        // then
        assertThat(actual).isEqualTo(new Decision(2, Status.ELIGIBLE, Boundary.DIRECTION_CHANGE));
    }

    @Test
    void 최대_관측_간격이_미설정이어도_같은_회차지의_출발_상태가_반복되면_EXCLUDED를_유지한다() {
        // given
        var before = new Previous(row(2, 10, 1, 82), 1, Status.EXCLUDED, new Start(10, 2));
        var current = row(3, 10, 2, 44);

        // when
        var actual = classify(GBIS_ONLY, before, current);

        // then
        assertThat(actual).isEqualTo(new Decision(1, Status.EXCLUDED, Boundary.CONTINUATION));
    }

    @Test
    void 최대_관측_간격이_미설정이어도_중간_정류소의_첫_관측과_순번_감소는_BOUNDARY_UNCONFIRMED다() {
        // given
        var firstObservation = row(1, 5, 2, 44);
        var before = previous(row(1, 6, 2, 44), 1, Status.ELIGIBLE);
        var reversed = row(2, 5, 2, 44);

        // when
        var first = classify(GBIS_ONLY, null, firstObservation);
        var backward = classify(GBIS_ONLY, before, reversed);

        // then
        assertThat(first.status()).isEqualTo(Status.BOUNDARY_UNCONFIRMED);
        assertThat(backward.status()).isEqualTo(Status.BOUNDARY_UNCONFIRMED);
    }

    @Test
    void 관측_간격이_설정값을_초과해도_회차지를_넘으면_새_편도를_시작한다() {
        // given
        var before = previous(row(1, 9, 2, 82), 1, Status.EXCLUDED);
        var current = row(100, 11, 0, 44);

        // when
        var actual = classify(ROUTE, before, current);

        // then
        assertThat(actual).isEqualTo(new Decision(100, Status.ELIGIBLE, Boundary.DIRECTION_CHANGE));
    }

    @Test
    void 관측_간격이_설정값을_초과해도_회차지_출발은_새_편도를_ELIGIBLE로_판정한다() {
        // given
        var before = previous(row(1, 9, 2, 82), 1, Status.EXCLUDED);
        var current = row(100, 10, 2, 44);

        // when
        var actual = classify(ROUTE, before, current);

        // then
        assertThat(actual).isEqualTo(new Decision(100, Status.ELIGIBLE, Boundary.DEPARTURE));
    }
    @Test
    void 관측_간격이_설정값을_초과해도_종점_직전에서_기점_다음으로_이동하면_새_편도를_시작한다() {
        // given
        var before = previous(row(1, 19, 2, 82), 1, Status.EXCLUDED);
        var current = row(100, 2, 0, 44);

        // when
        var actual = classify(ROUTE, before, current);

        // then
        assertThat(actual).isEqualTo(new Decision(100, Status.ELIGIBLE, Boundary.DIRECTION_CHANGE));
    }

}

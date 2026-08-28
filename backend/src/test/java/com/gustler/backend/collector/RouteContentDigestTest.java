package com.gustler.backend.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RouteContentDigestTest {

    private static final int TURN_SEQUENCE = 2;
    private static final String STOP_205000217 = "205000217";
    private static final String STOP_208000069 = "208000069";
    private static final String STOP_NAME_범계역 = "범계역";
    private static final String STOP_NAME_안양역 = "안양역";
    private static final String HEXADECIMAL_64 = "^[0-9a-f]{64}$";

    @Test
    void 정류소_목록이_같으면_같은_해시가_나온다() {
        // when
        RouteContentDigest actual = RouteContentDigest.of(threeStops());

        // then
        assertThat(actual).isEqualTo(RouteContentDigest.of(threeStops()));
    }

    @Test
    void 정류소_하나의_순번이_바뀌면_다른_해시가_나온다() {
        // given
        RouteStops moved = RouteStops.from(TURN_SEQUENCE, List.of(
            new UpstreamRouteStop(1, STOP_205000217, STOP_NAME_범계역),
            new UpstreamRouteStop(2, STOP_208000069, STOP_NAME_안양역),
            new UpstreamRouteStop(4, STOP_205000217, STOP_NAME_범계역)
        ));

        // when
        RouteContentDigest actual = RouteContentDigest.of(moved);

        // then
        assertThat(actual).isNotEqualTo(RouteContentDigest.of(threeStops()));
    }

    @Test
    void 정류소_이름이_바뀌면_다른_해시가_나온다() {
        // given
        RouteStops renamed = RouteStops.from(TURN_SEQUENCE, List.of(
            new UpstreamRouteStop(1, STOP_205000217, STOP_NAME_범계역),
            new UpstreamRouteStop(2, STOP_208000069, "안양역(경유)"),
            new UpstreamRouteStop(3, STOP_205000217, STOP_NAME_범계역)
        ));

        // when
        RouteContentDigest actual = RouteContentDigest.of(renamed);

        // then
        assertThat(actual).isNotEqualTo(RouteContentDigest.of(threeStops()));
    }

    @Test
    void 회차_순번이_바뀌면_다른_해시가_나온다() {
        // given
        RouteStops turnedElsewhere = RouteStops.from(1, List.of(
            new UpstreamRouteStop(1, STOP_205000217, STOP_NAME_범계역),
            new UpstreamRouteStop(2, STOP_208000069, STOP_NAME_안양역),
            new UpstreamRouteStop(3, STOP_205000217, STOP_NAME_범계역)
        ));

        // when
        RouteContentDigest actual = RouteContentDigest.of(turnedElsewhere);

        // then
        assertThat(actual).isNotEqualTo(RouteContentDigest.of(threeStops()));
    }

    @Test
    void 판본_해시는_16진수_64글자다() {
        // when
        RouteContentDigest actual = RouteContentDigest.of(threeStops());

        // then
        assertThat(actual.value()).matches(HEXADECIMAL_64);
    }

    @Test
    void 정류소_이름에_구분자가_섞여도_정류소가_하나인_노선과_둘인_노선은_해시가_다르다() {
        // given
        RouteStops oneStopWithSeparatorInName = RouteStops.from(1, List.of(
            new UpstreamRouteStop(1, STOP_205000217, "범계역\n2|208000069|안양역")));
        RouteStops twoStops = RouteStops.from(1, List.of(
            new UpstreamRouteStop(1, STOP_205000217, STOP_NAME_범계역),
            new UpstreamRouteStop(2, STOP_208000069, STOP_NAME_안양역)));

        // when
        RouteContentDigest actual = RouteContentDigest.of(oneStopWithSeparatorInName);

        // then
        assertThat(actual).isNotEqualTo(RouteContentDigest.of(twoStops));
    }

    private RouteStops threeStops() {
        return RouteStops.from(TURN_SEQUENCE, List.of(
            new UpstreamRouteStop(1, STOP_205000217, STOP_NAME_범계역),
            new UpstreamRouteStop(2, STOP_208000069, STOP_NAME_안양역),
            new UpstreamRouteStop(3, STOP_205000217, STOP_NAME_범계역)
        ));
    }
}

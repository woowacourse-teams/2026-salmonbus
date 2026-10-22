package com.gustler.backend.routecatalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Set;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;

class RouteHeadwaysTest {
    private static final RouteHeadways STORED = new RouteHeadways(
        new HeadwayRange(10, 20), new HeadwayRange(12, 25), new HeadwayRange(15, 30), new HeadwayRange(16, 35));

    @Test
    void 읽지_못한_요일은_저장된_값을_유지하고_읽은_요일과_없는_요일은_새로_받은_대로_둔다() {
        // given
        RouteHeadways incoming = new RouteHeadways(
            null, new HeadwayRange(8, 18), null, null, Set.of(ServiceDay.WEEKDAY, ServiceDay.HOLIDAY));

        // when
        RouteHeadways actual = incoming.keepingUnreadableDaysFrom(STORED);

        // then
        assertThat(actual).isEqualTo(new RouteHeadways(
            new HeadwayRange(10, 20), new HeadwayRange(8, 18), null, new HeadwayRange(16, 35)));
    }

    @Test
    void 저장된_값이_없으면_읽지_못한_요일은_비운다() {
        // given
        RouteHeadways incoming = new RouteHeadways(
            null, new HeadwayRange(8, 18), null, null, Set.of(ServiceDay.WEEKDAY));

        // when
        RouteHeadways actual = incoming.keepingUnreadableDaysFrom(null);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(actual).isEqualTo(new RouteHeadways(null, new HeadwayRange(8, 18), null, null));
            softly.assertThat(actual.hasUnreadableDays()).isFalse();
        }
    }

    @Test
    void 읽지_못한_요일이_없으면_새로_받은_값을_그대로_쓴다() {
        // given
        RouteHeadways incoming = RouteHeadways.none();

        // when
        RouteHeadways actual = incoming.keepingUnreadableDaysFrom(STORED);

        // then
        assertThat(actual).isEqualTo(RouteHeadways.none());
    }

    @Test
    void 읽지_못한_요일에_배차간격이_있으면_거부한다() {
        // when
        Throwable caughtFailure = catchThrowable(() -> new RouteHeadways(
            new HeadwayRange(10, 20), null, null, null, Set.of(ServiceDay.WEEKDAY)));

        // then
        assertThat(caughtFailure)
            .isNotNull()
            .isInstanceOf(IllegalArgumentException.class);
    }
}

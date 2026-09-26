package com.gustler.backend.routecatalog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteTest {

    private static final OffsetDateTime OPENED = OffsetDateTime.parse("2026-09-01T09:00:00+09:00");
    private static final OffsetDateTime CHANGED = OPENED.plusDays(1);
    private static final RouteTimetable TIMETABLE = new RouteTimetable("05:00", "22:00", "05:30", "22:30");
    private static final RouteTimetable LATER_LAST_BUS = new RouteTimetable("05:00", "23:00", "05:30", "22:30");

    @Test
    void 종료된_버전은_노선과_시간표가_같아도_현재_버전으로_반환하지_않는다() {
        Route route = new Route(1L, new RouteVersion(10L, OPENED, CHANGED, content(stops())));

        assertThatThrownBy(() -> route.accept(stops(), TIMETABLE, CHANGED.plusDays(1)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 종료된_버전의_시간표는_수정하지_않는다() {
        RouteVersion closed = new RouteVersion(10L, OPENED, CHANGED, content(stops()));
        Route route = new Route(1L, closed);

        assertThatThrownBy(() -> route.accept(stops(), LATER_LAST_BUS, CHANGED.plusDays(1)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(route.currentVersion()).isEqualTo(closed);
    }

    @Test
    void 정류장이_변경되면_종료와_신규_버전의_시각을_같게_정한다() {
        Route route = new Route(1L, new RouteVersion(10L, OPENED, null, content(stops())));
        RouteStops incoming = new RouteStops(null, List.of(
            new RouteStop(1, "100", "기점", StopDirection.UP, true),
            new RouteStop(2, "300", "새 종점", StopDirection.UP, true)));

        route.accept(incoming, TIMETABLE, CHANGED);

        assertThat(route.closedVersion().orElseThrow().validTo()).isEqualTo(CHANGED);
        assertThat(route.currentVersion().validFrom()).isEqualTo(CHANGED);
        assertThat(route.currentVersion().id()).isNull();
        assertThat(route.newVersionStops()).isEqualTo(incoming);
    }

    @Test
    void 시간표만_변경되면_버전_ID와_시작_시각을_유지한다() {
        Route route = new Route(1L, new RouteVersion(10L, OPENED, null, content(stops())));

        route.accept(stops(), LATER_LAST_BUS, CHANGED);

        assertThat(route.currentVersion().id()).isEqualTo(10L);
        assertThat(route.currentVersion().validFrom()).isEqualTo(OPENED);
        assertThat(route.currentVersion().content().timetable()).isEqualTo(LATER_LAST_BUS);
        assertThat(route.closedVersion()).isEmpty();
    }

    @Test
    void 현재_버전의_시작_시각에_버전을_교체하지_않는다() {
        RouteVersion current = new RouteVersion(10L, OPENED, null, content(stops()));

        assertThatThrownBy(() -> current.closeAt(OPENED)).isInstanceOf(IllegalArgumentException.class);
        assertThat(current.validTo()).isNull();
    }

    private static RouteVersionContent content(RouteStops stops) {
        return RouteVersionContent.of(stops, TIMETABLE);
    }

    private static RouteStops stops() {
        return new RouteStops(null, List.of(
            new RouteStop(1, "100", "기점", StopDirection.UP, true),
            new RouteStop(2, "200", "종점", StopDirection.UP, true)));
    }
}

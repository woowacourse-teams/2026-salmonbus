package com.gustler.backend.worker.station;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import com.gustler.backend.stations.api.FillStationTiles;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class StationFillSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-11-06T07:00:00Z");

    private final FillStationTiles fill = mock(FillStationTiles.class);
    private final Clock clock = mock(Clock.class);
    private final StationFillScheduler scheduler = new StationFillScheduler(
        fill,
        mock(DataSource.class),
        clock,
        Duration.ofSeconds(5)
    );

    @Test
    void 대기열이_비면_유휴_간격_동안_다시_확인하지_않는다() {
        // given
        given(fill.fillNext()).willReturn(FillStationTiles.Step.NOTHING_TO_DO);
        given(clock.instant()).willReturn(NOW, NOW.plusMillis(200), NOW.plusMillis(4800));

        // when
        scheduler.step();
        scheduler.step();
        scheduler.step();

        // then
        then(fill).should(times(1)).fillNext();
    }

    @Test
    void 유휴_간격이_지나면_다시_확인한다() {
        // given
        given(fill.fillNext()).willReturn(FillStationTiles.Step.NOTHING_TO_DO);
        given(clock.instant()).willReturn(NOW, NOW.plusSeconds(5));

        // when
        scheduler.step();
        scheduler.step();

        // then
        then(fill).should(times(2)).fillNext();
    }

    @Test
    void 새_접수로_깨우면_유휴_간격을_기다리지_않는다() {
        // given
        given(fill.fillNext()).willReturn(FillStationTiles.Step.NOTHING_TO_DO);
        given(clock.instant()).willReturn(NOW, NOW.plusMillis(200));
        scheduler.step();

        // when
        scheduler.wake();
        scheduler.step();

        // then
        then(fill).should(times(2)).fillNext();
    }

    @Test
    void 처리할_일이_있으면_매_차례_채운다() {
        // given
        given(fill.fillNext()).willReturn(FillStationTiles.Step.PROBED);
        given(clock.instant()).willReturn(NOW, NOW.plusMillis(200));

        // when
        scheduler.step();
        scheduler.step();

        // then
        then(fill).should(times(2)).fillNext();
    }
}

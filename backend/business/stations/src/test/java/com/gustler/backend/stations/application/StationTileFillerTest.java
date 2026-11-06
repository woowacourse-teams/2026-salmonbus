package com.gustler.backend.stations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.gustler.backend.quota.api.StationCallQuota;
import com.gustler.backend.station.contract.StationTileId;
import com.gustler.backend.stations.api.FillStationTiles;
import com.gustler.backend.stations.api.StationFillPolicy;
import com.gustler.backend.stations.domain.StationAroundSource;
import com.gustler.backend.stations.domain.StationTileStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StationTileFillerTest {
    private static final Instant NOW = Instant.parse("2026-11-06T07:00:00Z");
    private static final StationTileStore.TilePass PASS = new StationTileStore.TilePass(
        StationTileId.of(127.11, 37.39),
        NOW,
        3
    );

    private final StationTileStore store = mock(StationTileStore.class);
    private final StationCallQuota quota = mock(StationCallQuota.class);

    @BeforeEach
    void readyOnePass() {
        given(store.next(any(), any())).willReturn(Optional.of(PASS));
        given(quota.reserve(anyString(), any(), anyInt())).willReturn(true);
    }

    @Test
    void 원천_결과_코드_오류는_상한_전이면_다시_시도하도록_남긴다() {
        // given
        given(store.fail(eq(PASS), eq("UPSTREAM_RESULT_1"), any())).willReturn(1);
        StationTileFiller filler = filler(new StationAroundSource.Failed("UPSTREAM_RESULT_1", null));

        // when
        FillStationTiles.Step step = filler.fillNext();

        // then
        assertThat(step).isEqualTo(FillStationTiles.Step.FAILED);
        then(store).should(never()).giveUp(any(), anyString(), any());
    }

    @Test
    void 잘못된_정류장_행은_바로_접수를_거둔다() {
        // given
        StationTileFiller filler = filler(new StationAroundSource.Failed("INVALID_STATION_ROW", null));

        // when
        filler.fillNext();

        // then
        then(store).should().giveUp(eq(PASS), eq("INVALID_STATION_ROW"), any());
    }

    @Test
    void 연속_실패가_상한에_닿으면_접수를_거둔다() {
        // given
        given(store.fail(eq(PASS), eq("UPSTREAM_ERROR"), any())).willReturn(5);
        StationTileFiller filler = filler(new StationAroundSource.Failed("UPSTREAM_ERROR", null));

        // when
        filler.fillNext();

        // then
        then(store).should().giveUp(eq(PASS), eq("UPSTREAM_ERROR"), any());
    }

    @Test
    void 연속_실패가_상한_전이면_다시_시도하도록_남긴다() {
        // given
        given(store.fail(eq(PASS), eq("UPSTREAM_ERROR"), any())).willReturn(4);
        StationTileFiller filler = filler(new StationAroundSource.Failed("UPSTREAM_ERROR", null));

        // when
        FillStationTiles.Step step = filler.fillNext();

        // then
        assertThat(step).isEqualTo(FillStationTiles.Step.FAILED);
        then(store).should(never()).giveUp(any(), anyString(), any());
    }

    private StationTileFiller filler(StationAroundSource.Result result) {
        return new StationTileFiller(
            store,
            (longitude, latitude, keyAlias) -> result,
            policy(),
            Clock.fixed(NOW, ZoneOffset.UTC),
            quota
        );
    }

    private static StationFillPolicy policy() {
        return new StationFillPolicy(
            "a", 8000, 0, Duration.ofSeconds(65), Duration.ofMinutes(10), Duration.ofDays(30),
            Duration.ofSeconds(40), Duration.ofSeconds(300), 5, Duration.ofHours(1)
        );
    }
}

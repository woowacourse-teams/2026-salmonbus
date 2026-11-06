package com.gustler.backend.api.station.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.gustler.backend.api.station.domain.StationTile;
import com.gustler.backend.station.contract.StationTileId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class StationTileQueryServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-06T07:00:00Z");

    private final StationReadRepository repository = mock(StationReadRepository.class);
    private final StationRefreshGateway refresh = mock(StationRefreshGateway.class);
    private final StationTileQueryService service = new StationTileQueryService(
        repository,
        refresh,
        Clock.fixed(NOW, ZoneOffset.UTC),
        Duration.ofDays(30)
    );

    @Test
    void 수집_범위_안의_검증되지_않은_칸은_갱신을_접수한다() {
        // given
        StationTileId inside = StationTileId.of(127.11, 37.39);
        given(repository.tile(inside)).willReturn(new StationTile(inside, true, List.of()));

        // when
        service.get(StationTileId.ZOOM, inside.x(), inside.y());

        // then
        then(refresh).should().request(inside);
    }

    @Test
    void 수집_범위_밖의_칸은_저장_자료만_돌려주고_갱신을_접수하지_않는다() {
        // given
        StationTileId outside = StationTileId.of(127.13, 35.82);
        StationTile stored = new StationTile(outside, true, List.of());
        given(repository.tile(outside)).willReturn(stored);

        // when
        StationTile tile = service.get(StationTileId.ZOOM, outside.x(), outside.y());

        // then
        assertThat(tile).isSameAs(stored);
        then(refresh).shouldHaveNoInteractions();
    }

    @Test
    void 유효기간_안에_검증한_칸은_갱신을_접수하지_않는다() {
        // given
        StationTileId inside = StationTileId.of(127.11, 37.39);
        given(repository.tile(inside)).willReturn(
            new StationTile(inside, true, List.of(), null, NOW.minus(Duration.ofDays(1)))
        );

        // when
        service.get(StationTileId.ZOOM, inside.x(), inside.y());

        // then
        then(refresh).shouldHaveNoInteractions();
    }
}

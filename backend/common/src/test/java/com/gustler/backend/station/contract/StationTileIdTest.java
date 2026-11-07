package com.gustler.backend.station.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class StationTileIdTest {

    @Test
    void 판교역_정류장은_줌_14_칸_13977_6354에_속한다() {
        // when
        StationTileId actual = StationTileId.of(127.1118833, 37.3918667);

        // then
        assertThat(actual).isEqualTo(new StationTileId(13977, 6354));
    }

    @Test
    void 칸_경계는_서쪽과_북쪽을_포함하고_동쪽과_남쪽은_뺀다() {
        // given
        StationTileId tile = new StationTileId(13977, 6354);

        // when
        StationTileId onWestNorth = StationTileId.of(tile.west(), tile.north());
        StationTileId onEast = StationTileId.of(tile.east(), tile.north());

        // then
        assertThat(onWestNorth).isEqualTo(tile);
        assertThat(onEast).isEqualTo(new StationTileId(13978, 6354));
    }

    @Test
    void 경기_서울_인천_경계_상자_밖의_칸은_정류장_지도_범위가_아니다() {
        assertThat(StationTileId.of(127.1118833, 37.3918667).inServiceArea()).isTrue();
        assertThat(StationTileId.of(129.0756, 35.1796).inServiceArea()).isFalse();
    }

    @Test
    void 칸_번호가_줌_14_범위를_벗어나면_만들지_않는다() {
        assertThatThrownBy(() -> new StationTileId(16384, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 탐색점은_12개이고_칸_가운데에_가까운_점부터_돈다() {
        // given
        StationTileId tile = new StationTileId(13977, 6354);
        final double centerX = (tile.west() + tile.east()) / 2;
        final double centerY = (tile.south() + tile.north()) / 2;

        // when
        List<StationTileProbes.Probe> probes = StationTileProbes.of(tile);

        // then
        assertThat(probes).hasSize(StationTileProbes.COUNT);
        assertThat(Math.hypot(probes.get(0).x() - centerX, probes.get(0).y() - centerY))
            .isLessThanOrEqualTo(Math.hypot(probes.get(11).x() - centerX, probes.get(11).y() - centerY));
    }
}

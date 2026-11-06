package com.gustler.backend.worker.station;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.gustler.backend.station.contract.StationTileId;
import com.gustler.backend.station.contract.StationTileRefresh;
import com.gustler.backend.stations.api.RequestStationTileRefresh;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class StationRefreshControllerTest {
    private final StationFillScheduler scheduler = mock(StationFillScheduler.class);

    @Test
    void 인증_실패는_작업을_접수하지_않는다() {
        // given
        RequestStationTileRefresh queue = mock(RequestStationTileRefresh.class);
        StationRefreshController controller = new StationRefreshController(queue, scheduler, "test-token");

        // when
        ResponseEntity<StationTileRefresh> response = controller.refresh(
            "wrong", new StationTileRefresh.Request(14, 13976, 6354)
        );

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        then(queue).shouldHaveNoInteractions();
    }

    @Test
    void 수학적으로_잘못된_칸은_작업을_접수하지_않는다() {
        // given
        RequestStationTileRefresh queue = mock(RequestStationTileRefresh.class);
        StationRefreshController controller = new StationRefreshController(queue, scheduler, "test-token");

        // when
        ResponseEntity<StationTileRefresh> response = controller.refresh(
            "test-token", new StationTileRefresh.Request(13, 13976, 6354)
        );

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        then(queue).shouldHaveNoInteractions();
    }

    @Test
    void 인증된_요청은_수집완료를_기다리지_않고_접수결과를_반환한다() {
        // given
        RequestStationTileRefresh queue = mock(RequestStationTileRefresh.class);
        StationTileId tile = new StationTileId(13976, 6354);
        given(queue.request(tile)).willReturn(new StationTileRefresh(StationTileRefresh.Status.QUEUED));
        StationRefreshController controller = new StationRefreshController(queue, scheduler, "test-token");

        // when
        ResponseEntity<StationTileRefresh> response = controller.refresh(
            "test-token", new StationTileRefresh.Request(14, tile.x(), tile.y())
        );

        // then
        assertThat(response.getBody()).isNotNull();
        assertSoftly(softly -> {
            softly.assertThat(response.getBody().status()).isEqualTo(StationTileRefresh.Status.QUEUED);
            softly.assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        });
        then(queue).should().request(tile);
        then(scheduler).should().wake();
    }

    @Test
    void 이미_진행_중인_칸에_합류하면_채우기_대기를_깨우지_않는다() {
        // given
        RequestStationTileRefresh queue = mock(RequestStationTileRefresh.class);
        StationTileId tile = new StationTileId(13976, 6354);
        given(queue.request(tile)).willReturn(new StationTileRefresh(StationTileRefresh.Status.JOINED));
        StationRefreshController controller = new StationRefreshController(queue, scheduler, "test-token");

        // when
        controller.refresh("test-token", new StationTileRefresh.Request(14, tile.x(), tile.y()));

        // then
        then(scheduler).shouldHaveNoInteractions();
    }
}

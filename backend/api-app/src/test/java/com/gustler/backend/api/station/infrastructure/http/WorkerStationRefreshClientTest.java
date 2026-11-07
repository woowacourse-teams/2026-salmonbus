package com.gustler.backend.api.station.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gustler.backend.station.contract.StationTileId;
import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class WorkerStationRefreshClientTest {
    @Test
    void worker가_느려도_목록_요청을_막지_않고_같은_칸은_합류한다() throws Exception {
        // given
        RestClient.Builder builder = RestClient.builder().baseUrl("http://worker");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1)
        );
        server.expect(requestTo("http://worker/internal/stations/refresh"))
            .andExpect(header("X-Station-Token", "test-token"))
            .andRespond(request -> {
                entered.countDown();
                awaitRelease(release);
                return withSuccess("{\"status\":\"QUEUED\"}", MediaType.APPLICATION_JSON).createResponse(request);
            });
        StationTileId tile = new StationTileId(13976, 6354);

        try (WorkerStationRefreshClient client = new WorkerStationRefreshClient(
            builder.build(), "test-token", Clock.systemUTC(), executor
        )) {
            // when
            client.request(tile);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            client.request(tile);
            release.countDown();
            executor.shutdown();

            // then
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            server.verify();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static void awaitRelease(CountDownLatch release) throws IOException {
        try {
            release.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }
}

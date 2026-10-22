package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.arrival.support.ArrivalRuntime;
import com.gustler.backend.arrival.support.ArrivalRuntime.Reply;
import com.gustler.backend.arrival.support.FakeGbisArrivalServer;
import com.gustler.backend.arrival.support.FakeGbisArrivalServer.Plan;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ArrivalFirstResultDefaultRuntimeTest {
    @Test
    @Timeout(90)
    void 기본_500ms로_첫_예측을_제공하고_계속_수집중이면_1초_재조회를_안내한다() throws Exception {
        // given
        // 대기 설정을 넘기지 않아 실행 JAR의 기본값을 검증한다.

        // when
        try (ArrivalRuntime runtime = new ArrivalRuntime(List.of(), Map.of())) {
            String fast = FakeGbisArrivalServer.STATIONS.getFirst();
            runtime.source.plan(fast, Plan.slow(Duration.ofMillis(100)));
            runtime.startWorker(false, Map.of());
            // 시작 지연 동안의 응답은 수집을 접수하지 않는다.
            // 처음으로 시작 지연이 아닌 응답이 500ms 첫 결과 대기를 거친 응답이라 그 응답을 그대로 검증한다.
            Reply first = runtime.get(fast);
            while ("STARTING".equals(first.value("/refresh/reason"))) {
                Thread.sleep(50);
                first = runtime.get(fast);
            }

            // then
            assertThat(first.httpStatus()).isEqualTo(200);
            assertThat(first.value("/status")).isEqualTo("READY");
            assertThat(first.value("/routes/0/arrivals/0/eta/status")).isEqualTo("AVAILABLE");
            assertThat(first.value("/refresh/status")).isEqualTo("IDLE");
            assertThat(runtime.get(fast).value("/snapshot/snapshotId")).isEqualTo(first.value("/snapshot/snapshotId"));
            assertThat(runtime.source.calls()).isEqualTo(1);

            String slow = FakeGbisArrivalServer.STATIONS.get(1);
            Plan held = Plan.held();
            runtime.source.plan(slow, held);
            for (int index = 0; index < 2; index++) {
                Reply collecting = runtime.get(slow);
                assertThat(collecting.value("/status")).isEqualTo("UNAVAILABLE");
                assertThat(collecting.value("/refresh/reason")).isEqualTo("COLLECTING");
                assertThat(collecting.body().path("routes")).isEmpty();
                Duration untilPoll = Duration.between(Instant.parse(collecting.value("/generatedAt")),
                    Instant.parse(collecting.value("/refresh/suggestedRefreshAt")));
                assertThat(untilPoll).isLessThanOrEqualTo(Duration.ofSeconds(1));
                assertThat(runtime.source.calls()).isEqualTo(2);
                assertThat(runtime.reservations()).isEqualTo(2);
            }
            held.release();
            assertThat(runtime.awaitValue(slow, "/status", "READY", Duration.ofSeconds(5)).httpStatus()).isEqualTo(200);
            assertThat(runtime.source.calls()).isEqualTo(2);
        }
    }
}

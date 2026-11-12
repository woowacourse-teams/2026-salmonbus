package com.gustler.backend.api.arrival.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.arrival.application.ArrivalFirstResultPolicy;
import com.gustler.backend.api.arrival.application.ArrivalRefreshDispatcher;
import com.gustler.backend.arrival.contract.ArrivalRefresh;
import com.gustler.backend.arrival.contract.ArrivalRefresh.Status;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ArrivalFirstResultConfigurationTest {
    private static final String STATION = "206000637";
    private static final Instant RECEIVED = Instant.parse("2026-11-13T03:00:00Z");
    private static final ArrivalSnapshot SNAPSHOT = new ArrivalSnapshot(UUID.randomUUID(), STATION, RECEIVED,
        RECEIVED.plusSeconds(40), RECEIVED.plusSeconds(90), "20261113120000", List.of());
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(ArrivalApiConfiguration.class)
        .withBean(Clock.class, Clock::systemUTC)
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withInitializer(app -> app.getBeanFactory()
            .setConversionService(ApplicationConversionService.getSharedInstance()));

    @Test
    void 기본은_500ms와_동시_32건이며_0ms로_비활성화할_수_있다() {
        // when
        context.run(app -> {
            ArrivalFirstResultPolicy policy = app.getBean(ArrivalFirstResultPolicy.class);

            // then
            assertThat(policy.maximumWait()).isEqualTo(Duration.ofMillis(500));
            for (int index = 0; index < 32; index++) {
                assertThat(policy.tryAcquire()).isTrue();
            }
            assertThat(policy.tryAcquire()).isFalse();
            for (int index = 0; index < 32; index++) {
                policy.release();
            }
        });
        context.withPropertyValues("arrivals.first-result-wait=0ms").run(app ->
            assertThat(app.getBean(ArrivalFirstResultPolicy.class).tryAcquire()).isFalse());
    }

    @Test
    void 갱신_요청은_Worker_갱신이_켜져_있으면_기본으로_응답과_따로_보내고_설정으로_요청_스레드에서_보낼_수_있다() {
        // given
        ApplicationContextRunner enabled = context.withPropertyValues("arrivals.enabled=true",
            "arrivals.worker-base-url=http://127.0.0.1:1", "arrivals.internal-token=test-token");

        // when
        enabled.run(app -> {
            ArrivalRefresh refresh = app.getBean(ArrivalRefreshDispatcher.class).request(STATION, SNAPSHOT);

            // then
            assertThat(refresh).isEqualTo(new ArrivalRefresh(Status.IDLE, null, SNAPSHOT.refreshAfterAt()));
        });
        enabled.withPropertyValues("arrivals.refresh-async=false").run(app -> {
            ArrivalRefresh refresh = app.getBean(ArrivalRefreshDispatcher.class).request(STATION, SNAPSHOT);

            // then
            assertThat(refresh.reason()).isEqualTo("WORKER_UNAVAILABLE");
        });
    }

    @Test
    void Worker_갱신을_꺼_두면_예전처럼_요청마다_갱신_꺼짐을_싣는다() {
        // when
        context.run(app -> {
            ArrivalRefreshDispatcher dispatcher = app.getBean(ArrivalRefreshDispatcher.class);

            // then
            for (int index = 0; index < 3; index++) {
                ArrivalRefresh refresh = dispatcher.request(STATION, SNAPSHOT);
                assertThat(refresh.status()).isEqualTo(Status.UNAVAILABLE);
                assertThat(refresh.reason()).isEqualTo("REFRESH_DISABLED");
            }
        });
    }

    @Test
    void 잘못된_대기_설정은_기동_시_거절한다() {
        // when
        for (String value : new String[]{"-1ms", "1001ms"}) {
            context.withPropertyValues("arrivals.first-result-wait=" + value).run(app ->

                // then
                assertThat(app).hasFailed());
        }
    }
}

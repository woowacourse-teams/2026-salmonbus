package com.gustler.backend.api.arrival.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.arrival.application.ArrivalFirstResultPolicy;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.convert.ApplicationConversionService;

class ArrivalFirstResultConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withUserConfiguration(ArrivalApiConfiguration.class).withBean(Clock.class, Clock::systemUTC)
        .withInitializer(app -> app.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()));

    @Test
    void 기본은_500ms와_동시_32건이며_0ms로_비활성화할_수_있다() {
        context.run(app -> {
            var policy = app.getBean(ArrivalFirstResultPolicy.class);
            assertThat(policy.maximumWait()).isEqualTo(Duration.ofMillis(500));
            for (int index = 0; index < 32; index++) assertThat(policy.tryAcquire()).isTrue();
            assertThat(policy.tryAcquire()).isFalse();
            for (int index = 0; index < 32; index++) policy.release();
        });
        context.withPropertyValues("arrivals.first-result-wait=0ms").run(app ->
            assertThat(app.getBean(ArrivalFirstResultPolicy.class).tryAcquire()).isFalse());
    }

    @Test
    void 잘못된_대기_설정은_기동_시_거절한다() {
        for (String value : new String[]{"-1ms", "1001ms"}) {
            context.withPropertyValues("arrivals.first-result-wait=" + value).run(app ->
                assertThat(app).hasFailed());
        }
    }
}

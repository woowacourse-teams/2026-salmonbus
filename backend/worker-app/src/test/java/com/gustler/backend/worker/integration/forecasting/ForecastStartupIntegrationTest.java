package com.gustler.backend.worker.integration.forecasting;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.model.LoadConfiguredModel;
import com.gustler.backend.forecasting.api.model.ModelLoadResult;
import com.gustler.backend.forecasting.domain.deployment.ForecastRuntime;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.support.TestDatabase;
import com.gustler.backend.worker.WorkerApplication;
import com.gustler.backend.worker.configuration.CollectionRuntimeConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.SmartContextLoader;

/**
 * 예보를 켠 worker를 실제로 띄워, 기동하며 모델을 올린 뒤에는 첫 수집 배치가 줍기 주기를 기다리지 않고 예보되는지 본다.
 *
 * <p>줍기 주기를 1시간으로 두어 시험 중에는 주기 줍기가 돌지 않는다. 그래서 배치가 예보되려면 기동할 때의 첫 줍기가 모델을 올린 뒤에
 * 돌아 노선 판본과 실행 줄의 짝을 기억해 두어야 한다. 모델은 실제 번들 대신, worker가 기동을 마치며 모델 적재를 요청하는 그 순간에
 * 활성이 되게 한다. 노선은 기동 전에 있어야 하므로 스키마를 만든 DB에 먼저 넣고 worker를 띄운다.
 *
 * <p>worker의 시계는 시험이 옮긴다({@link ManualClock}). 수신 뒤 착수까지의 시간이 실제로 흐른 시간에 기대지 않는다.
 */
class ForecastStartupIntegrationTest {

    /** 예보 실행이 남기를 기다리는 한도다. 넘기면 시험이 실패한다. 판정에 드는 시각은 여기서 나오지 않는다. */
    private static final Duration DEADLINE = Duration.ofSeconds(20);
    private static final ManualClock CLOCK = new ManualClock();

    private static TestDatabase database;
    private static volatile RuntimeSnapshot bundled;

    @BeforeAll
    static void 컨텍스트가_닫힌_뒤에도_남는_DB를_만든다() {
        database = TestDatabase.create();
    }

    @AfterAll
    static void 만든_DB를_지운다() {
        database.close();
    }

    @Test
    void 기동하며_모델을_올린_뒤에는_첫_수집_배치가_줍기_주기를_기다리지_않고_예보된다() throws Exception {
        // given
        JdbcClient stored = JdbcClient.create(new DriverManagerDataSource(database.jdbcUrl(), database.username(),
            database.password()));
        startWorker().close();
        OffsetDateTime validFrom = CLOCK.now().minusDays(1);
        ForecastRestartIntegrationTest.Route route = ForecastRestartIntegrationTest.insertRoute(stored, "204000057",
            validFrom);
        bundled = ForecastRestartIntegrationTest.insertDeployment(stored, List.of(route), validFrom);
        ConfigurableApplicationContext worker = startWorker();
        try {
            // when
            final long batch = ForecastRestartIntegrationTest.collect(worker, route, 1);
            final boolean forecast = waitUntil(
                () -> !ForecastRestartIntegrationTest.runsOf(stored, List.of(batch)).isEmpty());

            // then
            assertSoftly(softly -> {
                softly.assertThat(forecast).as("줍기 주기(1시간)를 기다리지 않고 예보 실행을 남겼다").isTrue();
                softly.assertThat(ForecastRestartIntegrationTest.runsOf(stored, List.of(batch)))
                    .containsExactly("PUBLISHED:true");
            });
        } finally {
            worker.close();
        }
    }

    /** {@link Worker}에 적힌 설정으로 컨텍스트를 새로 띄운다. 컨텍스트 캐시에 넣지 않으므로 닫는 것은 부른 쪽이 한다. */
    private static ConfigurableApplicationContext startWorker() throws Exception {
        MergedContextConfiguration configuration = BootstrapUtils.resolveTestContextBootstrapper(
            Worker.class
        )
            .buildMergedContextConfiguration();
        SmartContextLoader loader = (SmartContextLoader) configuration.getContextLoader();
        return (ConfigurableApplicationContext) loader.loadContext(configuration);
    }

    private static boolean waitUntil(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(DEADLINE);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    /** 띄울 worker의 설정이다. 시험 클래스가 아니라 설정을 적어 두는 자리여서 시험 메서드가 없다. */
    @SpringBootTest(classes = WorkerApplication.class, properties = {
        "forecast.enabled=true",
        "forecast.interval=1h"
    })
    @Import({ StartupModel.class, CollectionRuntimeConfiguration.class })
    private static final class Worker {

    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StartupModel {

        @Bean
        JdbcConnectionDetails externalConnectionDetails() {
            return new JdbcConnectionDetails() {
                @Override
                public String getUsername() {
                    return database.username();
                }

                @Override
                public String getPassword() {
                    return database.password();
                }

                @Override
                public String getJdbcUrl() {
                    return database.jdbcUrl();
                }
            };
        }

        @Bean
        @Primary
        LoadedAtStartup loadedAtStartup() {
            return new LoadedAtStartup();
        }

        @Bean
        @Primary
        ManualClock manualClock() {
            return CLOCK;
        }

        /** worker가 기동을 마치며 모델 적재를 요청하면 그때 모델을 활성으로 만든다. 그 전에는 활성 모델이 없다. */
        @Bean
        @Primary
        LoadConfiguredModel loadsWhenAsked(LoadedAtStartup runtime) {
            return command -> {
                runtime.selected = bundled;
                return new ModelLoadResult.Reloaded("forecast-startup-test");
            };
        }
    }

    /** 모델 적재를 요청받기 전에는 비어 있는 모델 선택이다. */
    static final class LoadedAtStartup implements ForecastRuntime {

        private volatile RuntimeSnapshot selected;

        @Override
        public Optional<RuntimeSnapshot> resolveActive() {
            return Optional.ofNullable(selected);
        }
    }
}

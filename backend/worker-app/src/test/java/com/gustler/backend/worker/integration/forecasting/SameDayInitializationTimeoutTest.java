package com.gustler.backend.worker.integration.forecasting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import com.gustler.backend.forecasting.application.evaluation.SameDayFullOutcomesInitializer;
import com.gustler.backend.forecasting.domain.evaluation.SeoulDay;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcSameDayFullOutcomesStore;
import com.gustler.backend.support.PostgresTestContainer;
import com.gustler.backend.worker.configuration.BusinessConfiguration;
import com.gustler.backend.worker.integration.forecasting.SameDayInitializationTimeoutTest.WorkerBusiness;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(classes = WorkerBusiness.class,
    webEnvironment = WebEnvironment.NONE, properties = {
        "forecast.enabled=true",
        "spring.datasource.hikari.data-source-properties.options=-c statement_timeout=5000 -c lock_timeout=2000"
    })
class SameDayInitializationTimeoutTest {
    static final Instant OBSERVED_AT = Instant.parse("2026-09-28T00:00:00Z");
    static final Instant NOW = OBSERVED_AT.plusSeconds(60);
    @Autowired
    ApplicationContext context;
    @Autowired
    DataSource dataSource;
    @Autowired
    JdbcClient jdbc;
    @MockitoSpyBean
    JdbcSameDayFullOutcomesStore countsSpy;
    long routeId;

    @BeforeEach
    void 작업자_설정으로_초기화할_노선을_준비한다() {
        jdbc.sql("TRUNCATE route, model_deployment RESTART IDENTITY CASCADE").update();
        routeId = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES ('fixture','fixture','fixture','fixture','start','end') RETURNING id
            """).query(Long.class).single();
        jdbc.sql("INSERT INTO route_data_quality(route_id) VALUES (?)").param(routeId).update();
        jdbc.sql("""
            INSERT INTO model_deployment(deployment_key,release_id,model_key,model_version,bundle_digest,
                prediction_target_version,calculation_version,supported_scope_digest,data_until,state)
            VALUES (?, 'fixture', 'seat-full-chance', '1', ?, 'SEAT_FULL_CHANCE_V1', 'fixture', ?, ?, 'ACTIVE')
            """).params(UUID.randomUUID(), "0".repeat(64), "0".repeat(64),
            OffsetDateTime.ofInstant(OBSERVED_AT, ZoneOffset.UTC)).update();
        jdbc.sql("INSERT INTO route_version(route_id,content_digest,valid_from,turn_sequence) VALUES (?,?,?,20)")
            .params(routeId, "0".repeat(64), OffsetDateTime.ofInstant(OBSERVED_AT, ZoneOffset.UTC)).update();
    }

    @Test
    void 초기화는_2초를_넘긴_조회도_25초_SQL과_30초_트랜잭션_예산_안에서_완료한다() {
        // when
        AtomicLong sourceQueryMs = new AtomicLong();
        doAnswer(call -> {
            final long started = System.nanoTime();

            // then
            assertThat(jdbc.sql("SHOW statement_timeout").query(String.class).single()).isEqualTo("25s");
            assertThat(jdbc.sql("SHOW lock_timeout").query(String.class).single()).isEqualTo("100ms");
            ConnectionHolder holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource);
            assertThat(holder.getTimeToLiveInMillis()).isGreaterThan(25_000L).isLessThanOrEqualTo(30_000L);
            // 과거 트랜잭션 제한 2초를 실제 DB 호출로 넘긴다. 운영 데이터 부하는 재현하지 않는다.
            jdbc.sql("SELECT pg_sleep(2.1)").query().singleRow();
            Object counted = call.callRealMethod();
            sourceQueryMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            return counted;
        }).when(countsSpy).countFromSource(anyLong(), anyLong(), any(), any());

        assertThat(context.getBean(SameDayFullOutcomesInitializer.class)
                .initialize(routeId, SeoulDay.containing(NOW))).isTrue();
        assertThat(sourceQueryMs.get()).isGreaterThanOrEqualTo(2_000L);
        assertThat(count("same_day_model_full_outcomes")).isEqualTo(1);
    }

    long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({PostgresTestContainer.class, BusinessConfiguration.class})
    static class WorkerBusiness {
    }
}

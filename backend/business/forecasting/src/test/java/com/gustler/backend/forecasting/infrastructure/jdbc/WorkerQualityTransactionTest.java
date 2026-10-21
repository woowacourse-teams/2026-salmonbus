package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.forecasting.application.quality.RouteDataQualityChanges;
import com.gustler.backend.forecasting.application.quality.WorkerQualityState;
import com.gustler.backend.forecasting.application.quality.WorkerQualityState.Read;
import com.gustler.backend.forecasting.application.quality.WorkerQualityTransactions;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@ForecastingIntegrationTest
@TestPropertySource(properties = "forecast.quality-memory.enabled=true")
class WorkerQualityTransactionTest {
    @Autowired
    JdbcClient jdbc;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    WorkerQualityState state;
    @Autowired
    WorkerQualityTransactions changes;
    @Autowired
    RouteDataQualityChanges qualityChanges;
    private long route;
    private long version;

    @BeforeEach
    void 품질_기록이_있는_노선을_만든다() {
        String source = "memory-" + UUID.randomUUID().toString().substring(0, 12);
        route = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES(?,'GBIS',?,'품질 변경','출발','도착') RETURNING id
            """).params(source, source).query(Long.class).single();
        version = jdbc.sql("""
            INSERT INTO route_version(route_id,content_digest,valid_from)
            VALUES(?,?,CURRENT_TIMESTAMP) RETURNING id
            """).params(route, source).query(Long.class).single();
        jdbc.sql("INSERT INTO route_data_quality(route_id) VALUES(?)").param(route).update();
    }

    @AfterEach
    void 이번_테스트의_노선을_삭제한다() {
        jdbc.sql("DELETE FROM stop_demand_rebuild_request WHERE route_version_id=?").param(version).update();
        jdbc.sql("DELETE FROM route_version WHERE id=?").param(version).update();
        jdbc.sql("DELETE FROM route_data_quality WHERE route_id=?").param(route).update();
        jdbc.sql("DELETE FROM route WHERE id=?").param(route).update();
    }

    @Test
    void 실제_품질_변경_서비스가_커밋되면_메모리의_이전_버전을_버린다() {
        // given
        Read previous = state.read(version).orElseThrow();

        // when
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            qualityChanges.vehiclesChanged(version, List.of("vehicle-A"));
            assertThat(state.read(version)).isEmpty();
        });

        // then
        assertThat(state.stillCurrent(previous)).isFalse();
        assertThat(state.read(version).orElseThrow().snapshot().revision()).isEqualTo(2);
    }

    @Test
    void 품질_변경이_커밋되면_다음_요청에서_DB의_품질_정보를_다시_읽는다() {
        // given
        Read previous = state.read(version).orElseThrow();

        // when
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            changes.changing();
            changes.changing();
            jdbc.sql("UPDATE route_data_quality SET quality_revision=2 WHERE route_id=?").param(route).update();
            assertThat(state.read(version)).isEmpty();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    assertThat(state.read(version)).isEmpty();
                }
            });
        });

        // then
        assertThat(state.stillCurrent(previous)).isFalse();
        assertThat(state.read(version).orElseThrow().snapshot().revision()).isEqualTo(2);
    }

    @Test
    void 품질_변경이_롤백되면_DB에_남은_버전을_다시_읽는다() {
        // given
        state.read(version);

        // when
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            changes.changing();
            jdbc.sql("UPDATE route_data_quality SET quality_revision=2 WHERE route_id=?").param(route).update();
            status.setRollbackOnly();
        });

        // then
        assertThat(state.read(version).orElseThrow().snapshot().revision()).isEqualTo(1);
    }

    @Test
    void 한_트랜잭션이_롤백돼도_다른_품질_변경이_진행_중이면_메모리를_사용하지_않는다() {
        // given
        TransactionTemplate inner = new TransactionTemplate(transactions);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // when
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            changes.changing();
            inner.executeWithoutResult(nested -> {
                changes.changing();
                nested.setRollbackOnly();
            });

            // then
            assertThat(state.read(version)).isEmpty();
        });
        assertThat(state.read(version)).isPresent();
    }

    @Test
    void 트랜잭션_없이_품질_변경을_등록하지_못한다() {
        // when
        Throwable caughtFailure = catchThrowable(changes::changing);

        // then
        assertThat(caughtFailure)
            .isNotNull().isInstanceOf(IllegalStateException.class);
        assertThat(state.read(version)).isPresent();
    }

    @Test
    void 다른_커밋_콜백이_실패해도_품질_정보는_다시_읽는다() {
        // given
        state.read(version);

        // when
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
            changes.changing();
            jdbc.sql("UPDATE route_data_quality SET quality_revision=2 WHERE route_id=?").param(route).update();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    throw new IllegalStateException("콜백 실패");
                }
            });
        })).isInstanceOf(IllegalStateException.class);

        // then
        assertThat(state.read(version).orElseThrow().snapshot().revision()).isEqualTo(2);
    }
}

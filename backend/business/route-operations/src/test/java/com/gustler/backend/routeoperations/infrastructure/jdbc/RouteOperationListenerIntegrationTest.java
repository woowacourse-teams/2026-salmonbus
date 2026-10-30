package com.gustler.backend.routeoperations.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.gustler.backend.routeoperations.api.RouteOperations;
import com.gustler.backend.routeoperations.configuration.RouteOperationsConfiguration;
import com.gustler.backend.support.PostgresTestContainer;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = RouteOperationListenerIntegrationTest.Config.class,
    properties = {"route-operations.enabled=true", "route-operations.notifications-enabled=false"})
class RouteOperationListenerIntegrationTest {
    private static final String ID = "228000184";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({PostgresTestContainer.class, RouteOperationsConfiguration.class})
    static class Config {
    }

    @Autowired
    DataSource dataSource;
    @Autowired
    JdbcRouteOperations database;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    PlatformTransactionManager transactions;
    RouteOperationCache cache;
    RouteOperationListener listener;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE route_operation_change,route_service_event,route_operation CASCADE").update();
        jdbc.sql("""
            INSERT INTO route_operation(source_route_id,registration_state,collection_enabled,
                service_requested,service_enabled,ever_published,reason,message,enable_after)
            VALUES (?, 'READY',true,true,true,true,'NORMAL','','2020-01-01T00:00:00Z')
            """).param(ID).update();
        cache = new RouteOperationCache();
        listener = new RouteOperationListener(dataSource, database, cache);
    }

    @AfterEach
    void stop() {
        if (listener != null) {
            listener.stop();
        }
    }

    @Test
    void 시작과_재시작에서_전체_상태를_읽고_실행_중에는_변경_알림을_반영한다() {
        listener.start();
        awaitLoaded(true);
        jdbc.sql("UPDATE route_operation SET service_requested=false,service_enabled=false WHERE source_route_id=?")
            .param(ID).update();
        awaitLoaded(false);
        listener.stop();
        assertThatThrownBy(cache::list).hasMessage("ROUTE_OPERATIONS_UNAVAILABLE");
        jdbc.sql("UPDATE route_operation SET service_requested=true,service_enabled=true WHERE source_route_id=?")
            .param(ID).update();
        listener.start();
        awaitLoaded(true);
        jdbc.sql("DELETE FROM route_operation WHERE source_route_id=?").param(ID).update();
        await().ignoreException(IllegalStateException.class).atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(cache.find(ID)).isEmpty());
    }

    @Test
    void 연결이_끊어지면_다시_구독하고_전체_상태를_복구한다() {
        listener.start();
        awaitLoaded(true);
        final int previousPid = listener.backendPid();
        assertThat(previousPid).isPositive();
        jdbc.sql("SELECT pg_terminate_backend(?)").param(previousPid).query(Boolean.class).single();
        jdbc.sql("UPDATE route_operation SET service_requested=false,service_enabled=false WHERE source_route_id=?")
            .param(ID).update();
        await().ignoreException(IllegalStateException.class).atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(listener.backendPid()).isPositive().isNotEqualTo(previousPid);
            assertThat(cache.find(ID).orElseThrow().serviceEnabled()).isFalse();
        });
    }

    @Test
    void 롤백과_시각만_바뀐_갱신은_알리지_않고_커밋된_변경만_알린다() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.execute("LISTEN " + RouteOperationListener.CHANNEL);
            try {
                PGConnection postgres = connection.unwrap(PGConnection.class);
                try (Connection writer = dataSource.getConnection(); Statement update = writer.createStatement()) {
                    writer.setAutoCommit(false);
                    update.executeUpdate("UPDATE route_operation SET service_enabled=false");
                    writer.rollback();
                }
                jdbc.sql("UPDATE route_operation SET applied_at=CURRENT_TIMESTAMP").update();
                assertThat(postgres.getNotifications(200)).isNullOrEmpty();
                jdbc.sql("UPDATE route_operation SET service_enabled=false WHERE source_route_id=?")
                    .param(ID).update();
                PGNotification[] notifications = postgres.getNotifications(2000);
                assertThat(notifications).hasSize(1);
                assertThat(notifications[0].getParameter()).isEqualTo(ID);
                jdbc.sql("TRUNCATE route_operation CASCADE").update();
                assertThat(postgres.getNotifications(2000)).hasSize(1);
            } finally {
                statement.execute("UNLISTEN " + RouteOperationListener.CHANNEL);
            }
        }
    }

    @Test
    void 캐시에_ON이_남아도_발행_직전_DB가_OFF이면_거절한다() {
        cache.replaceAll(database.list());
        CachedRouteOperations operations = new CachedRouteOperations(database, cache);
        jdbc.sql("TRUNCATE route CASCADE").update();
        final long route = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES (?,'GBIS',?,'5600','A','B') RETURNING id
            """).params(ID, ID).query(Long.class).single();
        final long version = jdbc.sql("""
            INSERT INTO route_version(route_id,content_digest,valid_from)
            VALUES (?,?,'2020-01-01T00:00:00Z') RETURNING id
            """).params(route, "a".repeat(64)).query(Long.class).single();
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        Boolean beforeOff = transaction.execute(status -> operations.allowPublication(version, Instant.now()));
        assertThat(beforeOff).isTrue();
        jdbc.sql("UPDATE route_operation SET service_requested=false,service_enabled=false WHERE source_route_id=?")
            .param(ID).update();
        assertThat(operations.required(ID).serviceEnabled()).isTrue();
        Boolean afterOff = transaction.execute(status -> operations.allowPublication(version, Instant.now()));
        assertThat(afterOff).isFalse();
    }

    @Test
    void 기본_설정에서는_캐시_구현과_알림_수신기를_자동으로_연결한다() {
        new ApplicationContextRunner()
            .withUserConfiguration(RouteOperationsConfiguration.class)
            .withPropertyValues("route-operations.enabled=true")
            .withBean(DataSource.class, () -> new DelegatingDataSource(dataSource))
            .withBean(JdbcClient.class, () -> jdbc)
            .withBean(PlatformTransactionManager.class, () -> transactions)
            .run(context -> {
                assertThat(context.getBean(RouteOperations.class)).isInstanceOf(CachedRouteOperations.class);
                RouteOperationCache configuredCache = context.getBean(RouteOperationCache.class);
                await().ignoreException(IllegalStateException.class).atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(configuredCache.find(ID)).isPresent());
            });
    }

    private void awaitLoaded(final boolean enabled) {
        await().ignoreException(IllegalStateException.class).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
            assertThat(cache.find(ID).orElseThrow().serviceEnabled()).isEqualTo(enabled));
    }
}

package com.gustler.backend.forecasting.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gustler.backend.forecasting.api.forecast.ForecastClosed;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** 정확도 집계 로그에 적는 노선·정류장 이름을 실제 DB에서 읽는지 본다. */
@ForecastingIntegrationTest
@Transactional
class ForecastAccuracyNamesTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    void 집계_로그에_노선_이름과_목표_정류장의_이름_번호_방향을_적고_없는_정류장은_정보_없음으로_적는다() {
        // given
        final long routeId = jdbc.sql("""
            INSERT INTO route (public_route_id, source_id, source_route_id, display_name, start_stop_name,
                end_stop_name)
            VALUES ('204000057', 'GBIS', '204000057', '3330', '출발', '도착') RETURNING id
            """).query(Long.class).single();
        final long versionId = jdbc.sql("""
            INSERT INTO route_version (route_id, content_digest, valid_from, turn_sequence)
            VALUES (:route, :digest, :at, 8) RETURNING id
            """).param("route", routeId).param("digest", "0".repeat(64))
            .param("at", OffsetDateTime.parse("2026-08-19T00:00+09:00")).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO route_stop (route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
            VALUES (:version, 4, 'station4', '야탑역 "광장"', 'UP', true)
            """).param("version", versionId).update();
        ForecastMonitoring monitoring = new ForecastMonitoring(new SimpleMeterRegistry(), dataSource,
            Clock.systemUTC());
        monitoring.closed(List.of(
            new ForecastClosed(
                    routeId,
                    versionId,
                    6,
                    4,
                    2,
                    10.0,
                    0.25,
                    3.0,
                    3
            ),
            new ForecastClosed(
                    routeId,
                    versionId,
                    6,
                    9,
                    5,
                    10.0,
                    0.25,
                    null,
                    null
            )));

        // when
        List<String> lines = accuracyLines(monitoring::flushAccuracy);

        // then
        assertThat(lines).containsExactly(
            "event=forecast_accuracy telemetrySchema=1 routeId=%d routeVersionId=%d stopOrder=4 routeName=\"3330\" "
                .formatted(routeId, versionId)
                + "stopName=\"야탑역 \\\"광장\\\"\" stopId=\"station4\" direction=\"UP\" modelDeploymentId=6 "
                + "distance=1-3 completed=1 maeCount=1 absoluteErrorSum=7.0 probabilityCount=1 brierSum=0.0625 "
                + "predictedFullSum=0.25 actualFullCount=0",
            "event=forecast_accuracy telemetrySchema=1 routeId=%d routeVersionId=%d stopOrder=9 routeName=\"3330\" "
                .formatted(routeId, versionId)
                + "stopName=\"정류장 정보 없음\" stopId=\"unknown\" direction=\"unknown\" modelDeploymentId=6 "
                + "distance=4-6 completed=1 maeCount=0 absoluteErrorSum=0.0 probabilityCount=0 brierSum=0.0 "
                + "predictedFullSum=0.0 actualFullCount=0");
    }

    private static List<String> accuracyLines(Runnable work) {
        Logger logger = (Logger) LoggerFactory.getLogger(ForecastMonitoring.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            work.run();
            return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        } finally {
            logger.detachAppender(logs);
        }
    }
}

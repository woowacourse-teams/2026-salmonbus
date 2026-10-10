package com.gustler.backend.forecasting.infrastructure.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.ArgumentMatchers.contains;

import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;
import java.nio.file.Files;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@ForecastingIntegrationTest
@TestPropertySource(properties = "sal175.extractor-test=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JdbcStatisticsInputExtractorTest {
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    private JdbcStatisticsInputExtractor extractor;
    private long route;
    private long source;
    @TempDir Path directory;
    private static final Instant UNTIL = Instant.parse("2026-10-09T00:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @BeforeEach
    void 완료된_정산이_있는_독립된_노선을_준비한다() {
        extractor = new JdbcStatisticsInputExtractor(jdbc, transactions);
        String key = UUID.randomUUID().toString().substring(0, 20);
        long routeId = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES(:key,'GBIS',:key,'추출 시험','출발','도착') RETURNING id
            """).param("key", key).query(Long.class).single();
        route = jdbc.sql("""
            INSERT INTO route_version(route_id,content_digest,valid_from)
            VALUES(:route,:digest,'2026-10-01T00:00:00Z') RETURNING id
            """).param("route", routeId).param("digest", "0".repeat(64)).query(Long.class).single();
        jdbc.sql("INSERT INTO route_data_quality(route_id) VALUES(?)").param(routeId).update();
        jdbc.sql("""
            INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)
            VALUES(:route,8,'stop-eight','출발','UP',true),
              (:route,9,'stop-nine','도착','UP',true),(:route,10,'stop-ten','다음','UP',true)
            """).param("route", route).update();
        long batch = jdbc.sql("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,
              requested_at,response_received_at,outcome,normalization_version,collection_strategy_version)
            VALUES(:route,'2026-10-08T23:00:00Z',1,:key,'2026-10-08T23:00:00Z','2026-10-08T23:00:00Z',
              'SUCCESS_ROWS','normalization-v1.0.0','adaptive-kst-v1.0.1') RETURNING id
            """).param("route", route).param("key", key).query(Long.class).single();
        source = jdbc.sql("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,
              vehicle_id,stop_order,stop_id,passed_stop_order,running_state,remaining_seats)
            VALUES(:batch,:route,0,'bus',8,'stop-eight',8,2,20) RETURNING id
            """).param("batch", batch).param("route", route).query(Long.class).single();
        long model = jdbc.sql("""
            INSERT INTO model_deployment(deployment_key,release_id,model_key,model_version,bundle_digest,
              prediction_target_version,calculation_version,supported_scope_digest,data_until,state)
            VALUES(:key,'extract-test','seat-full-chance','1',:digest,'SEAT_FULL_CHANCE_V1','CALCULATION_V1',
              :digest,'2026-10-01T00:00:00Z','STAGED') RETURNING id
            """).param("key", UUID.randomUUID()).param("digest", "0".repeat(64)).query(Long.class).single();
        long publication = jdbc.sql("""
            INSERT INTO forecast_publication(source_batch_id,route_version_id,model_deployment_id,
              demand_statistics_revision,quality_revision,observed_at,generated_at,published_at,prediction_count)
            VALUES(:batch,:route,:model,1,1,'2026-10-08T23:00:00Z','2026-10-08T23:00:00Z',
              '2026-10-08T23:00:00Z',2) RETURNING id
            """).param("batch", batch).param("route", route).param("model", model).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO seat_forecast(vehicle_observation_id,target_stop_order,route_version_id,stops_to_target,
              model_deployment_id,demand_statistics_revision,seat_full_chance_raw,seat_full_chance,
              expected_seats,generated_at,quality_revision,publication_id)
            SELECT :source,target,:route,target-8,:model,1,0.5,0.5,10,'2026-10-08T23:00:00Z',1,:publication
            FROM generate_series(9,10) target
            """).param("source", source).param("route", route).param("model", model).param("publication", publication).update();
        jdbc.sql("""
            INSERT INTO forecast_evaluation_result(vehicle_observation_id,target_stop_order,route_version_id,
              scoring_state,scored_at)
            VALUES(:source,9,:route,'LOST','2026-10-08T23:30:00Z'),
              (:source,10,:route,'LOST','2026-10-08T23:30:00Z')
            """).param("source", source).param("route", route).update();
    }

    @Test
    void 지정한_관측_범위의_완료_결과를_품질_판본과_함께_읽는다() {
        // given
        long before = jdbc.sql("SELECT count(*) FROM forecast_evaluation_result").query(Long.class).single();

        // when
        var result = extractor.extract(route, source - 1, source, UNTIL, ZONE, 10);

        // then
        assertThat(result.scope().rowCount()).isEqualTo(2);
        assertThat(result.scope().qualityRevision()).isEqualTo(1);
        assertThat(result.rows()).extracting(StatisticsInputRow::targetStopOrder).containsExactly(9, 10);
        assertThat(jdbc.sql("SELECT count(*) FROM forecast_evaluation_result").query(Long.class).single()).isEqualTo(before);
    }

    @Test
    void 기준_시각_이후에_완료된_자료는_이번_묶음에서_제외한다() {
        // given
        jdbc.sql("UPDATE forecast_evaluation_result SET scored_at='2026-10-10T00:00:00Z' WHERE vehicle_observation_id=? AND target_stop_order=10")
            .param(source).update();

        // when
        var result = extractor.extract(route, source - 1, source, UNTIL, ZONE, 10);

        // then
        assertThat(result.rows()).extracting(StatisticsInputRow::targetStopOrder).containsExactly(9);
    }

    @Test
    void 건수_한도를_넘으면_잘린_자료를_완료된_묶음으로_반환하지_않는다() {
        // given
        int rowLimit = 1;

        // when & then
        assertThatThrownBy(() -> extractor.extract(route, source - 1, source, UNTIL, ZONE, rowLimit))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("한도");
    }

    @Test
    void 너무_넓은_관측_범위는_DB를_읽기_전에_거부한다() {
        // given
        long outsideAllowedRange = 10001;

        // when & then
        assertThatThrownBy(() -> extractor.extract(route, 0, outsideAllowedRange, UNTIL, ZONE, 10))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("범위");
    }

    @Test
    void 정상_정산을_추출하고_다시_받은_파일만으로_통계를_계산한다() {
        // given
        long arrivalBatch = jdbc.sql("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,
              requested_at,response_received_at,outcome,normalization_version,collection_strategy_version)
            VALUES(:route,'2026-10-08T23:10:00Z',1,:key,'2026-10-08T23:10:00Z','2026-10-08T23:10:00Z',
              'SUCCESS_ROWS','normalization-v1.0.0','adaptive-kst-v1.0.1') RETURNING id
            """).param("route", route).param("key", UUID.randomUUID().toString().substring(0, 20))
            .query(Long.class).single();
        long arrival = jdbc.sql("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,
              vehicle_id,stop_order,stop_id,passed_stop_order,running_state,remaining_seats)
            SELECT :batch,route_version_id,0,vehicle_id,9,'stop-nine',9,2,5
            FROM vehicle_observation WHERE id=:source RETURNING id
            """).param("batch", arrivalBatch).param("source", source).query(Long.class).single();
        jdbc.sql("""
            UPDATE forecast_evaluation_result SET scoring_state='SETTLED',arrival_observation_id=:arrival,
              seats_on_arrival=5,arrived_at='2026-10-08T23:10:00Z',arrival_route_version_id=:route,
              arrival_vehicle_id='bus',arrival_stop_order=9,arrival_running_state=2,arrival_remaining_seats=5,
              arrival_quality_direction=0 WHERE vehicle_observation_id=:source AND target_stop_order=9
            """).param("arrival", arrival).param("route", route).param("source", source).update();

        // when
        var extracted = extractor.extract(route, source - 1, source, UNTIL, ZONE, 10);
        var bundle = StatisticsExtractWriter.write(directory, extracted, 8192);
        // 전송 경계만 메모리 저장소로 대체한다. 실제 S3 실행 결과와 구분한다.
        Map<String, byte[]> objects = new HashMap<>();
        StatisticsExtractUploader.Command storage = command -> {
            String key = command.get(command.indexOf("--key") + 1);
            try {
                if (command.contains("put-object")) {
                    objects.put(key, Files.readAllBytes(Path.of(command.get(command.indexOf("--body") + 1))));
                } else {
                    Files.write(Path.of(command.getLast()), objects.get(key));
                }
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
        };
        UUID exportId = UUID.randomUUID();
        new StatisticsExtractUploader(storage).upload(bundle, exportId);
        var received = new StatisticsExtractDownloader(storage).download(directory, exportId, bundle.manifest());
        var result = StatisticsFileCalculator.calculate(received.directory(), received.manifest().files(),
            extracted.scope(), Map.of("bus", 20), 100, 4096);

        // then
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().cell().sampleCount()).isEqualTo(1);
        assertThat(result.getFirst().cell().averageFillRate()).isEqualTo(0.75);
        assertThat(result.getFirst().cell().averageNetBoardingRate()).isEqualTo(0.75);
    }

    @Test
    void 추출_중_정산이_바뀌어도_처음_읽은_시점의_자료로_묶는다() {
        // given
        var instrumented = spy(jdbc);
        doAnswer(invocation -> {
            var concurrent = new TransactionTemplate(transactions);
            concurrent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            concurrent.executeWithoutResult(status -> jdbc.sql("""
                UPDATE forecast_evaluation_result SET scored_at='2026-10-10T00:00:00Z'
                WHERE vehicle_observation_id=:source
                """).param("source", source).update());
            return invocation.callRealMethod();
        }).when(instrumented).sql(contains("SELECT EXISTS"));

        // when
        var result = new JdbcStatisticsInputExtractor(instrumented, transactions)
            .extract(route, source - 1, source, UNTIL, ZONE, 10);

        // then
        assertThat(result.rows()).hasSize(2);
        assertThat(extractor.extract(route, source - 1, source, UNTIL, ZONE, 10).rows()).isEmpty();
    }
}

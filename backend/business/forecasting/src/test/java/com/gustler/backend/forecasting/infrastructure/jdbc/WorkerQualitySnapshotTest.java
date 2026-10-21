package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.forecasting.application.quality.WorkerQualityState.Snapshot;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@ForecastingIntegrationTest
@Transactional
class WorkerQualitySnapshotTest {
    @Autowired
    JdbcClient jdbc;
    private JdbcWorkerQualitySnapshotQuery query;
    private long route;
    private long version;

    @BeforeEach
    void 노선과_노선_버전을_만든다() {
        query = new JdbcWorkerQualitySnapshotQuery(jdbc);
        String source = "quality-" + UUID.randomUUID().toString().substring(0, 12);
        route = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES(?,'GBIS',?,'품질 조회','출발','도착') RETURNING id
            """).params(source, source).query(Long.class).single();
        version = jdbc.sql("""
            INSERT INTO route_version(route_id,content_digest,valid_from)
            VALUES(?,? ,CURRENT_TIMESTAMP) RETURNING id
            """).params(route, source).query(Long.class).single();
    }

    @Test
    void 품질_기록과_진행_중인_조사가_없으면_최초_버전과_빈_목록을_읽는다() {
        // when
        Snapshot result = query.read(version);

        // then
        assertThat(result.revision()).isEqualTo(1);
        assertThat(result.investigatingVehicles()).isEmpty();
    }

    @Test
    void 완료된_조사는_빼고_진행_중인_차량만_읽는다() {
        // given
        jdbc.sql("INSERT INTO route_data_quality(route_id,quality_revision) VALUES(?,7)")
            .param(route).update();
        investigation("vehicle-A", false);
        investigation("vehicle-B", true);

        // when
        Snapshot result = query.read(version);

        // then
        assertThat(result.revision()).isEqualTo(7);
        assertThat(result.investigatingVehicles()).containsExactly("vehicle-A");
    }

    @Test
    void 노선_전체_조사가_있으면_모든_차량이_조사_대상이다() {
        // given
        investigation("", false);

        // when
        Snapshot result = query.read(version);

        // then
        assertThat(result.investigationPending("vehicle-A")).isTrue();
        assertThat(result.investigationPending("vehicle-B")).isTrue();
    }

    @Test
    void 존재하지_않는_노선_버전을_정상_상태로_반환하지_않는다() {
        // when
        Throwable caughtFailure = catchThrowable(() -> query.read(-1));

        // then
        assertThat(caughtFailure)
            .isNotNull().isInstanceOf(EmptyResultDataAccessException.class);
    }

    private void investigation(String vehicle, final boolean completed) {
        jdbc.sql("""
            INSERT INTO trip_quality_rebuild(route_version_id,vehicle_id,until_at,completed)
            VALUES(?,?,CURRENT_TIMESTAMP,?)
            """).params(version, vehicle, completed).update();
    }
}

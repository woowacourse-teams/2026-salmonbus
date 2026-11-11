package com.gustler.backend.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.support.TestDatabase;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 새 DB의 설치와 운영 데이터가 있는 전환을 구분한다. */
class ArchiveMigrationHandoffTest {
    @Test
    void 제거할_수요_통계를_초기화하지_않고_운영_DB를_전환한다() {
        try (TestDatabase db = TestDatabase.create()) {
            DriverManagerDataSource source = new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
            Flyway.configure().dataSource(source).target("66").load().migrate();
            JdbcClient jdbc = JdbcClient.create(source);
            seed(jdbc);
            jdbc.sql("""
                CREATE FUNCTION reject_retired_statistics() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'retired statistics initialization'; END $$
                """).update();
            jdbc.sql("""
                CREATE TRIGGER reject_retired_statistics BEFORE INSERT ON stop_demand_baseline
                FOR EACH ROW EXECUTE FUNCTION reject_retired_statistics()
                """).update();

            Flyway flyway = Flyway.configure().dataSource(source).load();
            flyway.migrate();

            flyway.validate();
            assertThat(jdbc.sql("SELECT to_regclass('stop_demand_baseline') IS NULL")
                .query(Boolean.class).single()).isTrue();
            assertThat(jdbc.sql("SELECT count(*) FROM observation_batch").query(Long.class).single()).isOne();
        }
    }

    void seed(JdbcClient jdbc) {
        jdbc.sql(
            "INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name) VALUES('r','GBIS','r','3330','A','B')")
            .update();
        jdbc.sql("INSERT INTO route_version(route_id,content_digest,valid_from) VALUES(1,repeat('0',64),'2026-01-01')")
            .update();
        jdbc.sql(
            "INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,outcome,normalization_version,collection_strategy_version) VALUES(1,now(),1,'r','SUCCESS_EMPTY','n','c')")
            .update();
    }

    @Test
    void 운영_V60_스키마와_수집_이력을_보존하며_V72로_전환한다() {
        try (TestDatabase db = TestDatabase.create()) {
            DriverManagerDataSource source = new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
            Flyway.configure().dataSource(source).target("60").load().migrate();
            JdbcClient jdbc = JdbcClient.create(source);
            seed(jdbc);
            Flyway flyway = Flyway.configure().dataSource(source).target("72").load();
            flyway.migrate();
            flyway.validate();
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("72");
            assertThat(jdbc.sql("SELECT to_regclass('ix_batch_unfinished_recovery') IS NOT NULL")
                .query(Boolean.class).single()).isTrue();
            assertThat(jdbc.sql("SELECT count(*) FROM observation_batch").query(Long.class).single()).isOne();
            assertThat(jdbc.sql("SELECT count(*) FROM vehicle_capacity").query(Long.class).single()).isEqualTo(426);
            assertThat(jdbc.sql("SELECT to_regclass('route_data_quality') IS NULL").query(Boolean.class).single()).isTrue();
        }
    }

    @Test
    void 구_이관_예약이_남으면_장부를_보존하고_전환을_거부한다() {
        try (TestDatabase db = TestDatabase.create()) {
            DriverManagerDataSource source = new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
            Flyway.configure().dataSource(source).target("65").load().migrate();
            JdbcClient jdbc = JdbcClient.create(source);
            seed(jdbc);
            jdbc.sql(
                "INSERT INTO evaluation_archive_batch(id,route_version_id,quality_revision,lease_token,lease_until,row_count) VALUES(gen_random_uuid(),1,1,gen_random_uuid(),now(),1)")
                .update();
            assertThatThrownBy(() -> Flyway.configure().dataSource(source).load().migrate())
                .isInstanceOf(FlywayException.class).hasMessageContaining("기존 이관 예약");
            assertLegacySchemaIntact(jdbc);
            assertThat(jdbc.sql("SELECT count(*) FROM evaluation_archive_batch").query(Long.class).single()).isOne();
        }
    }

    @Test
    void 업무_데이터가_있는_DB에_구_Worker가_연결돼_있으면_전환하지_않는다() throws Exception {
        try (TestDatabase db = TestDatabase.create()) {
            DriverManagerDataSource source = new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
            Flyway.configure().dataSource(source).target("65").load().migrate();
            JdbcClient jdbc = JdbcClient.create(source);
            seed(jdbc);
            Properties properties = new Properties();
            properties.setProperty("user", db.username());
            properties.setProperty("password", db.password());
            properties.setProperty("ApplicationName", "salmonbus-worker");
            try (Connection worker = DriverManager.getConnection(db.jdbcUrl(), properties)) {
                assertThat(worker.isValid(1)).isTrue();
                assertThatThrownBy(() -> Flyway.configure().dataSource(source).load().migrate())
                    .isInstanceOf(FlywayException.class).hasMessageContaining("Worker와 기존 이관");
                assertLegacySchemaIntact(jdbc);
            }
            Flyway.configure().dataSource(source).load().migrate();
            assertThat(jdbc.sql("SELECT count(*) FROM observation_batch").query(Long.class).single()).isOne();
        }
    }

    @Test
    void 미완료_재구축이_남으면_품질_스키마를_보존하고_전환을_거부한다() {
        try (TestDatabase db = TestDatabase.create()) {
            DriverManagerDataSource source = new DriverManagerDataSource(db.jdbcUrl(), db.username(), db.password());
            Flyway.configure().dataSource(source).target("65").load().migrate();
            JdbcClient jdbc = JdbcClient.create(source);
            seed(jdbc);
            jdbc.sql("INSERT INTO stop_demand_rebuild_request(route_version_id,vehicle_id,request_id) VALUES(1,'bus',gen_random_uuid())")
                .update();
            assertThatThrownBy(() -> Flyway.configure().dataSource(source).load().migrate())
                .isInstanceOf(FlywayException.class).hasMessageContaining("진행 중인 수요 재구축");
            assertLegacySchemaIntact(jdbc);
            assertThat(jdbc.sql("SELECT count(*) FROM stop_demand_rebuild_request").query(Long.class).single()).isOne();
        }
    }

    private void assertLegacySchemaIntact(JdbcClient jdbc) {
        assertThat(jdbc.sql("""
            SELECT to_regclass('route_data_quality') IS NOT NULL
                AND to_regclass('forecast_observation_quality') IS NOT NULL
                AND to_regclass('vehicle_one_way_trip') IS NOT NULL
                AND NOT EXISTS(SELECT 1 FROM information_schema.columns
                    WHERE table_schema=current_schema() AND table_name='route' AND column_name='data_revision')
            """).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT max(version::int) FROM flyway_schema_history WHERE success")
            .query(Integer.class).single()).isEqualTo(65);
    }

}

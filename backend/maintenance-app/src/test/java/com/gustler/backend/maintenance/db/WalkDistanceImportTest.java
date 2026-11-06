package com.gustler.backend.maintenance.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.maintenance.DatabaseEnvironment;
import com.gustler.backend.maintenance.DatabaseEnvironment.TargetKind;
import com.gustler.backend.maintenance.configuration.WalkDistanceImportConfiguration;
import com.gustler.backend.routecatalog.api.LoadStopWalkDistances;
import com.gustler.backend.routecatalog.api.LoadStopWalkDistances.Status;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class WalkDistanceImportTest extends PostgresMigrationTestSupport {
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-06T12:00:00+09:00");

    @Test
    void 정비_구성으로_도보_거리_파일을_한_트랜잭션에_넣고_같은_파일은_다시_쓰지_않는다() throws Exception {
        // given
        Path distances = Path.of(getClass().getResource("/walking-fixture/two-pairs.csv.gz").toURI());
        Path metadata = Path.of(getClass().getResource("/walking-fixture/two-pairs.json").toURI());
        DatabaseEnvironment environment = new DatabaseEnvironment(TargetKind.LOCAL, postgres.getJdbcUrl(),
            postgres.getUsername(), postgres.getPassword(), "test");
        JdbcClient jdbc = JdbcClient.create(
            new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DatabaseEnvironment.class, () -> environment);
            context.register(WalkDistanceImportConfiguration.class);
            context.refresh();
            LoadStopWalkDistances load = context.getBean(LoadStopWalkDistances.class);

            // when
            LoadStopWalkDistances.Result first = load.load(distances, metadata, AT);
            LoadStopWalkDistances.Result second = load.load(distances, metadata, AT.plusDays(1));

            // then
            assertThat(first.status()).isEqualTo(Status.LOADED);
            assertThat(first.pairCount()).isEqualTo(2);
            assertThat(second.status()).isEqualTo(Status.UNCHANGED);
            assertThat(jdbc.sql("SELECT count(*) FROM stop_walk_distance").query(Long.class).single()).isEqualTo(2);
            assertThat(context.getBeanDefinitionNames())
                .noneMatch(name -> name.toLowerCase().contains("gbis") || name.toLowerCase().contains("flyway"));
        }
    }
}

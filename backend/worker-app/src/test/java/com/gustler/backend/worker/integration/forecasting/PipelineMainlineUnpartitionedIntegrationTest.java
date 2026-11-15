package com.gustler.backend.worker.integration.forecasting;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.support.PostgresTestContainer;
import com.gustler.backend.worker.configuration.CollectionRuntimeConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 관측 두 테이블이 일반 테이블인 DB에서 {@link PipelineMainlineIntegrationTest}의 시험을 그대로 다시 돌린다. 줄이 있는 DB는 수동 전환
 * SQL을 실행하기 전까지 이 모양에서 수집 원장 저장, 예보, 채점을 한다.
 *
 * <p>운영 DB의 두 테이블이 날짜 파티션 테이블로 바뀌면 이 시험과, 두 테이블이 일반 테이블일 때를 받는 코드 갈래를 함께 지운다.
 */
@Import({ PostgresTestContainer.class, CollectionRuntimeConfiguration.class, UnpartitionedObservationTables.class,
    PipelineMainlineIntegrationTest.Diagnostics.class })
class PipelineMainlineUnpartitionedIntegrationTest extends PipelineMainlineIntegrationTest {
    @Autowired
    private JdbcClient catalog;

    @Test
    void 이_시험의_DB는_관측_두_테이블이_일반_테이블이고_관측에서_배치로_가는_FK가_있다() {
        // when
        String kinds = catalog.sql("""
            SELECT string_agg(relname || ':' || relkind::text, ',' ORDER BY relname) FROM pg_class
            WHERE relnamespace = current_schema()::regnamespace
              AND relname IN ('observation_batch', 'vehicle_observation')
            """).query(String.class).single();
        final long foreignKeys = catalog.sql("""
            SELECT count(*) FROM pg_constraint
            WHERE contype = 'f' AND conrelid = 'vehicle_observation'::regclass
              AND confrelid = 'observation_batch'::regclass
            """).query(Long.class).single();
        final long seedRows = catalog.sql("SELECT count(*) FROM observation_batch").query(Long.class).single();

        // then
        assertSoftly(softly -> {
            softly.assertThat(kinds).isEqualTo("observation_batch:r,vehicle_observation:r");
            softly.assertThat(foreignKeys).isOne();
            softly.assertThat(seedRows).isZero();
        });
    }
}

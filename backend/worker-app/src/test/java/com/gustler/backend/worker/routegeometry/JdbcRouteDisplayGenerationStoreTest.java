package com.gustler.backend.worker.routegeometry;

import static org.assertj.core.api.Assertions.*;

import com.gustler.backend.support.PostgresTestContainer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.core.io.ClassPathResource;

@SpringBootTest(classes = JdbcRouteDisplayGenerationStoreTest.Config.class,
    properties = "route-geometry.generation.max-attempts=5")
class JdbcRouteDisplayGenerationStoreTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({PostgresTestContainer.class, JdbcRouteDisplayGenerationStore.class})
    static class Config {}

    @Autowired JdbcClient jdbc;
    @Autowired JdbcRouteDisplayGenerationStore store;
    @Autowired DataSource dataSource;
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final String KEY = "a".repeat(64);
    private static final String DOCUMENT = "{\"quality\": \"SOURCE_ONLY\"}";

    @BeforeEach
    void clear() {
        jdbc.sql("TRUNCATE route_display_generation_job,route_display_automatic_artifact").update();
    }

    private void enqueue() {
        store.enqueue(KEY, "204000057", "b".repeat(64), "evidence-v1", "algorithm-v1", "{\"input\":1}");
    }

    private void expire() {
        jdbc.sql("UPDATE route_display_generation_job SET lease_until=clock_timestamp()-interval '1 second'").update();
    }

    @Test
    void duplicate_submission_preserves_the_original_input_and_published_artifact() {
        enqueue();
        store.enqueue(KEY, "204000057", "b".repeat(64), "evidence-v1", "algorithm-v1", "{\"input\":2}");
        var job = store.claim(LEASE).orElseThrow();
        assertThat(job.inputJson()).contains("1").doesNotContain("2");
        assertThat(store.complete(job, DOCUMENT, JdbcRouteDisplayGenerationStore.sha256(DOCUMENT))).isTrue();
        enqueue();
        assertThat(store.claim(LEASE)).isEmpty();
        assertThat(jdbc.sql("SELECT document FROM route_display_automatic_artifact").query(String.class).single()).isEqualTo(DOCUMENT);
        assertThat(store.complete(job, DOCUMENT, JdbcRouteDisplayGenerationStore.sha256(DOCUMENT))).isFalse();
    }

    @Test
    void reclaimed_lease_fences_all_operations_from_the_old_worker() {
        enqueue();
        var old = store.claim(LEASE).orElseThrow();
        expire();
        var replacement = store.claim(LEASE).orElseThrow();
        assertThat(replacement.token()).isNotEqualTo(old.token());
        assertThat(replacement.attempts()).isEqualTo(2);
        assertThat(store.complete(old, DOCUMENT, JdbcRouteDisplayGenerationStore.sha256(DOCUMENT))).isFalse();
        assertThat(store.fail(old, Duration.ofSeconds(1))).isFalse();
        assertThat(store.supersede(old)).isFalse();
        assertThat(store.complete(replacement, DOCUMENT, JdbcRouteDisplayGenerationStore.sha256(DOCUMENT))).isTrue();
    }

    @Test
    void expired_unreclaimed_worker_cannot_publish() {
        enqueue();
        var job = store.claim(LEASE).orElseThrow();
        expire();
        assertThat(store.complete(job, DOCUMENT, JdbcRouteDisplayGenerationStore.sha256(DOCUMENT))).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM route_display_automatic_artifact").query(Integer.class).single()).isZero();
    }

    @Test
    void retry_is_durable_and_not_claimed_before_its_deadline() {
        enqueue();
        var job = store.claim(LEASE).orElseThrow();
        assertThat(store.fail(job, Duration.ofHours(1))).isTrue();
        assertThat(store.claim(LEASE)).isEmpty();
        jdbc.sql("UPDATE route_display_generation_job SET next_attempt_at=clock_timestamp()-interval '1 second'").update();
        var restartedStore = new JdbcRouteDisplayGenerationStore(jdbc);
        assertThat(restartedStore.claim(LEASE).orElseThrow().attempts()).isEqualTo(2);
    }

    @Test
    void superseded_work_cannot_publish_or_retry() {
        enqueue();
        var job = store.claim(LEASE).orElseThrow();
        assertThat(store.supersede(job)).isTrue();
        assertThat(store.claim(LEASE)).isEmpty();
        assertThat(store.complete(job, DOCUMENT, JdbcRouteDisplayGenerationStore.sha256(DOCUMENT))).isFalse();
        assertThat(store.fail(job, Duration.ofSeconds(1))).isFalse();
    }

    @Test
    void returning_to_a_superseded_source_requeues_its_original_snapshot() {
        enqueue();
        var old = store.claim(LEASE).orElseThrow();
        assertThat(store.supersede(old)).isTrue();
        store.enqueue(KEY, "204000057", "b".repeat(64), "evidence-v1", "algorithm-v1", "{\"input\":2}");
        var returned = store.claim(LEASE).orElseThrow();
        assertThat(returned.token()).isNotEqualTo(old.token());
        assertThat(returned.inputJson()).contains("1").doesNotContain("2");
        assertThat(returned.attempts()).isEqualTo(2);
        assertThat(store.complete(old, DOCUMENT, JdbcRouteDisplayGenerationStore.sha256(DOCUMENT))).isFalse();
        assertThat(store.complete(returned, DOCUMENT, JdbcRouteDisplayGenerationStore.sha256(DOCUMENT))).isTrue();
    }

    @Test
    void source_validation_is_claimed_before_full_generation() {
        enqueue();
        store.enqueue("c".repeat(64), "204000057", "b".repeat(64), "evidence-v1",
            "algorithm-v1/source-only", "{}");
        assertThat(store.claim(LEASE).orElseThrow().algorithmRevision()).endsWith("/source-only");
        assertThat(store.claim(LEASE).orElseThrow().generationKey()).isEqualTo(KEY);
    }

    @Test
    void concurrent_claimers_cannot_share_a_live_job() throws Exception {
        enqueue();
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Optional<JdbcRouteDisplayGenerationStore.Job>> claim = () -> store.claim(LEASE);
            var results = executor.invokeAll(List.of(claim, claim));
            int claims = 0;
            for (var result : results) if (result.get().isPresent()) claims++;
            assertThat(claims).isEqualTo(1);
        }
    }

    @Test
    void digest_mismatch_never_completes_a_job() {
        enqueue();
        var job = store.claim(LEASE).orElseThrow();
        assertThatThrownBy(() -> store.complete(job, DOCUMENT, "c".repeat(64)))
            .hasRootCauseInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.sql("SELECT status FROM route_display_generation_job").query(String.class).single()).isEqualTo("RUNNING");
        assertThat(store.complete(job, DOCUMENT, JdbcRouteDisplayGenerationStore.sha256(DOCUMENT))).isTrue();
    }

    @Test
    void generation_input_cannot_be_rewritten_after_enqueue() {
        enqueue();
        assertThatThrownBy(() -> jdbc.sql("UPDATE route_display_generation_job SET input_json='{}'").update())
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE route_display_generation_job SET evidence_revision='other'").update())
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(store.claim(LEASE).orElseThrow().inputJson()).contains("1");
    }

    @Test
    void published_documents_cannot_be_changed_or_deleted() {
        enqueue();
        var job = store.claim(LEASE).orElseThrow();
        store.complete(job, DOCUMENT, JdbcRouteDisplayGenerationStore.sha256(DOCUMENT));
        assertThatThrownBy(() -> jdbc.sql("UPDATE route_display_automatic_artifact SET document='{}'").update())
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM route_display_automatic_artifact").update())
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.sql("SELECT document FROM route_display_automatic_artifact").query(String.class).single()).isEqualTo(DOCUMENT);
    }

    @Test
    void fifth_execution_failure_is_terminal_and_rediscovery_or_restart_cannot_reset_it() {
        enqueue();
        JdbcRouteDisplayGenerationStore.Job last = null;
        for (int attempt=1; attempt<=5; attempt++) {
            last=store.claim(LEASE).orElseThrow();
            assertThat(last.attempts()).isEqualTo(attempt);
            assertThat(store.failWithOutcome(last,Duration.ofSeconds(1),"EXECUTION_ERROR"))
                .isEqualTo(attempt==5 ? JdbcRouteDisplayGenerationStore.FailureOutcome.FAILED
                    : JdbcRouteDisplayGenerationStore.FailureOutcome.RETRY_SCHEDULED);
            if (attempt<5) jdbc.sql("UPDATE route_display_generation_job SET next_attempt_at=clock_timestamp()-interval '1 second'").update();
        }
        var failed=jdbc.sql("SELECT status,attempts,attempt_limit,last_failure_code,failed_at IS NOT NULL FROM route_display_generation_job")
            .query((r,n)->List.of(r.getString(1),Integer.toString(r.getInt(2)),Integer.toString(r.getInt(3)),r.getString(4),Boolean.toString(r.getBoolean(5)))).single();
        assertThat(failed).containsExactly("FAILED","5","5","EXECUTION_ERROR","true");
        var restarted=new JdbcRouteDisplayGenerationStore(jdbc,99);
        restarted.enqueue(KEY,"204000057","b".repeat(64),"evidence-v1","algorithm-v1","{\"input\":1}");
        assertThat(restarted.claim(LEASE)).isEmpty();
        assertThat(jdbc.sql("SELECT attempt_limit FROM route_display_generation_job").query(Integer.class).single()).isEqualTo(5);
        assertThat(store.complete(last,DOCUMENT,JdbcRouteDisplayGenerationStore.sha256(DOCUMENT))).isFalse();
        assertThat(store.fail(last,Duration.ofSeconds(1))).isFalse();
        assertThat(store.reject(last,"INVALID_SNAPSHOT")).isFalse();
        // A different generation key has an independent execution budget.
        store.enqueue("c".repeat(64),"204000057","d".repeat(64),"evidence-v2","algorithm-v1","{}");
        assertThat(store.claim(LEASE).orElseThrow().attempts()).isEqualTo(1);
    }

    @Test
    void last_live_lease_can_complete_but_its_expiry_is_terminal_without_another_claim() {
        var oneAttempt=new JdbcRouteDisplayGenerationStore(jdbc,1);
        oneAttempt.enqueue(KEY,"204000057","b".repeat(64),"evidence-v1","algorithm-v1","{}");
        var last=oneAttempt.claim(LEASE).orElseThrow();
        assertThat(oneAttempt.claim(LEASE)).isEmpty();
        assertThat(jdbc.sql("SELECT status FROM route_display_generation_job").query(String.class).single()).isEqualTo("RUNNING");
        expire();
        assertThat(oneAttempt.claim(LEASE)).isEmpty();
        assertThat(jdbc.sql("SELECT status || ':' || last_failure_code FROM route_display_generation_job").query(String.class).single())
            .isEqualTo("FAILED:LEASE_EXPIRED");
        assertThat(jdbc.sql("SELECT attempts FROM route_display_generation_job").query(Integer.class).single()).isEqualTo(1);
        assertThat(store.complete(last,DOCUMENT,JdbcRouteDisplayGenerationStore.sha256(DOCUMENT))).isFalse();
        assertThat(store.fail(last,Duration.ofSeconds(1))).isFalse();
        assertThat(store.supersede(last)).isFalse();
        assertThat(store.reject(last,"INVALID_SNAPSHOT")).isFalse();
    }

    @Test
    void the_final_valid_attempt_may_publish_successfully() {
        var oneAttempt=new JdbcRouteDisplayGenerationStore(jdbc,1);
        oneAttempt.enqueue(KEY,"204000057","b".repeat(64),"evidence-v1","algorithm-v1","{}");
        var last=oneAttempt.claim(LEASE).orElseThrow();
        assertThat(store.complete(last,DOCUMENT,JdbcRouteDisplayGenerationStore.sha256(DOCUMENT))).isTrue();
        assertThat(oneAttempt.claim(LEASE)).isEmpty();
        assertThat(jdbc.sql("SELECT status FROM route_display_generation_job").query(String.class).single()).isEqualTo("COMPLETED");
    }

    @Test
    void exhausted_pending_rows_are_retired_instead_of_remaining_unclaimable_forever() {
        enqueue();
        jdbc.sql("UPDATE route_display_generation_job SET attempts=attempt_limit").update();
        assertThat(store.claim(LEASE)).isEmpty();
        assertThat(jdbc.sql("SELECT status || ':' || last_failure_code FROM route_display_generation_job").query(String.class).single())
            .isEqualTo("FAILED:ATTEMPTS_EXHAUSTED");
        assertThat(jdbc.sql("SELECT failed_at IS NOT NULL AND last_failure_at IS NOT NULL FROM route_display_generation_job")
            .query(Boolean.class).single()).isTrue();
    }

    @Test
    void permanent_rejection_ends_an_unused_budget_and_cannot_be_rediscovered() {
        enqueue();var job=store.claim(LEASE).orElseThrow();
        assertThat(store.reject(job,"INVALID_SNAPSHOT")).isTrue();
        enqueue();assertThat(store.claim(LEASE)).isEmpty();
        assertThat(jdbc.sql("SELECT status || ':' || attempts FROM route_display_generation_job").query(String.class).single())
            .isEqualTo("FAILED:1");
        assertThat(store.reject(job,"INVALID_SNAPSHOT")).isFalse();
    }

    @Test
    void database_budget_rejects_an_extra_attempt_even_if_an_old_worker_ignores_the_limit() {
        enqueue();
        assertThatThrownBy(()->jdbc.sql("UPDATE route_display_generation_job SET attempts=attempt_limit+1").update())
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.sql("SELECT attempts FROM route_display_generation_job").query(Integer.class).single()).isZero();
        assertThatThrownBy(()->new JdbcRouteDisplayGenerationStore(jdbc,0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failure_migration_preserves_existing_attempt_history_above_the_new_default() throws Exception {
        String schema="retry_budget_"+UUID.randomUUID().toString().replace("-", "");
        String oldSchema;
        try (var connection=dataSource.getConnection();var statement=connection.createStatement()) {
            oldSchema=connection.getSchema();statement.execute("CREATE SCHEMA "+schema);
            try {
                connection.setSchema(schema);
                String original=new ClassPathResource("db/migration/V49__route_display_generation.sql")
                    .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                // Exercise the actual legacy job/artifact DDL without unrelated trigger definitions.
                statement.execute(original.substring(0,original.indexOf("-- Published artifacts")));
                statement.executeUpdate("INSERT INTO route_display_generation_job(generation_key,route_id,input_digest,evidence_revision,algorithm_revision,input_json,attempts) VALUES ('"+
                    KEY+"','204000057','"+"b".repeat(64)+"','evidence','algorithm','{}',8)");
                statement.execute(new ClassPathResource("db/migration/V51__route_display_generation_failure.sql")
                    .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
                try (var result=statement.executeQuery("SELECT attempts,attempt_limit FROM route_display_generation_job")) {
                    assertThat(result.next()).isTrue();assertThat(result.getInt(1)).isEqualTo(8);assertThat(result.getInt(2)).isEqualTo(8);
                }
                assertThatThrownBy(()->statement.executeUpdate("UPDATE route_display_generation_job SET attempts=9"))
                    .isInstanceOf(java.sql.SQLException.class);
            } finally {
                connection.setSchema(oldSchema);statement.execute("DROP SCHEMA "+schema+" CASCADE");
            }
        }
    }
}

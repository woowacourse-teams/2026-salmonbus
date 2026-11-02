package com.gustler.backend.worker.routegeometry;

import static org.assertj.core.api.Assertions.*;

import com.gustler.backend.support.PostgresTestContainer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(classes = JdbcRouteDisplayGenerationStoreTest.Config.class)
class JdbcRouteDisplayGenerationStoreTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({PostgresTestContainer.class, JdbcRouteDisplayGenerationStore.class})
    static class Config {}

    @Autowired JdbcClient jdbc;
    @Autowired JdbcRouteDisplayGenerationStore store;
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
}

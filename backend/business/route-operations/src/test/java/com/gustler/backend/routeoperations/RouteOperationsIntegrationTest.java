package com.gustler.backend.routeoperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.routeoperations.api.RouteOperation;
import com.gustler.backend.routeoperations.api.RouteOperationPreview;
import com.gustler.backend.routeoperations.api.RouteOperations;
import com.gustler.backend.routeoperations.configuration.RouteOperationsConfiguration;
import com.gustler.backend.support.PostgresTestContainer;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses the shared PostgreSQL fixture, no additional database server per test. */
@SpringBootTest(
    classes = RouteOperationsIntegrationTest.Config.class,
    properties = {"route-operations.enabled=true", "route-operations.notifications-enabled=false"})
class RouteOperationsIntegrationTest {
    private static final String ID = "228000184";

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({PostgresTestContainer.class, RouteOperationsConfiguration.class})
    static class Config {
    }

    @Autowired
    RouteOperations operations;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    PlatformTransactionManager transactions;
    long version;

    @BeforeEach
    void reset() {
        jdbc.sql(
                "TRUNCATE"
                    + " route_operation_change,route_service_event,route_operation,route_operation_runtime")
            .update();
        jdbc.sql("TRUNCATE route CASCADE").update();
        final long route =
            jdbc.sql(
                    "INSERT INTO"
                        + " route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)"
                        + " VALUES (?,'GBIS',?,'5600','A','B') RETURNING id")
                .params(ID, ID)
                .query(Long.class)
                .single();
        version =
            jdbc.sql(
                    "INSERT INTO route_version(route_id,content_digest,valid_from)"
                        + " VALUES (?,?,'2020-01-01T00:00:00Z') RETURNING id")
                .params(route, "a".repeat(64))
                .query(Long.class)
                .single();
        operations.reportRuntime(true, true, 3);
    }

    RouteOperation readyWithControlsOff() {
        RouteOperation row = operations.register(ID, UUID.randomUUID(), "test");
        operations.registrationResult(ID, row.revision(), true);
        // 개별 스위치 테스트의 시작 상태는 명시적으로 OFF로 준비한다.
        jdbc.sql(
                "UPDATE route_operation SET"
                    + " collection_enabled=false,service_requested=false WHERE"
                    + " source_route_id=?")
            .param(ID)
            .update();
        return operations.required(ID);
    }

    RouteOperation change(String action, final boolean on, String reason) {
        return operations.change(
            ID,
            operations.required(ID).revision(),
            UUID.randomUUID(),
            "test",
            action,
            on,
            reason,
            "안내",
            "https://example.org");
    }

    @Test
    void 신규_노선은_수집과_서비스_ON을_요청하고_등록_재시도는_중복되지_않는다() {
        UUID request = UUID.randomUUID();
        RouteOperation first = operations.register(ID, request, "test");
        RouteOperation second = operations.register(ID, request, "test");
        assertSoftly(assertions -> {
            assertions.assertThat(first.state()).isEqualTo("PENDING");
            assertions.assertThat(second.revision()).isEqualTo(first.revision());
            assertions.assertThat(second.collectionEnabled()).isTrue();
            assertions.assertThat(second.serviceRequested()).isTrue();
            assertions.assertThat(second.serviceEnabled()).isFalse();
            assertions.assertThat(second.canCollect()).isFalse();
            assertions.assertThat(operations.list()).hasSize(1);
        });
    }

    @Test
    void 같은_인스턴스가_재시작_없이_수집_변경을_읽는다() {
        readyWithControlsOff();
        assertThat(operations.required(ID).canCollect()).isFalse();
        change("COLLECTION", true, "NORMAL");
        assertThat(operations.required(ID).canCollect()).isTrue();
        change("COLLECTION", false, "MANUAL");
        assertThat(operations.required(ID).canCollect()).isFalse();
    }

    @Test
    void 서비스_ON은_새_발행을_기다리고_OFF는_이전_예보를_숨긴다() {
        readyWithControlsOff();
        change("COLLECTION", true, "NORMAL");
        change("SERVICE", true, "NORMAL");
        assertThat(operations.required(ID).state()).isEqualTo("WARMING_UP");
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactions);
        transactionTemplate.executeWithoutResult(
            s -> {
                assertThat(
                        operations.allowPublication(
                            version,
                            operations.required(ID).enableAfter().minusSeconds(1)))
                    .isFalse();
                Instant now = operations.required(ID).enableAfter().plusSeconds(1);
                assertThat(operations.allowPublication(version, now)).isTrue();
                operations.publicationCommitted(version, now);
            });
        assertThat(operations.required(ID).serviceEnabled()).isTrue();
        assertThatThrownBy(() -> change("COLLECTION", false, "MANUAL"))
            .hasMessage("COLLECTION_REQUIRED");
        change("SERVICE", false, "STRIKE");
        assertThat(operations.required(ID).serviceEnabled()).isFalse();
        assertThat(operations.required(ID).collectionEnabled()).isTrue();
        assertThat(transactionTemplate.<Boolean>execute(s -> operations.allowPublication(version, Instant.now())))
            .isFalse();
    }

    @Test
    void 이전_revision은_거절하고_같은_요청은_중복_변경하지_않는다() {
        RouteOperation row = readyWithControlsOff();
        UUID request = UUID.randomUUID();
        operations.change(ID, row.revision(), request, "test", "COLLECTION", true, "NORMAL", "notice", "");
        RouteOperation again =
            operations.change(
                ID,
                row.revision(),
                request,
                "test",
                "COLLECTION",
                true,
                "NORMAL",
                "notice",
                "");
        assertThat(again.revision()).isEqualTo(row.revision() + 1);
        assertThatThrownBy(
                () ->
                    operations.change(
                        ID,
                        row.revision(),
                        UUID.randomUUID(),
                        "test",
                        "COLLECTION",
                        false,
                        "NORMAL",
                        "notice",
                        ""))
            .hasMessage("REVISION_CONFLICT");
    }

    @Test
    void 같은_노선도_내용이_다르면_요청_ID를_재사용할_수_없다() {
        RouteOperation row = readyWithControlsOff();
        UUID request = UUID.randomUUID();
        operations.change(ID, row.revision(), request, "test", "COLLECTION", true, "NORMAL", "notice", "");
        assertThatThrownBy(
                () ->
                    operations.change(
                        ID,
                        row.revision(),
                        request,
                        "test",
                        "COLLECTION",
                        false,
                        "NORMAL",
                        "notice",
                        ""))
            .hasMessage("REQUEST_ID_REUSED");
        assertThat(operations.required(ID).collectionEnabled()).isTrue();
        assertThatThrownBy(
                () ->
                    operations.change(
                        ID,
                        row.revision(),
                        request,
                        "test",
                        "COLLECTION",
                        true,
                        "NORMAL",
                        "changed",
                        ""))
            .hasMessage("REQUEST_ID_REUSED");
    }

    @Test
    void 추가_예보를_발행해도_운행_예고_안내를_유지한다() {
        readyWithControlsOff();
        change("COLLECTION", true, "NORMAL");
        change("SERVICE", true, "NORMAL");
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactions);
        Instant observed = operations.required(ID).enableAfter().plusSeconds(1);
        transactionTemplate.executeWithoutResult(
            s -> {
                operations.allowPublication(version, observed);
                operations.publicationCommitted(version, observed);
            });
        change("NOTICE", false, "PLANNED");
        transactionTemplate.executeWithoutResult(
            s -> {
                operations.allowPublication(version, observed);
                operations.publicationCommitted(version, observed);
            });
        assertThat(operations.required(ID).reason()).isEqualTo("PLANNED");
        assertThat(operations.required(ID).serviceEnabled()).isTrue();
    }

    @Test
    void 수집_ON은_살아_있는_worker의_한도가_필요하다() {
        readyWithControlsOff();
        operations.reportRuntime(true, true, 0);
        assertThatThrownBy(
                () ->
                    operations.change(
                        ID,
                        1,
                        UUID.randomUUID(),
                        "test",
                        "COLLECTION",
                        true,
                        "NORMAL",
                        "notice",
                        ""))
            .hasMessage("COLLECTION_BUDGET_EXCEEDED");
    }

    @Test
    void 등록_실패는_명시적으로_재시도하고_ON_요청을_유지한다() {
        RouteOperation row = operations.register(ID, UUID.randomUUID(), "test");
        operations.registrationResult(ID, row.revision(), false);
        assertThat(operations.required(ID).state()).isEqualTo("FAILED");
        assertThatThrownBy(() -> change("COLLECTION", true, "NORMAL"))
            .hasMessage("REGISTRATION_NOT_READY");
        change("RETRY", false, "ONBOARDING");
        assertThat(operations.required(ID).state()).isEqualTo("PENDING");
        assertThat(operations.required(ID).collectionEnabled()).isTrue();
        assertThat(operations.required(ID).serviceRequested()).isTrue();
        assertThat(operations.required(ID).canCollect()).isFalse();
    }

    @Test
    void 복구해도_파업_기간을_보존하고_일반_OFF는_학습에서_제외하지_않는다() {
        readyWithControlsOff();
        change("COLLECTION", true, "NORMAL");
        change("SERVICE", false, "MANUAL");
        assertThat(operations.excluded(ID, Instant.now(), Instant.now())).isFalse();
        change("SERVICE", false, "STRIKE");
        jdbc.sql("UPDATE route_service_event SET started_at='2020-01-01T00:00:00Z'").update();
        Instant during = Instant.parse("2020-01-02T00:00:00Z");
        assertThat(operations.excluded(ID, during, during)).isTrue();
        change("SERVICE", true, "RECOVERING");
        assertThat(operations.excluded(ID, during, during)).isTrue();
        Instant endedAt = jdbc.sql("SELECT ended_at FROM route_service_event WHERE source_route_id=?")
            .param(ID)
            .query(OffsetDateTime.class)
            .single()
            .toInstant();
        assertThat(operations.excluded(ID, endedAt, endedAt.plusSeconds(1))).isFalse();
    }

    @Test
    void worker가_없거나_모델이_미지원이면_서비스_ON을_거절한다() {
        readyWithControlsOff();
        change("COLLECTION", true, "NORMAL");
        operations.reportRuntime(true, false, 3);
        assertThatThrownBy(() -> change("SERVICE", true, "NORMAL")).hasMessage("WORKER_NOT_READY");
        operations.reportRuntime(true, true, 3);
        operations.modelResult(ID, false);
        assertThatThrownBy(() -> change("SERVICE", true, "NORMAL"))
            .hasMessage("MODEL_NOT_SUPPORTED");
    }

    @Test
    void 조회에서_worker_반영과_형상_준비와_요청_상태를_구분한다() {
        readyWithControlsOff();
        RouteOperationPreview preview = operations.preview(ID);
        assertThat(preview.state()).isEqualTo("OFF");
        assertThat(preview.workerFresh()).isTrue();
        assertThat(preview.maximumCollectionRoutes()).isEqualTo(3);
        assertThat(preview.operation().geometryState()).isEqualTo("PENDING");
        operations.geometryResult(ID, operations.required(ID).revision(), false);
        assertThat(operations.preview(ID).operation().geometryState()).isEqualTo("FAILED");
        change("RETRY_GEOMETRY", false, "ONBOARDING");
        assertThat(operations.preview(ID).operation().geometryState()).isEqualTo("PENDING");
    }

    @Test
    void 정상_시각_사이의_중단도_제외하고_종료_시각은_허용한다() {
        readyWithControlsOff();
        jdbc.sql(
                "INSERT INTO"
                    + " route_service_event(source_route_id,reason,started_at,ended_at)"
                    + " VALUES (?,'STRIKE','2020-01-02T00:00:00Z','2020-01-03T00:00:00Z')")
            .param(ID)
            .update();
        assertThat(period("2020-01-01T00:00:00Z", "2020-01-04T00:00:00Z")).isFalse();
        assertThat(period("2020-01-01T00:00:00Z", "2020-01-01T01:00:00Z")).isTrue();
        assertThat(period("2020-01-03T00:00:00Z", "2020-01-04T00:00:00Z")).isTrue();
    }

    private boolean period(String from, String until) {
        return jdbc.sql(
                "SELECT route_service_period_eligible(?,CAST(? AS timestamptz),CAST(? AS"
                    + " timestamptz))")
            .params(version, from, until)
            .query(Boolean.class)
            .single();
    }

    @Test
    void 잘못된_노선_ID와_안전하지_않은_안내_URL을_거절한다() {
        assertThatThrownBy(() -> operations.register("5600", UUID.randomUUID(), "test"))
            .hasMessage("INVALID_ROUTE_ID");
        readyWithControlsOff();
        assertThatThrownBy(
                () ->
                    operations.change(
                        ID,
                        1,
                        UUID.randomUUID(),
                        "test",
                        "SERVICE",
                        false,
                        "STRIKE",
                        "notice",
                        "javascript:alert(1)"))
            .hasMessage("INVALID_SOURCE_URL");
    }

    @Test
    void 기본_ON_등록은_카탈로그_준비_후_수집하고_새_발행_후_공개한다() {
        // given
        RouteOperation registered = operations.register(ID, UUID.randomUUID(), "test");

        // when
        operations.registrationResult(ID, registered.revision(), true);
        RouteOperation ready = operations.required(ID);

        // then
        assertThat(ready.canCollect()).isTrue();
        assertThat(ready.state()).isEqualTo("WARMING_UP");
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        transaction.executeWithoutResult(
            status -> {
                Instant old = ready.enableAfter().minusSeconds(1);
                assertThat(operations.allowPublication(version, old)).isFalse();
                Instant fresh = ready.enableAfter().plusSeconds(1);
                assertThat(operations.allowPublication(version, fresh)).isTrue();
                operations.publicationCommitted(version, fresh);
            });
        assertThat(operations.required(ID).state()).isEqualTo("ON");
    }

    @Test
    void 수집과_서비스를_끈_노선은_재등록해도_다시_켜지지_않는다() {
        // given
        readyWithControlsOff();
        operations.reportRuntime(true, true, 0);

        // when
        RouteOperation registered = operations.register(ID, UUID.randomUUID(), "operator");

        // then
        assertSoftly(assertions -> {
            assertions.assertThat(registered.collectionEnabled()).isFalse();
            assertions.assertThat(registered.serviceRequested()).isFalse();
            assertions.assertThat(registered.revision()).isEqualTo(1);
        });
    }

    @Test
    void 한도가_없으면_기본_ON_노선을_등록하지_않는다() {
        // given
        operations.reportRuntime(true, true, 0);

        assertThatThrownBy(() -> operations.register(ID, UUID.randomUUID(), "test"))
            .hasMessage("COLLECTION_BUDGET_EXCEEDED");
        assertThat(operations.list()).isEmpty();
    }

    @Test
    void 전역_예보가_꺼져_있으면_기본_ON_노선을_등록하지_않는다() {
        // given
        operations.reportRuntime(true, false, 3);

        assertThatThrownBy(() -> operations.register(ID, UUID.randomUUID(), "test"))
            .hasMessage("WORKER_NOT_READY");
        assertThat(operations.list()).isEmpty();
    }
}

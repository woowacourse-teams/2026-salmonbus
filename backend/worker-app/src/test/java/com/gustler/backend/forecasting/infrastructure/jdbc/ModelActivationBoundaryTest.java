package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.api.model.ActivateModel;
import com.gustler.backend.forecasting.api.model.ActivateModelCommand;
import com.gustler.backend.forecasting.api.model.LoadConfiguredModel;
import com.gustler.backend.forecasting.api.model.LoadConfiguredModelCommand;
import com.gustler.backend.forecasting.api.model.ModelActivationResult;
import com.gustler.backend.forecasting.api.model.ModelLoadResult;
import com.gustler.backend.forecasting.application.model.ModelBundleLoader;
import com.gustler.backend.forecasting.domain.model.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.model.ActiveModelSlot;
import com.gustler.backend.forecasting.domain.model.ModelIdentity;
import com.gustler.backend.forecasting.domain.model.ModelRelease;
import com.gustler.backend.forecasting.domain.model.SeatForecastModel;
import com.gustler.backend.forecasting.domain.model.SupportedForecastScope;
import com.gustler.backend.support.IntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 공개 API의 트랜잭션 프록시와 실제 DB 쓰기 경계를 검증한다. 파일 해석만 대체한다. */
@IntegrationTest
class ModelActivationBoundaryTest {

    private static final String MODEL_DIRECTORY = "test-model-directory";

    @Autowired
    private ActivateModel activateModel;

    @Autowired
    private LoadConfiguredModel loadConfiguredModel;

    @Autowired
    private JdbcModelDeploymentRepository repository;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private ModelBundleLoader modelBundleLoader;

    private String releaseId;
    private ModelRelease release;
    private ActiveModelSlot originalSlot;
    private Optional<ActiveModelDeployment> originalActive;

    @BeforeEach
    void 테스트용_모델과_활성_슬롯을_준비한다() {
        releaseId = "boundary-test-" + UUID.randomUUID();
        SupportedForecastScope scope = new SupportedForecastScope(List.of("1650", "3330"));
        ModelIdentity identity = new ModelIdentity(releaseId, "seat-distribution-a18", "model-v1",
            "a".repeat(64), "seat-distribution-0-70", "feature-v1", scope.digest(),
            Instant.parse("2026-09-01T00:00:00Z"));
        release = new ModelRelease(identity, scope, mock(SeatForecastModel.class));
        when(modelBundleLoader.load(MODEL_DIRECTORY)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            return release;
        });
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            originalSlot = repository.lockActiveSlot();
            originalActive = repository.findActive();
            jdbcClient.sql("UPDATE model_deployment SET state = 'RETIRED' WHERE state = 'ACTIVE'").update();
            jdbcClient.sql("UPDATE model_active_slot SET model_deployment_id = NULL, version = 0 WHERE id = 1")
                .update();
        });
    }

    @AfterEach
    void 생성한_모델만_정리하고_이전_활성_상태를_복원한다() {
        if (originalSlot == null) {
            return;
        }
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            repository.lockActiveSlot();
            jdbcClient.sql("""
                    DELETE FROM model_activation_request
                    WHERE target_deployment_id IN (SELECT id FROM model_deployment WHERE release_id = :release)
                    """).param("release", releaseId).update();
            jdbcClient.sql("UPDATE model_active_slot SET model_deployment_id = NULL WHERE id = 1").update();
            jdbcClient.sql("DELETE FROM model_deployment WHERE release_id = :release")
                .param("release", releaseId).update();
            originalActive.ifPresent(active -> jdbcClient.sql("UPDATE model_deployment SET state = 'ACTIVE' WHERE id = ?")
                .param(active.id()).update());
            jdbcClient.sql("UPDATE model_active_slot SET model_deployment_id = :id, version = :version WHERE id = 1")
                .param("id", originalSlot.deployment().map(ActiveModelDeployment::id).orElse(null), java.sql.Types.BIGINT)
                .param("version", originalSlot.version()).update();
        });
    }

    @Test
    void 읽기_트랜잭션에서_호출해도_파일_검증은_밖에서_하고_활성화는_별도로_커밋한다() {
        // given
        UUID requestId = UUID.randomUUID();
        TransactionTemplate outer = readOnlyTransaction();

        // when
        ModelActivationResult result = outer.execute(status -> {
            assertReadOnlyTransaction();
            ModelActivationResult activated = activateModel.activate(new ActivateModelCommand(requestId, 0, MODEL_DIRECTORY));
            assertReadOnlyTransaction();
            status.setRollbackOnly();
            return activated;
        });

        // then: 호출자의 롤백 후에도 공개 기능에서 확정한 활성화가 남아야 한다.
        assertThat(result).isNotNull();
        assertThat(repository.findActiveSlot().version()).isOne();
        assertThat(repository.findActive().orElseThrow().identity()).isEqualTo(release.identity());
        assertThat(repository.findActivation(requestId)).get()
            .satisfies(saved -> assertThat(saved.target().id()).isEqualTo(result.deploymentId()));
        verify(modelBundleLoader).load(MODEL_DIRECTORY);
    }

    @Test
    void 기동_적재도_호출자의_읽기_트랜잭션과_분리해_처음_활성화한다() {
        // when
        ModelLoadResult result = readOnlyTransaction().execute(status -> {
            assertReadOnlyTransaction();
            ModelLoadResult loaded = loadConfiguredModel.load(new LoadConfiguredModelCommand(MODEL_DIRECTORY, false));
            assertReadOnlyTransaction();
            status.setRollbackOnly();
            return loaded;
        });

        // then
        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo(ModelLoadResult.Status.ACTIVATED);
        assertThat(repository.findActiveSlot().version()).isOne();
        assertThat(repository.findActive()).get()
            .satisfies(active -> assertThat(active.id()).isEqualTo(result.deploymentId()));
        verify(modelBundleLoader).load(MODEL_DIRECTORY);
    }

    @Test
    void 같은_모델을_선택한_요청도_읽기_트랜잭션과_분리해_기록한다() {
        // given
        ModelActivationResult first = activateModel.activate(new ActivateModelCommand(UUID.randomUUID(), 0, MODEL_DIRECTORY));
        UUID requestId = UUID.randomUUID();

        // when
        ModelActivationResult result = readOnlyTransaction().execute(status -> {
            assertReadOnlyTransaction();
            ModelActivationResult unchanged = activateModel.activate(new ActivateModelCommand(requestId, 1, MODEL_DIRECTORY));
            assertReadOnlyTransaction();
            status.setRollbackOnly();
            return unchanged;
        });

        // then
        assertThat(result).isNotNull();
        assertThat(result.deploymentId()).isEqualTo(first.deploymentId());
        assertThat(result.activeVersion()).isOne();
        assertThat(repository.findActiveSlot().version()).isOne();
        assertThat(repository.findActivation(requestId)).isPresent();
        assertThat(jdbcClient.sql("SELECT count(*) FROM model_deployment WHERE release_id = :release")
            .param("release", releaseId).query(Integer.class).single()).isOne();
    }

    private TransactionTemplate readOnlyTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        return transaction;
    }

    private void assertReadOnlyTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
    }
}

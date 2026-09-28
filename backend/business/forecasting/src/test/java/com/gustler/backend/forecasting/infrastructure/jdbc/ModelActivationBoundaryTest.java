package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.api.model.LoadConfiguredModel;
import com.gustler.backend.forecasting.api.model.LoadConfiguredModelCommand;
import com.gustler.backend.forecasting.api.model.ModelLoadResult;
import com.gustler.backend.forecasting.application.model.ModelBundleFiles;
import com.gustler.backend.forecasting.application.model.ModelBundleLoader;
import com.gustler.backend.forecasting.application.model.ModelStartupService;
import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.model.SeatDistributionPredictor;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.util.ArrayList;
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

@ForecastingIntegrationTest
class ModelActivationBoundaryTest {

    private static final String MODEL_DIRECTORY = "test-model-directory";

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
    private Optional<ActiveModelDeployment> originalActive;

    @BeforeEach
    void 도는_배포를_비운_상태에서_시작한다() {
        releaseId = "boundary-test-" + UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            originalActive = repository.findActive();
            jdbcClient.sql("UPDATE model_deployment SET state = 'RETIRED' WHERE state = 'ACTIVE'").update();
        });
    }

    @AfterEach
    void 만든_배포를_지우고_도는_배포를_되돌린다() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcClient.sql("DELETE FROM model_deployment WHERE release_id = :release")
                .param("release", releaseId).update();
            originalActive.ifPresent(active -> jdbcClient.sql("UPDATE model_deployment SET state = 'ACTIVE' WHERE id = ?")
                .param(active.id()).update());
        });
    }

    @Test
    void 기동_적재는_파일_자리_확인부터_승격까지_한_트랜잭션에서_한다() {
        // given
        List<String> transactions = new ArrayList<>();
        ModelRelease release = new ModelRelease(releaseId, "model-v1", "a".repeat(64), "feature-v1",
            "2026-09-01T00:00:00Z", new SupportedForecastScope(List.of("1650", "3330")),
            mock(SeatDistributionPredictor.class));
        ModelBundleFiles files = () -> {
            transactions.add(currentTransaction());
            return release;
        };
        when(modelBundleLoader.filesUnder(MODEL_DIRECTORY)).thenAnswer(invocation -> {
            transactions.add(currentTransaction());
            return files;
        });

        // when
        ModelLoadResult actual = loadConfiguredModel.load(new LoadConfiguredModelCommand(MODEL_DIRECTORY, false));

        // then
        String startup = ModelStartupService.class.getName() + ".load";
        assertThat(transactions).containsExactly(startup, startup);
        assertThat(actual).isEqualTo(new ModelLoadResult.Activated(repository.findActive().orElseThrow().id()));
        assertThat(repository.findActive().orElseThrow().releaseId()).isEqualTo(releaseId);
    }

    private static String currentTransaction() {
        return TransactionSynchronizationManager.isActualTransactionActive()
            ? TransactionSynchronizationManager.getCurrentTransactionName()
            : null;
    }
}

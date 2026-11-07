package com.gustler.backend.forecasting.application.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.model.SeatDistributionForecastModel;
import com.gustler.backend.forecasting.domain.model.SeatDistributionPredictor;
import com.gustler.backend.forecasting.domain.model.les.LesForecastModel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LesRuntimeRollbackTest {
    @Test
    void 모델_전환과_복귀_후에도_선택한_배치의_모델을_유지한다() {
        ModelDeploymentRepository deployments = mock(ModelDeploymentRepository.class);
        LoadedModelRegistry registry = new LoadedModelRegistry();
        SupportedForecastScope scope = new SupportedForecastScope(List.of("3330"));
        LesForecastModel model = mock(LesForecastModel.class);
        ModelRelease les = ModelRelease.les("les-release", "b".repeat(64), "2026-10-21T00:00:00Z", scope, model);
        ModelRelease legacy = new ModelRelease("legacy-release", "legacy-v1", "a".repeat(64), "feature-v1",
            "2026-10-01T00:00:00Z", scope, mock(SeatDistributionPredictor.class));
        registry.add(les);
        registry.add(legacy);
        ActiveForecastRuntimeResolver resolver = new ActiveForecastRuntimeResolver(deployments, registry);
        when(deployments.findActive()).thenReturn(Optional.of(active(les, 2)));
        RuntimeSnapshot selected = resolver.resolveActive().orElseThrow();
        assertThat(selected.model()).isSameAs(model);
        assertThat(les.receiptWith(UUID.randomUUID()).predictionTargetVersion())
            .isEqualTo("after-alighting-and-departure-zero-v1");

        when(deployments.findActive()).thenReturn(Optional.of(active(legacy, 1)));
        assertThat(resolver.resolveActive().orElseThrow().model()).isInstanceOf(SeatDistributionForecastModel.class);
        assertThat(selected.model()).isSameAs(model);
        assertThat(selected.deploymentId()).isEqualTo(2);
    }

    private static ActiveModelDeployment active(ModelRelease release, final long id) {
        return new ActiveModelDeployment(id, release.featureContractVersion(), release.releaseId(),
            release.bundleDigest());
    }
}

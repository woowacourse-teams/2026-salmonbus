package com.gustler.backend.forecasting.application.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.domain.deployment.ModelIdentity;
import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import com.gustler.backend.forecasting.domain.model.SeatForecastModel;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ActiveForecastRuntimeResolverTest {
    private static final SupportedForecastScope SCOPE = new SupportedForecastScope(List.of("1650", "3330"));
    private static final ModelIdentity IDENTITY = new ModelIdentity("release-A", "seat-distribution-a18", "model-v1",
        "a".repeat(64), "seat-distribution-0-70", "feature-v1", SCOPE.digest(), Instant.parse("2026-09-01T00:00:00Z"));

    @Test
    void 활성_배포가_없으면_모델을_반환하지_않는다() {
        var holder = new LoadedModelRegistry();
        holder.register(release(IDENTITY));
        assertThat(resolver(null, holder).resolveActive()).isEmpty();
    }

    @Test
    void 활성_배포가_있어도_모델이_준비되지_않으면_반환하지_않는다() {
        assertThat(resolver(new ActiveModelDeployment(7, IDENTITY), new LoadedModelRegistry()).resolveActive()).isEmpty();
    }

    @Test
    void 전체_식별_정보가_같으면_해당_모델을_반환한다() {
        var release = release(IDENTITY);
        var holder = new LoadedModelRegistry();
        holder.register(release);
        var result = resolver(new ActiveModelDeployment(7, IDENTITY), holder).resolveActive().orElseThrow();
        assertThat(result.deploymentId()).isEqualTo(7);
        assertThat(result.model()).isSameAs(release.model());
        assertThat(result.dataUntil()).isEqualTo(IDENTITY.dataUntil());
    }

    @ParameterizedTest
    @MethodSource("differentIdentities")
    void 식별_정보의_어느_항목이라도_다르면_대체_모델을_사용하지_않는다(ModelIdentity identity) {
        var holder = new LoadedModelRegistry();
        holder.register(release(IDENTITY));
        assertThat(resolver(new ActiveModelDeployment(7, identity), holder).resolveActive()).isEmpty();
    }

    @Test
    void 같은_digest의_다른_모델을_준비해도_기존_모델과_계산_참조를_유지한다() {
        var holder = new LoadedModelRegistry();
        var first = release(IDENTITY);
        holder.register(first);
        var resolver = resolver(new ActiveModelDeployment(7, IDENTITY), holder);
        var snapshot = resolver.resolveActive().orElseThrow();
        var nextIdentity = differentIdentities().findFirst().orElseThrow();

        holder.register(release(nextIdentity));

        assertThat(holder.size()).isEqualTo(2);
        assertThat(holder.find(IDENTITY)).contains(first);
        assertThat(snapshot.model()).isSameAs(first.model());
        assertThat(resolver.resolveActive().orElseThrow().model()).isSameAs(snapshot.model());
    }

    private static Stream<ModelIdentity> differentIdentities() {
        var i = IDENTITY;
        return Stream.of(
            new ModelIdentity("release-B", i.modelKey(), i.modelVersion(), i.bundleDigest(), i.predictionTargetVersion(), i.calculationVersion(), i.supportedScopeDigest(), i.dataUntil()),
            new ModelIdentity(i.releaseId(), "other-model", i.modelVersion(), i.bundleDigest(), i.predictionTargetVersion(), i.calculationVersion(), i.supportedScopeDigest(), i.dataUntil()),
            new ModelIdentity(i.releaseId(), i.modelKey(), "model-v2", i.bundleDigest(), i.predictionTargetVersion(), i.calculationVersion(), i.supportedScopeDigest(), i.dataUntil()),
            new ModelIdentity(i.releaseId(), i.modelKey(), i.modelVersion(), "b".repeat(64), i.predictionTargetVersion(), i.calculationVersion(), i.supportedScopeDigest(), i.dataUntil()),
            new ModelIdentity(i.releaseId(), i.modelKey(), i.modelVersion(), i.bundleDigest(), "other-target", i.calculationVersion(), i.supportedScopeDigest(), i.dataUntil()),
            new ModelIdentity(i.releaseId(), i.modelKey(), i.modelVersion(), i.bundleDigest(), i.predictionTargetVersion(), "feature-v2", i.supportedScopeDigest(), i.dataUntil()),
            new ModelIdentity(i.releaseId(), i.modelKey(), i.modelVersion(), i.bundleDigest(), i.predictionTargetVersion(), i.calculationVersion(), "b".repeat(64), i.dataUntil()),
            new ModelIdentity(i.releaseId(), i.modelKey(), i.modelVersion(), i.bundleDigest(), i.predictionTargetVersion(), i.calculationVersion(), i.supportedScopeDigest(), i.dataUntil().plusSeconds(1)));
    }

    private static ModelRelease release(ModelIdentity identity) {
        return new ModelRelease(identity, SCOPE, mock(SeatForecastModel.class));
    }

    private static ActiveForecastRuntimeResolver resolver(ActiveModelDeployment deployment, LoadedModelRegistry holder) {
        ModelDeploymentRepository repository = mock(ModelDeploymentRepository.class);
        when(repository.findActive()).thenReturn(Optional.ofNullable(deployment));
        return new ActiveForecastRuntimeResolver(repository, holder);
    }
}

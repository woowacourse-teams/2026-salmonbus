package com.gustler.backend.forecasting.configuration;

import com.gustler.backend.forecasting.api.model.CheckActiveModelCoverage;
import com.gustler.backend.forecasting.application.deployment.ActiveForecastRuntimeResolver;
import com.gustler.backend.forecasting.application.deployment.BundleActivation;
import com.gustler.backend.forecasting.application.deployment.LoadedModelRegistry;
import com.gustler.backend.forecasting.application.deployment.ModelBundleLoader;
import com.gustler.backend.forecasting.application.deployment.ModelStartupService;
import com.gustler.backend.forecasting.domain.deployment.ForecastRuntime;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.infrastructure.bundle.FileModelBundleLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 모델 파일 검증, 활성 배포, 런타임 해석을 각각의 협력 객체로 연결한다.
 */
@Configuration(proxyBeanMethods = false)
public class ForecastingModelConfiguration {

    @Bean
    public LoadedModelRegistry loadedModelRegistry() {
        return new LoadedModelRegistry();
    }

    /**
     * 합성 번들 허용({@code model.bundle.allow-synthetic-fixture})은 기본이 꺼짐이고, 저장소의 설정 파일 가운데 로컬 개발 설정만
     * 켠다. 일반 설정 값이라 환경변수({@code MODEL_BUNDLE_ALLOW_SYNTHETIC_FIXTURE})로도 켜지며 코드가 막지 않는다. 켜져 있어도
     * 번들이 합성 표시를 달고 실행하지 않은 검증을 스스로 밝힌 경우가 아니면 적재 결과가 달라지지 않는다.
     */
    @Bean
    public ModelBundleLoader modelBundleLoader(
        @Value("${model.bundle.allow-synthetic-fixture:false}") final boolean allowSyntheticFixture
    ) {
        return new FileModelBundleLoader(allowSyntheticFixture);
    }

    @Bean
    public BundleActivation bundleActivation(ModelDeploymentRepository deployments, LoadedModelRegistry bundles) {
        return new BundleActivation(deployments, bundles);
    }

    @Bean
    public ModelStartupService modelStartupService(
        ModelBundleLoader loader,
        ModelDeploymentRepository deployments,
        LoadedModelRegistry bundles,
        BundleActivation activation
    ) {
        return new ModelStartupService(
                loader,
                deployments,
                bundles,
                activation
        );
    }

    @Bean
    public CheckActiveModelCoverage checkActiveModelCoverage(ForecastRuntime runtime) {
        return routeIds -> runtime.resolveActive()
            .filter(snapshot -> routeIds.stream().allMatch(snapshot::covers))
            .isPresent();
    }

    @Bean
    public ActiveForecastRuntimeResolver activeForecastRuntimeResolver(
        ModelDeploymentRepository deployments,
        LoadedModelRegistry bundles
    ) {
        return new ActiveForecastRuntimeResolver(deployments, bundles);
    }
}

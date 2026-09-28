package com.gustler.backend.forecasting.configuration;

import com.gustler.backend.forecasting.application.deployment.ActiveForecastRuntimeResolver;
import com.gustler.backend.forecasting.application.deployment.BundleActivation;
import com.gustler.backend.forecasting.application.deployment.LoadedModelRegistry;
import com.gustler.backend.forecasting.application.deployment.ModelBundleLoader;
import com.gustler.backend.forecasting.application.deployment.ModelStartupService;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.infrastructure.bundle.FileModelBundleLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ForecastingModelConfiguration {
    @Bean public LoadedModelRegistry loadedModelRegistry() { return new LoadedModelRegistry(); }
    @Bean public ModelBundleLoader modelBundleLoader() { return new FileModelBundleLoader(); }
    @Bean public BundleActivation bundleActivation(ModelDeploymentRepository deployments, LoadedModelRegistry bundles) {
        return new BundleActivation(deployments, bundles);
    }
    @Bean public ModelStartupService modelStartupService(ModelBundleLoader loader, ModelDeploymentRepository deployments,
            LoadedModelRegistry bundles, BundleActivation activation) {
        return new ModelStartupService(loader, deployments, bundles, activation);
    }
    @Bean public ActiveForecastRuntimeResolver activeForecastRuntimeResolver(ModelDeploymentRepository deployments,
                                                                            LoadedModelRegistry bundles) {
        return new ActiveForecastRuntimeResolver(deployments, bundles);
    }
}

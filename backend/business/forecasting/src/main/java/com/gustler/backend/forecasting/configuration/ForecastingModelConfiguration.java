package com.gustler.backend.forecasting.configuration;

import com.gustler.backend.forecasting.application.model.ActiveForecastRuntimeResolver;
import com.gustler.backend.forecasting.application.model.LoadedModelRegistry;
import com.gustler.backend.forecasting.application.model.ModelActivationService;
import com.gustler.backend.forecasting.application.model.ModelBundleLoader;
import com.gustler.backend.forecasting.application.model.ModelStartupService;
import com.gustler.backend.forecasting.application.model.TransactionalModelActivation;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.infrastructure.bundle.FileModelBundleLoader;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ForecastingModelConfiguration {
    @Bean public LoadedModelRegistry loadedModelRegistry() { return new LoadedModelRegistry(); }
    @Bean public ModelBundleLoader modelBundleLoader() { return new FileModelBundleLoader(); }
    @Bean public TransactionalModelActivation transactionalModelActivation(ModelDeploymentRepository deployments, Clock clock) {
        return new TransactionalModelActivation(deployments, clock);
    }
    @Bean public ModelActivationService modelActivationService(ModelBundleLoader loader, LoadedModelRegistry models,
                                                               TransactionalModelActivation activation) {
        return new ModelActivationService(loader, models, activation);
    }
    @Bean public ModelStartupService modelStartupService(ModelBundleLoader loader, LoadedModelRegistry models,
            ModelDeploymentRepository deployments, TransactionalModelActivation activation) {
        return new ModelStartupService(loader, models, deployments, activation);
    }
    @Bean public ActiveForecastRuntimeResolver activeForecastRuntimeResolver(ModelDeploymentRepository deployments,
                                                                            LoadedModelRegistry models) {
        return new ActiveForecastRuntimeResolver(deployments, models);
    }
}

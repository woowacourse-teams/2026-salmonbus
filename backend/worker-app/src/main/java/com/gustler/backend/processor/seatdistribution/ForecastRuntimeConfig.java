package com.gustler.backend.processor.seatdistribution;

import com.gustler.backend.processor.ModelDeploymentRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 계수 묶음을 읽어 예보 경로에 붙이는 자리.
 *
 * <p>여기서 트랜잭션 경계도 잡는다. {@link BundleActivation} 이 자기 안에 애너테이션을 달면
 * 자기 호출이라 프록시를 안 거쳐 아무 일도 안 한다. 그래서 부르는 쪽인 {@link BundleStartupLoader}
 * 가 빈이고 거기에 단다.
 */
@Configuration
@EnableConfigurationProperties(ModelBundleProperties.class)
public class ForecastRuntimeConfig {

    @Bean
    public LoadedBundleHolder loadedBundleHolder() {
        return new LoadedBundleHolder();
    }

    @Bean
    public BundleActivation bundleActivation(
        ModelDeploymentRepository deployments,
        LoadedBundleHolder bundles
    ) {
        return new BundleActivation(deployments, bundles);
    }

    @Bean
    public ActiveForecastRuntimeResolver activeForecastRuntimeResolver(
        ModelDeploymentRepository deployments,
        LoadedBundleHolder bundles
    ) {
        return new ActiveForecastRuntimeResolver(deployments, bundles);
    }
}

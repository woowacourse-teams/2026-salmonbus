package com.gustler.backend.worker.startup;

import com.gustler.backend.forecasting.api.model.LoadConfiguredModel;
import com.gustler.backend.forecasting.api.model.LoadConfiguredModelCommand;
import com.gustler.backend.forecasting.api.model.ModelLoadException;
import com.gustler.backend.forecasting.api.model.ModelLoadResult;
import com.gustler.backend.worker.configuration.ModelBundleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Worker 기동 후 설정된 모델의 적재를 요청한다. */
@Component
public class BundleStartupLoader {

    private static final Logger log = LoggerFactory.getLogger(BundleStartupLoader.class);

    private final ModelBundleProperties properties;
    private final LoadConfiguredModel loadConfiguredModel;

    public BundleStartupLoader(ModelBundleProperties properties, LoadConfiguredModel loadConfiguredModel) {
        this.properties = properties;
        this.loadConfiguredModel = loadConfiguredModel;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadConfiguredBundle() {
        try {
            ModelLoadResult result = loadConfiguredModel.load(
                new LoadConfiguredModelCommand(properties.directory(), properties.promoteOnStart()));
            switch (result.status()) {
                case NOT_CONFIGURED -> log.info("모델 파일 경로가 설정되지 않아 예보 계산을 시작하지 않는다");
                case READY -> log.info("활성 모델의 계수를 메모리에 적재했다. 배포={}", result.deploymentId());
                case ACTIVATED -> log.info("설정된 모델을 활성화했다. 배포={} 활성 버전={}",
                    result.deploymentId(), result.activeVersion());
                case ACTIVATION_CONFLICT -> log.warn("모델 활성화 중 다른 요청이 먼저 적용되어 자동 승격을 중단했다. "
                    + "배포={} 활성 버전={}", result.deploymentId(), result.activeVersion());
                case IDENTITY_MISMATCH -> log.warn("활성 모델과 설정된 모델의 식별 정보가 다르다. "
                    + "자동 승격 설정이 꺼져 있어 예보 계산을 시작하지 않는다. 배포={}", result.deploymentId());
            }
        } catch (ModelLoadException error) {
            log.error("모델 파일을 적재하지 못해 예보 계산을 시작하지 않는다: {}", error.getMessage());
        }
    }
}

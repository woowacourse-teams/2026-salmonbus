package com.gustler.backend.worker.startup;

import com.gustler.backend.forecasting.api.model.LoadConfiguredModel;
import com.gustler.backend.forecasting.api.model.LoadConfiguredModelCommand;
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
        ModelLoadResult result = loadConfiguredModel.load(
            new LoadConfiguredModelCommand(properties.directory(), properties.promoteOnStart()));
        switch (result) {
            case ModelLoadResult.NotConfigured notConfigured ->
                log.info("계수 파일 자리가 안 잡혀 있다. 예보는 계수가 붙을 때까지 batch 를 안 연다");
            case ModelLoadResult.Rejected rejected ->
                log.error("계수 파일을 못 올렸다. 예보는 batch 를 안 연다: {}", rejected.reason());
            case ModelLoadResult.Activated activated ->
                log.info("도는 배포가 없어 계수 파일을 올린다: 배포 {}", activated.deploymentId());
            case ModelLoadResult.Reloaded reloaded ->
                log.info("도는 배포의 계수를 다시 올렸다: {}", reloaded.releaseId());
            case ModelLoadResult.IdentityMismatch mismatch ->
                log.warn("도는 배포와 계수 파일의 신원이 다르다. model.bundle.promote-on-start 가 꺼져 있어 "
                    + "안 올린다. 도는 배포 {}, 파일 {}", mismatch.activeReleaseId(), mismatch.fileReleaseId());
            case ModelLoadResult.Promoted promoted ->
                log.warn("사람이 켜 둔 스위치로 계수를 갈아 끼운다. {} 에서 {} 로, 배포 {}",
                    promoted.activeReleaseId(), promoted.fileReleaseId(), promoted.deploymentId());
        }
    }
}

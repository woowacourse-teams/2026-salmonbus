package com.gustler.backend.worker.startup;

import com.gustler.backend.forecasting.api.model.LoadConfiguredModel;
import com.gustler.backend.forecasting.api.model.LoadConfiguredModelCommand;
import com.gustler.backend.forecasting.api.model.ModelLoadResult;
import com.gustler.backend.forecasting.api.model.ModelLoadResult.Activated;
import com.gustler.backend.forecasting.api.model.ModelLoadResult.IdentityMismatch;
import com.gustler.backend.forecasting.api.model.ModelLoadResult.NotConfigured;
import com.gustler.backend.forecasting.api.model.ModelLoadResult.Promoted;
import com.gustler.backend.forecasting.api.model.ModelLoadResult.Rejected;
import com.gustler.backend.forecasting.api.model.ModelLoadResult.Reloaded;
import com.gustler.backend.forecasting.api.pipeline.ForecastPipeline;
import com.gustler.backend.worker.configuration.ModelBundleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Worker 기동 후 설정된 모델의 적재를 요청하고, 예보가 켜져 있으면 그 뒤에 첫 줍기를 돌린다.
 *
 * <p>줍기는 활성 모델이 있어야 노선 판본과 실행 줄의 짝을 기억하고, 수집 신호는 짝을 아는 판본만 처리한다. 그래서 첫 줍기가 모델보다
 * 먼저 돌면 다음 줍기 주기까지 예보가 나오지 않는다. 줍기의 주기 실행은 한 주기 뒤에 시작한다({@code PipelineForecastTrigger}).
 */
@Component
public class BundleStartupLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(BundleStartupLoader.class);

    private final ModelBundleProperties properties;
    private final LoadConfiguredModel loadConfiguredModel;
    private ForecastPipeline pipeline;

    public BundleStartupLoader(ModelBundleProperties properties, LoadConfiguredModel loadConfiguredModel) {
        this.properties = properties;
        this.loadConfiguredModel = loadConfiguredModel;
    }

    /** 모델을 올린 뒤 첫 줍기를 돌릴 예보를 정한다. 예보가 꺼져 있으면 부르지 않는다. */
    @Autowired(required = false)
    public void setForecastPipeline(ForecastPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadConfiguredBundle() {
        ModelLoadResult result = loadConfiguredModel.load(
            new LoadConfiguredModelCommand(properties.directory(), properties.promoteOnStart()));
        switch (result) {
            case NotConfigured notConfigured ->
            LOGGER.info("계수 파일 자리가 안 잡혀 있다. 예보는 계수가 붙을 때까지 batch 를 안 연다");
            case Rejected rejected ->
            LOGGER.error("계수 파일을 못 올렸다. 예보는 batch 를 안 연다: {}", rejected.reason());
            case Activated activated ->
            LOGGER.info("도는 배포가 없어 계수 파일을 올린다: 배포 {}", activated.deploymentId());
            case Reloaded reloaded ->
            LOGGER.info("도는 배포의 계수를 다시 올렸다: {}", reloaded.releaseId());
            case IdentityMismatch mismatch ->
            LOGGER.warn("도는 배포와 계수 파일의 신원이 다르다. model.bundle.promote-on-start 가 꺼져 있어 "
                + "안 올린다. 도는 배포 {}, 파일 {}", mismatch.activeReleaseId(), mismatch.fileReleaseId());
            case Promoted promoted ->
            LOGGER.warn("사람이 켜 둔 스위치로 계수를 갈아 끼운다. {} 에서 {} 로, 배포 {}",
                promoted.activeReleaseId(), promoted.fileReleaseId(), promoted.deploymentId());
        }
        if (pipeline != null) {
            pipeline.pickUp();
        }
    }
}

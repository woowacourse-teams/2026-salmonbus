package com.gustler.backend.worker.startup;

import com.gustler.backend.forecasting.api.forecast.RunForecasts;
import com.gustler.backend.forecasting.api.model.LoadConfiguredModel;
import com.gustler.backend.forecasting.api.model.LoadConfiguredModelCommand;
import com.gustler.backend.forecasting.api.model.ModelLoadResult;
import com.gustler.backend.worker.configuration.ModelBundleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * worker 기동 후 설정된 모델을 적재하고, 예보가 켜져 있으면 첫 미처리 배치 조회를 실행한다.
 *
 * <p>활성 모델이 있어야 노선 버전과 실행기의 배정 관계를 등록할 수 있다.
 * 수집 알림은 이 관계가 등록된 버전만 처리하므로 모델 적재보다 먼저 조회하면 다음 주기까지 예보가 지연된다.
 * 주기 조회는 기동 한 주기 뒤부터 실행된다({@code ForecastScheduler}).
 */
@Component
public class BundleStartupLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(BundleStartupLoader.class);

    private final ModelBundleProperties properties;
    private final LoadConfiguredModel loadConfiguredModel;
    private RunForecasts forecasts;

    public BundleStartupLoader(ModelBundleProperties properties, LoadConfiguredModel loadConfiguredModel) {
        this.properties = properties;
        this.loadConfiguredModel = loadConfiguredModel;
    }

    /** 모델을 올린 뒤 첫 미처리 배치 조회를 돌릴 예보를 정한다. 예보가 꺼져 있으면 부르지 않는다. */
    @Autowired(required = false)
    public void setRunForecasts(RunForecasts forecasts) {
        this.forecasts = forecasts;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadConfiguredBundle() {
        ModelLoadResult result = loadConfiguredModel.load(
            new LoadConfiguredModelCommand(properties.directory(), properties.promoteOnStart()));
        logModelLoadResult(result);

        if (forecasts != null) {
            forecasts.pickUp();
        }
    }
    private static void logModelLoadResult(ModelLoadResult result) {
        switch (result) {
            case ModelLoadResult.NotConfigured notConfigured ->
            LOGGER.info("모델 번들 경로가 설정되지 않았다. 모델을 사용할 수 있을 때까지 예보를 실행하지 않는다");
            case ModelLoadResult.Rejected rejected ->
            LOGGER.error("모델 번들을 적재하지 못해 예보를 실행하지 않는다: {}", rejected.reason());
            case ModelLoadResult.Activated activated ->
            LOGGER.info("활성 모델 배포가 없어 모델 번들을 적재한다: 배포 {}", activated.deploymentId());
            case ModelLoadResult.Reloaded reloaded ->
            LOGGER.info("활성 배포의 모델 번들을 다시 적재했다: {}", reloaded.releaseId());
            case ModelLoadResult.IdentityMismatch mismatch ->
            LOGGER.warn("활성 배포와 모델 번들의 식별 정보가 다르다. model.bundle.promote-on-start가 꺼져 있어 "
                + "적재하지 않는다. 활성 배포 {}, 파일 {}", mismatch.activeReleaseId(), mismatch.fileReleaseId());
            case ModelLoadResult.Promoted promoted ->
            LOGGER.warn(
                    "model.bundle.promote-on-start 설정에 따라 활성 모델을 전환했다. {}에서 {}로, 배포 {}",
                    promoted.activeReleaseId(),
                    promoted.fileReleaseId(),
                    promoted.deploymentId()
            );
        }
    }

}

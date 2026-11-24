package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.forecast.RunForecasts;
import com.gustler.backend.observations.api.ObservationChange;
import com.gustler.backend.observations.api.ObservationChangeListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 관측 저장 알림과 정기 호출을 예보에 연결한다.
 *
 * <ul>
 *   <li>수집이 배치를 저장한 트랜잭션이 커밋된 뒤에만 그 배치를 예보에 넘긴다. 롤백된 저장과 관측 삭제 전달은 넘기지 않는다.</li>
 *   <li>{@code forecast.interval}(기본 30초)마다 처리하지 않은 배치를 줍게 한다. 넘기다 잃은 신호와 처리 중에 죽은 배치를 신선도
 *       창 안에서 다시 넣는다. 예보 스케줄러({@code forecastTaskScheduler}) 한 스레드에서 돈다. 첫 주기 실행은 한 주기 뒤다.
 *       기동 직후의 첫 줍기는 모델을 올린 뒤에 돌아야 하므로 모델을 올리는 쪽({@code BundleStartupLoader})이 부른다.</li>
 * </ul>
 * 실제 처리는 노선별 실행 줄에서 한다. 이 클래스는 기다리지 않는다.
 */
public class ForecastScheduler implements ObservationChangeListener {

    private final RunForecasts forecasts;

    public ForecastScheduler(RunForecasts forecasts) {
        this.forecasts = forecasts;
    }

    @Scheduled(initialDelayString = "${forecast.interval}", fixedDelayString = "${forecast.interval}",
        scheduler = "forecastTaskScheduler")
    public void pickUp() {
        forecasts.pickUp();
    }

    @Override
    @Transactional(readOnly = false, propagation = Propagation.MANDATORY)
    public void record(ObservationChange observationChange) {
        if (observationChange.kind() != ObservationChange.Kind.STORED) {
            return;
        }

        final long batchId = observationChange.batchId();
        final long routeVersionId = observationChange.routeVersionId();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                forecasts.batchStored(batchId, routeVersionId);
            }
        });
    }
}

package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.pipeline.ForecastPipeline;
import com.gustler.backend.observations.api.ObservationDeliveryBatch;
import com.gustler.backend.observations.api.ObservationDeliveryBatch.Kind;
import com.gustler.backend.observations.api.ObservationDeliveryHook;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 관측 저장 알림과 정기 호출을 새 예보 경로의 본선({@code forecast.pipeline=v2})에 연결한다. 옛 경로의 {@link ForecastTrigger}
 * 자리를 대신한다.
 *
 * <ul>
 *   <li>수집이 배치를 저장한 트랜잭션이 커밋된 뒤에만 그 배치를 본선에 넘긴다. 롤백된 저장과 관측 삭제 전달은 넘기지 않는다.</li>
 *   <li>기동 직후와 {@code forecast.interval}(기본 30초)마다 처리하지 않은 배치를 줍게 한다. 넘기다 잃은 신호와 처리 중에 죽은
 *       배치를 신선도 창 안에서 다시 넣는다. 예보 스케줄러({@code forecastTaskScheduler}) 한 스레드에서 돈다.</li>
 * </ul>
 * 실제 처리는 본선의 노선별 실행 줄에서 한다. 이 클래스는 기다리지 않는다.
 */
public class PipelineForecastTrigger implements ObservationDeliveryHook {
    private final ForecastPipeline pipeline;

    public PipelineForecastTrigger(ForecastPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${forecast.interval}", scheduler = "forecastTaskScheduler")
    public void pickUp() {
        pipeline.pickUp();
    }

    @Override
    @Transactional(readOnly = false, propagation = Propagation.MANDATORY)
    public void record(ObservationDeliveryBatch batch) {
        if (batch.kind() != Kind.STORED) {
            return;
        }
        final long batchId = batch.batchId();
        final long routeVersionId = batch.routeVersionId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                pipeline.batchStored(batchId, routeVersionId);
            }
        });
    }
}

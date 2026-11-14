package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.pipeline.MaintainPipelinePartitions;
import com.gustler.backend.forecasting.api.pipeline.MaintainPipelinePartitions.Report;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 날짜 파티션 테이블(새 예보 테이블, 파티션이 된 관측 테이블)의 파티션을 기동 직후 한 번, 그 뒤
 * {@code forecast.partition-interval}(기본 1시간)마다 미리 만든다.
 *
 * <p>통계·이관과 같은 스레드에서 돈다. 만들지 못해도 줄은 기본 파티션으로 들어가므로 실패는 경고로만 남기고 다음 회차에 다시 시도한다.
 * 새 경로 스위치가 하나라도 켜졌거나 수집이 켜졌을 때만 등록된다. 어느 테이블을 다룰지는 유지 작업이 정한다.
 */
public class PipelinePartitionJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(PipelinePartitionJob.class);
    private final MaintainPipelinePartitions partitions;

    public PipelinePartitionJob(MaintainPipelinePartitions partitions) {
        this.partitions = partitions;
    }

    @Scheduled(initialDelay = 0, fixedDelayString = "${forecast.partition-interval:1h}",
        scheduler = "calibrationTaskScheduler")
    public void preparePartitions() {
        try {
            Report report = partitions.maintain();
            if (!report.failed().isEmpty()) {
                LOGGER.warn("event=pipeline_partition_maintenance_incomplete failed={}", report.failed());
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("event=pipeline_partition_maintenance_failed exceptionType={}",
                failure.getClass().getSimpleName(), failure);
        }
    }
}

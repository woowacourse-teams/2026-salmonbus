package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.pipeline.MaintainPipelinePartitions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 날짜 파티션 테이블(예보 기록 테이블, 파티션 테이블인 관측 테이블)의 파티션을 미리 만든다. 빈이 만들어질 때 한 번 끝내고, 그 뒤
 * {@code forecast.partition-interval}(기본 1시간)마다 다시 한다.
 *
 * <p>첫 유지를 빈 초기화에서 끝내는 것은 수집과 예보가 그날 첫 줄을 쓰기 전에 그날 파티션이 있게 하려는 것이다. 파티션이 없을 때 쓴
 * 줄은 기본 파티션으로 들어가고, 그 뒤에는 그 날짜의 파티션을 만들 수 없어 그 줄이 날짜 단위 보관에서 빠진다. 유지 작업은 파티션
 * 하나에 잠금을 몇 초만 기다리므로 기동을 오래 붙잡지 않는다.
 *
 * <p>주기 실행은 전용 스케줄러({@code calibrationTaskScheduler}) 한 스레드에서 돈다. 이 스케줄러를 쓰는 작업은 이것 하나다. 만들지
 * 못해도 줄은 기본 파티션으로 들어가므로 실패는 경고로만 남기고 다음 회차에 다시 시도한다. 첫 유지가 실패해도 기동은 계속한다.
 * 예보나 수집이 켜졌을 때만 등록된다. 어느 테이블을 다룰지는 유지 작업이 정한다.
 */
public class PipelinePartitionJob implements InitializingBean {
    private static final Logger LOGGER = LoggerFactory.getLogger(PipelinePartitionJob.class);
    private final MaintainPipelinePartitions partitions;

    public PipelinePartitionJob(MaintainPipelinePartitions partitions) {
        this.partitions = partitions;
    }

    @Override
    public void afterPropertiesSet() {
        preparePartitions();
    }

    @Scheduled(initialDelayString = "${forecast.partition-interval:1h}",
        fixedDelayString = "${forecast.partition-interval:1h}", scheduler = "calibrationTaskScheduler")
    public void preparePartitions() {
        try {
            MaintainPipelinePartitions.Report report = partitions.maintain();
            if (!report.failed().isEmpty()) {
                LOGGER.warn("event=pipeline_partition_maintenance_incomplete failed={}", report.failed());
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("event=pipeline_partition_maintenance_failed exceptionType={}",
                failure.getClass().getSimpleName(), failure);
        }
    }
}

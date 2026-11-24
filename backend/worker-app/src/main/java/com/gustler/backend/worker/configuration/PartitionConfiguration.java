package com.gustler.backend.worker.configuration;

import com.gustler.backend.forecasting.api.forecast.MaintainPartitions;
import com.gustler.backend.worker.scheduling.PartitionJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 날짜 파티션 유지 작업을 등록한다.
 *
 * <p>등록 조건은 둘 가운데 하나다.
 * <ul>
 *   <li>예보가 켜짐({@code forecast.enabled=true}). 예보 기록 테이블 다섯 개의 날짜 파티션을 미리 만든다.</li>
 *   <li>수집이 켜짐({@code collection.enabled=true}). 관측 두 테이블({@code observation_batch}, {@code vehicle_observation})이
 *       파티션 테이블이면 날짜 파티션을 만들어야 한다. 일반 테이블인 동안에는 카탈로그만 읽고 아무것도 쓰지 않는다.</li>
 * </ul>
 * 수집과 예보를 모두 끈 worker는 이 구성이 빈도 스케줄도 더하지 않는다.
 *
 * <p>작업은 빈이 만들어질 때 첫 유지를 끝낸다. 수집 예약({@code CollectionSchedulingConfiguration})은 이 빈 뒤에 만들어지고 예보의 미처리 배치 조회와 정산의 쓰기는
 * 컨텍스트가 다 뜬 뒤에 시작하므로, 첫 줄을 쓰기 전에 그날 파티션이 있다. DB 마이그레이션이 끝난 뒤에 만들어져야 한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnExpression(PartitionConfiguration.MAINTAINS_PARTITIONS)
public class PartitionConfiguration {
    /** 유지 작업의 등록 조건. */
    static final String MAINTAINS_PARTITIONS = "${forecast.enabled:false} or ${collection.enabled:false}";
    /** 유지 작업의 빈 이름. 줄을 쓰기 시작하는 빈이 이 빈 뒤에 만들어지도록 가리킨다. */
    static final String PARTITION_JOB = "partitionJob";

    @Bean(name = PARTITION_JOB)
    @DependsOnDatabaseInitialization
    PartitionJob partitionJob(MaintainPartitions partitions) {
        return new PartitionJob(partitions);
    }
}

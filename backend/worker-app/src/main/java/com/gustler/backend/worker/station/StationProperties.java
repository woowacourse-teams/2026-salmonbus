package com.gustler.backend.worker.station;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 정류장 채우기 배치 설정. 키 별칭·하루 상한·원천 남은 값 하한·차단 대기·재채우기·재시도를 바인딩한다.
 *
 * <p>idleInterval은 대기열이 비었을 때 DB를 다시 확인하기까지 쉬는 시간이다.
 * maxFailures는 한 칸의 회차에서 연속으로 실패해도 되는 횟수다.
 */
@ConfigurationProperties("stations.fill")
public record StationProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("a") String keyAlias,
    @DefaultValue("8000") int dailyLimit,
    @DefaultValue("2000") int remainingFloor,
    @DefaultValue("200ms") Duration stepInterval,
    @DefaultValue("5s") Duration idleInterval,
    @DefaultValue("65s") Duration blockWait,
    @DefaultValue("10m") Duration blockWaitMaximum,
    @DefaultValue("30d") Duration refreshInterval,
    @DefaultValue("40s") Duration retryBase,
    @DefaultValue("300s") Duration retryMaximum,
    @DefaultValue("5") int maxFailures,
    @DefaultValue("1h") Duration salmonTileCheckInterval
) {
}

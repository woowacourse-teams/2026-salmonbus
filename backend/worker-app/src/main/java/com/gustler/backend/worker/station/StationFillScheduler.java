package com.gustler.backend.worker.station;

import com.gustler.backend.stations.api.FillStationTiles;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 전용 스레드에서 탐색점 하나씩 채운다. 위치 수집·예보·정산이 DB 연결을 기다리고 있으면 이번 차례를 건너뛴다.
 *
 * <p>대기열이 비면 idleInterval 동안 DB를 다시 확인하지 않는다. 이 worker가 새 접수를 받으면 바로 다시 확인한다.
 */
public class StationFillScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(StationFillScheduler.class);
    private final FillStationTiles fill;
    private final DataSource dataSource;
    private final Clock clock;
    private final Duration idleInterval;

    private volatile Instant idleUntil = Instant.MIN;

    public StationFillScheduler(
        FillStationTiles fill,
        DataSource dataSource,
        Clock clock,
        Duration idleInterval
    ) {
        if (idleInterval.isNegative()) {
            throw new IllegalArgumentException("정류장 채우기 유휴 간격은 음수일 수 없다");
        }
        this.fill = fill;
        this.dataSource = dataSource;
        this.clock = clock;
        this.idleInterval = idleInterval;
    }

    @Scheduled(
        fixedDelayString = "${stations.fill.step-interval:200ms}",
        initialDelayString = "30s",
        scheduler = "stationFillTaskScheduler"
    )
    public void step() {
        Instant now = clock.instant();
        if (now.isBefore(idleUntil)) {
            return;
        }
        if (othersAreWaitingForConnection()) {
            return;
        }
        try {
            if (fill.fillNext() == FillStationTiles.Step.NOTHING_TO_DO) {
                idleUntil = now.plus(idleInterval);
            }
        } catch (RuntimeException failure) {
            // 원천 응답이나 URL에 인증 정보가 있을 수 있어 예외 형식만 남긴다.
            LOGGER.warn("event=station_fill_failed exceptionType={}", failure.getClass().getSimpleName());
        }
    }

    /**
     * 새 접수가 들어왔으니 유휴 대기를 끝낸다.
     */
    public void wake() {
        idleUntil = Instant.MIN;
    }

    private boolean othersAreWaitingForConnection() {
        if (!(dataSource instanceof HikariDataSource hikari) || hikari.getHikariPoolMXBean() == null) {
            return false;
        }
        return hikari.getHikariPoolMXBean().getThreadsAwaitingConnection() > 0;
    }
}

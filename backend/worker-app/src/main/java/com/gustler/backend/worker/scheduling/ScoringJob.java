package com.gustler.backend.worker.scheduling;

import com.gustler.backend.forecasting.api.forecast.ScoringCycle;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 정산의 주기 작업이다. 예보가 켜졌을 때만 등록된다({@code forecast.enabled=true}).
 *
 * <ul>
 *   <li>기동이 끝나면 최근 예보가 있는 노선을 모두 한 번 복구하게 한다.</li>
 *   <li>{@value #FLUSH_INTERVAL_SECONDS}초마다 마감 정리를 정산 큐에 넣고, 모아 둔 결과를 한 트랜잭션으로 쓴다.</li>
 * </ul>
 * 주기 작업은 전용 스케줄러({@code scoringTaskScheduler}) 한 스레드에서 돈다. 실패는 로그로만 남기고 다음 회차에 다시 한다. 컨텍스트가
 * 닫힐 때 돌던 쓰기는 스프링이 스케줄러의 정지 단계에서 끝나기를 기다리고, 그 뒤로는 쓰지 않는다. 쓰지 못한 결과는 재시작한 뒤 정산
 * 복구가 다시 만든다.
 */
public class ScoringJob {

    /** 정산 결과를 모았다가 쓰는 간격(초). 같은 간격으로 마감 정리도 한다. */
    static final long FLUSH_INTERVAL_SECONDS = 5;
    private static final Logger LOGGER = LoggerFactory.getLogger(ScoringJob.class);

    private final ScoringCycle cycle;

    public ScoringJob(ScoringCycle cycle) {
        this.cycle = cycle;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!cycle.start()) {
            LOGGER.warn("event=scoring_start_rejected");
        }
    }

    @Scheduled(initialDelay = FLUSH_INTERVAL_SECONDS, fixedDelay = FLUSH_INTERVAL_SECONDS, timeUnit = TimeUnit.SECONDS,
        scheduler = "scoringTaskScheduler")
    public void flush() {
        cycle.tick();
        cycle.flush();
    }
}

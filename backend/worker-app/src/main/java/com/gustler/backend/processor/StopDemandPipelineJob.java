package com.gustler.backend.processor;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 한 tick에 노선 하나의 한 단계만 실행하고 처리 스레드를 반환한다. */
@Component
@ConditionalOnProperty(prefix="forecast",name="enabled",havingValue="true")
public class StopDemandPipelineJob {
    private static final Logger log=LoggerFactory.getLogger(StopDemandPipelineJob.class);
    private final StopDemandPipeline pipeline;
    private final RouteVersionRepository routes;
    private final Clock clock;
    private List<Long> versions=List.of();
    private Instant refreshAt=Instant.MIN;
    private int next;
    private final Map<Long,Instant> retryAt=new HashMap<>();
    private final Map<Long,Integer> failures=new HashMap<>();
    private final Map<Long,Instant> progressAt=new HashMap<>();

    public StopDemandPipelineJob(StopDemandPipeline pipeline,RouteVersionRepository routes,Clock clock) {
        this.pipeline=pipeline; this.routes=routes; this.clock=clock;
    }

    @Scheduled(fixedDelayString="${forecast.statistics-step-interval:100ms}")
    public void advance() {
        Instant now=clock.instant();
        if(!now.isBefore(refreshAt)) {
            refreshAt=now.plusSeconds(10);
            versions=routes.findActiveVersionIds().stream().sorted().toList();
            retryAt.keySet().retainAll(versions);
            failures.keySet().retainAll(versions);
            progressAt.keySet().retainAll(versions);
        }
        for(int visited=0;visited<versions.size();visited++) {
            long version=versions.get(Math.floorMod(next++,versions.size()));
            if(now.isBefore(retryAt.getOrDefault(version,Instant.MIN))) { continue; }
            long started=System.nanoTime();
            try {
                var result=pipeline.step(version); // 프록시가 commit한 뒤만 성공/진행을 기록한다.
                failures.remove(version);
                retryAt.put(version,now.plusSeconds(result.status().equals("IDLE") || result.status().equals("COMPLETED") ? 10
                    : result.status().equals("WAITING") ? 1 : 0));
                boolean progress=result.status().equals("PROGRESSED") && !now.isBefore(progressAt.getOrDefault(version,Instant.MIN));
                if(result.status().equals("STARTED") || result.status().equals("COMPLETED") || progress) {
                    log.info("event=stop_demand_statistics status={} phase={} routeVersionId={} dataUntil={} stepDurationMs={}",
                        result.status(),result.phase(),version,result.dataUntil()==null ? "-" : result.dataUntil().atZone(clock.getZone())
                            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),elapsed(started));
                    progressAt.put(version,now.plusSeconds(60));
                }
            } catch(RuntimeException exception) {
                int attempts=failures.merge(version,1,Integer::sum);
                long delay=Math.min(60,1L<<Math.min(attempts,6));
                retryAt.put(version,now.plusSeconds(delay));
                log.error("event=stop_demand_statistics status=FAILED routeVersionId={} stepDurationMs={} retryAfterSeconds={} exceptionType={}",
                    version,elapsed(started),delay,exception.getClass().getSimpleName());
                throw exception;
            }
            return;
        }
    }
    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started); }
}

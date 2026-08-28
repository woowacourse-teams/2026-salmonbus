package com.gustler.backend.collector;

import com.gustler.backend.collector.dto.BusLocationResponse.BusLocation;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 한 판의 호출과 그 판에서 본 차량을 한 트랜잭션에 쌓는다.
 *
 * <p>차가 한 대도 없는 판도 묶음을 남긴다. 지운 적이 없어야 관측 간격 90초 판정에서
 * 연결이 끊긴 자리를 알아본다.
 */
@Component
public class ObservationLoader {

    private static final Logger log = LoggerFactory.getLogger(ObservationLoader.class);

    private final ObservationRepository observationRepository;

    public ObservationLoader(
        ObservationRepository observationRepository
    ) {
        this.observationRepository = observationRepository;
    }

    @Transactional
    public long load(
        ObservationAttempt attempt,
        List<BusLocation> buses
    ) {
        CollectedObservations collected = CollectedObservations.from(buses);
        logExcludedRows(attempt, collected);

        return observationRepository.save(
            ObservationBatch.of(attempt, collected),
            collected.storableRows());
    }

    /**
     * 뺀 행은 값 하나하나를 남긴다. 응답 원문을 통째로 찍으면 번호판이 로그로 샌다.
     * 차량 아이디와 번호판은 찍지 않는다.
     */
    private void logExcludedRows(
        ObservationAttempt attempt,
        CollectedObservations collected
    ) {
        for (UpstreamObservationRow row : collected.excludedRows()) {
            log.warn(
                "뜻을 모르는 운행 상태라 관측을 뺐다. 판본={} 시도키={} 상류행={} 운행상태={}",
                attempt.routeVersionId(),
                attempt.attemptKey(),
                row.sourceRowNumber(),
                row.observation().runningState());
        }
    }
}

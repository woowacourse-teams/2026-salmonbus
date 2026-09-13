package com.gustler.backend.collector;

import com.gustler.backend.collector.dto.BusLocationResponse.BusLocation;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이미 열려 있는 판에 그 판에서 본 차량을 쌓고, 같은 트랜잭션에서 판을 닫는다.
 *
 * <p>판은 여기서 만들지 않는다. 호출을 보내기 전에 자리를 잡으면서 ObservationBatchLedger 가 연다.
 *
 * <p>차가 한 대도 없는 판도 닫아서 남긴다. 지운 적이 없어야 관측 간격 90초 판정에서
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
    public void load(
        final long batchId,
        ObservationBatchConclusion conclusion,
        List<BusLocation> buses,
        OffsetDateTime responseReceivedAt
    ) {
        CollectedObservations collected = CollectedObservations.from(buses);
        logExcludedRows(batchId, collected);

        observationRepository.concludeWithRows(batchId, conclusion, collected, responseReceivedAt);
    }

    /**
     * 뺀 행은 값 하나하나를 남긴다. 응답 원문을 통째로 찍으면 번호판이 로그로 샌다.
     * 차량 아이디와 번호판은 찍지 않는다.
     */
    private void logExcludedRows(
        final long batchId,
        CollectedObservations collected
    ) {
        for (UpstreamObservationRow row : collected.excludedRows()) {
            log.warn(
                "쌓을 수 없는 관측을 뺐다. 묶음={} 상류행={} 운행상태={} 정류소순번={}",
                batchId,
                row.sourceRowNumber(),
                row.observation().runningState(),
                row.observation().stopSequence());
        }
    }
}

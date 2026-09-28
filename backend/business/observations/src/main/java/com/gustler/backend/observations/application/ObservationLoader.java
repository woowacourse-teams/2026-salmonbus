package com.gustler.backend.observations.application;

import com.gustler.backend.observations.api.CollectionQualityHook;
import com.gustler.backend.observations.api.VehicleObservationsStored;
import com.gustler.backend.observations.domain.CollectedObservations;
import com.gustler.backend.observations.domain.ObservationBatchConclusion;
import com.gustler.backend.observations.domain.ObservationRepository;
import com.gustler.backend.observations.domain.StoredObservations;
import com.gustler.backend.observations.domain.UpstreamObservationRow;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 관측 저장과 품질 조사 등록을 같은 트랜잭션에서 조율한다. */
@Component
public class ObservationLoader {
    private static final Logger log = LoggerFactory.getLogger(ObservationLoader.class);
    private final ObservationRepository observations;
    private final List<CollectionQualityHook> qualityHooks;

    public ObservationLoader(ObservationRepository observations, List<CollectionQualityHook> qualityHooks) {
        this.observations = observations;
        this.qualityHooks = List.copyOf(qualityHooks);
    }

    @Transactional
    public void load(final long batchId, ObservationBatchConclusion conclusion,
                     CollectedObservations collected, OffsetDateTime responseReceivedAt) {
        logExcludedRows(batchId, collected);
        StoredObservations stored = observations.concludeWithRows(batchId, conclusion, collected, responseReceivedAt);
        var event = new VehicleObservationsStored(stored.batchId(), stored.routeVersionId(), stored.observedAt(),
            stored.rows().stream().map(row -> new VehicleObservationsStored.Row(
                row.observationId(), row.vehicleId(), row.remainingSeats())).toList());
        qualityHooks.forEach(hook -> hook.observationsStored(event));
    }

    /**
     * 뺀 행은 값 하나하나를 남긴다. 응답 원문을 통째로 찍으면 번호판이 로그로 샌다.
     * 차량 아이디와 번호판은 찍지 않는다.
     */
    private void logExcludedRows(final long batchId, CollectedObservations collected) {
        for (UpstreamObservationRow row : collected.excludedRows()) {
            log.warn("쌓을 수 없는 관측을 뺐다. 묶음={} 상류행={} 운행상태={} 정류소순번={}",
                batchId, row.sourceRowNumber(), row.observation().runningState(), row.observation().stopSequence());
        }
    }
}

package com.gustler.backend.observations.application;

import com.gustler.backend.observations.api.CollectionQualityHook;
import com.gustler.backend.observations.api.ObservedSeatValue;
import com.gustler.backend.observations.api.VehicleObservationsStored;
import com.gustler.backend.observations.domain.CollectedObservations;
import com.gustler.backend.observations.domain.CollectionAttemptToken;
import com.gustler.backend.observations.domain.ObservationBatchConclusion;
import com.gustler.backend.observations.domain.ObservationRepository;
import com.gustler.backend.observations.domain.RemainingSeats;
import com.gustler.backend.observations.domain.StoredObservations;
import com.gustler.backend.observations.domain.UpstreamObservationRow;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 관측 저장과 필요한 품질 잠금·조사 등록을 같은 트랜잭션에서 조율한다. */
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
    public void load(CollectionAttemptToken token, ObservationBatchConclusion conclusion,
                     CollectedObservations collected, OffsetDateTime responseReceivedAt) {
        logExcludedRows(token.batchId(), collected);
        final long routeVersionId = observations.routeVersionOf(token.batchId());
        List<ObservedSeatValue> seatValues = collected.storableRows().stream().map(row -> {
            var observation = row.observation();
            Integer seats = observation.remainingSeats() instanceof RemainingSeats.Known known ? known.seats() : null;
            return new ObservedSeatValue(observation.vehicleId(), seats);
        }).toList();
        // 품질 → 수집 배치 잠금 순서를 지킨다. 정상 관측의 훅은 추가 SQL 없이 반환한다.
        qualityHooks.forEach(hook -> hook.beforeRowsStored(routeVersionId, seatValues));
        observations.concludeWithRows(token, conclusion, collected, responseReceivedAt)
            .ifPresent(this::registerQualityInvestigation);
    }

    private void registerQualityInvestigation(StoredObservations stored) {
        var event = new VehicleObservationsStored(stored.batchId(), stored.routeVersionId(), stored.observedAt(),
            stored.rows().stream().map(row -> new VehicleObservationsStored.Row(
                row.observationId(), row.vehicleId(), row.remainingSeats())).toList());
        qualityHooks.forEach(hook -> hook.observationsStored(event));
    }

    /** 응답 원문에 포함된 차량 ID와 번호판은 로그에서 제외한다. */
    private void logExcludedRows(final long batchId, CollectedObservations collected) {
        for (UpstreamObservationRow row : collected.excludedRows()) {
            log.warn("필수값이 없는 관측을 저장 대상에서 제외했다. 배치={} 응답행={} 운행상태={} 정류소순번={}",
                batchId, row.sourceRowNumber(), row.observation().runningState(), row.observation().stopSequence());
        }
    }
}

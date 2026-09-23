package com.gustler.backend.observations.application;

import com.gustler.backend.observations.domain.CollectedObservations;
import com.gustler.backend.observations.domain.ObservationBatchConclusion;
import com.gustler.backend.observations.domain.ObservationRepository;
import com.gustler.backend.observations.domain.UpstreamObservationRow;

import com.gustler.backend.gbis.api.dto.BusLocationResponse.BusLocation;
import java.time.OffsetDateTime;
import com.gustler.backend.observations.domain.CollectionAttemptToken;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 외부 응답을 정규화하고 현재 시도의 관측과 결과를 함께 저장한다. */
@Component
public class ObservationLoader {

    private static final Logger log = LoggerFactory.getLogger(ObservationLoader.class);

    private final ObservationRepository observationRepository;
    private final CollectionResponseMapper responses;

    public ObservationLoader(
        ObservationRepository observationRepository,
        CollectionResponseMapper responses
    ) {
        this.observationRepository = observationRepository;
        this.responses = responses;
    }

    @Transactional
    public void load(
        CollectionAttemptToken token,
        ObservationBatchConclusion conclusion,
        List<BusLocation> buses,
        OffsetDateTime responseReceivedAt
    ) {
        CollectedObservations collected = responses.observationsOf(buses);
        logExcludedRows(token.batchId(), collected);

        observationRepository.concludeWithRows(token, conclusion, collected, responseReceivedAt);
    }

    /**
     * 제외한 행은 필요한 필드만 로그에 남긴다. 응답 원문에는 번호판이 포함되므로 전체를 기록하지 않는다.
     * 차량 아이디와 번호판은 로그에서 제외한다.
     */
    private void logExcludedRows(
        final long batchId,
        CollectedObservations collected
    ) {
        for (UpstreamObservationRow row : collected.excludedRows()) {
            log.warn(
                "필수값이 없는 관측을 저장 대상에서 제외했다. 배치={} 응답행={} 운행상태={} 정류소순번={}",
                batchId,
                row.sourceRowNumber(),
                row.observation().runningState(),
                row.observation().stopSequence());
        }
    }
}

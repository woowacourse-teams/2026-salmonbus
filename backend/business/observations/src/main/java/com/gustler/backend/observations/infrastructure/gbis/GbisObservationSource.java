package com.gustler.backend.observations.infrastructure.gbis;

import com.gustler.backend.gbis.api.GbisLocationResult;
import com.gustler.backend.gbis.api.GbisLocationSource;
import com.gustler.backend.observations.domain.ObservationResponse;
import com.gustler.backend.observations.domain.ObservationSource;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** GBIS 조회와 응답 해석을 수집 업무의 조회 계약에 연결한다. */
public class GbisObservationSource implements ObservationSource {
    private static final Logger log = LoggerFactory.getLogger(GbisObservationSource.class);
    private final GbisLocationSource locationSource;
    private final Clock clock;

    public GbisObservationSource(GbisLocationSource locationSource, Clock clock) {
        this.locationSource = locationSource;
        this.clock = clock;
    }

    @Override
    public ObservationResponse read(String sourceRouteId) {
        GbisLocationResult response;
        try {
            response = locationSource.read(sourceRouteId);
        } catch (RuntimeException exception) {
            log.error("외부 호출 중 예외가 발생해 응답을 확인하지 못했다. 노선={}", sourceRouteId, exception);
            return ObservationResponse.unconfirmed(now());
        }
        OffsetDateTime receivedAt = now();
        // 정규화 오류는 응답 미수신으로 바꾸지 않는다. 저장을 중단하고 원래 오류를 호출자에 전달한다.
        return GbisObservationMapper.response(response, receivedAt);
    }

    private OffsetDateTime now() {
        return clock.instant().atZone(clock.getZone()).toOffsetDateTime();
    }
}

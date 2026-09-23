package com.gustler.backend.observations.application;

import com.gustler.backend.observations.domain.ObservationSource;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.domain.ObservationBatchReservation;
import com.gustler.backend.routecatalog.api.CurrentRouteVersion;
import com.gustler.backend.routecatalog.api.RouteReference;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 한 노선의 예약, HTTP 호출, 수집 결과 저장을 순서대로 진행한다. */
@Component
public class ObservationCollector implements com.gustler.backend.observations.api.CollectObservations {

    private static final Logger log = LoggerFactory.getLogger(ObservationCollector.class);

    private final CurrentRouteVersion currentRouteVersion;
    private final ObservationBatchLedger batchLedger;
    private final ObservationSource observationSource;
    private final Clock clock;

    public ObservationCollector(
        CurrentRouteVersion currentRouteVersion,
        ObservationBatchLedger batchLedger,
        ObservationSource observationSource,
        Clock clock
    ) {
        this.currentRouteVersion = currentRouteVersion;
        this.batchLedger = batchLedger;
        this.observationSource = observationSource;
        this.clock = clock;
    }

    public void collectOnce(
        String sourceRouteId
    ) {
        OffsetDateTime scheduledAt = now();

        Optional<RouteReference> routeVersionId = currentRouteVersion.currentVersionOf(sourceRouteId, scheduledAt);
        if (routeVersionId.isEmpty()) {
            log.warn("현재 노선 버전이 없어 수집을 건너뛴다. 노선={}", sourceRouteId);
            return;
        }

        collectOn(routeVersionId.orElseThrow().routeVersionId(), sourceRouteId, scheduledAt);
    }

    private void collectOn(
        final long routeVersionId,
        String sourceRouteId,
        OffsetDateTime scheduledAt
    ) {
        ObservationBatchReservation reservation = batchLedger.reserve(
            new CollectionPlan(routeVersionId, scheduledAt, attemptKeyOf(sourceRouteId, scheduledAt)),
            scheduledAt);

        if (!reservation.reserved()) {
            log.warn("하루 호출 한도가 남지 않아 수집을 건너뛴다. 노선={} 배치={}",
                sourceRouteId, reservation.batchId());
            return;
        }

        if (!batchLedger.markDispatching(reservation.token(), scheduledAt, now())) {
            log.warn("전송 상태 또는 호출 한도 조건을 만족하지 않아 요청을 보내지 않는다. 노선={} 배치={}", sourceRouteId, reservation.batchId());
            return;
        }

        batchLedger.conclude(reservation.token(), observationSource.read(sourceRouteId));
    }

    /** 같은 초에 계획한 동일 노선의 수집은 같은 배치로 처리한다. */
    private String attemptKeyOf(
        String sourceRouteId,
        OffsetDateTime scheduledAt
    ) {
        return "%s-%s".formatted(sourceRouteId, scheduledAt.toInstant().truncatedTo(ChronoUnit.SECONDS));
    }

    private OffsetDateTime now() {
        return clock.instant().atZone(clock.getZone()).toOffsetDateTime();
    }
}

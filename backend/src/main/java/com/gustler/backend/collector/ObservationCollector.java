package com.gustler.backend.collector;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 노선 하나의 한 판을 처음부터 끝까지 수행한다.
 *
 * <p>자리를 먼저 잡고 보낸다. 보내고 나서 세면 늦는다. 하루 10,000회에 두 노선 실측이 8,194회라
 * 여유가 없다.
 *
 * <p>막힌 판도 판으로 남긴다. 예산이 없어 호출을 건너뛴다는 것은 그 노선의 그 시각 데이터에
 * 구멍이 난다는 뜻인데, 아무 기록 없이 막으면 구멍이 난 것을 한참 뒤에야 안다.
 */
@Component
public class ObservationCollector {

    private static final Logger log = LoggerFactory.getLogger(ObservationCollector.class);

    private final RouteCatalogLoader routeCatalogLoader;
    private final ObservationBatchLedger batchLedger;
    private final GbisLocationSource locationSource;
    private final Clock clock;

    public ObservationCollector(
        RouteCatalogLoader routeCatalogLoader,
        ObservationBatchLedger batchLedger,
        GbisLocationSource locationSource,
        Clock clock
    ) {
        this.routeCatalogLoader = routeCatalogLoader;
        this.batchLedger = batchLedger;
        this.locationSource = locationSource;
        this.clock = clock;
    }

    public void collectOnce(
        String upstreamRouteId
    ) {
        OffsetDateTime scheduledAt = now();

        OptionalLong routeVersionId = routeCatalogLoader.currentVersionOf(upstreamRouteId, scheduledAt);
        if (routeVersionId.isEmpty()) {
            log.warn("지금 쓰는 노선 판본이 없어 수집을 건너뛴다. 노선={}", upstreamRouteId);
            return;
        }

        collectOn(routeVersionId.getAsLong(), upstreamRouteId, scheduledAt);
    }

    private void collectOn(
        final long routeVersionId,
        String upstreamRouteId,
        OffsetDateTime scheduledAt
    ) {
        ObservationBatchReservation reservation = batchLedger.reserve(
            new ObservationAttempt(routeVersionId, scheduledAt, attemptKeyOf(upstreamRouteId, scheduledAt)),
            scheduledAt);

        if (!reservation.reserved()) {
            log.warn("하루 호출 한도가 남지 않아 수집을 건너뛴다. 이 판은 관측이 비어 있다. 노선={} 묶음={}",
                upstreamRouteId, reservation.batchId());
            return;
        }

        batchLedger.markDispatching(reservation.batchId(), now());
        batchLedger.conclude(reservation.batchId(), readOrGiveUp(upstreamRouteId), now());
    }

    /**
     * 보낸 뒤에 뜻밖의 예외가 나도 그 묶음을 열어둔 채로 끝내지 않는다.
     *
     * <p>GbisLocationSource 가 RestClientException 은 NoResponse 로 접어주는데 그 밖의 것은 그대로 올라온다.
     * 그러면 conclude 까지 못 가고 묶음이 DISPATCHING 으로 굳는다. 보낸 것은 맞고 결과만 모르는 상태라
     * 응답이 안 온 것과 같은 자리(UNKNOWN_AFTER_DISPATCH)로 닫는다.
     */
    private GbisLocationResult readOrGiveUp(
        String upstreamRouteId
    ) {
        try {
            return locationSource.read(upstreamRouteId);
        } catch (final RuntimeException e) {
            log.error("상류를 부른 뒤 뜻밖의 예외가 났다. 보낸 것은 맞고 결과만 모른다. 노선={}",
                upstreamRouteId, e);
            return new GbisLocationResult.NoResponse(e.getMessage());
        }
    }

    /**
     * 한 번의 계획을 가리키는 키. 같은 계획을 재시도하면 값이 같아 묶음이 두 개 안 생긴다.
     * 첨두에 15초마다 부르므로 초까지 넣어야 판이 서로 안 겹친다.
     */
    private String attemptKeyOf(
        String upstreamRouteId,
        OffsetDateTime scheduledAt
    ) {
        return "%s-%s".formatted(upstreamRouteId, scheduledAt.toInstant().truncatedTo(ChronoUnit.SECONDS));
    }

    private OffsetDateTime now() {
        return clock.instant().atZone(clock.getZone()).toOffsetDateTime();
    }
}

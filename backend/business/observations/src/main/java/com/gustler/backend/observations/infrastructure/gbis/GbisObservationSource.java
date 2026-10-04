package com.gustler.backend.observations.infrastructure.gbis;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.gbis.api.GbisLocationResult;
import com.gustler.backend.gbis.api.GbisLocationSource;
import com.gustler.backend.observations.domain.ObservationReply;
import com.gustler.backend.observations.domain.ObservationSource;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import com.gustler.backend.observations.domain.ObservationResponse;
import org.slf4j.MDC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** GBIS 조회와 응답 해석을 수집 업무의 조회 계약에 연결한다. */
public class GbisObservationSource implements ObservationSource {
    private static final Logger log = LoggerFactory.getLogger(GbisObservationSource.class);
    private final GbisLocationSource locationSource;

    public GbisObservationSource(GbisLocationSource locationSource) {
        this.locationSource = locationSource;
    }

    /**
     * 보낸 뒤에 뜻밖의 예외가 나도 그 묶음을 열어둔 채로 끝내지 않는다.
     *
     * <p>GbisLocationSource 가 RestClientException 은 NoResponse 로 접어주는데 그 밖의 것은 그대로 올라온다.
     * 그러면 conclude 까지 못 가고 묶음이 DISPATCHING 으로 굳는다. 보낸 것은 맞고 결과만 모르는 상태라
     * 응답이 안 온 것과 같은 자리(UNKNOWN_AFTER_DISPATCH)로 닫는다.
     */
    @Override
    public ObservationReply read(String sourceRouteId, String keyAlias) {
        GbisLocationResult result;
        try {
            result = WorkerOperationLog.measure("collection_upstream", sourceRouteId,
                () -> locationSource.read(sourceRouteId, keyAlias));
        } catch (final RuntimeException e) {
            log.error("상류를 부른 뒤 뜻밖의 예외가 났다. 보낸 것은 맞고 결과만 모른다. 노선={} exceptionType={}",
                sourceRouteId, e.getClass().getSimpleName());
            if (MDC.get("collectionAttemptId") != null) {
                MDC.put("collectionTransport", "UNEXPECTED_EXCEPTION");
                MDC.put("collectionTransportCause", e.getClass().getSimpleName());
            }
            result = new GbisLocationResult.NoResponse(e.getMessage());
        }
        if (MDC.get("collectionAttemptId") != null) {
            MDC.put("collectionUpstreamResult", result.getClass().getSimpleName());
            if (result instanceof GbisLocationResult.Success success && success.buses() != null) {
                long mismatches = success.buses().stream()
                    .filter(bus -> !Objects.equals(sourceRouteId, bus.routeId())).count();
                MDC.put("collectionRouteMismatchRows", Long.toString(mismatches));
            }
        }
        GbisLocationResult received = result;
        return new ObservationReply() {
            @Override public ObservationResponse interpret(OffsetDateTime receivedAt) {
                return GbisObservationMapper.response(received, receivedAt);
            }

            @Override public Optional<StopReference> sourceReference(int stopOrder, String stopId) {
                if (received instanceof GbisLocationResult.Success success && success.buses() != null) {
                    for (int index = 0; index < success.buses().size(); index++) {
                        var bus = success.buses().get(index);
                        if (Objects.equals(bus.stopSequence(), stopOrder) && Objects.equals(bus.stopId(), stopId)) {
                            return Optional.of(new StopReference(index, bus.routeId(), bus.stopSequence(), bus.stopId()));
                        }
                    }
                }
                return Optional.empty();
            }
        };
    }
}

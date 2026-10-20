package com.gustler.backend.observations.infrastructure.gbis;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.gbis.api.GbisLocationResult;
import com.gustler.backend.gbis.api.GbisLocationResult.NoResponse;
import com.gustler.backend.gbis.api.GbisLocationResult.Success;
import com.gustler.backend.gbis.api.GbisLocationSource;
import com.gustler.backend.gbis.api.dto.BusLocationResponse.BusLocation;
import com.gustler.backend.observations.domain.ObservationReply;
import com.gustler.backend.observations.domain.ObservationReply.StopReference;
import com.gustler.backend.observations.domain.ObservationResponse;
import com.gustler.backend.observations.domain.ObservationSource;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** GBIS 조회와 응답 해석을 수집 업무의 조회 계약에 연결한다. */
public class GbisObservationSource implements ObservationSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(GbisObservationSource.class);
    private final GbisLocationSource locationSource;

    public GbisObservationSource(GbisLocationSource locationSource) {
        this.locationSource = locationSource;
    }

    /**
     * 전송한 뒤 예상하지 못한 예외가 나도 수집 묶음을 열린 상태로 남기지 않는다.
     *
     * <p>GbisLocationSource 가 RestClientException 은 NoResponse 로 변환하지만 그 밖의 예외는 그대로 전파한다.
     * 그러면 conclude 까지 진행하지 못해 묶음이 DISPATCHING 에 머문다. 전송했고 결과만 모르는 상태이므로
     * 응답이 오지 않은 경우와 같은 상태(UNKNOWN_AFTER_DISPATCH)로 닫는다.
     */
    @Override
    public ObservationReply read(String sourceRouteId, String keyAlias) {
        GbisLocationResult result;
        try {
            result = WorkerOperationLog.measure("collection_upstream", sourceRouteId,
                () -> locationSource.read(sourceRouteId, keyAlias));
        } catch (RuntimeException e) {
            LOGGER.error("상류를 부른 뒤 뜻밖의 예외가 났다. 보낸 것은 맞고 결과만 모른다. 노선={} exceptionType={}",
                sourceRouteId, e.getClass().getSimpleName());
            if (MDC.get("collectionAttemptId") != null) {
                MDC.put("collectionTransport", "UNEXPECTED_EXCEPTION");
                MDC.put("collectionTransportCause", e.getClass().getSimpleName());
            }
            result = new NoResponse(e.getMessage());
        }
        if (MDC.get("collectionAttemptId") != null) {
            MDC.put("collectionUpstreamResult", result.getClass().getSimpleName());
            if (result instanceof Success success && success.buses() != null) {
                final long mismatches = success.buses()
                    .stream()
                    .filter(bus -> !Objects.equals(sourceRouteId, bus.routeId()))
                    .count();
                MDC.put("collectionRouteMismatchRows", Long.toString(mismatches));
            }
        }
        GbisLocationResult received = result;
        return new ObservationReply() {
            @Override
            public ObservationResponse interpret(OffsetDateTime receivedAt) {
                return GbisObservationMapper.response(received, receivedAt);
            }

            @Override
            public Optional<StopReference> sourceReference(final int stopOrder, String stopId) {
                if (received instanceof Success success && success.buses() != null) {
                    for (int index = 0; index < success.buses().size(); index++) {
                        BusLocation bus = success.buses().get(index);
                        if (Objects.equals(bus.stopSequence(), stopOrder) && Objects.equals(bus.stopId(), stopId)) {
                            return Optional.of(
                                new StopReference(index, bus.routeId(), bus.stopSequence(), bus.stopId()));
                        }
                    }
                }
                return Optional.empty();
            }
        };
    }
}

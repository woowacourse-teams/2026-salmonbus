package com.gustler.backend.observations.infrastructure.gbis;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.gbis.api.GbisLocationResult;
import com.gustler.backend.gbis.api.GbisLocationSource;
import com.gustler.backend.gbis.api.dto.BusLocationResponse;
import com.gustler.backend.observations.domain.CollectionResult;
import com.gustler.backend.observations.domain.ObservationResponse;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** GBIS 조회와 응답 해석을 수집 업무의 조회 계약에 연결한다. */
public class GbisObservationClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(GbisObservationClient.class);

    private final GbisLocationSource locationSource;

    public GbisObservationClient(GbisLocationSource locationSource) {
        this.locationSource = locationSource;
    }

    /**
     * 전송한 뒤 예상하지 못한 예외가 나도 수집 묶음을 열린 상태로 남기지 않는다.
     *
     * <p>GbisLocationSource 가 RestClientException 은 NoResponse 로 변환하지만 그 밖의 예외는 그대로 전파한다.
     * 그러면 conclude 까지 진행하지 못해 묶음이 DISPATCHING 에 머문다. 전송했고 결과만 모르는 상태이므로
     * 응답이 오지 않은 경우와 같은 상태(UNKNOWN_AFTER_DISPATCH)로 닫는다.
     */
    public ObservationResponse read(String sourceRouteId, String keyAlias) {
        GbisLocationResult upstreamResult;
        try {
            upstreamResult = WorkerOperationLog.measure("collection_upstream", sourceRouteId,
                () -> locationSource.read(sourceRouteId, keyAlias));
        } catch (RuntimeException transportFailure) {
            LOGGER.error("상류를 부른 뒤 뜻밖의 예외가 났다. 보낸 것은 맞고 결과만 모른다. 노선={} exceptionType={}",
                sourceRouteId, transportFailure.getClass().getSimpleName());

            if (MDC.get("collectionAttemptId") != null) {
                MDC.put("collectionTransport", "UNEXPECTED_EXCEPTION");
                MDC.put("collectionTransportCause", transportFailure.getClass().getSimpleName());
            }
            upstreamResult = new GbisLocationResult.NoResponse(transportFailure.getMessage());
        }

        if (MDC.get("collectionAttemptId") != null) {
            MDC.put("collectionUpstreamResult", upstreamResult.getClass().getSimpleName());

            if (upstreamResult instanceof GbisLocationResult.Success successfulResponse
                && successfulResponse.buses() != null) {
                final long routeMismatchCount = successfulResponse.buses()
                    .stream()
                    .filter(bus -> !Objects.equals(sourceRouteId, bus.routeId()))
                    .count();
                MDC.put("collectionRouteMismatchRows", Long.toString(routeMismatchCount));
            }
        }

        GbisLocationResult receivedResult = upstreamResult;
        return new ObservationResponse() {
            @Override
            public CollectionResult interpret(OffsetDateTime receivedAt) {
                return GbisObservationMapper.response(receivedResult, receivedAt);
            }

            @Override
            public Optional<ObservationResponse.SourceStopReference> sourceReference(
                final int stopOrder,
                String stopId
            ) {
                if (!(receivedResult instanceof GbisLocationResult.Success successfulResponse)
                    || successfulResponse.buses() == null) {
                    return Optional.empty();
                }
                return IntStream.range(0, successfulResponse.buses().size())
                    .filter(index -> {
                        BusLocationResponse.BusLocation bus = successfulResponse.buses().get(index);
                        return Objects.equals(bus.stopSequence(), stopOrder) && Objects.equals(bus.stopId(), stopId);
                    })
                    .mapToObj(index -> {
                        BusLocationResponse.BusLocation bus = successfulResponse.buses().get(index);
                        return new ObservationResponse.SourceStopReference(
                                index,
                                bus.routeId(),
                                bus.stopSequence(),
                                bus.stopId()
                        );
                    })
                    .findFirst();
            }
        };
    }
}

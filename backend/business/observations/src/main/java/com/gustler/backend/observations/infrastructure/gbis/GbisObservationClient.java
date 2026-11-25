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

/** GBIS에서 관측을 조회하고 응답을 수집 결과로 해석한다. */
public class GbisObservationClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(GbisObservationClient.class);

    private final GbisLocationSource locationSource;

    public GbisObservationClient(GbisLocationSource locationSource) {
        this.locationSource = locationSource;
    }

    /**
     * GBIS 요청을 보낸 뒤 예외가 발생해도 수집 배치를 미완료 상태로 남기지 않는다.
     *
     * <p>{@code GbisLocationSource}는 {@code RestClientException}만 {@code NoResponse}로 바꾸고 다른 예외는 전파한다.
     * 이때는 {@code conclude}를 호출하지 못해 배치가 {@code DISPATCHING}에 남는다.
     * 요청은 보냈지만 결과를 확인할 수 없으므로 응답 미수신과 같은 {@code UNKNOWN_AFTER_DISPATCH}로 종료한다.
     */
    public ObservationResponse read(String sourceRouteId, String keyAlias) {
        GbisLocationResult upstreamResult;
        try {
            upstreamResult = WorkerOperationLog.measure("collection_upstream", sourceRouteId,
                () -> locationSource.read(sourceRouteId, keyAlias));
        } catch (RuntimeException transportFailure) {
            LOGGER.error("GBIS 요청을 보낸 뒤 예외가 발생해 수집 결과를 확인할 수 없다. 노선={} exceptionType={}",
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

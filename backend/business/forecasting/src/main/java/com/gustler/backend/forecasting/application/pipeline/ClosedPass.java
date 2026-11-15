package com.gustler.backend.forecasting.application.pipeline;

import com.gustler.backend.forecasting.api.pipeline.PassClosed;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvent;
import com.gustler.backend.forecasting.domain.pipeline.SourceDates;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 배치 하나로 닫힌 정류장 통과다. 통과 사건은 {@code AlightingAwareHistoryCompactor.resolve}가 만든 것을 그대로 담고,
 * 그 차량의 연결 시작 관측 번호와 "지난 정류장이 목표와 정확히 같고 좌석을 아는 관측 가운데 가장 늦은 것의 확정 시각"을 더한다.
 * 그런 관측이 없으면 마지막 값은 비어 있다. 연결 시작을 실제로 본 차량의 통과만 만든다.
 */
public record ClosedPass(
    AlightingAwarePassEvent event,
    long chainStartObservationId,
    Instant reachedKnownSeatAt
) {
    /**
     * 저장·전달용 사건으로 바꾼다. 출발 날짜는 마지막 관측 확정 시각의 한국 날짜이고,
     * 노선 판본은 통과를 닫은 관측의 판본이다.
     *
     * @param sourceDate 통과를 닫은 배치의 나눔 날짜
     * @param routeId 통과를 닫은 배치의 노선
     * @return {@code stop_pass} 한 줄과 같은 사건
     */
    public PassClosed toEvent(LocalDate sourceDate, final long routeId) {
        AlightingAwareObservation arrival = event.arrival();
        AlightingAwareObservation first = event.firstDeparture();
        AlightingAwareObservation last = event.departure();
        AlightingAwareObservation closure = event.confirmation();
        return new PassClosed(sourceDate, routeId, event.tripId(), event.stopOrder(), closure.routeVersionId(),
            event.vehicleId(), chainStartObservationId, last.receivedAt().atZone(SourceDates.KOREA).toLocalDate(),
            arrival.observationId(), arrival.seats(), arrival.receivedAt(), arrival.sourceReceivedAt(),
            first.observationId(), first.receivedAt(), last.observationId(), last.seats(), last.receivedAt(),
            last.sourceReceivedAt(), reachedKnownSeatAt, closure.observationId(), closure.batchId(),
            event.confirmedAt());
    }
}

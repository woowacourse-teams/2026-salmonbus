package com.gustler.backend.forecasting.api.forecast;

import java.util.List;

/** 커밋한 배치의 정산 입력이다. 목록 순서대로 처리하며 큐에는 목록 전체를 한 번에 등록한다. */
public record ForecastBatchEvents(List<Event> events) {

    public ForecastBatchEvents {
        events = List.copyOf(events);
    }

    public sealed interface Event permits ObservationBatchSeen, ForecastPublished, ClosedPasses {

        long routeId();
    }

    /** 같은 노선의 비어 있지 않은 통과 묶음이다. 결과를 저장 버퍼에 넣는 기존 단위를 유지한다. */
    public record ClosedPasses(List<PassClosed> passes) implements Event {

        public ClosedPasses {
            passes = List.copyOf(passes);
        }

        @Override
        public long routeId() {
            return passes.getFirst().routeId();
        }
    }
}

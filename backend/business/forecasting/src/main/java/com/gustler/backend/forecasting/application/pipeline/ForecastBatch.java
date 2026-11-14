package com.gustler.backend.forecasting.application.pipeline;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 예보 단계에 넣는 관측 배치 하나다. 수신 시각은 {@code response_received_at}, 착수 시각은 이 배치의 입력을 확정한 시각이다.
 * 줄 목록은 배치에 저장된 모든 줄이며, 옛 경로의 읽기 순서와 같도록 차량 번호 순(DB 정렬)으로 넘긴다.
 * 수집 출처·결과·저장 줄 수는 옛 경로의 확정 조건({@code CONFIRMED_INPUT})을 판정하는 데 쓴다.
 */
public record ForecastBatch(
    long observationBatchId,
    long routeVersionId,
    Instant receivedAt,
    Instant confirmedAt,
    String ingestionOrigin,
    String outcome,
    Integer storedRows,
    List<ForecastObservationRow> rows
) {
    /** 수신 뒤 이 시간 안에 착수해야 확정 입력이다. 옛 경로의 {@code interval '90 seconds'}와 같다. */
    public static final Duration MAX_CONFIRMATION_DELAY = Duration.ofSeconds(90);

    public ForecastBatch {
        rows = List.copyOf(rows);
    }

    /**
     * 정상 수집이고 줄이 있는 성공이며, 착수 시각이 수신 시각부터 90초 안(양 끝 포함)일 때만 참이다.
     * 이 조건을 만족하지 않는 배치는 상태에 넣지 않고 순위도 쓰지 않는다.
     */
    public boolean confirmed() {
        if (!"LIVE".equals(ingestionOrigin) || !"SUCCESS_ROWS".equals(outcome)) {
            return false;
        }
        if (storedRows == null || storedRows <= 0 || receivedAt == null || confirmedAt == null) {
            return false;
        }
        return !confirmedAt.isBefore(receivedAt) && !confirmedAt.isAfter(receivedAt.plus(MAX_CONFIRMATION_DELAY));
    }

    /** 저장 줄 수와 실제로 읽은 줄 수가 같은지다. 옛 경로의 {@code CHECK_ROWS}와 같은 뜻이다. */
    public boolean rowsComplete() {
        return storedRows != null && storedRows == rows.size();
    }
}

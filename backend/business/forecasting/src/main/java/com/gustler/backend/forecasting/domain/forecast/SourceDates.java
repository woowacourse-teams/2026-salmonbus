package com.gustler.backend.forecasting.domain.forecast;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 예보 기록 테이블의 나눔 기준 날짜({@code source_date})를 정하는 유일한 곳이다.
 *
 * <p>기준은 관측 배치의 예약 시각({@code observation_batch.scheduled_at})의 한국 날짜다. 수신 시각은 예약 때 비어 있고
 * 실패한 배치는 끝까지 비어 있어 기준으로 쓰지 않는다. 예보 실행·예보 기록은 그 배치의 날짜, 통과·정답은 닫은 배치의 날짜,
 * 미해결 기록은 예보의 날짜를 따른다.
 */
public final class SourceDates {
    /** 나눔 기준 날짜의 시간대. */
    public static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    private SourceDates() {
    }

    /**
     * 배치 예약 시각이 속한 한국 날짜를 돌려준다.
     *
     * @param scheduledAt 관측 배치의 예약 시각
     * @return 한국 시간대 기준 날짜
     */
    public static LocalDate of(Instant scheduledAt) {
        Objects.requireNonNull(scheduledAt, "배치 예약 시각이 필요하다");
        return scheduledAt.atZone(KOREA).toLocalDate();
    }
}

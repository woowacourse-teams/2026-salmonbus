package com.gustler.backend.processor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** 한국 시각 기준 하루. 당일 성적은 자정에 새로 시작한다. 경계를 {@code Instant} 로 들고 있다. */
public record SeoulDay(
    LocalDate date,
    Instant start,
    Instant end
) {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    public static SeoulDay containing(
        Instant moment
    ) {
        return of(moment.atZone(KOREA).toLocalDate());
    }

    public static SeoulDay of(
        LocalDate date
    ) {
        return new SeoulDay(
            date,
            date.atStartOfDay(KOREA).toInstant(),
            date.plusDays(1).atStartOfDay(KOREA).toInstant());
    }
}

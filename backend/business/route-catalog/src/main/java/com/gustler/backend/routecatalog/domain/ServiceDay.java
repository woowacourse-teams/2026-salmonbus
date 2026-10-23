package com.gustler.backend.routecatalog.domain;

/**
 * 배차간격의 평일·토요일·일요일·공휴일 구분이다.
 * 오늘의 공휴일 여부를 판정하는 달력은 아니다.
 */
public enum ServiceDay {
    WEEKDAY,
    SATURDAY,
    SUNDAY,
    HOLIDAY,
    ;
}

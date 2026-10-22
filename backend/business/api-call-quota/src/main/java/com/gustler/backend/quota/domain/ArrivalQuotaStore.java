package com.gustler.backend.quota.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 한국 날짜별 도착정보 전체 예산과 키별 예산을 원자적으로 갱신하는 저장 포트다. */
public interface ArrivalQuotaStore {
    /** 전체 한도와 키 한도를 한 트랜잭션에서 예약한다. 전부 소진됐으면 아무 행도 증가시키지 않는다. */
    Optional<String> reserve(LocalDate date, int totalLimit, List<KeyLimit> orderedKeys);

    /** 해당 날짜의 키별 장부를 소진 처리한다. 전체 장부의 예약 횟수는 바꾸지 않는다. */
    void exclude(LocalDate date, String keyAlias);

    /** 키 별칭과 해당 키의 하루 호출 한도이며, 목록의 순서가 예약 시도 순서다. */
    record KeyLimit(String alias, int limit) {
    }
}

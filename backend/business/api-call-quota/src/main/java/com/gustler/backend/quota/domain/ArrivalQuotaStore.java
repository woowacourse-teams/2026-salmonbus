package com.gustler.backend.quota.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ArrivalQuotaStore {
    /** 전체 한도와 키 한도를 한 트랜잭션에서 예약한다. 전부 소진됐으면 아무 행도 증가시키지 않는다. */
    Optional<String> reserve(LocalDate date, int totalLimit, List<KeyLimit> orderedKeys);

    void exclude(LocalDate date, String keyAlias);

    record KeyLimit(String alias, int limit) { }
}

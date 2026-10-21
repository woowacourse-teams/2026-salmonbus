package com.gustler.backend.quota.application;

import com.gustler.backend.quota.api.ArrivalCallQuota;
import com.gustler.backend.quota.api.ArrivalQuotaPolicy;
import com.gustler.backend.quota.domain.ArrivalQuotaStore;
import com.gustler.backend.quota.domain.ArrivalQuotaStore.KeyLimit;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** 날짜와 키 선택 정책을 적용하고, 원자적인 예산 예약을 저장 포트에 맡긴다. */
public final class ArrivalCallQuotaLedger implements ArrivalCallQuota {
    private final ArrivalQuotaStore store;
    private final ArrivalQuotaPolicy policy;
    private final List<String> aliases;
    private final AtomicInteger cursor = new AtomicInteger();

    public ArrivalCallQuotaLedger(ArrivalQuotaStore store, ArrivalQuotaPolicy policy) {
        this.store = store;
        this.policy = policy;
        this.aliases = policy.keyLimits()
            .keySet()
            .stream()
            .sorted()
            .toList();
    }

    @Override
    public Optional<ArrivalCallQuota.Reservation> reserve(Instant dispatchAt) {
        LocalDate date = dispatchAt.atZone(KOREA).toLocalDate();
        final int start = Math.floorMod(cursor.getAndIncrement(), aliases.size());
        List<KeyLimit> keys = IntStream.range(0, aliases.size())
            .mapToObj(offset -> aliases.get((start + offset) % aliases.size()))
            .map(alias -> new KeyLimit(alias, policy.keyLimits().get(alias)))
            .collect(Collectors.toCollection(ArrayList::new));
        return store.reserve(date, policy.dailyLimit(), keys).map(alias -> new ArrivalCallQuota.Reservation(alias, date));
    }

    @Override
    public void exclude(ArrivalCallQuota.Reservation reservation) {
        store.exclude(reservation.koreanDate(), reservation.keyAlias());
    }
}

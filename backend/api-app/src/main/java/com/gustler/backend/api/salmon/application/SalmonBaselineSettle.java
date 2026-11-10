package com.gustler.backend.api.salmon.application;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** 기준 토큰이 같으면 그 읽기를 쓰고, 예산 안에서 계속 바뀌면 한 번 더 확인한 뒤 불안정으로 본다. */
public final class SalmonBaselineSettle {
    private SalmonBaselineSettle() {
    }

    public static <T> Optional<T> untilStable(SalmonEpochBudget budget, Supplier<T> read, Function<T, ?> token) {
        Object seen = token.apply(read.get());
        for (int epoch = 2; epoch <= budget.budget(); epoch++) {
            T next = read.get();
            Object nextToken = token.apply(next);
            if (Objects.equals(seen, nextToken)) {
                return Optional.of(next);
            }
            seen = nextToken;
        }
        T confirm = read.get();
        if (Objects.equals(seen, token.apply(confirm))) {
            return Optional.of(confirm);
        }
        return Optional.empty();
    }
}

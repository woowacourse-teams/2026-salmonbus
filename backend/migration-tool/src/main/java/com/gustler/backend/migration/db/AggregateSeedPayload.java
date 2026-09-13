package com.gustler.backend.migration.db;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

record AggregateSeedPayload(
    String compressedSha256,
    String receiptSha256,
    String canonicalSha256,
    String canonicalRowsSha256,
    String primaryKeySha256,
    String calculationVersion,
    int generatedGuardSeconds,
    int settlementGuardSeconds,
    Map<String, Instant> sourceCutoffByRoute,
    List<SeedHourlyRow> rows,
    SeedRows.AggregateSet aggregates
) {

    AggregateSeedPayload {
        sourceCutoffByRoute = Map.copyOf(sourceCutoffByRoute);
        rows = List.copyOf(rows);
    }

    long sampleCount() {
        return aggregates.global().sampleCount();
    }
}

record SeedHourlyRow(
    String modelRoute,
    int stopOrder,
    LocalDate arrivalDateKst,
    Instant arrivalHourStart,
    BigDecimal fillRateTotal,
    BigDecimal netBoardingTotal,
    BigDecimal capacityTotal,
    int sampleCount
) {
}

package com.gustler.backend.migration.db;

import com.gustler.backend.migration.CanonicalJson;
import com.gustler.backend.migration.Sha256;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SeedRows {

    private SeedRows() {
    }

    static Comparator<SeedHourlyRow> order() {
        return Comparator.comparing(SeedHourlyRow::modelRoute)
            .thenComparing(SeedHourlyRow::arrivalHourStart)
            .thenComparingInt(SeedHourlyRow::stopOrder);
    }

    static List<SeedHourlyRow> sorted(
        List<SeedHourlyRow> rows
    ) {
        return rows.stream().sorted(order()).toList();
    }

    static String canonicalRowsSha256(
        List<SeedHourlyRow> rows
    ) {
        return Sha256.of(CanonicalJson.bytesOf(
            sorted(rows).stream().map(SeedRows::rowMap).toList()));
    }

    static String primaryKeySha256(
        List<SeedHourlyRow> rows
    ) {
        StringBuilder lines = new StringBuilder();
        for (SeedHourlyRow row : sorted(rows)) {
            lines.append(row.modelRoute()).append('\t')
                .append(row.stopOrder()).append('\t')
                .append(row.arrivalHourStart()).append('\n');
        }
        return Sha256.of(lines.toString().getBytes(StandardCharsets.UTF_8));
    }

    static AggregateSet aggregateSet(
        List<SeedHourlyRow> rows
    ) {
        LinkedHashMap<String, AggregateTotals> routes = new LinkedHashMap<>();
        routes.put("1650", totals(rows.stream().filter(row -> "1650".equals(row.modelRoute())).toList()));
        routes.put("3330", totals(rows.stream().filter(row -> "3330".equals(row.modelRoute())).toList()));
        return new AggregateSet(Map.copyOf(routes), totals(rows));
    }

    static Map<String, Object> rowMap(
        SeedHourlyRow row
    ) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("modelRoute", row.modelRoute());
        value.put("stopOrder", row.stopOrder());
        value.put("arrivalDateKst", row.arrivalDateKst().toString());
        value.put("arrivalHourStartUtc", row.arrivalHourStart().toString());
        value.put("fillRateTotal", row.fillRateTotal());
        value.put("netBoardingTotal", row.netBoardingTotal());
        value.put("capacityTotal", row.capacityTotal());
        value.put("sampleCount", row.sampleCount());
        return value;
    }

    private static AggregateTotals totals(
        List<SeedHourlyRow> rows
    ) {
        BigDecimal fill = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal capacity = BigDecimal.ZERO;
        long samples = 0;
        for (SeedHourlyRow row : rows) {
            fill = fill.add(row.fillRateTotal());
            net = net.add(row.netBoardingTotal());
            capacity = capacity.add(row.capacityTotal());
            samples += row.sampleCount();
        }
        return new AggregateTotals(rows.size(), samples, fill, net, capacity);
    }

    record AggregateSet(
        Map<String, AggregateTotals> routes,
        AggregateTotals global
    ) {

        Map<String, Object> map() {
            LinkedHashMap<String, Object> routeMaps = new LinkedHashMap<>();
            routeMaps.put("1650", routes.get("1650").map());
            routeMaps.put("3330", routes.get("3330").map());
            return Map.of("routes", routeMaps, "global", global.map());
        }
    }

    record AggregateTotals(
        int rowCount,
        long sampleCount,
        BigDecimal fillRateTotal,
        BigDecimal netBoardingTotal,
        BigDecimal capacityTotal
    ) {

        Map<String, Object> map() {
            return Map.of(
                "rowCount", rowCount,
                "sampleCount", sampleCount,
                "fillRateTotal", decimal(fillRateTotal),
                "netBoardingTotal", decimal(netBoardingTotal),
                "capacityTotal", decimal(capacityTotal));
        }

        boolean numericallyEquals(
            AggregateTotals other
        ) {
            return rowCount == other.rowCount
                && sampleCount == other.sampleCount
                && fillRateTotal.compareTo(other.fillRateTotal) == 0
                && netBoardingTotal.compareTo(other.netBoardingTotal) == 0
                && capacityTotal.compareTo(other.capacityTotal) == 0;
        }
    }

    static String decimal(
        BigDecimal value
    ) {
        String text = value.toPlainString();
        return text.indexOf('.') < 0 ? text + ".0" : text;
    }
}

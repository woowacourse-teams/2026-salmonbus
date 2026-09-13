package com.gustler.backend.migration.source;

import com.gustler.backend.migration.Configuration;
import com.gustler.backend.migration.MigrationException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SourceInventoryBuilder {

    private final SourceObjectStore store;

    public SourceInventoryBuilder(
        SourceObjectStore store
    ) {
        this.store = store;
    }

    public SourceInventory build(
        Configuration configuration
    ) {
        String expectedAccount = configuration.required("source.account");
        if (!expectedAccount.equals(store.callerAccountId())) {
            throw new MigrationException("SOURCE_AWS_ACCOUNT_MISMATCH");
        }
        String bucket = configuration.required("source.bucket");
        String region = configuration.required("source.region");
        LocalDate from = LocalDate.parse(configuration.required("source.from-date"));
        LocalDate through = LocalDate.parse(configuration.required("source.through-date"));
        if (through.isBefore(from)) {
            throw new MigrationException("SOURCE_DATE_RANGE_INVALID");
        }
        List<String> routes = configuration.commaSeparated("source.routes");
        String previousPath = configuration.optional("source.previous-inventory");
        SourceInventory previous = previousPath == null ? null : SourceInventory.read(Path.of(previousPath));
        java.time.Instant globalCutoff = configuration.requiredInstant("source.cutoff-at");
        if (previous != null && (!previous.sourceAccountId().equals(expectedAccount)
            || !previous.bucket().equals(bucket) || !previous.region().equals(region)
            || !previous.cutoffAt().isBefore(globalCutoff))) {
            throw new MigrationException("SOURCE_PREVIOUS_INVENTORY_IDENTITY_MISMATCH");
        }
        List<SourceObjectStore.ObjectInfo> current = new ArrayList<>();
        for (long day = 0; day <= ChronoUnit.DAYS.between(from, through); day++) {
            LocalDate partition = from.plusDays(day);
            String date = partition.toString();
            java.time.Instant partitionCutoff = previous == null
                ? dailyFreeze(partition, globalCutoff) : globalCutoff;
            for (String route : routes) {
                current.addAll(store.list(bucket, "records/route=" + route + "/dt=" + date + "/",
                    partitionCutoff));
                current.addAll(store.list(bucket, "raw/route=" + route + "/dt=" + date + "/",
                    partitionCutoff));
            }
        }
        requireUniqueKeys(current);
        List<SourceObjectStore.ObjectInfo> selected = selectDelta(current, previous);
        return new SourceInventory(
            expectedAccount,
            bucket,
            region,
            globalCutoff,
            previous == null ? null : previous.inventorySha256(),
            null,
            current,
            selected.stream().map(SourceObjectStore.ObjectInfo::key).map(com.gustler.backend.migration.Sha256::of)
                .toList());
    }

    private static java.time.Instant dailyFreeze(
        LocalDate partition,
        java.time.Instant globalCutoff
    ) {
        java.time.Instant daily = partition.plusDays(1)
            .atTime(LocalTime.of(0, 15))
            .atZone(ZoneId.of("Asia/Seoul"))
            .toInstant();
        return daily.isBefore(globalCutoff) ? daily : globalCutoff;
    }

    private static List<SourceObjectStore.ObjectInfo> selectDelta(
        List<SourceObjectStore.ObjectInfo> current,
        SourceInventory previous
    ) {
        if (previous == null) {
            return List.copyOf(current);
        }
        Map<String, SourceObjectStore.ObjectInfo> nowByKey = byKey(current);
        Set<String> previousKeys = new LinkedHashSet<>();
        for (SourceObjectStore.ObjectInfo old : previous.entries()) {
            SourceObjectStore.ObjectInfo now = nowByKey.get(old.key());
            if (now == null || now.size() != old.size() || !now.etag().equals(old.etag())) {
                throw new MigrationException("SOURCE_PREVIOUS_INVENTORY_CHANGED");
            }
            previousKeys.add(old.key());
        }
        return current.stream().filter(entry -> !previousKeys.contains(entry.key())).toList();
    }

    private static void requireUniqueKeys(
        List<SourceObjectStore.ObjectInfo> entries
    ) {
        if (byKey(entries).size() != entries.size()) {
            throw new MigrationException("SOURCE_INVENTORY_DUPLICATE_KEY");
        }
    }

    private static Map<String, SourceObjectStore.ObjectInfo> byKey(
        List<SourceObjectStore.ObjectInfo> entries
    ) {
        Map<String, SourceObjectStore.ObjectInfo> result = new HashMap<>();
        for (SourceObjectStore.ObjectInfo entry : entries) {
            result.put(entry.key(), entry);
        }
        return result;
    }
}

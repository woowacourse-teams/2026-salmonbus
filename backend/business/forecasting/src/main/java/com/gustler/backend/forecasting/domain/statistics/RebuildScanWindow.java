package com.gustler.backend.forecasting.domain.statistics;

import java.time.Instant;
import java.util.Optional;

public record RebuildScanWindow(long batchUntilId, Instant afterAt, long afterBatchId, Instant groupEndAt,
                                long groupEndId) {

    public static RebuildScanWindow startingAt(final long batchUntilId) {
        return new RebuildScanWindow(batchUntilId, null, 0, null, 0);
    }

    public boolean hasOpenGroup() {
        return groupEndAt != null;
    }

    public RebuildScanWindow openGroup(final Instant endAt, final long endBatchId) {
        return new RebuildScanWindow(batchUntilId, afterAt, afterBatchId, endAt, endBatchId);
    }

    public RebuildScanWindow closeGroup() {
        return new RebuildScanWindow(batchUntilId, groupEndAt, groupEndId, null, 0);
    }

    public Optional<Instant> after() {
        return Optional.ofNullable(afterAt);
    }
}

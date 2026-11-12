package com.gustler.backend.forecasting.api.alighting;

/** 원관측을 읽어 로컬 체크포인트를 만들며 모델 활성화·예보 발행·DB 쓰기는 하지 않는다. */
public interface BootstrapAlightingAwareHistory {
    Result rebuild(Request request);

    record Request(
        String bundleDirectory, long routeVersionId, long sourceOriginBatchId, long throughBatchId,
        String checkpointDirectory, int batchLimit, int maximumHistoryRows, int maximumCheckpoints, int maximumSeconds
    ) {
        public Request(String bundleDirectory, long routeVersionId, long sourceOriginBatchId, long throughBatchId,
            String checkpointDirectory, int batchLimit, int maximumHistoryRows) {
            this(bundleDirectory, routeVersionId, sourceOriginBatchId, throughBatchId, checkpointDirectory,
                batchLimit, maximumHistoryRows, 10, 30);
        }

        public Request {
            if (hasMissingPath(bundleDirectory, checkpointDirectory)
                || hasInvalidCursor(routeVersionId, sourceOriginBatchId, throughBatchId)
                || hasInvalidLimits(batchLimit, maximumHistoryRows)
                || maximumCheckpoints < 1 || maximumCheckpoints > 1000 || maximumSeconds < 1 || maximumSeconds > 3600) {
                throw new IllegalArgumentException("Invalid AlightingAware history bootstrap arguments");
            }
        }
    }

    private static boolean hasMissingPath(String bundleDirectory, String checkpointDirectory) {
        return bundleDirectory == null || bundleDirectory.isBlank()
            || checkpointDirectory == null || checkpointDirectory.isBlank();
    }

    private static boolean hasInvalidCursor(
        final long routeVersionId,
        final long sourceOriginBatchId,
        final long throughBatchId) {
        return routeVersionId <= 0 || sourceOriginBatchId <= 0 || throughBatchId <= 0;
    }

    private static boolean hasInvalidLimits(final int batchLimit, final int maximumHistoryRows) {
        return batchLimit < 1 || batchLimit > 1000 || maximumHistoryRows < 1
            || maximumHistoryRows == Integer.MAX_VALUE;
    }

    record Result(
        long routeVersionId, long sourceOriginBatchId, long throughBatchId, int checkpoints,
        String checkpointDirectory, String bundleDigest, long processedThroughBatchId, boolean complete
    ) {
        public Result(long routeVersionId, long sourceOriginBatchId, long throughBatchId, int checkpoints,
            String checkpointDirectory, String bundleDigest) {
            this(routeVersionId, sourceOriginBatchId, throughBatchId, checkpoints, checkpointDirectory, bundleDigest,
                throughBatchId, true);
        }
    }
}

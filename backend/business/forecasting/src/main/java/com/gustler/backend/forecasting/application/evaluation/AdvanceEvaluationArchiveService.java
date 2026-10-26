package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.api.evaluation.AdvanceEvaluationArchive;
import com.gustler.backend.forecasting.api.evaluation.EvaluationArchivePolicy;
import com.gustler.backend.forecasting.application.evaluation.ArchiveCompletedEvaluationsService.Archived;
import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveQueue.Candidates;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.State;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 한 호출에 최대 한 묶음만 처리한다. 실패하면 원본을 유지하고 전역 재시도 시각을 늦춘다. */
public final class AdvanceEvaluationArchiveService implements AdvanceEvaluationArchive {
    private static final Logger LOGGER = LoggerFactory.getLogger(AdvanceEvaluationArchiveService.class);
    private final EvaluationArchiveQueue queue;
    private final EvaluationArchiveStore store;
    private final ArchiveCompletedEvaluationsService archive;
    private final PurgeArchivedEvaluationsService purge;
    private final EvaluationArchivePolicy policy;
    private final Clock clock;

    public AdvanceEvaluationArchiveService(
        EvaluationArchiveQueue queue,
        EvaluationArchiveStore store,
        ArchiveCompletedEvaluationsService archive,
        PurgeArchivedEvaluationsService purge,
        EvaluationArchivePolicy policy,
        Clock clock
    ) {
        this.queue = queue;
        this.store = store;
        this.archive = archive;
        this.purge = purge;
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    public synchronized void advance() {
        EvaluationArchiveBatch batch = null;
        String stage = "DISCOVER";
        final long started = System.nanoTime();
        try {
            if (!queue.available()) {
                return;
            }
            Optional<EvaluationArchiveBatch> selected = reserveNextBatch();
            if (selected.isEmpty()) {
                return;
            }
            batch = selected.get();
            stage = "REVALIDATE";
            if (queue.retireChanged(batch)) {
                LOGGER.info("event=evaluation_archive status=RETIRED batchId={} routeVersionId={}", batch.id(),
                    batch.routeVersionId());
                return;
            }
            Archived archived = null;
            if (batch.state() == State.RESERVED) {
                stage = "ARCHIVE";
                archived = archive.archive(batch);
            }
            int removed = 0;
            if (policy.deleteEnabled()) {
                stage = "PURGE";
                removed = purge.purge(batch, archived);
            }
            LOGGER.info(
                "event=evaluation_archive status={} batchId={} routeVersionId={} rows={} deletedRows={} lastSuccessAt={} durationMs={}",
                successStatus(), batch.id(), batch.routeVersionId(),
                batch.rowCount(), removed, clock.instant(), (System.nanoTime() - started) / 1_000_000);
        } catch (RuntimeException failure) {
            // 원본/품질 변경은 다음 순회에서 새 묶음으로 예약한다. 파일은 삭제하지 않는다.
            try {
                if (batch == null || !queue.retireChanged(batch)) {
                    queue.defer(batch);
                }
            } catch (RuntimeException retryFailure) {
                failure.addSuppressed(retryFailure);
            }
            // SQL 원문과 바인딩 값을 운영 로그로 보내지 않는다.
            LOGGER.warn("event=evaluation_archive status=FAILED stage={} batchId={} failureType={} durationMs={}",
                stage, batchIdOf(batch), failure.getClass().getSimpleName(),
                (System.nanoTime() - started) / 1_000_000);
        }
    }

    private Optional<EvaluationArchiveBatch> reserveNextBatch() {
        Optional<UUID> resume = queue.resumable(policy.deleteEnabled());
        if (resume.isPresent()) {
            return store.reclaim(resume.get());
        }
        Optional<Candidates> candidates = queue.next(policy.pageSize());
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Candidates candidate = candidates.orElseThrow();
        return store.reserve(candidate.routeVersionId(), candidate.keys());
    }

    private String successStatus() {
        if (policy.deleteEnabled()) {
            return "PURGED";
        }
        return "ARCHIVED";
    }

    private static Object batchIdOf(EvaluationArchiveBatch batch) {
        if (batch == null) {
            return "-";
        }
        return batch.id();
    }
}

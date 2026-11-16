package com.gustler.backend.forecasting.support;

import com.gustler.backend.forecasting.application.forecast.PendingBatches;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 시험이 수집이 커밋했다고 친 배치만 돌려주는 처리하지 않은 배치 조회다.
 *
 * <p>시험은 흐름의 배치를 미리 모두 넣어 두고, 배치마다 시계를 맞춰 하나씩 처리한다. 예보는 노선 판본의 처리하지 않은 배치를 모두
 * 읽으므로, 아직 수집하지 않은 것으로 칠 배치를 가려야 시험이 정한 배치만 그 시각에 처리된다. 조건과 순서는 감싼 조회의 것을 그대로
 * 쓴다.
 */
public final class CommittedBatches implements PendingBatches {
    private final PendingBatches delegate;
    private final Set<Long> committed = new HashSet<>();
    private Runnable afterNextFind;

    public CommittedBatches(PendingBatches delegate) {
        this.delegate = delegate;
    }

    /** 배치를 수집이 커밋한 것으로 친다. */
    public void commit(final long observationBatchId) {
        committed.add(observationBatchId);
    }

    /** 커밋한 것으로 친 배치를 모두 잊는다. 흐름을 처음부터 다시 처리할 때 쓴다. */
    public void forgetAll() {
        committed.clear();
    }

    /** 다음 조회가 배치를 읽은 직후에 한 번 실행할 일을 정한다. 조회와 처리 사이에 끼어드는 커밋을 흉내 낸다. */
    public void afterNextFind(Runnable task) {
        afterNextFind = task;
    }

    @Override
    public List<Pending> find(final long routeVersionId, Instant notBefore, final int limit) {
        List<Pending> found = delegate.find(routeVersionId, notBefore, Integer.MAX_VALUE).stream()
            .filter(batch -> committed.contains(batch.observationBatchId()))
            .limit(limit)
            .toList();
        Runnable task = afterNextFind;
        afterNextFind = null;
        if (task != null) {
            task.run();
        }
        return found;
    }
}

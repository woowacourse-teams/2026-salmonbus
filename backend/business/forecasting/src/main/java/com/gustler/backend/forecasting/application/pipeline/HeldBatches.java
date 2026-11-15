package com.gustler.backend.forecasting.application.pipeline;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 한 노선의 실행 줄에서 예외로 실패해 다시 처리해야 하는 배치와, 그 배치를 기다리는 뒤 배치를 수신 순서로 기억한다.
 * 맨 앞 배치가 이력에 들어가기 전에는 뒤 배치를 처리하지 않게 하려는 것이다. 메모리에만 두며, 다시 뜨면 줍기가 처리하지 않은
 * 배치를 수신 순서로 다시 넣는다.
 *
 * <p>배치마다 "이 시각이 지나면 다시 처리해도 이력에 들어가지 않는다"는 시각을 함께 든다. 수신 뒤 90초를 넘겨 착수한 배치는 늦음으로
 * 닫히고 이력에 들어가지 않으므로, 그때부터는 뒤 배치를 막을 이유가 없다. 동기화하지 않는다. 그 노선의 줄 스레드에서만 쓴다.
 */
final class HeldBatches {
    private static final Comparator<Held> RECEIVED_ORDER = Comparator.comparing(Held::releaseAt)
        .thenComparingLong(Held::observationBatchId);

    private final List<Held> held = new ArrayList<>();

    boolean isEmpty() {
        return held.isEmpty();
    }

    int size() {
        return held.size();
    }

    boolean contains(final long observationBatchId) {
        return held.stream().anyMatch(batch -> batch.observationBatchId() == observationBatchId);
    }

    /** 기다리는 배치이면서 맨 앞이 아닌지다. 맨 앞 배치가 풀려야 처리할 수 있다. */
    boolean waitsBehindOther(final long observationBatchId) {
        return contains(observationBatchId) && held.getFirst().observationBatchId() != observationBatchId;
    }

    /** 가장 먼저 수신한 기다리는 배치. 비어 있으면 부르지 않는다. */
    Held head() {
        return held.getFirst();
    }

    void removeHead() {
        held.removeFirst();
    }

    /** 기다리는 배치를 더한다. 이미 있으면 그대로 둔다. */
    void add(Held batch) {
        if (contains(batch.observationBatchId())) {
            return;
        }
        held.add(batch);
        held.sort(RECEIVED_ORDER);
    }

    /**
     * 기다리는 배치 하나다.
     *
     * @param releaseAt 이 시각이 지나면 다시 처리해도 이력에 들어가지 않는다. 수신 시각(모르면 그보다 늦지 않은 시각)에 90초를 더한
     *     값이라 수신 순서의 기준으로도 쓴다
     */
    record Held(long observationBatchId, long routeVersionId, Instant releaseAt) {
    }
}

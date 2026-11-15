package com.gustler.backend.forecasting.application.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 채점 결과를 모아 두었다가 한 트랜잭션으로 {@link PassLabelStore}에 쓴다.
 *
 * <p>채점 스레드는 {@link #add(ScoringResult)}로 넣고, 주기 작업은 몇 초마다 {@link #flush()}를 부른다. 두 메서드는 서로 다른
 * 스레드에서 불러도 된다. 쓰기가 실패하면 꺼낸 줄을 다시 앞에 돌려놓고 예외를 그대로 던지므로 다음 {@code flush}가 다시 쓴다.
 * 저장소가 이미 있는 키를 건너뛰므로 같은 줄을 두 번 써도 결과가 같다. {@code route} 행 잠금을 잡지 않는다.
 */
public final class ScoringWriter {
    private final PassLabelStore store;
    private final TransactionOperations transactions;
    private final Object lock = new Object();
    private List<PassLabel> labels = new ArrayList<>();
    private List<UnresolvedForecast> unresolved = new ArrayList<>();

    /**
     * 쓰기를 만든다.
     *
     * @param store 정답·미해결 저장소
     * @param transactions 한 번의 쓰기를 감쌀 트랜잭션
     */
    public ScoringWriter(PassLabelStore store, TransactionOperations transactions) {
        this.store = Objects.requireNonNull(store, "저장소가 필요하다");
        this.transactions = Objects.requireNonNull(transactions, "트랜잭션이 필요하다");
    }

    /**
     * 채점 결과를 쓰기 대기에 넣는다. 짝지어진 예측({@link ScoringResult#scored()})은 저장하지 않는다.
     *
     * @param result 채점 결과
     */
    public void add(ScoringResult result) {
        synchronized (lock) {
            labels.addAll(result.labels());
            unresolved.addAll(result.unresolved());
        }
    }

    /** 쓰기를 기다리는 줄의 수. */
    public int pendingCount() {
        synchronized (lock) {
            return labels.size() + unresolved.size();
        }
    }

    /**
     * 기다리는 줄을 모두 한 트랜잭션으로 쓴다.
     *
     * @return 새로 넣은 줄 수. 이미 있던 키는 세지 않는다
     */
    public Flushed flush() {
        List<PassLabel> takenLabels;
        List<UnresolvedForecast> takenUnresolved;
        synchronized (lock) {
            takenLabels = labels;
            takenUnresolved = unresolved;
            labels = new ArrayList<>();
            unresolved = new ArrayList<>();
        }
        if (takenLabels.isEmpty() && takenUnresolved.isEmpty()) {
            return new Flushed(0, 0);
        }
        try {
            return Objects.requireNonNull(transactions.execute(status -> new Flushed(
                saveLabels(takenLabels),
                saveUnresolved(takenUnresolved))));
        } catch (RuntimeException failure) {
            synchronized (lock) {
                takenLabels.addAll(labels);
                takenUnresolved.addAll(unresolved);
                labels = takenLabels;
                unresolved = takenUnresolved;
            }
            throw failure;
        }
    }

    private int saveLabels(List<PassLabel> taken) {
        if (taken.isEmpty()) {
            return 0;
        }
        return store.saveLabels(taken);
    }

    private int saveUnresolved(List<UnresolvedForecast> taken) {
        if (taken.isEmpty()) {
            return 0;
        }
        return store.saveUnresolved(taken);
    }

    /**
     * 한 번 쓴 결과다.
     *
     * @param labels 새로 넣은 정답 줄 수
     * @param unresolved 새로 넣은 미해결 줄 수
     */
    public record Flushed(int labels, int unresolved) {
    }
}

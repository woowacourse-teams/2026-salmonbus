package com.gustler.backend.forecasting.application.pipeline;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 채점 결과를 모아 두었다가 트랜잭션으로 {@link PassLabelStore}에 쓴다.
 *
 * <p>채점 스레드는 {@link #add(ScoringResult)}로 넣고, 주기 작업은 몇 초마다 {@link #flush()}를 부른다. 두 메서드는 서로 다른
 * 스레드에서 불러도 된다. 쓰기가 실패하면 꺼낸 결과를 다시 앞에 돌려놓고 예외를 그대로 던지므로 다음 {@code flush}가 다시 쓴다.
 * 쓰기는 한 번에 하나만 돈다. 다른 스레드가 쓰는 중에 부른 {@code flush}는 그 쓰기가 끝난 뒤에 남은 줄을 쓰므로, {@code flush}가
 * 돌아온 뒤의 {@link #pendingCount()}는 쓰는 중인 줄을 빠뜨리지 않는다. 저장소가 이미 있는 키를 건너뛰므로 같은 줄을 두 번 써도
 * 결과가 같다. {@code route} 행 잠금을 잡지 않는다.
 *
 * <p>결과는 넣은 순서대로, 넣은 단위를 쪼개지 않고 쓴다. 한 번에 넣은 결과의 정답 줄과 미해결 줄은 같은 트랜잭션에 들어가므로 통과가
 * 닫히며 나온 정답과 그 통과에서 짝이 되지 못한 예측의 미해결 줄이 따로 보이지 않는다. 기다리는 줄이
 * {@code maxRowsPerTransaction}보다 많으면 결과 단위의 경계에서 여러 트랜잭션으로 나눈다. 오래 멈췄다 되감은 직후처럼 줄이
 * 한꺼번에 몰려도 한 트랜잭션이 제한 시간을 넘겨 같은 줄을 끝없이 다시 쓰는 일을 막는다. 한 단위가 상한보다 크면 그 단위만 한
 * 트랜잭션으로 쓴다. 이렇게 쓰므로 저장된 줄이 있으면 그보다 먼저 넣은 결과는 모두 저장돼 있다. 되감기({@link ScoringReplay})가
 * 이 성질로 예보를 다시 읽을 범위를 정한다.
 *
 * <p>트랜잭션 하나의 시간은 {@link WorkerOperationLog}에 {@value #WRITE_OPERATION} 작업으로 남긴다. 쓸 줄이 없으면 트랜잭션을 열지
 * 않으므로 남기지 않는다.
 */
public final class ScoringWriter {
    /** 한 트랜잭션에 쓰는 줄 수의 기본 상한. */
    public static final int DEFAULT_MAX_ROWS_PER_TRANSACTION = 20_000;
    /** 쓰기 트랜잭션 하나의 시간을 남기는 {@link WorkerOperationLog} 작업 이름. */
    public static final String WRITE_OPERATION = "scoring_write";
    private static final String ALL_ROUTES = "all";

    private final PassLabelStore store;
    private final TransactionOperations transactions;
    private final int maxRowsPerTransaction;
    private final Object lock = new Object();
    private final Object writing = new Object();
    private final Deque<ScoringResult> pending = new ArrayDeque<>();
    private int pendingRows;

    /**
     * 기본 상한으로 쓰기를 만든다.
     *
     * @param store 정답·미해결 저장소
     * @param transactions 한 번의 쓰기를 감쌀 트랜잭션
     */
    public ScoringWriter(PassLabelStore store, TransactionOperations transactions) {
        this(store, transactions, DEFAULT_MAX_ROWS_PER_TRANSACTION);
    }

    /**
     * 쓰기를 만든다.
     *
     * @param store 정답·미해결 저장소
     * @param transactions 한 번의 쓰기를 감쌀 트랜잭션
     * @param maxRowsPerTransaction 한 트랜잭션에 쓰는 줄 수의 상한. 1 이상이다
     */
    public ScoringWriter(PassLabelStore store, TransactionOperations transactions, final int maxRowsPerTransaction) {
        this.store = Objects.requireNonNull(store, "저장소가 필요하다");
        this.transactions = Objects.requireNonNull(transactions, "트랜잭션이 필요하다");
        if (maxRowsPerTransaction < 1) {
            throw new IllegalArgumentException("한 트랜잭션의 줄 수 상한은 1 이상이어야 한다: " + maxRowsPerTransaction);
        }
        this.maxRowsPerTransaction = maxRowsPerTransaction;
    }

    /**
     * 채점 결과를 쓰기 대기에 넣는다. 짝지어진 예측({@link ScoringResult#scored()})은 저장하지 않는다.
     *
     * @param result 채점 결과
     */
    public void add(ScoringResult result) {
        if (result.isEmpty()) {
            return;
        }
        synchronized (lock) {
            pending.addLast(result);
            pendingRows += rows(result);
        }
    }

    /** 쓰기를 기다리는 줄의 수. */
    public int pendingCount() {
        synchronized (lock) {
            return pendingRows;
        }
    }

    /**
     * 기다리는 줄을 모두 쓴다. 줄이 상한 이하면 한 트랜잭션이고, 많으면 넣은 단위의 경계에서 여러 트랜잭션으로 나눠 쓴다. 한
     * 트랜잭션이 실패하면 그 결과를 돌려놓고 예외를 던지며, 그 앞 트랜잭션이 쓴 줄은 저장된 채로 남는다.
     *
     * @return 새로 넣은 줄 수. 이미 있던 키는 세지 않는다
     */
    public Flushed flush() {
        synchronized (writing) {
            Flushed total = new Flushed(0, 0);
            List<ScoringResult> taken = take();
            while (!taken.isEmpty()) {
                total = total.plus(write(taken));
                taken = take();
            }
            return total;
        }
    }

    private List<ScoringResult> take() {
        synchronized (lock) {
            List<ScoringResult> taken = new ArrayList<>();
            int rows = 0;
            while (canTake(taken, rows)) {
                ScoringResult result = pending.removeFirst();
                rows += rows(result);
                taken.add(result);
            }
            pendingRows -= rows;
            return taken;
        }
    }

    /** 맨 앞의 결과는 상한보다 커도 꺼내고, 그 뒤로는 꺼낸 줄 수가 상한을 넘지 않을 때만 꺼낸다. */
    private boolean canTake(List<ScoringResult> taken, final int rows) {
        if (pending.isEmpty()) {
            return false;
        }
        if (taken.isEmpty()) {
            return true;
        }
        return rows + rows(pending.peekFirst()) <= maxRowsPerTransaction;
    }

    private Flushed write(List<ScoringResult> taken) {
        List<PassLabel> labels = new ArrayList<>();
        List<UnresolvedForecast> unresolved = new ArrayList<>();
        for (ScoringResult result : taken) {
            labels.addAll(result.labels());
            unresolved.addAll(result.unresolved());
        }
        try {
            return WorkerOperationLog.measure(WRITE_OPERATION, ALL_ROUTES,
                () -> Objects.requireNonNull(transactions.execute(status -> new Flushed(
                    saveLabels(labels),
                    saveUnresolved(unresolved)))));
        } catch (RuntimeException failure) {
            synchronized (lock) {
                for (ScoringResult result : taken.reversed()) {
                    pending.addFirst(result);
                    pendingRows += rows(result);
                }
            }
            throw failure;
        }
    }

    private static int rows(ScoringResult result) {
        return result.labels().size() + result.unresolved().size();
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
        private Flushed plus(Flushed other) {
            return new Flushed(labels + other.labels, unresolved + other.unresolved);
        }
    }
}

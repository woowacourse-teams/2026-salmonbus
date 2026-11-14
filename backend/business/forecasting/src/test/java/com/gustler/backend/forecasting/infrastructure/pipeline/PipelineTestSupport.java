package com.gustler.backend.forecasting.infrastructure.pipeline;

import com.gustler.backend.forecasting.domain.model.alighting.NumericTreeEnsemble;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

/** 새 예보 경로의 통합 시험이 함께 쓰는 모델·시계·트랜잭션 도우미다. */
final class PipelineTestSupport {
    private PipelineTestSupport() {
    }

    /** 따로 커밋하는 트랜잭션을 시험 트랜잭션에 합류시킨다. 같은 SQL을 그대로 쓴다. */
    static PlatformTransactionManager joining(PlatformTransactionManager delegate) {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                DefaultTransactionDefinition joined = new DefaultTransactionDefinition(definition);
                joined.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
                joined.setReadOnly(false);
                joined.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
                return delegate.getTransaction(joined);
            }

            @Override
            public void commit(TransactionStatus status) {
                delegate.commit(status);
            }

            @Override
            public void rollback(TransactionStatus status) {
                delegate.rollback(status);
            }
        };
    }

    static NumericTreeEnsemble stumps(final int features, NumericTreeEnsemble.Kind kind, double[] baseline,
        Random random) {
        double[] thresholds = {0.0, 0.5, 1, 2, 5, 10, 30};
        List<List<List<NumericTreeEnsemble.Node>>> iterations = new ArrayList<>();
        for (int iteration = 0; iteration < 24; iteration++) {
            List<List<NumericTreeEnsemble.Node>> trees = new ArrayList<>();
            for (int tree = 0; tree < baseline.length; tree++) {
                trees.add(List.of(
                    new NumericTreeEnsemble.Node(random.nextInt(features), thresholds[random.nextInt(thresholds.length)],
                        random.nextBoolean(), 1, 2, 0, false),
                    new NumericTreeEnsemble.Node(0, 0, false, 0, 0, random.nextDouble() - 0.5, true),
                    new NumericTreeEnsemble.Node(0, 0, false, 0, 0, random.nextDouble() - 0.5, true)));
            }
            iterations.add(trees);
        }
        return new NumericTreeEnsemble(features, kind, baseline, iterations);
    }

    static String digest() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        byte[] bytes = new byte[32];
        ByteBuffer.wrap(bytes).putLong(first.getMostSignificantBits()).putLong(first.getLeastSignificantBits())
            .putLong(second.getMostSignificantBits()).putLong(second.getLeastSignificantBits());
        return HexFormat.of().formatHex(bytes);
    }

    /** 시험이 배치마다 정하는 시계다. */
    static final class MutableClock extends Clock {
        Instant at = Instant.EPOCH;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return at;
        }
    }
}

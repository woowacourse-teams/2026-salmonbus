package com.gustler.backend.member.infrastructure.jdbc;

import com.gustler.backend.member.api.AnalyticsDeletions;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 삭제 요청 저장과 재시도 결과 처리를 맡는다. 성공할 때까지 공개 ID를 남긴다. */
@Repository
public class JdbcAnalyticsDeletions implements AnalyticsDeletions {
    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcAnalyticsDeletions.class);
    private final JdbcClient jdbc;

    public JdbcAnalyticsDeletions(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void save(UUID publicId) {
        jdbc.sql("INSERT INTO analytics_deletion (public_id, attempts) VALUES (?, 0)")
            .param(publicId).update();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void attempt(UUID publicId, Consumer<List<UUID>> send) {
        List<UUID> pending = jdbc.sql("""
                SELECT public_id FROM analytics_deletion
                WHERE public_id = ? FOR UPDATE SKIP LOCKED
                """)
            .param(publicId).query(UUID.class).list();
        send(pending, send);
    }

    /** 한 번의 HTTP 요청 동안 잠금을 유지한다. 다른 서버가 처리 중인 행은 기다리지 않고 건너뛴다. */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retry(Consumer<List<UUID>> send) {
        List<UUID> pending = jdbc.sql("""
                SELECT public_id FROM analytics_deletion
                ORDER BY attempts, public_id LIMIT 100 FOR UPDATE SKIP LOCKED
                """)
            .query(UUID.class).list();
        send(pending, send);
    }

    private void send(List<UUID> publicIds, Consumer<List<UUID>> send) {
        if (publicIds.isEmpty()) {
            return;
        }
        try {
            send.accept(publicIds);
        } catch (RuntimeException exception) {
            for (UUID publicId : publicIds) {
                jdbc.sql("""
                        UPDATE analytics_deletion
                        SET attempts = LEAST(attempts + 1::bigint, 2147483647) WHERE public_id = ?
                        """)
                    .param(publicId).update();
            }
            LOGGER.warn("Amplitude 삭제 요청에 실패했다. 대기 기록을 유지한다. count={} reason={}",
                publicIds.size(), exception.getClass().getSimpleName());
            return;
        }
        for (UUID publicId : publicIds) {
            jdbc.sql("DELETE FROM analytics_deletion WHERE public_id = ?").param(publicId).update();
        }
    }
}

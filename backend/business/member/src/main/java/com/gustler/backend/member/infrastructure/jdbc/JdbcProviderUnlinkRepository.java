package com.gustler.backend.member.infrastructure.jdbc;

import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.MemberIdentity;
import com.gustler.backend.member.api.PendingUnlink;
import com.gustler.backend.member.domain.ProviderUnlinkRepository;
import com.gustler.backend.member.domain.UnlinkFailure;
import com.gustler.backend.member.domain.UnlinkStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 연결 해제 요청을 임대 시각으로 나눠 가진다.
 *
 * <p>요청을 집는 일은 {@code UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING} 한 문장이다.
 * 안쪽 SELECT 는 다른 트랜잭션이 잠근 행을 기다리지 않고 건너뛰고, 잠근 행의 다음 시도 시각을 임대 만료 시각으로 옮긴 뒤
 * 돌려준다. 이미 다른 트랜잭션이 옮기고 커밋한 행은 잠글 때 조건을 다시 평가해 시도할 때가 아니므로 빠진다.
 * 그래서 서버 여러 대가 동시에 집어도 한 요청은 한 곳에만 나온다. 상태 조건은 부분 인덱스
 * {@code ix_provider_unlink_due} 의 조건과 같은 상수로 써야 인덱스를 탄다.
 */
@Repository
public class JdbcProviderUnlinkRepository implements ProviderUnlinkRepository {
    private static final String INSERT_PENDING = """
        INSERT INTO provider_unlink
            (provider, subject, status, attempts, next_attempt_at, created_at, updated_at)
        VALUES (?, ?, ?, 0, ?, ?, ?)
        RETURNING id
        """;

    private static final String CLAIM_DUE = """
        UPDATE provider_unlink
        SET next_attempt_at = ?, updated_at = ?
        WHERE id IN (
            SELECT id FROM provider_unlink
            WHERE status = 'PENDING' AND next_attempt_at <= ?
            ORDER BY next_attempt_at, id
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        )
        RETURNING id, provider, subject, attempts
        """;

    private static final String LOCK_PENDING_ATTEMPTS = """
        SELECT attempts FROM provider_unlink
        WHERE id = ? AND status = 'PENDING'
        FOR UPDATE
        """;

    private static final String RECORD_FAILURE = """
        UPDATE provider_unlink
        SET attempts = ?, status = ?, next_attempt_at = ?, last_error = ?, updated_at = ?
        WHERE id = ?
        """;

    private static final String DELETE_GAVE_UP = """
        DELETE FROM provider_unlink
        WHERE id IN (
            SELECT id FROM provider_unlink
            WHERE status = 'GAVE_UP' AND updated_at < ?
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        )
        """;

    private final JdbcClient jdbcClient;

    public JdbcProviderUnlinkRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public long savePending(MemberIdentity identity, OffsetDateTime leasedUntil, OffsetDateTime now) {
        return jdbcClient.sql(INSERT_PENDING)
            .params(identity.provider().name(), identity.subject(), UnlinkStatus.PENDING.name(), leasedUntil, now, now)
            .query(Long.class)
            .single();
    }

    @Override
    public List<PendingUnlink> claimDue(OffsetDateTime now, OffsetDateTime leasedUntil, int limit) {
        return jdbcClient.sql(CLAIM_DUE)
            .params(leasedUntil, now, now, limit)
            .query((rows, rowNumber) -> new PendingUnlink(
                rows.getLong("id"),
                new MemberIdentity(LoginProvider.valueOf(rows.getString("provider")), rows.getString("subject")),
                rows.getInt("attempts")))
            .list();
    }

    @Override
    public Optional<Integer> lockPendingAttempts(long id) {
        return jdbcClient.sql(LOCK_PENDING_ATTEMPTS).param(id).query(Integer.class).optional();
    }

    @Override
    public void recordFailure(
        long id,
        UnlinkFailure failure,
        String lastError,
        OffsetDateTime now
    ) {
        jdbcClient.sql(RECORD_FAILURE)
            .params(failure.attempts(), failure.status().name(), failure.nextAttemptAt(), lastError, now, id)
            .update();
    }

    @Override
    public void delete(long id) {
        jdbcClient.sql("DELETE FROM provider_unlink WHERE id = ?").param(id).update();
    }

    @Override
    public int deleteGaveUp(OffsetDateTime updatedBefore, int limit) {
        return jdbcClient.sql(DELETE_GAVE_UP).params(updatedBefore, limit).update();
    }
}

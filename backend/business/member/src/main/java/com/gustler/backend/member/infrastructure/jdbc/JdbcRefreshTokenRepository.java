package com.gustler.backend.member.infrastructure.jdbc;

import com.gustler.backend.member.domain.RefreshTokenFamily;
import com.gustler.backend.member.domain.RefreshTokenRepository;
import com.gustler.backend.member.domain.RotatedRefreshToken;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 계통마다 한 행을 두고, 세대 올리기를 UPDATE 한 문장으로 처리한다.
 *
 * <p>세대 올리기는 {@code UPDATE ... WHERE generation = ? AND expires_at > ? RETURNING} 한 문장이다. 새 만료도 같은
 * 문장에서 {@code LEAST(?, started_at + 최대 유지 시간)} 으로 정하므로 시작 시각을 따로 읽지 않는다.
 * READ COMMITTED 에서 같은 행을 먼저 바꾼 트랜잭션이 있으면 이 문장은 그 트랜잭션이 끝나기를 기다렸다가 조건을 다시
 * 평가한다. 그래서 같은 세대를 동시에 올리려 해도 한 문장만 행을 얻고, 나머지는 0행을 받아 호출하는 쪽이 현재 상태를
 * 읽어 판단한다. 폐기는 행 하나를 지우는 문장이라 진행 중인 세대 올리기와 겹쳐도 그 뒤에 행이 남지 않는다.
 */
@Repository
public class JdbcRefreshTokenRepository implements RefreshTokenRepository {
    private static final String START_FAMILY = """
        INSERT INTO refresh_token (family_id, member_id, generation, started_at, rotated_at, expires_at)
        SELECT ?, id, 0, ?, ?, ?
        FROM member
        WHERE public_id = ?
        """;

    private static final String ROTATE = """
        WITH rotated AS (
            UPDATE refresh_token
            SET generation = generation + 1, rotated_at = ?,
                expires_at = LEAST(?, started_at + make_interval(secs => ?))
            WHERE family_id = ? AND generation = ? AND expires_at > ?
            RETURNING member_id, generation, expires_at
        )
        SELECT m.public_id, rotated.generation, rotated.expires_at
        FROM rotated
        JOIN member m ON m.id = rotated.member_id
        """;

    private static final String FIND = """
        SELECT t.family_id, m.public_id, t.generation, t.rotated_at, t.expires_at
        FROM refresh_token t
        JOIN member m ON m.id = t.member_id
        WHERE t.family_id = ?
        """;

    /** 회원 인덱스로 그 회원의 계통만 찾는다. */
    static final String DELETE_ALL_OF = """
        DELETE FROM refresh_token
        WHERE member_id = (SELECT id FROM member WHERE public_id = ?)
        """;

    /** 잠긴 행은 건너뛰어 다른 트랜잭션을 기다리지 않는다. 정렬 없이 만료 인덱스로 찾는다. */
    static final String DELETE_EXPIRED = """
        DELETE FROM refresh_token
        WHERE family_id IN (
            SELECT family_id FROM refresh_token
            WHERE expires_at < ?
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        )
        """;

    private static final int ONE_ROW_CHANGED = 1;

    private final JdbcClient jdbcClient;

    public JdbcRefreshTokenRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public boolean startFamily(
        UUID publicId,
        UUID familyId,
        OffsetDateTime now,
        OffsetDateTime expiresAt
    ) {
        final int saved = jdbcClient.sql(START_FAMILY)
            .params(familyId, now, now, expiresAt, publicId)
            .update();
        return saved == ONE_ROW_CHANGED;
    }

    @Override
    public Optional<RotatedRefreshToken> rotate(
        UUID familyId,
        int generation,
        OffsetDateTime now,
        OffsetDateTime expiresAt,
        Duration maxLifetime
    ) {
        return jdbcClient.sql(ROTATE)
            .params(now, expiresAt, maxLifetime.toSeconds(), familyId, generation, now)
            .query((rows, rowNumber) -> new RotatedRefreshToken(
                rows.getObject("public_id", UUID.class), rows.getInt("generation"),
                rows.getObject("expires_at", OffsetDateTime.class)))
            .optional();
    }

    @Override
    public Optional<RefreshTokenFamily> find(UUID familyId) {
        return jdbcClient.sql(FIND)
            .param(familyId)
            .query((rows, rowNumber) -> new RefreshTokenFamily(
                rows.getObject("family_id", UUID.class), rows.getObject("public_id", UUID.class),
                rows.getInt("generation"), rows.getObject("rotated_at", OffsetDateTime.class),
                rows.getObject("expires_at", OffsetDateTime.class)))
            .optional();
    }

    @Override
    public void delete(UUID familyId) {
        jdbcClient.sql("DELETE FROM refresh_token WHERE family_id = ?").param(familyId).update();
    }

    @Override
    public int deleteAllOf(UUID publicId) {
        return jdbcClient.sql(DELETE_ALL_OF).param(publicId).update();
    }

    @Override
    public int deleteExpired(OffsetDateTime now, int limit) {
        return jdbcClient.sql(DELETE_EXPIRED).params(now, limit).update();
    }
}

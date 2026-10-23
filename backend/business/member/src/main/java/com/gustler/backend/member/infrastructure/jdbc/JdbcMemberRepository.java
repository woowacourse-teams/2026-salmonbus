package com.gustler.backend.member.infrastructure.jdbc;

import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.api.MemberIdentity;
import com.gustler.backend.member.api.MemberProfile;
import com.gustler.backend.member.domain.Member;
import com.gustler.backend.member.domain.MemberRepository;
import com.gustler.backend.member.domain.NewMember;
import com.gustler.backend.member.domain.Nickname;
import com.gustler.backend.member.domain.NicknameUpdate;
import com.gustler.backend.member.domain.RegistrationResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 가입을 문장 단위의 충돌 무시로 처리한다.
 *
 * <p>PostgreSQL 은 유일 제약을 어기면 그 트랜잭션을 중단시켜 이후 문장을 실행할 수 없다.
 * 그래서 {@code ON CONFLICT DO NOTHING} 으로 충돌을 행 수로 읽고, 예외가 트랜잭션을 깨뜨리지 않게 한다.
 *
 * <p>닉네임 고유성은 {@code lower(nickname)} 유일 인덱스가 판정하므로 대소문자만 다른 닉네임도 같은 닉네임이다.
 * 제공자 계정이 먼저 가입했으면 방금 넣은 회원 행을 같은 트랜잭션에서 지워 고아 회원을 남기지 않는다.
 */
@Repository
public class JdbcMemberRepository implements MemberRepository {
    private static final String FIND_BY_IDENTITY = """
        SELECT m.id, m.public_id, m.nickname
        FROM member m
        JOIN member_identity i ON i.member_id = m.id
        WHERE i.provider = ? AND i.subject = ?
        """;

    private static final String FIND_PROFILE = """
        SELECT m.public_id, m.nickname, i.provider, m.created_at
        FROM member m
        JOIN member_identity i ON i.member_id = m.id
        WHERE m.public_id = ?
        """;

    private static final String INSERT_MEMBER = """
        INSERT INTO member (public_id, nickname, created_at, last_login_at)
        VALUES (?, ?, ?, ?)
        ON CONFLICT (lower(nickname)) DO NOTHING
        RETURNING id
        """;

    private static final String INSERT_IDENTITY = """
        INSERT INTO member_identity (provider, subject, member_id, created_at)
        VALUES (?, ?, ?, ?)
        ON CONFLICT ON CONSTRAINT pk_member_identity DO NOTHING
        """;

    private static final String FIND_IDENTITY = """
        SELECT i.provider, i.subject
        FROM member m
        JOIN member_identity i ON i.member_id = m.id
        WHERE m.public_id = ?
        """;

    /**
     * 회원 행 삭제와 제공자 계정 읽기를 한 문장으로 한다. 바깥 SELECT 는 문장 시작 시점의 스냅숏을 보고 CASCADE 는 문장이
     * 끝날 때 일어나므로 지우는 회원의 제공자 계정이 보인다. 같은 회원을 먼저 지운 트랜잭션이 있으면 DELETE 가 그 트랜잭션이
     * 끝나기를 기다렸다가 행이 사라진 것을 보고 0행을 지우므로, 나중 호출은 빈 결과를 받는다.
     */
    private static final String DELETE_RETURNING_IDENTITY = """
        WITH deleted AS (
            DELETE FROM member WHERE public_id = ? RETURNING id
        )
        SELECT i.provider, i.subject
        FROM deleted
        LEFT JOIN member_identity i ON i.member_id = deleted.id
        """;

    /**
     * 탈퇴한 뒤 같은 제공자 계정으로 다시 가입하면 그 계정의 남은 연결 해제 요청을 지운다. 남겨 두면 다시 시도할 때 새 회원의
     * 제공자 연결이 끊긴다. 다시 가입했다는 것은 사용자가 연결을 원한다는 뜻이다. 이미 나가 있는 해제 호출은 막지 못한다.
     * 탈퇴 직후 몇 초 안에 다시 가입하는 드문 경우라, 그때는 다음 로그인에서 동의를 다시 받는다.
     */
    private static final String DELETE_UNLINK_REQUESTS = """
        DELETE FROM provider_unlink WHERE provider = ? AND subject = ?
        """;

    private static final int ONE_ROW_CHANGED = 1;

    private final JdbcClient jdbcClient;

    public JdbcMemberRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<Member> findByIdentity(LoginProvider provider, String subject) {
        return jdbcClient.sql(FIND_BY_IDENTITY)
            .params(provider.name(), subject)
            .query((rows, rowNumber) -> new Member(
                rows.getLong("id"), rows.getObject("public_id", UUID.class), rows.getString("nickname")))
            .optional();
    }

    @Override
    public Optional<MemberProfile> findProfile(UUID publicId) {
        return jdbcClient.sql(FIND_PROFILE)
            .param(publicId)
            .query((rows, rowNumber) -> new MemberProfile(
                rows.getObject("public_id", UUID.class), rows.getString("nickname"),
                LoginProvider.valueOf(rows.getString("provider")), rows.getObject("created_at", OffsetDateTime.class)))
            .optional();
    }

    @Override
    @Transactional
    public RegistrationResult register(NewMember newMember) {
        Optional<Long> memberId = jdbcClient.sql(INSERT_MEMBER)
            .params(newMember.publicId(), newMember.nickname().value(), newMember.joinedAt(), newMember.joinedAt())
            .query(Long.class)
            .optional();
        if (memberId.isEmpty()) {
            return new RegistrationResult.NicknameTaken();
        }
        final int linked = jdbcClient.sql(INSERT_IDENTITY)
            .params(newMember.provider().name(), newMember.subject(), memberId.get(), newMember.joinedAt())
            .update();
        if (linked != ONE_ROW_CHANGED) {
            jdbcClient.sql("DELETE FROM member WHERE id = ?").param(memberId.get()).update();
            return new RegistrationResult.AlreadyRegistered();
        }
        jdbcClient.sql(DELETE_UNLINK_REQUESTS)
            .params(newMember.provider().name(), newMember.subject())
            .update();
        Member member = new Member(memberId.get(), newMember.publicId(), newMember.nickname().value());
        return new RegistrationResult.Registered(member);
    }

    @Override
    public void recordLogin(long memberId, OffsetDateTime at) {
        jdbcClient.sql("UPDATE member SET last_login_at = ? WHERE id = ?")
            .params(at, memberId)
            .update();
    }

    /**
     * 갱신 한 문장으로 처리한다. 겹치는 닉네임은 {@code ux_member_nickname} 이 거절하고, 이 문장 하나가 트랜잭션이라
     * 예외를 잡아도 이어서 실행할 문장이 없다.
     */
    @Override
    public NicknameUpdate changeNickname(UUID publicId, Nickname nickname) {
        try {
            final int changed = jdbcClient.sql("UPDATE member SET nickname = ? WHERE public_id = ?")
                .params(nickname.value(), publicId)
                .update();
            if (changed == ONE_ROW_CHANGED) {
                return NicknameUpdate.UPDATED;
            }
            return NicknameUpdate.MEMBER_NOT_FOUND;
        } catch (DuplicateKeyException exception) {
            return NicknameUpdate.TAKEN;
        }
    }

    @Override
    public Optional<MemberIdentity> findIdentity(UUID publicId) {
        return jdbcClient.sql(FIND_IDENTITY)
            .param(publicId)
            .query((rows, rowNumber) -> identityOf(rows))
            .optional();
    }

    /** 제공자 계정이 없는 회원은 가입 경로상 생길 수 없다. 만나면 예외로 트랜잭션을 되돌려 회원을 지우지 않는다. */
    @Override
    public Optional<MemberIdentity> delete(UUID publicId) {
        return jdbcClient.sql(DELETE_RETURNING_IDENTITY)
            .param(publicId)
            .query((rows, rowNumber) -> identityOf(rows))
            .optional();
    }

    private static MemberIdentity identityOf(ResultSet rows) throws SQLException {
        String provider = rows.getString("provider");
        if (provider == null) {
            throw new IllegalStateException("제공자 계정이 없는 회원이다");
        }
        return new MemberIdentity(LoginProvider.valueOf(provider), rows.getString("subject"));
    }
}

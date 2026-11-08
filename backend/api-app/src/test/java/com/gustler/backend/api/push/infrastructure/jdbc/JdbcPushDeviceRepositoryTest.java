package com.gustler.backend.api.push.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.push.domain.DeviceToken;
import com.gustler.backend.api.push.domain.PushEnvironment;
import com.gustler.backend.support.IntegrationTest;
import com.gustler.backend.member.api.RefreshTokens;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class JdbcPushDeviceRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-11-06T01:30:00Z");
    private static final DeviceToken TOKEN = new DeviceToken("ABCDEF");

    @Autowired
    private JdbcPushDeviceRepository repository;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private RefreshTokens refreshTokens;

    @Test
    void registersAndRefreshesOneRow() {
        UUID member = member("연어하나");
        UUID sessionId = session(member);
        assertThat(repository.register(member, sessionId, TOKEN, PushEnvironment.SANDBOX, NOW)).isTrue();
        assertThat(repository.register(member, sessionId, TOKEN, PushEnvironment.PRODUCTION, NOW.plusSeconds(60))).isTrue();
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT token FROM push_device").query(String.class).single()).isEqualTo("abcdef");
        assertThat(jdbc.sql("SELECT session_family_id FROM push_device").query(UUID.class).single()).isEqualTo(sessionId);
        assertThat(jdbc.sql("SELECT environment FROM push_device").query(String.class).single()).isEqualTo("PRODUCTION");
        assertThat(time("created_at")).isEqualTo(NOW);
        assertThat(time("last_registered_at")).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void movesTokenWithoutChangingIdentityOrCreationTime() {
        UUID first = member("연어하나");
        UUID second = member("연어둘");
        repository.register(first, session(first), TOKEN, PushEnvironment.SANDBOX, NOW);
        long id = jdbc.sql("SELECT id FROM push_device").query(Long.class).single();
        UUID secondSession = session(second);
        assertThat(repository.register(second, secondSession, TOKEN, PushEnvironment.PRODUCTION, NOW.plusSeconds(60))).isTrue();
        assertThat(jdbc.sql("SELECT session_family_id FROM push_device").query(UUID.class).single())
            .isEqualTo(secondSession);
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT id FROM push_device").query(Long.class).single()).isEqualTo(id);
        assertThat(jdbc.sql("SELECT m.public_id FROM push_device p JOIN member m ON m.id = p.member_id")
            .query(UUID.class).single()).isEqualTo(second);
        assertThat(time("created_at")).isEqualTo(NOW);
        assertThat(time("last_registered_at")).isEqualTo(NOW.plusSeconds(60));
        assertThat(jdbc.sql("SELECT environment FROM push_device").query(String.class).single()).isEqualTo("PRODUCTION");
    }

    @Test
    void unregisterOnlyDeletesOwnTokenAndIsIdempotent() {
        UUID first = member("연어하나");
        UUID second = member("연어둘");
        repository.register(first, session(first), TOKEN, PushEnvironment.SANDBOX, NOW);
        repository.register(second, session(second), new DeviceToken("bb"), PushEnvironment.PRODUCTION, NOW);
        assertThat(repository.unregister(second, TOKEN)).isTrue();
        assertThat(repository.unregister(first, new DeviceToken("cc"))).isTrue();
        assertThat(count()).isEqualTo(2);
        assertThat(repository.unregister(first, TOKEN)).isTrue();
        assertThat(repository.unregister(first, TOKEN)).isTrue();
        assertThat(jdbc.sql("SELECT token FROM push_device").query(String.class).list()).containsExactly("bb");
    }

    @Test
    void deleteByIdRemovesOnlyThatDevice() {
        UUID member = member("연어하나");
        repository.register(member, session(member), TOKEN, PushEnvironment.SANDBOX, NOW);
        repository.register(member, session(member), new DeviceToken("bb"), PushEnvironment.PRODUCTION, NOW);
        long id = jdbc.sql("SELECT id FROM push_device WHERE token = ?")
            .param("abcdef")
            .query(Long.class)
            .single();
        assertThat(repository.deleteById(id)).isTrue();
        assertThat(jdbc.sql("SELECT token FROM push_device").query(String.class).single()).isEqualTo("bb");
        assertThat(repository.deleteById(id)).isFalse();
    }

    @Test
    void missingMemberCannotRegisterOrUnregister() {
        UUID missing = UUID.randomUUID();
        assertThat(repository.register(missing, UUID.randomUUID(), TOKEN, PushEnvironment.SANDBOX, NOW)).isFalse();
        assertThat(repository.unregister(missing, TOKEN)).isFalse();
        assertThat(count()).isZero();
    }

    private UUID session(UUID member) {
        return refreshTokens.issue(member).familyId();
    }

    private UUID member(String nickname) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        jdbc.sql("INSERT INTO member (public_id, nickname, created_at, last_login_at) VALUES (?, ?, ?, ?)")
            .params(id, nickname, now, now).update();
        return id;
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM push_device").query(Long.class).single();
    }

    private Instant time(String column) {
        return jdbc.sql("SELECT " + column + " FROM push_device").query(OffsetDateTime.class).single().toInstant();
    }
}

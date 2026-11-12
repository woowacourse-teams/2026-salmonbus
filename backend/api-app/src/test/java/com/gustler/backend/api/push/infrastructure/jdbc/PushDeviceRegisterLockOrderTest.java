package com.gustler.backend.api.push.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.push.domain.DeviceToken;
import com.gustler.backend.api.push.domain.PushEnvironment;
import com.gustler.backend.member.api.RefreshTokens;
import com.gustler.backend.support.IntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class PushDeviceRegisterLockOrderTest {
    private static final Instant NOW = Instant.parse("2026-11-10T01:30:00Z");
    private static final DeviceToken TOKEN = new DeviceToken("ab".repeat(32));

    @Autowired
    private JdbcPushDeviceRepository repository;
    @Autowired
    private RefreshTokens refreshTokens;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private DataSource dataSource;

    private UUID member;

    @AfterEach
    void tearDown() {
        if (member != null) {
            jdbc.sql("DELETE FROM member WHERE public_id = ?").param(member).update();
        }
    }

    @Test
    void 세션을_옮기는_등록은_기기_행보다_세션_행을_먼저_잠근다() throws Exception {
        member = member();
        UUID previous = refreshTokens.issue(member).familyId();
        UUID next = refreshTokens.issue(member).familyId();
        repository.register(member, previous, TOKEN, PushEnvironment.SANDBOX, NOW);

        try (Connection logout = dataSource.getConnection()) {
            logout.setAutoCommit(false);
            lockSession(logout, next);
            CompletableFuture<Boolean> registering = CompletableFuture.supplyAsync(() ->
                repository.register(member, next, TOKEN, PushEnvironment.PRODUCTION, NOW.plusSeconds(60)));
            Thread.sleep(500);

            assertThat(deviceRowIsFree()).isTrue();

            logout.rollback();
            assertThat(registering.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.sql("SELECT session_family_id FROM push_device WHERE token = ?")
            .param(TOKEN.value())
            .query(UUID.class)
            .single()).isEqualTo(next);
    }

    private void lockSession(Connection connection, UUID familyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT 1 FROM refresh_token WHERE family_id = ? FOR UPDATE")) {
            statement.setObject(1, familyId);
            statement.executeQuery();
        }
    }

    private boolean deviceRowIsFree() throws SQLException {
        try (Connection probe = dataSource.getConnection()) {
            probe.setAutoCommit(false);
            try (PreparedStatement statement = probe.prepareStatement(
                "SELECT 1 FROM push_device WHERE token = ? FOR UPDATE NOWAIT")) {
                statement.setString(1, TOKEN.value());
                statement.executeQuery();
                return true;
            } catch (SQLException exception) {
                if ("55P03".equals(exception.getSQLState())) {
                    return false;
                }
                throw exception;
            } finally {
                probe.rollback();
            }
        }
    }

    private UUID member() {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        jdbc.sql("INSERT INTO member (public_id, nickname, created_at, last_login_at) VALUES (?, ?, ?, ?)")
            .params(id, "잠금순서", now, now)
            .update();
        return id;
    }
}

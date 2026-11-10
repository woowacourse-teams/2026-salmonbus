package com.gustler.backend.api.push.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.push.NotificationTargetNotFavoriteException;
import com.gustler.backend.support.IntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class NotificationSettingSaveLockTest {
    private static final Instant NOW = Instant.parse("2026-11-10T01:30:00Z");
    private static final String ROUTE = "234001739";
    private static final String STOP = "214002018";
    private static final FavoriteTarget TARGET = new FavoriteTarget(ROUTE, STOP, FavoriteDirection.UP);
    private static final List<String> WEEKDAYS = List.of("MON", "TUE", "WED", "THU", "FRI");

    @Autowired
    private NotificationSettingService service;
    @Autowired
    private NotificationSettingRepository repository;
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
    void 즐겨찾기를_지운_잠금이_풀린_뒤에야_알림_저장을_판단한다() throws Exception {
        member = member();
        long memberId = repository.memberIdOf(member).orElseThrow();
        addFavorite(memberId);
        AtomicReference<CompletableFuture<?>> saving = new AtomicReference<>();

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockMember(holder, member);
            deleteFavorite(holder, memberId);
            CompletableFuture<?> current = CompletableFuture.supplyAsync(() ->
                service.save(member, ROUTE, STOP, "UP", "07:40", WEEKDAYS));
            saving.set(current);
            Thread.sleep(500);

            assertThat(current).isNotDone();
            assertThat(settingCount(memberId)).isZero();

            holder.commit();
            assertThatThrownBy(() -> current.orTimeout(10, TimeUnit.SECONDS).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(NotificationTargetNotFavoriteException.class);
        } finally {
            CompletableFuture<?> current = saving.get();
            if (current != null && !current.isDone()) {
                try {
                    current.orTimeout(10, TimeUnit.SECONDS).join();
                } catch (CompletionException ignored) {
                    // 잠금이 풀린 뒤 저장이 거절되면 여기로 온다. 앞선 실패를 이 예외로 가리지 않는다.
                }
            }
        }
        assertThat(settingCount(memberId)).isZero();
    }

    private void lockMember(Connection connection, UUID publicId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id FROM member WHERE public_id = ? FOR UPDATE")) {
            statement.setObject(1, publicId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("회원이 없습니다.");
                }
            }
        }
    }

    private void deleteFavorite(Connection connection, long memberId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            DELETE FROM favorite
            WHERE favorite_group_id IN (SELECT id FROM favorite_group WHERE member_id = ?)
            """)) {
            statement.setLong(1, memberId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("즐겨찾기를 지우지 못했습니다.");
            }
        }
    }

    private void addFavorite(long memberId) {
        UUID groupId = UUID.randomUUID();
        jdbc.sql("INSERT INTO favorite_group (id, member_id, name, position) VALUES (?, ?, ?, ?)")
            .params(groupId, memberId, "출근", 0)
            .update();
        jdbc.sql("""
            INSERT INTO favorite (id, favorite_group_id, route_id, stop_id, direction, position)
            VALUES (?, ?, ?, ?, ?, ?)
            """)
            .params(UUID.randomUUID(), groupId, TARGET.routeId(), TARGET.stopId(), TARGET.direction().name(), 0)
            .update();
    }

    private long settingCount(long memberId) {
        return jdbc.sql("SELECT count(*) FROM notification_setting WHERE member_id = ?")
            .param(memberId)
            .query(Long.class)
            .single();
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

package com.gustler.backend.api.push.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.push.application.NotificationSettingRepository.SavedSetting;
import com.gustler.backend.api.push.domain.NotificationSchedule;
import com.gustler.backend.support.IntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class JdbcNotificationSettingRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-11-01T22:39:00Z");
    private static final FavoriteTarget TARGET = new FavoriteTarget("234001739", "214002018", FavoriteDirection.UP);
    private static final NotificationSchedule SCHEDULE = NotificationSchedule.parse(
        "07:40",
        List.of("MON", "TUE", "WED", "THU", "FRI")
    );

    @Autowired
    private JdbcNotificationSettingRepository repository;
    @Autowired
    private JdbcClient jdbc;

    @Test
    void 같은_대상은_한_행이고_id를_유지한다() {
        UUID member = member("연어하나");
        long memberId = memberId(member);
        addFavorite(memberId, TARGET);
        Instant firstOccurrence = Instant.parse("2026-11-01T22:40:00Z");
        SavedSetting first = repository.upsert(memberId, TARGET, SCHEDULE, firstOccurrence, NOW);
        NotificationSchedule changed = NotificationSchedule.parse("08:00", List.of("MON"));
        SavedSetting second = repository.upsert(
            memberId,
            TARGET,
            changed,
            Instant.parse("2026-11-01T23:00:00Z"),
            NOW.plusSeconds(60)
        );
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.time()).isEqualTo("08:00");
        assertThat(second.weekdays()).containsExactly("MON");
        assertThat(count()).isEqualTo(1);
        assertThat(instantOf("created_at")).isEqualTo(NOW);
        assertThat(instantOf("updated_at")).isEqualTo(NOW.plusSeconds(60));
        assertThat(instantOf("occurrence_at")).isEqualTo(Instant.parse("2026-11-01T23:00:00Z"));
    }

    @Test
    void 시각과_요일이_같으면_다시_저장해도_아직_보내지_않은_차례를_넘기지_않는다() {
        UUID member = member("연어하나");
        long memberId = memberId(member);
        addFavorite(memberId, TARGET);
        Instant dueAt = Instant.parse("2026-11-01T22:40:00Z");
        repository.upsert(memberId, TARGET, SCHEDULE, dueAt, NOW);

        SavedSetting resaved = repository.upsert(
            memberId,
            TARGET,
            SCHEDULE,
            Instant.parse("2026-11-02T22:40:00Z"),
            dueAt.plusSeconds(30)
        );

        assertThat(resaved.time()).isEqualTo("07:40");
        assertThat(instantOf("occurrence_at")).isEqualTo(dueAt);
        assertThat(instantOf("updated_at")).isEqualTo(dueAt.plusSeconds(30));
    }

    @Test
    void 남의_설정은_지우지_않는다() {
        UUID owner = member("연어하나");
        UUID other = member("연어둘");
        long ownerId = memberId(owner);
        addFavorite(ownerId, TARGET);
        SavedSetting saved = repository.upsert(ownerId, TARGET, SCHEDULE, NOW.plusSeconds(60), NOW);
        assertThat(repository.delete(memberId(other), saved.id())).isFalse();
        assertThat(count()).isEqualTo(1);
        assertThat(repository.delete(ownerId, saved.id())).isTrue();
        assertThat(count()).isZero();
    }

    @Test
    void 없는_회원은_비어_있다() {
        assertThat(repository.memberIdOf(UUID.randomUUID())).isEmpty();
    }

    private UUID member(String nickname) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        jdbc.sql("INSERT INTO member (public_id, nickname, created_at, last_login_at) VALUES (?, ?, ?, ?)")
            .params(id, nickname, now, now)
            .update();
        return id;
    }

    private long memberId(UUID publicId) {
        return repository.memberIdOf(publicId).orElseThrow();
    }

    private void addFavorite(long memberId, FavoriteTarget target) {
        UUID groupId = UUID.randomUUID();
        jdbc.sql("INSERT INTO favorite_group (id, member_id, name, position) VALUES (?, ?, ?, ?)")
            .params(groupId, memberId, "출근", 0)
            .update();
        jdbc.sql("""
            INSERT INTO favorite (id, favorite_group_id, route_id, stop_id, direction, position)
            VALUES (?, ?, ?, ?, ?, ?)
            """)
            .params(UUID.randomUUID(), groupId, target.routeId(), target.stopId(), target.direction().name(), 0)
            .update();
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM notification_setting").query(Long.class).single();
    }

    private Instant instantOf(String column) {
        return jdbc.sql("SELECT " + column + " FROM notification_setting")
            .query(OffsetDateTime.class)
            .single()
            .toInstant();
    }
}

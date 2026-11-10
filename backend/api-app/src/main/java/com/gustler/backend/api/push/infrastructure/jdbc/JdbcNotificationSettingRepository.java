package com.gustler.backend.api.push.infrastructure.jdbc;

import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.push.application.NotificationSettingRepository;
import com.gustler.backend.api.push.domain.NotificationSchedule;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcNotificationSettingRepository implements NotificationSettingRepository {
    private static final String UPSERT = """
        INSERT INTO notification_setting (
            id, member_id, route_id, stop_id, direction,
            local_time, weekdays, occurrence_at, created_at, updated_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (member_id, route_id, stop_id, direction) DO UPDATE SET
            local_time = EXCLUDED.local_time,
            weekdays = EXCLUDED.weekdays,
            occurrence_at = CASE
                WHEN notification_setting.local_time = EXCLUDED.local_time
                    AND notification_setting.weekdays = EXCLUDED.weekdays
                THEN notification_setting.occurrence_at
                ELSE EXCLUDED.occurrence_at
            END,
            updated_at = EXCLUDED.updated_at
        RETURNING id, route_id, stop_id, direction, local_time, weekdays
        """;

    private static final String FIND_DUE = """
        SELECT id, member_id, route_id, stop_id, direction, local_time, weekdays, occurrence_at
        FROM notification_setting
        WHERE occurrence_at <= ?
        ORDER BY occurrence_at, id
        LIMIT ?
        """;

    private static final String ADVANCE = """
        UPDATE notification_setting
        SET occurrence_at = ?, updated_at = ?
        WHERE occurrence_at = ? AND id = ?
        """;

    private static final String DELETE_ORPHAN = """
        DELETE FROM notification_setting
        WHERE occurrence_at = ? AND id = ?
        """;

    private static final String DELETE_WITHOUT_FAVORITE = """
        DELETE FROM notification_setting setting
        WHERE setting.member_id = ?
            AND NOT EXISTS (
                SELECT 1
                FROM favorite favorite
                JOIN favorite_group favorite_group ON favorite_group.id = favorite.favorite_group_id
                WHERE favorite_group.member_id = setting.member_id
                    AND favorite.route_id = setting.route_id
                    AND favorite.stop_id = setting.stop_id
                    AND favorite.direction = setting.direction
            )
        """;

    private final JdbcClient jdbc;

    public JdbcNotificationSettingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Long> memberIdOf(UUID memberPublicId) {
        return jdbc.sql("SELECT id FROM member WHERE public_id = ?")
            .param(memberPublicId)
            .query(Long.class)
            .optional();
    }

    @Override
    public boolean hasFavorite(long memberId, FavoriteTarget target) {
        return Boolean.TRUE.equals(jdbc.sql("""
            SELECT EXISTS (
                SELECT 1
                FROM favorite favorite
                JOIN favorite_group favorite_group ON favorite_group.id = favorite.favorite_group_id
                WHERE favorite_group.member_id = ?
                    AND favorite.route_id = ?
                    AND favorite.stop_id = ?
                    AND favorite.direction = ?
            )
            """)
            .params(memberId, target.routeId(), target.stopId(), target.direction().name())
            .query(Boolean.class)
            .single());
    }

    @Override
    @Transactional
    public SavedSetting upsert(
        long memberId,
        FavoriteTarget target,
        NotificationSchedule schedule,
        Instant occurrenceAt,
        Instant now
    ) {
        OffsetDateTime occurred = OffsetDateTime.ofInstant(occurrenceAt, ZoneOffset.UTC);
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        return jdbc.sql(UPSERT)
            .params(
                UUID.randomUUID(),
                memberId,
                target.routeId(),
                target.stopId(),
                target.direction().name(),
                schedule.time(),
                schedule.weekdays(),
                occurred,
                timestamp,
                timestamp
            )
            .query(JdbcNotificationSettingRepository::mapSetting)
            .single();
    }

    @Override
    public List<SavedSetting> findByMemberId(long memberId) {
        return jdbc.sql("""
            SELECT id, route_id, stop_id, direction, local_time, weekdays
            FROM notification_setting
            WHERE member_id = ?
            ORDER BY created_at, id
            """)
            .param(memberId)
            .query(JdbcNotificationSettingRepository::mapSetting)
            .list();
    }

    @Override
    @Transactional
    public boolean delete(long memberId, UUID settingId) {
        return jdbc.sql("DELETE FROM notification_setting WHERE member_id = ? AND id = ?")
            .params(memberId, settingId)
            .update() > 0;
    }

    @Override
    public List<DueSetting> findDue(Instant now, int limit) {
        return jdbc.sql(FIND_DUE)
            .params(OffsetDateTime.ofInstant(now, ZoneOffset.UTC), limit)
            .query(JdbcNotificationSettingRepository::mapDue)
            .list();
    }

    @Override
    @Transactional
    public boolean advance(UUID settingId, Instant readOccurrenceAt, Instant nextOccurrenceAt, Instant now) {
        return jdbc.sql(ADVANCE)
            .params(
                OffsetDateTime.ofInstant(nextOccurrenceAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(readOccurrenceAt, ZoneOffset.UTC),
                settingId
            )
            .update() > 0;
    }

    @Override
    @Transactional
    public boolean deleteOrphan(UUID settingId, Instant readOccurrenceAt) {
        return jdbc.sql(DELETE_ORPHAN)
            .params(OffsetDateTime.ofInstant(readOccurrenceAt, ZoneOffset.UTC), settingId)
            .update() > 0;
    }

    @Override
    public void deleteWithoutFavorite(long memberId) {
        jdbc.sql(DELETE_WITHOUT_FAVORITE).param(memberId).update();
    }

    private static SavedSetting mapSetting(ResultSet resultSet, int rowNum) throws SQLException {
        return new SavedSetting(
            resultSet.getObject("id", UUID.class),
            resultSet.getString("route_id"),
            resultSet.getString("stop_id"),
            resultSet.getString("direction"),
            NotificationSchedule.formatTime(resultSet.getObject("local_time", LocalTime.class)),
            NotificationSchedule.namesOf(resultSet.getInt("weekdays"))
        );
    }

    private static DueSetting mapDue(ResultSet resultSet, int rowNum) throws SQLException {
        return new DueSetting(
            resultSet.getObject("id", UUID.class),
            resultSet.getLong("member_id"),
            new FavoriteTarget(
                resultSet.getString("route_id"),
                resultSet.getString("stop_id"),
                FavoriteDirection.valueOf(resultSet.getString("direction"))
            ),
            new NotificationSchedule(
                resultSet.getObject("local_time", LocalTime.class),
                resultSet.getInt("weekdays")
            ),
            resultSet.getObject("occurrence_at", OffsetDateTime.class).toInstant()
        );
    }
}

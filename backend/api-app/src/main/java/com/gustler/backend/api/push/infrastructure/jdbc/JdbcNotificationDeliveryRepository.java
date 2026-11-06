package com.gustler.backend.api.push.infrastructure.jdbc;

import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.push.application.NotificationDeliveryRepository;
import com.gustler.backend.api.push.domain.DeliveryDecision;
import com.gustler.backend.api.push.domain.PushEnvironment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcNotificationDeliveryRepository implements NotificationDeliveryRepository {
    private static final String INSERT_FOR_DEVICES = """
        INSERT INTO notification_delivery (
            setting_id, occurrence_at, device_id, status, attempts, next_attempt_at, created_at, updated_at
        )
        SELECT ?, ?, device.id, 'PENDING', 0, ?, ?, ?
        FROM push_device device
        WHERE device.member_id = ?
        ON CONFLICT (setting_id, occurrence_at, device_id) DO NOTHING
        """;

    private static final String CLAIM_DUE = """
        WITH claimed AS (
            UPDATE notification_delivery
            SET next_attempt_at = ?, updated_at = ?
            WHERE id IN (
                SELECT id FROM notification_delivery
                WHERE status = 'PENDING' AND next_attempt_at <= ?
                ORDER BY next_attempt_at, id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
            )
            RETURNING id, setting_id, device_id, occurrence_at, attempts
        )
        SELECT claimed.id, claimed.setting_id, claimed.device_id, claimed.occurrence_at, claimed.attempts,
            device.token, device.environment, setting.route_id, setting.stop_id, setting.direction
        FROM claimed
        JOIN push_device device ON device.id = claimed.device_id
        JOIN notification_setting setting ON setting.id = claimed.setting_id
        """;

    private static final String RECORD = """
        UPDATE notification_delivery
        SET status = ?, attempts = attempts + 1, next_attempt_at = ?, updated_at = ?
        WHERE id = ? AND status = 'PENDING'
        """;

    private final JdbcClient jdbc;

    public JdbcNotificationDeliveryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public int createForMemberDevices(UUID settingId, Instant occurrenceAt, long memberId, Instant now) {
        OffsetDateTime occurred = OffsetDateTime.ofInstant(occurrenceAt, ZoneOffset.UTC);
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        return jdbc.sql(INSERT_FOR_DEVICES)
            .params(settingId, occurred, timestamp, timestamp, timestamp, memberId)
            .update();
    }

    @Override
    @Transactional
    public List<ClaimedDelivery> claimDue(Instant now, Instant leasedUntil, int limit) {
        OffsetDateTime current = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        OffsetDateTime leased = OffsetDateTime.ofInstant(leasedUntil, ZoneOffset.UTC);
        return jdbc.sql(CLAIM_DUE)
            .params(leased, current, current, limit)
            .query(JdbcNotificationDeliveryRepository::mapClaimed)
            .list();
    }

    @Override
    @Transactional
    public void record(long id, DeliveryDecision decision, Instant now) {
        jdbc.sql(RECORD)
            .params(
                decision.status().name(),
                OffsetDateTime.ofInstant(decision.nextAttemptAt(), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                id
            )
            .update();
    }

    private static ClaimedDelivery mapClaimed(ResultSet resultSet, int rowNum) throws SQLException {
        return new ClaimedDelivery(
            resultSet.getLong("id"),
            resultSet.getObject("setting_id", UUID.class),
            resultSet.getLong("device_id"),
            resultSet.getString("token"),
            PushEnvironment.valueOf(resultSet.getString("environment")),
            new FavoriteTarget(
                resultSet.getString("route_id"),
                resultSet.getString("stop_id"),
                FavoriteDirection.valueOf(resultSet.getString("direction"))
            ),
            resultSet.getObject("occurrence_at", OffsetDateTime.class).toInstant(),
            resultSet.getInt("attempts")
        );
    }
}

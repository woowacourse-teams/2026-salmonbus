package com.gustler.backend.api.push.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.push.application.NotificationDeliveryRepository.ClaimedDelivery;
import com.gustler.backend.api.push.application.NotificationSettingRepository.SavedSetting;
import com.gustler.backend.api.push.domain.DeliveryDecision;
import com.gustler.backend.api.push.domain.DeliveryStatus;
import com.gustler.backend.api.push.domain.DeviceToken;
import com.gustler.backend.api.push.domain.NotificationSchedule;
import com.gustler.backend.api.push.domain.PushEnvironment;
import com.gustler.backend.support.IntegrationTest;
import com.gustler.backend.member.api.RefreshTokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class JdbcNotificationDeliveryRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-11-01T22:40:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Instant CUTOFF = CLOCK.instant().minus(Duration.ofDays(7));
    private static final FavoriteTarget TARGET = new FavoriteTarget("234000884", "203000165", FavoriteDirection.UP);
    private static final NotificationSchedule SCHEDULE = NotificationSchedule.parse(
        "07:40",
        List.of("MON", "TUE", "WED", "THU", "FRI")
    );

    @Autowired
    private JdbcNotificationDeliveryRepository deliveries;
    @Autowired
    private JdbcNotificationSettingRepository settings;
    @Autowired
    private JdbcPushDeviceRepository devices;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private RefreshTokens refreshTokens;

    @Test
    void creatingTheSameOccurrenceTwiceDoesNotAddRows() {
        Prepared prepared = prepare(List.of("aa", "bb"));

        assertThat(deliveries.createForMemberDevices(prepared.settingId(), NOW, prepared.memberId(), NOW)).isEqualTo(2);
        assertThat(deliveries.createForMemberDevices(prepared.settingId(), NOW, prepared.memberId(), NOW)).isZero();
        assertThat(count()).isEqualTo(2);
    }

    @Test
    void claimDueLeasesPendingRowsAndSkipsOthers() {
        Prepared prepared = prepare(List.of("aa"));
        deliveries.createForMemberDevices(prepared.settingId(), NOW, prepared.memberId(), NOW);
        long futureId = insertDelivery(
            prepared.settingId(),
            NOW.plusSeconds(60),
            prepared.deviceIds().get(0),
            "PENDING",
            NOW.plusSeconds(120)
        );
        long sentId = insertDelivery(
            prepared.settingId(),
            NOW.plusSeconds(120),
            prepared.deviceIds().get(0),
            "SENT",
            NOW
        );

        long expiredId = insertDelivery(
            prepared.settingId(),
            NOW.minusSeconds(180),
            prepared.deviceIds().get(0),
            "PENDING",
            NOW
        );

        Instant leasedUntil = NOW.plusSeconds(120);
        List<ClaimedDelivery> claimed = deliveries.claimDue(NOW, leasedUntil, NOW.minusSeconds(120), 10);

        assertThat(claimed).hasSize(1);
        ClaimedDelivery first = claimed.get(0);
        assertThat(first.token()).isEqualTo("aa");
        assertThat(first.environment()).isEqualTo(PushEnvironment.SANDBOX);
        assertThat(first.target()).isEqualTo(TARGET);
        assertThat(first.occurrenceAt()).isEqualTo(NOW);
        assertThat(nextAttemptAt(first.id())).isEqualTo(leasedUntil);
        assertThat(claimed.stream().map(ClaimedDelivery::id).toList()).doesNotContain(futureId, sentId, expiredId);
    }

    @Test
    void claimDueSkipsDeliveryWhoseDeviceBelongsToAnotherMember() {
        Prepared prepared = prepare(List.of("aa"));
        deliveries.createForMemberDevices(prepared.settingId(), NOW, prepared.memberId(), NOW);
        UUID other = member("연어둘");
        jdbc.sql("UPDATE push_device SET member_id = (SELECT id FROM member WHERE public_id = ?)")
            .param(other)
            .update();

        assertThat(deliveries.claimDue(NOW, NOW.plusSeconds(120), NOW.minusSeconds(120), 10)).isEmpty();
    }

    @Test
    void unknownStatusIsRejected() {
        Prepared prepared = prepare(List.of("aa"));
        assertThatThrownBy(() -> insertDelivery(
            prepared.settingId(),
            NOW,
            prepared.deviceIds().get(0),
            "UNKNOWN",
            NOW
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingSettingRemovesDeliveriesAndRecordDoesNotFail() {
        Prepared prepared = prepare(List.of("aa"));
        deliveries.createForMemberDevices(prepared.settingId(), NOW, prepared.memberId(), NOW);
        long id = jdbc.sql("SELECT id FROM notification_delivery").query(Long.class).single();
        jdbc.sql("DELETE FROM notification_setting WHERE id = ?").param(prepared.settingId()).update();
        assertThat(count()).isZero();
        assertThatCode(() -> deliveries.record(
            id,
            new DeliveryDecision(DeliveryStatus.SENT, NOW),
            NOW
        )).doesNotThrowAnyException();
    }

    @Test
    void deletingMemberRemovesDeliveries() {
        Prepared prepared = prepare(List.of("aa", "bb"));
        deliveries.createForMemberDevices(prepared.settingId(), NOW, prepared.memberId(), NOW);
        jdbc.sql("DELETE FROM member WHERE public_id = ?").param(prepared.memberPublicId()).update();
        assertThat(count()).isZero();
    }

    @Test
    void purgeDeletesSentAndGaveUpRowsUpdatedBeforeRetention() {
        Prepared prepared = prepare(List.of("aa"));
        insertDeliveryUpdatedAt(prepared, "SENT", CUTOFF.minusSeconds(1), 0);
        insertDeliveryUpdatedAt(prepared, "GAVE_UP", CUTOFF.minusSeconds(60), 1);

        assertThat(deliveries.deleteCompletedBefore(CUTOFF, 1_000)).isEqualTo(2);
        assertThat(count()).isZero();
    }

    @Test
    void purgeKeepsPendingRecentAndExactlyAtCutoffRows() {
        Prepared prepared = prepare(List.of("aa"));
        insertDeliveryUpdatedAt(prepared, "PENDING", CUTOFF.minusSeconds(60), 0);
        insertDeliveryUpdatedAt(prepared, "SENT", CUTOFF, 1);
        insertDeliveryUpdatedAt(prepared, "GAVE_UP", CUTOFF, 2);
        insertDeliveryUpdatedAt(prepared, "SENT", CUTOFF.plusSeconds(1), 3);
        insertDeliveryUpdatedAt(prepared, "GAVE_UP", CUTOFF.plusSeconds(1), 4);

        assertThat(deliveries.deleteCompletedBefore(CUTOFF, 1_000)).isZero();
        assertThat(count()).isEqualTo(5);
    }

    @Test
    void purgeDeletesAtMostTheLimitUntilAllCompletedRowsAreGone() {
        Prepared prepared = prepare(List.of("aa"));
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(CUTOFF, ZoneOffset.UTC);
        jdbc.sql("""
            INSERT INTO notification_delivery (
                setting_id, occurrence_at, device_id, status, attempts, next_attempt_at, created_at, updated_at
            )
            SELECT ?, ?::timestamptz - sequence * INTERVAL '1 second', ?,
                CASE WHEN sequence % 2 = 0 THEN 'SENT' ELSE 'GAVE_UP' END,
                1, ?, ?, ?::timestamptz - INTERVAL '1 second'
            FROM generate_series(1, 2005) AS sequence
            """)
            .params(prepared.settingId(), cutoff, prepared.deviceIds().getFirst(), cutoff, cutoff, cutoff)
            .update();

        assertThat(deliveries.deleteCompletedBefore(CUTOFF, 1_000)).isEqualTo(1_000);
        assertThat(count()).isEqualTo(1_005);
        assertThat(deliveries.deleteCompletedBefore(CUTOFF, 1_000)).isEqualTo(1_000);
        assertThat(count()).isEqualTo(5);
        assertThat(deliveries.deleteCompletedBefore(CUTOFF, 1_000)).isEqualTo(5);
        assertThat(deliveries.deleteCompletedBefore(CUTOFF, 1_000)).isZero();
        assertThat(count()).isZero();
    }

    private void insertDeliveryUpdatedAt(Prepared prepared, String status, Instant updatedAt, int occurrenceOffset) {
        long id = insertDelivery(
            prepared.settingId(),
            CUTOFF.minusSeconds(3_600).plusSeconds(occurrenceOffset),
            prepared.deviceIds().getFirst(),
            status,
            CUTOFF
        );
        jdbc.sql("UPDATE notification_delivery SET created_at = ?, updated_at = ? WHERE id = ?")
            .params(
                OffsetDateTime.ofInstant(CUTOFF.minus(Duration.ofDays(1)), ZoneOffset.UTC),
                OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC),
                id
            )
            .update();
    }

    private Prepared prepare(List<String> tokens) {
        UUID member = member("연어하나");
        long memberId = settings.memberIdOf(member).orElseThrow();
        addFavorite(memberId, TARGET);
        SavedSetting setting = settings.upsert(memberId, TARGET, SCHEDULE, NOW, NOW);
        UUID sessionId = refreshTokens.issue(member).familyId();
        tokens.forEach(token -> devices.register(member, sessionId, new DeviceToken(token), PushEnvironment.SANDBOX, NOW));
        List<Long> deviceIds = jdbc.sql("SELECT id FROM push_device WHERE member_id = ? ORDER BY id")
            .param(memberId)
            .query(Long.class)
            .list();
        return new Prepared(member, memberId, setting.id(), deviceIds);
    }

    private UUID member(String nickname) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        jdbc.sql("INSERT INTO member (public_id, nickname, created_at, last_login_at) VALUES (?, ?, ?, ?)")
            .params(id, nickname, now, now)
            .update();
        return id;
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

    private long insertDelivery(
        UUID settingId,
        Instant occurrenceAt,
        long deviceId,
        String status,
        Instant nextAttemptAt
    ) {
        OffsetDateTime occurred = OffsetDateTime.ofInstant(occurrenceAt, ZoneOffset.UTC);
        OffsetDateTime next = OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC);
        OffsetDateTime now = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        return jdbc.sql("""
            INSERT INTO notification_delivery (
                setting_id, occurrence_at, device_id, status, attempts, next_attempt_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, 0, ?, ?, ?)
            RETURNING id
            """)
            .params(settingId, occurred, deviceId, status, next, now, now)
            .query(Long.class)
            .single();
    }

    private Instant nextAttemptAt(long id) {
        return jdbc.sql("SELECT next_attempt_at FROM notification_delivery WHERE id = ?")
            .param(id)
            .query(OffsetDateTime.class)
            .single()
            .toInstant();
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM notification_delivery").query(Long.class).single();
    }

    private record Prepared(UUID memberPublicId, long memberId, UUID settingId, List<Long> deviceIds) {
    }
}

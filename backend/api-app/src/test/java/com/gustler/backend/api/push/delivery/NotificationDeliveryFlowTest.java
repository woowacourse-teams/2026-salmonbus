package com.gustler.backend.api.push.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.push.application.NotificationDeliveryRepository;
import com.gustler.backend.api.push.application.NotificationDeliveryService;
import com.gustler.backend.api.push.application.NotificationSettingRepository;
import com.gustler.backend.api.push.application.NotificationSettingRepository.SavedSetting;
import com.gustler.backend.api.push.application.PushDeviceRepository;
import com.gustler.backend.api.push.domain.DeliveryPolicy;
import com.gustler.backend.api.push.domain.DeviceToken;
import com.gustler.backend.api.push.domain.NotificationSchedule;
import com.gustler.backend.api.push.domain.PushEnvironment;
import com.gustler.backend.support.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class NotificationDeliveryFlowTest {
    private static final Instant T0 = Instant.parse("2026-11-01T22:40:00Z");
    private static final Instant NEXT = Instant.parse("2026-11-02T22:40:00Z");
    private static final FavoriteTarget TARGET = new FavoriteTarget("234000884", "203000165", FavoriteDirection.UP);
    private static final NotificationSchedule WEEKDAY_0740 = NotificationSchedule.parse(
        "07:40",
        List.of("MON", "TUE", "WED", "THU", "FRI")
    );
    private static final String TOPIC = "com.salmonbus.app";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicInteger MEMBER_SEQUENCE = new AtomicInteger();

    @Autowired
    private NotificationSettingRepository settings;
    @Autowired
    private NotificationDeliveryRepository deliveries;
    @Autowired
    private PushDeviceRepository devices;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private DataSource dataSource;

    @TempDir
    Path directory;

    private final List<UUID> createdMembers = new ArrayList<>();
    private Path keyFile;
    private ApnsProviderToken providerToken;

    @BeforeEach
    void loadProviderToken() throws Exception {
        keyFile = TestApnsKeys.writePkcs8Pem(
            TestApnsKeys.ecKeyPair("secp256r1"),
            directory.resolve("flow-key.p8")
        );
        providerToken = ApnsProviderToken.load(new ApnsProperties(
            "TEAMTEST01",
            "KEYTEST001",
            keyFile.toString(),
            TOPIC,
            "http://127.0.0.1:9",
            "http://127.0.0.1:9"
        ), Clock.fixed(T0, ZoneOffset.UTC));
    }

    @AfterEach
    void deleteCreatedMembers() {
        for (UUID id : createdMembers) {
            jdbc.sql("DELETE FROM member WHERE public_id = ?").param(id).update();
        }
        createdMembers.clear();
    }

    @Test
    void sendsOncePerDeviceAfterOccurrence() throws Exception {
        Prepared prepared = prepare(2, true);
        try (FakeApnsServer fake = new FakeApnsServer()) {
            runAt(T0.minus(Duration.ofMinutes(1)), fake);
            assertThat(fake.requests()).isEmpty();
            runAt(T0, fake);
            assertThat(tokensOf(fake)).containsExactlyInAnyOrderElementsOf(prepared.tokens());
            assertThat(occurrenceOf(prepared.settingId())).isEqualTo(NEXT);
            assertThat(statusCounts("SENT")).isEqualTo(2);
        }
    }

    @Test
    void twoJobsSendEachDeviceOnce() throws Exception {
        Prepared prepared = prepare(12, true);
        try (FakeApnsServer fake = new FakeApnsServer()) {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            AtomicBoolean failed = new AtomicBoolean(false);
            Runnable task = () -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    runAt(T0, fake);
                } catch (Exception exception) {
                    failed.set(true);
                } finally {
                    done.countDown();
                }
            };
            Thread first = new Thread(task);
            Thread second = new Thread(task);
            first.start();
            second.start();
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(failed).isFalse();
            assertThat(tokensOf(fake)).containsExactlyInAnyOrderElementsOf(prepared.tokens());
            assertThat(deliveryCount()).isEqualTo(12);
        }
    }

    @Test
    void deliveryClaimedByACrashedRunIsSentByALaterRunInsideTheWindow() throws Exception {
        prepare(1, true);
        Duration schedulerLag = Duration.ofMillis(300);
        try (FakeApnsServer fake = new FakeApnsServer()) {
            NotificationDeliveryService crashed = serviceAt(T0.plusSeconds(1));
            crashed.expand(crashed.findDue(100).get(0));
            assertThat(crashed.claimDue(NotificationDeliveryJob.SEND_BATCH_SIZE)).hasSize(1);
            runAt(T0.plus(Duration.ofMinutes(1)).plus(schedulerLag), fake);
            assertThat(fake.requests()).isEmpty();
            runAt(T0.plus(Duration.ofMinutes(2)).plus(schedulerLag), fake);
            assertThat(fake.requests()).hasSize(1);
            assertThat(statusCounts("SENT")).isEqualTo(1);
        }
    }

    @Test
    void retriesOnlyTheFailedDevice() throws Exception {
        Prepared prepared = prepare(2, true);
        String firstToken = prepared.tokens().get(0);
        String secondToken = prepared.tokens().get(1);
        try (FakeApnsServer fake = new FakeApnsServer()) {
            fake.answer(secondToken, 503, "Shutdown");
            runAt(T0, fake);
            fake.answer(secondToken, 200, null);
            runAt(T0.plus(Duration.ofMinutes(1)), fake);
            assertThat(fake.requestsFor(firstToken)).hasSize(1);
            assertThat(fake.requestsFor(secondToken)).hasSize(2);
            assertThat(statusCounts("SENT")).isEqualTo(2);
        }
    }

    @Test
    void userTimeChangeSurvivesDelivery() throws Exception {
        Prepared prepared = prepare(1, true);
        Instant saved = Instant.parse("2026-11-01T23:00:00Z");
        try (FakeApnsServer fake = new FakeApnsServer()) {
            fake.beforeResponse(() -> settings.upsert(
                prepared.memberId(),
                TARGET,
                NotificationSchedule.parse("08:00", List.of("MON")),
                saved,
                T0
            ));
            runAt(T0, fake);
            assertThat(occurrenceOf(prepared.settingId())).isEqualTo(saved);
        }
    }

    @Test
    void rejectedTokensDeleteDevicesAndDeliveries() throws Exception {
        Prepared prepared = prepare(2, true);
        try (FakeApnsServer fake = new FakeApnsServer()) {
            fake.answer(prepared.tokens().get(0), 410, "Unregistered");
            fake.answer(prepared.tokens().get(1), 400, "BadDeviceToken");
            runAt(T0, fake);
            assertThat(deviceCount()).isZero();
            assertThat(deliveryCount()).isZero();
        }
    }

    @Test
    void continuous5xxGivesUpAfterThreeAttempts() throws Exception {
        prepare(1, true);
        try (FakeApnsServer fake = new FakeApnsServer()) {
            fake.answerAll(500, "InternalServerError");
            runAt(T0, fake);
            runAt(T0.plus(Duration.ofMinutes(1)), fake);
            runAt(T0.plus(Duration.ofMinutes(2)), fake);
            runAt(T0.plus(Duration.ofMinutes(3)), fake);
            assertThat(fake.requests()).hasSize(3);
            assertThat(jdbc.sql("SELECT attempts FROM notification_delivery").query(Integer.class).single())
                .isEqualTo(3);
            assertThat(statusCounts("GAVE_UP")).isEqualTo(1);
        }
    }

    @Test
    void schedulerStartingAfterTheMinuteStillMakesTheThirdAttempt() throws Exception {
        prepare(1, true);
        Duration schedulerLag = Duration.ofMillis(300);
        try (FakeApnsServer fake = new FakeApnsServer()) {
            fake.answerAll(500, "InternalServerError");
            runAt(T0.plus(schedulerLag), fake);
            runAt(T0.plus(Duration.ofMinutes(1)).plus(schedulerLag), fake);
            runAt(T0.plus(Duration.ofMinutes(2)).plus(schedulerLag), fake);
            runAt(T0.plus(Duration.ofMinutes(3)).plus(schedulerLag), fake);
            assertThat(fake.requests()).hasSize(3);
            assertThat(statusCounts("GAVE_UP")).isEqualTo(1);
        }
    }

    @Test
    void invalidProviderTokenGivesUpWithoutRetry(CapturedOutput output) throws Exception {
        prepare(1, true);
        try (FakeApnsServer fake = new FakeApnsServer()) {
            fake.answerAll(403, "InvalidProviderToken");
            runAt(T0, fake);
            runAt(T0.plus(Duration.ofMinutes(1)), fake);
            assertThat(fake.requests()).hasSize(1);
            assertThat(statusCounts("GAVE_UP")).isEqualTo(1);
            assertThat(output.getOut() + output.getErr()).contains("status=CONFIG_ERROR");
        }
    }

    @Test
    void firstRunHoursLateGivesUpWithoutSending() throws Exception {
        Prepared prepared = prepare(1, true);
        try (FakeApnsServer fake = new FakeApnsServer()) {
            runAt(T0.plus(Duration.ofHours(3)), fake);
            assertThat(fake.requests()).isEmpty();
            assertThat(statusCounts("GAVE_UP")).isEqualTo(1);
            assertThat(occurrenceOf(prepared.settingId())).isEqualTo(NEXT);
        }
    }

    @Test
    void retryAfterLongPauseGivesUpWithoutSending() throws Exception {
        prepare(1, true);
        try (FakeApnsServer fake = new FakeApnsServer()) {
            fake.answerAll(500, "InternalServerError");
            runAt(T0, fake);
            runAt(T0.plus(Duration.ofMinutes(10)), fake);
            assertThat(fake.requests()).hasSize(1);
            assertThat(statusCounts("GAVE_UP")).isEqualTo(1);
        }
    }

    @Test
    void firstRunAtWindowEdgeStillSends() throws Exception {
        prepare(1, true);
        try (FakeApnsServer fake = new FakeApnsServer()) {
            runAt(T0.plus(DeliveryPolicy.RETRY_WINDOW), fake);
            assertThat(fake.requests()).hasSize(1);
            assertThat(statusCounts("SENT")).isEqualTo(1);
        }
    }

    @Test
    void tokenMovedToAnotherMemberDoesNotReceivePreviousMembersRetry() throws Exception {
        Prepared prepared = prepare(1, true);
        try (FakeApnsServer fake = new FakeApnsServer()) {
            fake.answerAll(500, "InternalServerError");
            runAt(T0, fake);
            UUID other = newMember();
            devices.register(other, new DeviceToken(prepared.tokens().get(0)), PushEnvironment.SANDBOX, T0);
            fake.answerAll(200, "");
            runAt(T0.plus(Duration.ofMinutes(1)), fake);
            assertThat(fake.requests()).hasSize(1);
            assertThat(deliveryCount()).isZero();
        }
    }

    @Test
    void orphanSettingIsDeletedWithoutSending() throws Exception {
        prepare(1, false);
        try (FakeApnsServer fake = new FakeApnsServer()) {
            runAt(T0, fake);
            assertThat(fake.requests()).isEmpty();
            assertThat(deliveryCount()).isZero();
            assertThat(settingCount()).isZero();
        }
    }

    @Test
    void noDevicesStillAdvancesOccurrence() throws Exception {
        Prepared prepared = prepare(0, true);
        try (FakeApnsServer fake = new FakeApnsServer()) {
            runAt(T0, fake);
            assertThat(fake.requests()).isEmpty();
            assertThat(deliveryCount()).isZero();
            assertThat(occurrenceOf(prepared.settingId())).isEqualTo(NEXT);
        }
    }

    @Test
    void payloadTargetMatchesSetting() throws Exception {
        prepare(1, true);
        try (FakeApnsServer fake = new FakeApnsServer()) {
            runAt(T0, fake);
            JsonNode target = MAPPER.readTree(fake.requests().get(0).body()).path("target");
            assertThat(target.path("routeId").asText()).isEqualTo(TARGET.routeId());
            assertThat(target.path("stopId").asText()).isEqualTo(TARGET.stopId());
            assertThat(target.path("direction").asText()).isEqualTo(TARGET.direction().name());
        }
    }

    @Test
    void sendDoesNotHoldADatabaseTransaction() throws Exception {
        prepare(1, true);
        try (FakeApnsServer fake = new FakeApnsServer()) {
            NotificationDeliveryService service = serviceAt(T0);
            ApnsClient client = apnsClient(fake);
            AtomicBoolean txActive = new AtomicBoolean(true);
            AtomicInteger activeConnections = new AtomicInteger(-1);
            fake.beforeResponse(() -> {
                try {
                    activeConnections.set(
                        dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections()
                    );
                } catch (java.sql.SQLException exception) {
                    throw new IllegalStateException(exception.getClass().getSimpleName());
                }
            });
            NotificationDeliveryRunner runner = new NotificationDeliveryRunner(service, delivery -> {
                txActive.set(TransactionSynchronizationManager.isActualTransactionActive());
                return client.send(delivery);
            });
            new NotificationDeliveryJob(service, runner).run();
            assertThat(txActive).isFalse();
            assertThat(activeConnections).hasValue(0);
        }
    }

    @Test
    void logsDoNotContainTokenJwtOrKeyPath(CapturedOutput output) throws Exception {
        Prepared prepared = prepare(1, true);
        try (FakeApnsServer fake = new FakeApnsServer()) {
            runAt(T0, fake);
            String all = output.getOut() + output.getErr();
            assertThat(all).doesNotContain(prepared.tokens().get(0));
            assertThat(all).doesNotContain(keyFile.toString());
            String authorization = fake.requests().get(0).authorization();
            assertThat(authorization).startsWith("bearer ");
            assertThat(all).doesNotContain(authorization.substring("bearer ".length()));
        }
    }

    private void runAt(Instant when, FakeApnsServer fake) {
        NotificationDeliveryService service = serviceAt(when);
        new NotificationDeliveryJob(service, new NotificationDeliveryRunner(service, apnsClient(fake))).run();
    }

    private NotificationDeliveryService serviceAt(Instant when) {
        return new NotificationDeliveryService(
            settings,
            deliveries,
            devices,
            Clock.fixed(when, ZoneOffset.UTC)
        );
    }

    private ApnsClient apnsClient(FakeApnsServer fake) {
        return new ApnsClient(
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build(),
            fake.baseUri(),
            fake.baseUri(),
            TOPIC,
            providerToken,
            MAPPER,
            Duration.ofSeconds(5)
        );
    }

    private Prepared prepare(int deviceCount, boolean withFavorite) {
        UUID member = newMember();
        long memberId = settings.memberIdOf(member).orElseThrow();
        if (withFavorite) {
            addFavorite(memberId, TARGET);
        }
        SavedSetting setting = settings.upsert(memberId, TARGET, WEEKDAY_0740, T0, T0);
        List<String> tokens = IntStream.range(0, deviceCount)
            .mapToObj(index -> String.format("%064x", index + 0xabcd))
            .toList();
        tokens.forEach(token -> devices.register(member, new DeviceToken(token), PushEnvironment.SANDBOX, T0));
        return new Prepared(member, memberId, setting.id(), tokens);
    }

    private UUID newMember() {
        UUID id = UUID.randomUUID();
        createdMembers.add(id);
        OffsetDateTime now = OffsetDateTime.ofInstant(T0, ZoneOffset.UTC);
        jdbc.sql("INSERT INTO member (public_id, nickname, created_at, last_login_at) VALUES (?, ?, ?, ?)")
            .params(id, "flow" + MEMBER_SEQUENCE.incrementAndGet(), now, now)
            .update();
        return id;
    }

    private void addFavorite(long memberId, FavoriteTarget target) {
        UUID groupId = UUID.randomUUID();
        jdbc.sql("INSERT INTO favorite_group (id, member_id, name, position) VALUES (?, ?, ?, ?)")
            .params(groupId, memberId, "출근", 0)
            .update();
        jdbc.sql(
            "INSERT INTO favorite (id, favorite_group_id, route_id, stop_id, direction, position) "
                + "VALUES (?, ?, ?, ?, ?, ?)"
        )
            .params(UUID.randomUUID(), groupId, target.routeId(), target.stopId(), target.direction().name(), 0)
            .update();
    }

    private Instant occurrenceOf(UUID settingId) {
        return jdbc.sql("SELECT occurrence_at FROM notification_setting WHERE id = ?")
            .param(settingId)
            .query(OffsetDateTime.class)
            .single()
            .toInstant();
    }

    private long statusCounts(String status) {
        return jdbc.sql("SELECT count(*) FROM notification_delivery WHERE status = ?")
            .param(status)
            .query(Long.class)
            .single();
    }

    private long deliveryCount() {
        return jdbc.sql("SELECT count(*) FROM notification_delivery").query(Long.class).single();
    }

    private long deviceCount() {
        return jdbc.sql("SELECT count(*) FROM push_device").query(Long.class).single();
    }

    private long settingCount() {
        return jdbc.sql("SELECT count(*) FROM notification_setting").query(Long.class).single();
    }

    private static List<String> tokensOf(FakeApnsServer fake) {
        return fake.requests().stream().map(FakeApnsServer.Received::token).toList();
    }

    private record Prepared(UUID memberPublicId, long memberId, UUID settingId, List<String> tokens) {
    }
}

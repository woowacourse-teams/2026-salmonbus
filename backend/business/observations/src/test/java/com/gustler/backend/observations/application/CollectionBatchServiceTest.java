package com.gustler.backend.observations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.gbis.api.GbisLocationResult;
import com.gustler.backend.gbis.api.dto.BusLocationResponse;
import com.gustler.backend.observations.api.ObservationSeatQualityChecker;
import com.gustler.backend.observations.domain.CollectionInputConfirmedException;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.domain.CollectionPreparation;
import com.gustler.backend.observations.domain.ObservationResponse;
import com.gustler.backend.observations.infrastructure.gbis.GbisObservationMapper;
import com.gustler.backend.observations.support.ObservationIntegrationTest;
import com.gustler.backend.quota.api.CallQuota;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 예약이 자체 트랜잭션에서 커밋되므로 @Transactional 을 붙이면 장부가 정리되지 않는다. 매번 비우고 시작한다.
 */
@ObservationIntegrationTest
@Import(CollectionBatchServiceTest.QualityFailureConfiguration.class)
class CollectionBatchServiceTest {

    private static final String SOURCE_ID = "GBIS";
    private static final String ROUTE_204000057 = "204000057";
    private static final String CONTENT_DIGEST = "0".repeat(64);
    private static final String ATTEMPT_KEY = "204000057-2026-08-19T11:14";
    private static final String QUERY_TIME = "2026-08-19 11:14:04.9";
    private static final String VEHICLE_ID = "204000206";
    private static final String STOP_ID = "205000217";

    private static final OffsetDateTime SCHEDULED_AT = OffsetDateTime.parse("2026-08-19T11:14:00+09:00");
    private static final OffsetDateTime RESERVED_AT = OffsetDateTime.parse("2026-08-19T11:14:04.700+09:00");
    private static final OffsetDateTime REQUESTED_AT = OffsetDateTime.parse("2026-08-19T11:14:04.800+09:00");
    private static final OffsetDateTime RESPONSE_RECEIVED_AT = OffsetDateTime.parse("2026-08-19T11:14:04.911+09:00");
    private static final LocalDate KOREA_8_19 = LocalDate.of(2026, 8, 19);

    private static final int ALREADY_USED_UP = 3;
    private static final long MISSING_ROUTE_VERSION_ID = 9_999_999L;

    /** 한국 시각 2026-08-28 23:59:59 와 2026-08-29 00:00:00. 세계 표준시로는 같은 날이다. */
    private static final OffsetDateTime KOREA_8_28_LATE_NIGHT = OffsetDateTime.parse("2026-08-28T14:59:59Z");
    private static final OffsetDateTime KOREA_8_29_MIDNIGHT = OffsetDateTime.parse("2026-08-28T15:00:00Z");
    private static final LocalDate KOREA_8_29 = LocalDate.of(2026, 8, 29);

    @Autowired
    private CollectionBatchService ledger;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private FailingQualityHook failingQualityHook;

    private long routeVersionId;
    private boolean recordingWrites;

    @BeforeEach
    void 노선과_판본을_먼저_저장한다() {
        routeVersionId = insertRouteVersion(insertRoute());
    }

    @AfterEach
    void 쌓인_것을_비운다() {
        stopRecordingWrites();
        failingQualityHook.fail = false;
        failingQualityHook.afterCalls = 0;
        jdbcClient.sql("""
            TRUNCATE vehicle_observation, observation_batch, forecast_run, route_stop, route_version, route,
                daily_call_quota RESTART IDENTITY CASCADE
            """).update();
    }

    @Test
    void 준비_확정은_전송_준비_상태를_남기고_전송시각은_기록하지_않는다() {
        // when
        final long batchId = ledger.prepare(plan(), REQUESTED_AT).batchId();

        // then
        assertSoftly(checks -> {
            checks.assertThat(outcomeOf(batchId)).isEqualTo("DISPATCHING");
            checks.assertThat(requestedAtOf(batchId)).isNull();
            checks.assertThat(reservedCallsOn(KOREA_8_19)).isEqualTo(1);
        });
    }

    @Test
    void 계획일이_달라도_준비한_한국_날짜에서만_호출_한도를_확보한다() {
        // given
        CollectionPlan yesterday = new CollectionPlan(routeVersionId, KOREA_8_28_LATE_NIGHT, ATTEMPT_KEY);

        // when
        ledger.prepare(yesterday, KOREA_8_29_MIDNIGHT);

        // then
        assertSoftly(checks -> {
            checks.assertThat(reservedCallsOn(KOREA_8_29)).isEqualTo(1);
            checks.assertThat(keyAliasesOn(LocalDate.of(2026, 8, 28))).isEmpty();
        });
    }

    @Test
    void 준비된_수집은_DISPATCHING으로_남는다() {
        // when
        final long batchId = ledger.prepare(plan(), RESERVED_AT).batchId();

        // then
        assertThat(outcomeOf(batchId)).isEqualTo("DISPATCHING");
    }

    @Test
    void 자리를_잡은_판은_한도를_한_번_쓴다() {
        // when
        ledger.prepare(plan(), RESERVED_AT);

        // then
        assertThat(reservedCallsOn(KOREA_8_19)).isEqualTo(1);
    }

    @Test
    void 자리를_못_잡은_판은_NOT_RESERVED로_남는다() {
        // given
        useUpTheQuota();

        // when
        final long batchId = ledger.prepare(plan(), RESERVED_AT).batchId();

        // then
        assertThat(outcomeOf(batchId)).isEqualTo("NOT_RESERVED");
    }

    @Test
    void 자리를_못_잡은_판의_실패_사유는_LOCAL_QUOTA_EXHAUSTED다() {
        // given
        useUpTheQuota();

        // when
        final long batchId = ledger.prepare(plan(), RESERVED_AT).batchId();

        // then
        assertThat(failureCodeOf(batchId)).isEqualTo("LOCAL_QUOTA_EXHAUSTED");
    }

    @Test
    void 자리를_못_잡은_판은_보낸_시각이_비어_있다() {
        // given
        useUpTheQuota();

        // when
        final long batchId = ledger.prepare(plan(), RESERVED_AT).batchId();

        // then
        assertThat(requestedAtOf(batchId)).isNull();
    }

    @Test
    void 수집_준비는_호출자의_롤백과_독립적으로_확정된다() {
        // when
        Long batchId = new TransactionTemplate(transactionManager).execute(status -> {
            final long prepared = ledger.prepare(plan(), REQUESTED_AT).batchId();
            status.setRollbackOnly();
            return prepared;
        });

        // then
        assertThat(batchId).isNotNull();
        assertSoftly(checks -> {
            checks.assertThat(outcomeOf(batchId)).isEqualTo("DISPATCHING");
            checks.assertThat(reservedCallsOn(KOREA_8_19)).isEqualTo(1);
        });
    }

    @Test
    void 준비한_수집에는_응답_수신시각이_아직_없다() {
        // when
        final long batchId = ledger.prepare(plan(), REQUESTED_AT).batchId();

        // then
        assertThat(responseReceivedAtOf(batchId)).isNull();
    }

    @Test
    void 준비_후_중단된_수집은_전송_여부를_확정하지_않는다() {
        // given
        final long batchId = ledger.prepare(plan(), REQUESTED_AT).batchId();

        // when
        ledger.closeUnfinished(batchId);

        // then
        assertThat(outcomeOf(batchId)).isEqualTo("UNKNOWN_AFTER_DISPATCH");
    }

    @Test
    void 보냈는데_응답이_오지_않은_판은_UNKNOWN_AFTER_DISPATCH로_남는다() {
        // given
        final long batchId = dispatchedBatch();

        // when
        ledger.conclude(batchId, reply(new GbisLocationResult.NoResponse("I/O error on GET request")),
            RESPONSE_RECEIVED_AT);

        // then
        assertThat(outcomeOf(batchId)).isEqualTo("UNKNOWN_AFTER_DISPATCH");
    }

    @Test
    void 응답을_받은_판은_받은_시각을_남긴다() {
        // given
        final long batchId = dispatchedBatch();

        // when
        ledger.conclude(batchId, reply(new GbisLocationResult.NoVehicles(QUERY_TIME)), RESPONSE_RECEIVED_AT);

        // then
        assertThat(responseReceivedAtOf(batchId)).isEqualTo(RESPONSE_RECEIVED_AT);
    }

    @Test
    void 상류가_오류로_답한_판은_실패_사유를_같이_남긴다() {
        // given
        final long batchId = dispatchedBatch();

        // when
        ledger.conclude(batchId, reply(new GbisLocationResult.GbisSystemError(QUERY_TIME, "시스템 오류가 발생하였습니다.")),
            RESPONSE_RECEIVED_AT);

        // then
        assertThat(failureCodeOf(batchId)).isEqualTo("UPSTREAM_ERROR");
    }

    @Test
    void 운행_차량이_없다는_응답도_상류가_준_행_수_0을_남긴다() {
        // given
        final long batchId = dispatchedBatch();

        // when
        ledger.conclude(batchId, reply(new GbisLocationResult.NoVehicles(QUERY_TIME)), RESPONSE_RECEIVED_AT);

        // then
        assertThat(providerRowsOf(batchId)).isZero();
    }

    /**
     * 호출 예약과 수집 배치는 한 트랜잭션에서 커밋된다. 배치 생성이 실패하면 예약도 같이 롤백되어야 수집 배치 없이 호출 횟수만 증가하는
     * 상태를 막을 수 있다.
     */
    @Test
    void 판을_못_열면_쓴_횟수도_안_오른다() {
        // given
        // 없는 노선 버전이라 수집 배치를 생성하다 외래 키 제약에 걸린다
        CollectionPlan missingRoutePlan = new CollectionPlan(MISSING_ROUTE_VERSION_ID, SCHEDULED_AT, ATTEMPT_KEY);

        // when
        assertThatThrownBy(() -> ledger.prepare(missingRoutePlan, RESERVED_AT))
            .isInstanceOf(DataIntegrityViolationException.class);

        // then
        assertThat(quotaRowCount()).isZero();
    }

    @Test
    void 자정을_지난_준비는_새_날짜의_한도를_쓴다() {
        // given
        CollectionPlan planned = new CollectionPlan(routeVersionId, KOREA_8_28_LATE_NIGHT, ATTEMPT_KEY);

        // when
        ledger.prepare(planned, KOREA_8_29_MIDNIGHT);

        // then
        assertThat(reservedCallsOn(KOREA_8_29)).isEqualTo(1);
    }

    @Test
    void 자정을_지난_준비는_선택한_키의_새_날짜_장부에_센다() {
        // when
        CollectionPreparation prepared =
            ledger.prepare(plan(), KOREA_8_29_MIDNIGHT);

        // then
        assertThat(keyAliasesOn(KOREA_8_29)).containsExactly(prepared.keyAlias());
    }

    @Test
    void 준비하는_날의_한도가_없으면_호출_키를_내주지_않는다() {
        // given
        useUpQuotaOn(KOREA_8_29);

        // when
        CollectionPreparation prepared =
            ledger.prepare(plan(), KOREA_8_29_MIDNIGHT);

        // then
        assertSoftly(checks -> {
            checks.assertThat(prepared.reserved()).isFalse();
            checks.assertThat(prepared.keyAlias()).isNull();
        });
    }

    @Test
    void 준비하는_날의_한도가_없으면_NOT_RESERVED로_남는다() {
        // given
        useUpQuotaOn(KOREA_8_29);

        // when
        final long batchId = ledger.prepare(plan(), KOREA_8_29_MIDNIGHT).batchId();

        // then
        assertSoftly(checks -> {
            checks.assertThat(outcomeOf(batchId)).isEqualTo("NOT_RESERVED");
            checks.assertThat(requestedAtOf(batchId)).isNull();
        });
    }

    @Test
    void 계산_입력을_확정한_배치는_같은_계획으로_다시_열_수_없다() {
        // given
        confirmSuccessfulInput();

        // when
        Throwable caughtFailure = catchThrowable(() -> ledger.prepare(plan(), RESERVED_AT));

        // then
        assertThat(caughtFailure).isNotNull().isInstanceOf(CollectionInputConfirmedException.class);
    }

    @Test
    void 계산_입력이_확정되어_재수집이_거절되면_호출_횟수도_늘지_않는다() {
        // given
        confirmSuccessfulInput();

        // when
        assertThatThrownBy(() -> ledger.prepare(plan(), RESERVED_AT))
            .isInstanceOf(CollectionInputConfirmedException.class);

        // then
        // 첫 예약 하나에서 안 늘었다
        assertThat(reservedCallsOn(KOREA_8_19)).isEqualTo(1);
    }

    @Test
    void 예보가_쓴_배치는_같은_계획으로_다시_열지_않고_관측과_시도_횟수와_호출_횟수를_그대로_둔다() {
        // given
        insertStop();
        final long batchId = dispatchedBatch();
        BusLocationResponse.BusLocation bus = new BusLocationResponse.BusLocation(
                "경기70아0001",
                VEHICLE_ID,
                0,
                ROUTE_204000057,
                11,
                STOP_ID,
                1,
                2,
                43,
                3,
                1
        );
        ledger.conclude(batchId, reply(new GbisLocationResult.Success(QUERY_TIME, List.of(bus))), RESPONSE_RECEIVED_AT);
        insertForecastRun(batchId, 0);

        // when
        Throwable reopened = catchThrowable(() -> ledger.prepare(plan(), RESERVED_AT));

        // then
        assertSoftly(softly -> {
            softly.assertThat(reopened).isInstanceOf(CollectionInputConfirmedException.class);
            softly.assertThat(jdbcClient.sql("SELECT count(*) FROM vehicle_observation WHERE observation_batch_id = ?")
                .param(batchId).query(Integer.class).single()).isOne();
            softly.assertThat(attemptNumberOf(batchId)).isOne();
            softly.assertThat(reservedCallsOn(KOREA_8_19)).isOne();
        });
    }

    @Test
    void 다른_날짜의_예보_실행_기록은_같은_계획을_다시_여는_것을_막지_않는다() {
        // given
        final long batchId = dispatchedBatch();
        insertForecastRun(batchId, 1);

        // when
        ledger.prepare(plan(), RESERVED_AT);

        // then
        assertThat(attemptNumberOf(batchId)).isEqualTo(2);
    }

    @Test
    void 같은_계획을_재시도하면_수집_묶음은_한_행으로_남는다() {
        // given
        ledger.prepare(plan(), RESERVED_AT);

        // when
        ledger.prepare(plan(), RESERVED_AT);

        // then
        assertThat(batchCountOf(ATTEMPT_KEY)).isEqualTo(1);
    }

    @Test
    void 같은_계획을_재시도하면_한도를_두_번_쓴다() {
        // given
        ledger.prepare(plan(), RESERVED_AT);

        // when
        ledger.prepare(plan(), RESERVED_AT);

        // then
        assertThat(reservedCallsOn(KOREA_8_19)).isEqualTo(2);
    }

    @Test
    void 같은_계획을_재시도하면_시도_횟수가_2가_된다() {
        // given
        final long batchId = ledger.prepare(plan(), RESERVED_AT).batchId();

        // when
        ledger.prepare(plan(), RESERVED_AT);

        // then
        assertThat(attemptNumberOf(batchId)).isEqualTo(2);
    }

    @Test
    void 같은_계획을_다시_열면_지난_시도의_관측이_지워진다() {
        // given
        insertStop();
        final long batchId = dispatchedBatch();
        BusLocationResponse.BusLocation bus = new BusLocationResponse.BusLocation(
                "경기70아0001",
                VEHICLE_ID,
                0,
                ROUTE_204000057,
                11,
                STOP_ID,
                1,
                2,
                43,
                3,
                1
        );
        recordWrites();
        ledger.conclude(batchId, reply(new GbisLocationResult.Success(QUERY_TIME, List.of(bus))), RESPONSE_RECEIVED_AT);

        assertThat(recordedWrites()).containsExactly("INSERT:DISPATCHING:1", "UPDATE:SUCCESS_ROWS:1");
        jdbcClient.sql("TRUNCATE test_batch_write_order RESTART IDENTITY").update();

        // when
        ledger.prepare(plan(), RESERVED_AT);

        // then
        assertThat(recordedWrites()).containsExactly("DELETE:SUCCESS_ROWS:1", "UPDATE:DISPATCHING:2");
        assertThat(jdbcClient.sql("SELECT count(*) FROM vehicle_observation WHERE observation_batch_id = ?")
            .param(batchId).query(Integer.class).single()).isZero();
    }

    @Test
    void 재시도로_다시_연_판은_지난_시도의_보낸_시각을_들고_있지_않는다() {
        // given
        final long batchId = dispatchedBatch();

        jdbcClient.sql("UPDATE observation_batch SET requested_at=? WHERE id=?")
            .params(REQUESTED_AT, batchId).update();

        // when
        ledger.prepare(plan(), RESERVED_AT);

        // then
        assertThat(requestedAtOf(batchId)).isNull();
    }

    @Test
    void 품질_처리가_실패하면_관측과_완료_상태는_롤백되고_전송_기록은_남는다() {
        // given
        insertStop();
        final long batchId = dispatchedBatch();
        failingQualityHook.fail = true;
        BusLocationResponse.BusLocation bus = new BusLocationResponse.BusLocation(
                "경기70아0001",
                VEHICLE_ID,
                0,
                ROUTE_204000057,
                11,
                STOP_ID,
                1,
                2,
                71,
                3,
                1
        );

        // when
        Throwable caughtFailure = catchThrowable(() -> ledger.conclude(batchId,
            reply(new GbisLocationResult.Success(QUERY_TIME, List.of(bus))), RESPONSE_RECEIVED_AT));

        // then
        assertSoftly(checks -> {
            checks.assertThat(caughtFailure).isNotNull()
                .isInstanceOf(IllegalStateException.class).hasMessage("품질 처리 실패");
            checks.assertThat(outcomeOf(batchId)).isEqualTo("DISPATCHING");
            checks.assertThat(requestedAtOf(batchId)).isNull();
            checks.assertThat(responseReceivedAtOf(batchId)).isNull();
            checks.assertThat(reservedCallsOn(KOREA_8_19)).isEqualTo(1);
            checks.assertThat(jdbcClient.sql("SELECT count(*) FROM vehicle_observation WHERE observation_batch_id = ?")
                .param(batchId).query(Integer.class).single()).isZero();
            checks.assertThat(jdbcClient.sql(
                "SELECT count(*) FROM information_schema.tables WHERE table_name='trip_quality_rebuild' AND CAST(? AS bigint)>0")
                .param(routeVersionId).query(Integer.class).single()).isZero();
        });
    }

    /** 실제 SQL 실행 시점에 보이는 배치 상태를 남긴다. 이 테스트의 격리 DB에서만 생성한다. */
    private void recordWrites() {
        recordingWrites = true;
        jdbcClient.sql("""
            CREATE TABLE test_batch_write_order (
                sequence bigint GENERATED ALWAYS AS IDENTITY,
                operation text,
                outcome text,
                attempt_number integer
            )
            """).update();
        jdbcClient.sql("""
            CREATE FUNCTION test_record_observation_write() RETURNS trigger LANGUAGE plpgsql AS $$
            DECLARE batch_id bigint;
            BEGIN
                IF TG_OP = 'DELETE' THEN
                    batch_id := OLD.observation_batch_id;
                ELSE
                    batch_id := NEW.observation_batch_id;
                END IF;
                INSERT INTO test_batch_write_order (operation, outcome, attempt_number)
                    SELECT TG_OP, outcome, attempt_number FROM observation_batch WHERE id = batch_id;
                RETURN NULL;
            END $$
            """).update();
        jdbcClient.sql("""
            CREATE TRIGGER test_record_observation_write AFTER INSERT OR DELETE ON vehicle_observation
                FOR EACH ROW EXECUTE FUNCTION test_record_observation_write()
            """).update();
        jdbcClient.sql("""
            CREATE FUNCTION test_record_batch_write() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                INSERT INTO test_batch_write_order (operation, outcome, attempt_number)
                    VALUES (TG_OP, NEW.outcome, NEW.attempt_number);
                RETURN NULL;
            END $$
            """).update();
        jdbcClient.sql("""
            CREATE TRIGGER test_record_batch_write AFTER UPDATE ON observation_batch
                FOR EACH ROW EXECUTE FUNCTION test_record_batch_write()
            """).update();
    }

    private List<String> recordedWrites() {
        return jdbcClient.sql("""
            SELECT operation || ':' || outcome || ':' || attempt_number
            FROM test_batch_write_order ORDER BY sequence
            """).query(String.class).list();
    }

    private void stopRecordingWrites() {
        if (!recordingWrites) {
            return;
        }

        jdbcClient.sql("DROP TRIGGER IF EXISTS test_record_observation_write ON vehicle_observation").update();
        jdbcClient.sql("DROP TRIGGER IF EXISTS test_record_batch_write ON observation_batch").update();
        jdbcClient.sql("DROP FUNCTION IF EXISTS test_record_observation_write()").update();
        jdbcClient.sql("DROP FUNCTION IF EXISTS test_record_batch_write()").update();
        jdbcClient.sql("DROP TABLE IF EXISTS test_batch_write_order").update();
        recordingWrites = false;
    }

    private CollectionPlan plan() {
        return new CollectionPlan(routeVersionId, SCHEDULED_AT, ATTEMPT_KEY);
    }

    @Test
    void 예약만_남은_수집은_기록을_보존하고_상태만_종료한다() {
        final long batchId = ledger.prepare(plan(), RESERVED_AT).batchId();
        jdbcClient.sql("UPDATE observation_batch SET outcome='RESERVED' WHERE id=?").param(batchId).update();
        String before = batchWithoutOutcome(batchId);

        assertThat(ledger.closeUnfinished(batchId)).isEqualTo(1);

        assertSoftly(checks -> {
            checks.assertThat(outcomeOf(batchId)).isEqualTo("ABANDONED_BEFORE_SEND");
            checks.assertThat(batchWithoutOutcome(batchId)).isEqualTo(before);
            checks.assertThat(reservedCallsOn(KOREA_8_19)).isEqualTo(1);
        });
    }

    @Test
    void 전송한_수집의_종료는_호출자_트랜잭션이_롤백돼도_유지된다() {
        final long batchId = dispatchedBatch();
        String before = batchWithoutOutcome(batchId);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            ledger.closeUnfinished(batchId);
            status.setRollbackOnly();
        });

        assertSoftly(checks -> {
            checks.assertThat(outcomeOf(batchId)).isEqualTo("UNKNOWN_AFTER_DISPATCH");
            checks.assertThat(batchWithoutOutcome(batchId)).isEqualTo(before);
        });
    }

    @Test
    void 응답을_저장한_수집은_종료와_다음_회차_정리에서_제외한다() {
        final long batchId = dispatchedBatch();
        ledger.conclude(batchId, reply(new GbisLocationResult.NoVehicles(QUERY_TIME)), RESPONSE_RECEIVED_AT);
        String before = batchWithoutOutcome(batchId);

        assertThat(ledger.closeUnfinished(batchId)).isZero();
        assertThat(ledger.closePreviousRound()).isEmpty();

        assertSoftly(checks -> {
            checks.assertThat(outcomeOf(batchId)).isEqualTo("SUCCESS_EMPTY");
            checks.assertThat(batchWithoutOutcome(batchId)).isEqualTo(before);
        });
    }

    @Test
    void 관측이_저장된_배치는_미완료_상태라도_정리하지_않는다() {
        final long batchId = dispatchedBatch();
        insertStop();
        jdbcClient.sql("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,
                stop_order,stop_id,running_state,passed_stop_order,remaining_seats)
            VALUES(?,?,1,?,1,?,2,1,12)
            """).params(
                    batchId,
                    routeVersionId,
                    VEHICLE_ID,
                    STOP_ID
            ).update();

        assertThat(ledger.closeUnfinished(batchId)).isZero();
        assertThat(ledger.closePreviousRound()).isEmpty();

        assertSoftly(checks -> {
            checks.assertThat(outcomeOf(batchId)).isEqualTo("DISPATCHING");
            checks.assertThat(jdbcClient.sql("SELECT count(*) FROM vehicle_observation WHERE observation_batch_id=?")
                .param(batchId).query(Integer.class).single()).isEqualTo(1);
        });
    }

    @Test
    void 예보_실행_기록이_있는_배치는_미완료_상태라도_정리하지_않는다() {
        // given
        final long batchId = dispatchedBatch();
        final long routeId = jdbcClient.sql("SELECT route_id FROM route_version WHERE id=?").param(routeVersionId)
            .query(Long.class).single();
        jdbcClient.sql("""
            INSERT INTO forecast_run(source_date,observation_batch_id,route_id,route_version_id,received_at,
                confirmed_at,outcome,prediction_count,source_incomplete,counted_rows)
            VALUES((CAST(? AS timestamptz) AT TIME ZONE 'Asia/Seoul')::date,?,?,?,?,?,'EMPTY',0,false,0)
            """).params(
                    SCHEDULED_AT,
                    batchId,
                    routeId,
                    routeVersionId,
                    RESPONSE_RECEIVED_AT,
                    RESPONSE_RECEIVED_AT.plusSeconds(1)
            ).update();
        String before = batchWithoutOutcome(batchId);

        // when
        final int closed = ledger.closeUnfinished(batchId);
        List<Long> closedInRound = ledger.closePreviousRound();

        // then
        assertSoftly(softly -> {
            softly.assertThat(closed).isZero();
            softly.assertThat(closedInRound).isEmpty();
            softly.assertThat(outcomeOf(batchId)).isEqualTo("DISPATCHING");
            softly.assertThat(batchWithoutOutcome(batchId)).isEqualTo(before);
        });
    }

    @Test
    void 다른_날짜의_예보_실행_기록은_미완료_정리를_막지_않는다() {
        // given
        final long batchId = dispatchedBatch();
        final long routeId = jdbcClient.sql("SELECT route_id FROM route_version WHERE id=?").param(routeVersionId)
            .query(Long.class).single();
        jdbcClient.sql("""
            INSERT INTO forecast_run(source_date,observation_batch_id,route_id,route_version_id,received_at,
                confirmed_at,outcome,prediction_count,source_incomplete,counted_rows)
            VALUES((CAST(? AS timestamptz) AT TIME ZONE 'Asia/Seoul')::date + 1,?,?,?,?,?,'EMPTY',0,false,0)
            """).params(
                    SCHEDULED_AT,
                    batchId,
                    routeId,
                    routeVersionId,
                    RESPONSE_RECEIVED_AT,
                    RESPONSE_RECEIVED_AT.plusSeconds(1)
            ).update();

        // when
        final int closed = ledger.closeUnfinished(batchId);

        // then
        assertSoftly(softly -> {
            softly.assertThat(closed).isOne();
            softly.assertThat(outcomeOf(batchId)).isEqualTo("UNKNOWN_AFTER_DISPATCH");
        });
    }

    @Test
    void 다음_회차에서_오래된_미완료를_백건까지만_종료한다() {
        jdbcClient.sql("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,outcome,
                normalization_version,collection_strategy_version)
            SELECT ?,CAST(? AS timestamptz)+n*interval '1 second',1,concat('unfinished-',n),'RESERVED','n','c'
            FROM generate_series(1,101) n
            """).params(routeVersionId, SCHEDULED_AT).update();

        List<Long> closed = ledger.closePreviousRound();

        assertSoftly(checks -> {
            checks.assertThat(closed).hasSize(100);
            checks.assertThat(jdbcClient.sql("SELECT count(*) FROM observation_batch WHERE outcome='RESERVED'")
                .query(Integer.class).single()).isEqualTo(1);
        });
        assertThat(ledger.closePreviousRound()).hasSize(1);
    }

    @Test
    void 다른_트랜잭션이_잠근_미완료는_기다리지_않고_건너뛴다() {
        final long batchId = ledger.prepare(plan(), RESERVED_AT).batchId();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcClient.sql("SELECT id FROM observation_batch WHERE id=? FOR UPDATE")
                .param(batchId).query(Long.class).single();
            assertThat(ledger.closePreviousRound()).isEmpty();
        });

        assertThat(outcomeOf(batchId)).isEqualTo("DISPATCHING");
        assertThat(ledger.closePreviousRound()).containsExactly(batchId);
    }

    private String batchWithoutOutcome(final long batchId) {
        return jdbcClient.sql("SELECT (to_jsonb(batch)-'outcome')::text FROM observation_batch batch WHERE id=?")
            .param(batchId).query(String.class).single();
    }

    private long dispatchedBatch() {
        return ledger.prepare(plan(), RESERVED_AT).batchId();
    }

    private static ObservationResponse reply(GbisLocationResult result) {
        return receivedAt -> GbisObservationMapper.response(result, receivedAt);
    }

    private void insertStop() {
        jdbcClient.sql("""
            INSERT INTO route_stop (route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
            VALUES (?, 1, ?, '범계역', 'UP', true)
            """).params(routeVersionId, STOP_ID).update();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class QualityFailureConfiguration {
        @Bean
        FailingQualityHook failingQualityHook() {
            return new FailingQualityHook();
        }
    }

    @Order(Ordered.LOWEST_PRECEDENCE)
    static class FailingQualityHook implements ObservationSeatQualityChecker {
        private boolean fail;
        private int afterCalls;

        @Override
        public void observationsStored(final long batchId, final long routeVersionId, final boolean hasPositiveSeats) {
            afterCalls++;
            if (fail) {
                throw new IllegalStateException("품질 처리 실패");
            }
        }
    }

    @Test
    void 실시간_수집은_LIVE로_기록한다() {
        // given
        final long batchId = dispatchedBatch();

        // when
        ledger.conclude(batchId, reply(new GbisLocationResult.NoVehicles(QUERY_TIME)), RESPONSE_RECEIVED_AT);

        // then
        assertThat(jdbcClient.sql("SELECT ingestion_origin FROM observation_batch WHERE id=?")
            .param(batchId).query(String.class).single()).isEqualTo("LIVE");
    }

    private int quotaRowCount() {
        return jdbcClient.sql("SELECT count(*) FROM daily_call_quota").query(Integer.class).single();
    }

    private void useUpQuotaOn(LocalDate kstDate) {
        jdbcClient.sql("""
            INSERT INTO daily_call_quota (provider, api_service, kst_date, reserved_calls, daily_limit)
            VALUES (?, ?, ?, ?, ?)
            """).params(
                    CallQuota.BUS_LOCATION.provider(),
                    CallQuota.BUS_LOCATION.apiService(),
                    kstDate,
                    ALREADY_USED_UP,
                    ALREADY_USED_UP
            ).update();
    }

    /** 입력 확인 시각이 기록된 배치를 만든다. 예보 실행 기록이 생기기 전에 처리된 배치가 이 열에 처리 시작 시각을 갖고 있다. */
    /** 배치 예약 시각의 한국 날짜에서 {@code daysLater}일 뒤 날짜로 예보 실행 기록을 넣는다. */
    private void insertForecastRun(final long batchId, final int daysLater) {
        final long routeId = jdbcClient.sql("SELECT route_id FROM route_version WHERE id=?").param(routeVersionId)
            .query(Long.class).single();
        jdbcClient.sql("""
            INSERT INTO forecast_run(source_date,observation_batch_id,route_id,route_version_id,received_at,
                confirmed_at,outcome,prediction_count,source_incomplete,counted_rows)
            VALUES((CAST(? AS timestamptz) AT TIME ZONE 'Asia/Seoul')::date + ?,?,?,?,?,?,'EMPTY',0,false,0)
            """).params(
                    SCHEDULED_AT,
                    daysLater,
                    batchId,
                    routeId,
                    routeVersionId,
                    RESPONSE_RECEIVED_AT,
                    RESPONSE_RECEIVED_AT.plusSeconds(1)
            ).update();
    }

    private long confirmSuccessfulInput() {
        final long batchId = dispatchedBatch();
        ledger.conclude(batchId, reply(new GbisLocationResult.NoVehicles(QUERY_TIME)), RESPONSE_RECEIVED_AT);
        jdbcClient.sql("UPDATE observation_batch SET input_confirmed_at=? WHERE id=?")
            .params(RESPONSE_RECEIVED_AT.plusSeconds(1), batchId).update();
        return batchId;
    }

    private void useUpTheQuota() {
        jdbcClient.sql("""
            INSERT INTO daily_call_quota (provider, api_service, kst_date, reserved_calls, daily_limit)
            VALUES (?, ?, ?, ?, ?)
            """).params(
                    CallQuota.BUS_LOCATION.provider(),
                    CallQuota.BUS_LOCATION.apiService(),
                    KOREA_8_19,
                    ALREADY_USED_UP,
                    ALREADY_USED_UP
            ).update();
    }

    private long insertRoute() {
        return jdbcClient.sql("""
            INSERT INTO route (
                public_route_id, source_id, source_route_id,
                display_name, start_stop_name, end_stop_name
            ) VALUES (?, ?, ?, ?, ?, ?)
            RETURNING id
            """).params(
                    ROUTE_204000057,
                    SOURCE_ID,
                    ROUTE_204000057,
                    "3330",
                    "범계역",
                    "강남역"
            ).query(Long.class).single();
    }

    private long insertRouteVersion(final long routeId) {
        return jdbcClient.sql("""
            INSERT INTO route_version (route_id, content_digest, valid_from)
            VALUES (?, ?, ?)
            RETURNING id
            """).params(routeId, CONTENT_DIGEST, SCHEDULED_AT).query(Long.class).single();
    }

    private String outcomeOf(final long batchId) {
        return columnOf("outcome", batchId, String.class);
    }

    private String failureCodeOf(final long batchId) {
        return columnOf("failure_code", batchId, String.class);
    }

    private OffsetDateTime requestedAtOf(final long batchId) {
        return columnOf("requested_at", batchId, OffsetDateTime.class);
    }

    private OffsetDateTime responseReceivedAtOf(final long batchId) {
        return columnOf("response_received_at", batchId, OffsetDateTime.class);
    }

    private Integer providerRowsOf(final long batchId) {
        return columnOf("provider_rows", batchId, Integer.class);
    }

    private Integer attemptNumberOf(final long batchId) {
        return columnOf("attempt_number", batchId, Integer.class);
    }

    private <T> T columnOf(String column, final long batchId, Class<T> type) {
        return jdbcClient.sql("SELECT %s FROM observation_batch WHERE id = ?".formatted(column)).param(batchId)
            .query(type).optional().orElse(null);
    }

    private int batchCountOf(String attemptKey) {
        return jdbcClient.sql("SELECT count(*) FROM observation_batch WHERE attempt_key = ?").param(attemptKey)
            .query(Integer.class).single();
    }

    private List<String> keyAliasesOn(LocalDate kstDate) {
        return jdbcClient.sql("SELECT key_alias FROM daily_call_quota WHERE api_service = ? AND kst_date = ?")
            .params(CallQuota.BUS_LOCATION.apiService(), kstDate).query(String.class).list();
    }

    private int reservedCallsOn(LocalDate kstDate) {
        return jdbcClient.sql("""
            SELECT reserved_calls FROM daily_call_quota
            WHERE provider = ? AND api_service = ? AND kst_date = ?
            """).params(CallQuota.BUS_LOCATION.provider(), CallQuota.BUS_LOCATION.apiService(), kstDate)
            .query(Integer.class).single();
    }
}

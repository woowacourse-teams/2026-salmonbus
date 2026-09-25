package com.gustler.backend.processor.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.gustler.backend.observation.VehicleObservationsStored;
import com.gustler.backend.processor.FullSeatStreak;
import com.gustler.backend.processor.ObservedSeats;
import com.gustler.backend.processor.ObservedVehicle;
import com.gustler.backend.processor.PendingForecastBatch;
import com.gustler.backend.processor.PrecedingVehicle;
import com.gustler.backend.processor.SeatSlope;
import com.gustler.backend.processor.SeatUnknownReason;
import com.gustler.backend.processor.TrajectoryGap;
import com.gustler.backend.processor.TripQualityRepository;
import com.gustler.backend.processor.VehicleTrajectory;
import com.gustler.backend.support.ConfirmedTripFixture;
import com.gustler.backend.support.IntegrationTest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class JdbcVehicleTrajectoryRepositoryTest {

    private static final String SOURCE_ID = "GBIS";
    private static final String ROUTE_204000057 = "204000057";
    private static final String ROUTE_204000121 = "204000121";
    private static final String CONTENT_DIGEST = "0".repeat(64);
    private static final String NORMALIZATION_VERSION = "normalization-v1.0.0";
    private static final String STRATEGY_VERSION = "adaptive-kst-v1.0.1";

    private static final String REPORTED_UNKNOWN = "REPORTED_UNKNOWN";

    private static final String SUCCESS_ROWS = "SUCCESS_ROWS";
    private static final String SUCCESS_EMPTY = "SUCCESS_EMPTY";

    private static final String VEHICLE_204000206 = "204000206";
    private static final String VEHICLE_204003542 = "204003542";
    private static final String VEHICLE_204001188 = "204001188";

    private static final OffsetDateTime EARLIER_POLL = OffsetDateTime.parse("2026-08-19T11:14:04.911+09:00");
    private static final OffsetDateTime LATER_POLL = OffsetDateTime.parse("2026-08-19T11:14:19.911+09:00");

    private static final int RUNNING_STATE_DEPARTED = 2;
    private static final int CROWD_LEVEL_3 = 3;
    private static final int NO_SEAT_LEFT = 0;
    private static final int STOP_5 = 5;
    private static final int STOP_6 = 6;
    private static final int HIGHEST_STOP_ORDER = 10;
    private static final int ENOUGH_BATCHES = 10;

    /** 신선도 창을 안 보는 테스트가 쓰는 한계. 픽스처의 판이 전부 이보다 뒤다. */
    private static final Instant ANY_AGE = Instant.parse("2000-01-01T00:00:00Z");
    private static final long MISSING_BATCH_ID = 9_999_999L;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private JdbcVehicleTrajectoryRepository repository;

    private long routeVersionId;

    /** 시도 키는 판마다 달라야 한다. ux_batch_attempt 가 판본 안에서 그것을 막는다. */
    private int insertedBatchCount;

    @BeforeEach
    void 노선_판본과_경유_정류소를_먼저_저장한다() {
        insertedBatchCount = 0;
        routeVersionId = insertRouteVersion(ROUTE_204000057);
    }

    @Test
    void 한계보다_오래된_판은_안_준다() {
        // given
        insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);

        // when
        List<PendingForecastBatch> actual = repository.findBatchesAwaitingForecast(
            routeVersionId, LATER_POLL.toInstant(), ENOUGH_BATCHES);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 한계와_같은_시각의_판은_준다() {
        // given
        final long onTheEdge = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);

        // when
        List<PendingForecastBatch> actual = repository.findBatchesAwaitingForecast(
            routeVersionId, LATER_POLL.toInstant(), ENOUGH_BATCHES);

        // then
        assertThat(actual)
            .extracting(PendingForecastBatch::observationBatchId)
            .containsExactly(onTheEdge);
    }

    @Test
    void 창_밖으로_막_밀려난_판을_준다() {
        // given
        final long left = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);

        // when
        Optional<Instant> actual = repository.findOldestLeftBehindAt(
            routeVersionId, EARLIER_POLL.minusHours(1).toInstant(), LATER_POLL.toInstant());

        // then
        assertThat(actual).contains(readResponseReceivedAt(left));
    }

    /** 옮겨 넣은 관측이 이 자리다. 예보를 받을 일이 없어 완료 표시가 영영 안 찍힌다. */
    @Test
    void 거슬러_보는_폭보다_오래된_판은_안_준다() {
        // given
        insertBatch(routeVersionId, EARLIER_POLL.minusDays(1), SUCCESS_ROWS, null);

        // when
        Optional<Instant> actual = repository.findOldestLeftBehindAt(
            routeVersionId, EARLIER_POLL.minusHours(1).toInstant(), LATER_POLL.toInstant());

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 아직_창_안에_있는_판은_안_준다() {
        // given
        insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);

        // when
        Optional<Instant> actual = repository.findOldestLeftBehindAt(
            routeVersionId, EARLIER_POLL.minusHours(1).toInstant(), LATER_POLL.toInstant());

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 예보가_끝난_판은_밀려난_것으로_안_센다() {
        // given
        insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, LATER_POLL);

        // when
        Optional<Instant> actual = repository.findOldestLeftBehindAt(
            routeVersionId, EARLIER_POLL.minusHours(1).toInstant(), LATER_POLL.toInstant());

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 예보가_안_붙은_판을_오래된_것부터_준다() {
        // given
        final long later = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);
        final long earlier = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);

        // when
        List<PendingForecastBatch> actual =
            repository.findBatchesAwaitingForecast(routeVersionId, ANY_AGE, ENOUGH_BATCHES);

        // then
        assertThat(actual)
            .extracting(PendingForecastBatch::observationBatchId)
            .containsExactly(earlier, later);
    }

    @Test
    void 예보가_끝난_판은_대상에서_빠진다() {
        // given
        final long awaiting = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, LATER_POLL);

        // when
        List<PendingForecastBatch> actual =
            repository.findBatchesAwaitingForecast(routeVersionId, ANY_AGE, ENOUGH_BATCHES);

        // then
        assertThat(actual)
            .extracting(PendingForecastBatch::observationBatchId)
            .containsExactly(awaiting);
    }

    @Test
    void 차가_한_대도_없던_판도_예보_대상으로_준다() {
        // given
        final long empty = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_EMPTY, null);

        // when
        List<PendingForecastBatch> actual =
            repository.findBatchesAwaitingForecast(routeVersionId, ANY_AGE, ENOUGH_BATCHES);

        // then
        assertThat(actual)
            .extracting(PendingForecastBatch::observationBatchId)
            .containsExactly(empty);
    }

    @Test
    void 응답을_못_받은_판은_예보_대상이_아니다() {
        // given
        insertBatch(routeVersionId, null, SUCCESS_ROWS, null);

        // when
        List<PendingForecastBatch> actual =
            repository.findBatchesAwaitingForecast(routeVersionId, ANY_AGE, ENOUGH_BATCHES);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 다른_노선_판본의_판은_예보_대상에_섞이지_않는다() {
        // given
        insertBatch(insertRouteVersion(ROUTE_204000121), EARLIER_POLL, SUCCESS_ROWS, null);

        // when
        List<PendingForecastBatch> actual =
            repository.findBatchesAwaitingForecast(routeVersionId, ANY_AGE, ENOUGH_BATCHES);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 관측_시각은_수집_묶음의_응답_수신_시각으로_채운다() {
        // given
        final long batchId = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertObservation(batchId, VEHICLE_204000206, STOP_6, 43);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(batchId);

        // then
        assertThat(actual.getFirst().observation().observedAt()).isEqualTo(EARLIER_POLL.toInstant());
    }

    @Test
    void 한_차량의_궤적_재료_셋이_같이_나온다() {
        // given
        final long earlier = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertObservation(earlier, VEHICLE_204000206, STOP_5, 43);
        insertObservation(earlier, VEHICLE_204003542, STOP_6, NO_SEAT_LEFT);
        final long later = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);
        final long observationId = insertObservation(later, VEHICLE_204000206, STOP_6, 40);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(later);

        // then
        assertThat(actual).containsExactly(new VehicleTrajectory(
            observationId,
            new ObservedVehicle(
                VEHICLE_204000206, routeVersionId, STOP_6, LATER_POLL.toInstant(), 40, CROWD_LEVEL_3),
            new ObservedSeats.Known(40),
            new SeatSlope.Known(-3),
            new PrecedingVehicle.Known(VEHICLE_204003542, NO_SEAT_LEFT, EARLIER_POLL.toInstant()),
            new FullSeatStreak.SeenToEnd(0),
            43));
    }

    @Test
    void 줄곧_만석이던_차량은_궤적이_안_나온다() {
        // given 한 번도 빈자리를 안 보여 줘서 정원을 모르는 차량이다
        final long earlier = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertObservation(earlier, VEHICLE_204000206, STOP_5, NO_SEAT_LEFT);
        final long later = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);
        insertObservation(later, VEHICLE_204000206, STOP_6, NO_SEAT_LEFT);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(later);

        // then 1석으로 꾸며 내면 값은 나오는데 뜻이 없는 예보가 나간다
        assertThat(actual).isEmpty();
    }

    @Test
    void 잔여석을_한_번도_안_보여_준_차량은_궤적이_안_나온다() {
        // given
        final long batchId = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertObservationWithoutSeats(batchId, VEHICLE_204000206, STOP_6, REPORTED_UNKNOWN);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(batchId);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 최대_잔여석은_궤적을_잇는_30분_창_밖의_관측에서도_나온다() {
        // given 궤적은 30분까지만 거슬러 보는데 정원은 그 창 밖에서도 온다
        final long longAgoBatchId = insertBatch(
            routeVersionId, LATER_POLL.minusMinutes(40), SUCCESS_ROWS, null);
        insertObservation(longAgoBatchId, VEHICLE_204000206, STOP_5, 44);
        final long later = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);
        insertObservation(later, VEHICLE_204000206, STOP_6, 12);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(later);

        // then
        assertThat(actual.getFirst().maximumSeatsEverObserved()).isEqualTo(44);
    }

    @Test
    void 최대_잔여석은_예보를_내는_시각까지의_관측에서만_나온다() {
        // given 나중에 더 큰 잔여석이 들어와도 예전 예보를 다시 계산하면 같은 값이 나와야 한다
        final long targetBatchId = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertObservation(targetBatchId, VEHICLE_204000206, STOP_5, 12);
        final long laterBatchId = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);
        insertObservation(laterBatchId, VEHICLE_204000206, STOP_6, 44);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(targetBatchId);

        // then
        assertThat(actual.getFirst().maximumSeatsEverObserved()).isEqualTo(12);
    }

    @Test
    void 같은_시각의_뒤_batch_는_최대_잔여석에_안_들어간다() {
        // given 시각이 같고 id 만 큰 batch 가 뒤에 하나 더 있다
        final long targetBatchId = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertObservation(targetBatchId, VEHICLE_204000206, STOP_5, 12);
        final long sameTimeLaterBatchId = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertObservation(sameTimeLaterBatchId, VEHICLE_204000206, STOP_6, 44);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(targetBatchId);

        // then 시각만으로 자르면 44가 새어 들어온다
        assertThat(actual.getFirst().maximumSeatsEverObserved()).isEqualTo(12);
    }

    @Test
    void 여러_차량의_최대값은_각각_구하고_다른_판본의_값은_제외한다() {
        // given
        final long earlier = insertBatch(routeVersionId, EARLIER_POLL.minusHours(1), SUCCESS_ROWS, null);
        insertObservation(earlier, VEHICLE_204000206, STOP_5, 44);
        insertObservation(earlier, VEHICLE_204003542, STOP_6, 52);

        final long otherVersion = insertRouteVersion(ROUTE_204000121);
        final long otherBatch = insertBatch(otherVersion, EARLIER_POLL, SUCCESS_ROWS, null);
        insertObservation(otherVersion, otherBatch, VEHICLE_204000206, STOP_5, 68);

        final long target = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);
        insertObservation(target, VEHICLE_204000206, STOP_5, 12);
        insertObservation(target, VEHICLE_204003542, STOP_6, 20);
        insertObservation(target, VEHICLE_204001188, STOP_5, NO_SEAT_LEFT);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(target);

        // then
        assertThat(actual)
            .extracting(trajectory -> tuple(
                trajectory.observation().vehicleId(), trajectory.maximumSeatsEverObserved()))
            .containsExactlyInAnyOrder(
                tuple(VEHICLE_204000206, 44),
                tuple(VEHICLE_204003542, 52));
    }

    @Test
    void 같은_최대값이_여러_관측에_있어도_차량은_한_번만_반환한다() {
        // given
        final long earlier = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertObservation(earlier, VEHICLE_204000206, STOP_5, 44);

        final long target = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);
        insertObservation(target, VEHICLE_204000206, STOP_6, 44);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(target);

        // then
        assertThat(actual).hasSize(1);
        assertThat(actual.getFirst().maximumSeatsEverObserved()).isEqualTo(44);
    }

    @Test
    void 잔여석을_모르면_왜_모르는지까지_같이_준다() {
        // given 지금은 잔여석을 모르지만 예전에 빈자리를 보여 줘서 정원은 아는 차량이다
        final long earlier = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertObservation(earlier, VEHICLE_204000206, STOP_5, 44);
        final long later = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);
        insertObservationWithoutSeats(later, VEHICLE_204000206, STOP_6, REPORTED_UNKNOWN);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(later);

        // then
        assertThat(actual.getFirst().seats())
            .isEqualTo(new ObservedSeats.Unknown(SeatUnknownReason.REPORTED_UNKNOWN));
    }

    @Test
    void 차가_없던_판이_사이에_있으면_기울기가_판_결손으로_나온다() {
        // given
        final long earlier = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertObservation(earlier, VEHICLE_204000206, STOP_5, 43);
        insertBatch(routeVersionId, EARLIER_POLL.plusSeconds(5), SUCCESS_EMPTY, null);
        final long later = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);
        insertObservation(later, VEHICLE_204000206, STOP_6, 40);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(later);

        // then
        assertThat(actual.getFirst().seatSlope())
            .isEqualTo(new SeatSlope.Unknown(TrajectoryGap.OBSERVATION_BATCH_MISSING));
    }

    @Test
    void 다른_노선_판본의_차량은_앞차로_잡히지_않는다() {
        // given
        final long otherVersionId = insertRouteVersion(ROUTE_204000121);
        final long otherBatch = insertBatch(otherVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertObservation(otherVersionId, otherBatch, VEHICLE_204001188, STOP_6, NO_SEAT_LEFT);
        final long batchId = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);
        insertObservation(batchId, VEHICLE_204000206, STOP_6, 40);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(batchId);

        // then
        assertThat(actual.getFirst().precedingVehicle())
            .isEqualTo(new PrecedingVehicle.Unknown(TrajectoryGap.NO_VEHICLE_AHEAD));
    }

    @Test
    void 차량_아이디가_없는_관측은_궤적에서_뺀다() {
        // given
        final long batchId = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertObservation(batchId, null, STOP_6, 43);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(batchId);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 응답을_못_받은_판은_궤적을_못_낸다() {
        // given
        final long batchId = insertBatch(routeVersionId, null, SUCCESS_ROWS, null);
        insertObservation(batchId, VEHICLE_204000206, STOP_6, 43);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(batchId);

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 같은_시각의_판이_둘이어도_물어본_판의_궤적을_준다() {
        // given
        final long asked = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);
        insertObservation(asked, VEHICLE_204000206, STOP_6, 40);
        final long sameMoment = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);
        insertObservation(sameMoment, VEHICLE_204003542, STOP_5, 12);

        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(asked);

        // then
        assertThat(actual)
            .extracting(trajectory -> trajectory.observation().vehicleId())
            .containsExactly(VEHICLE_204000206);
    }

    @Test
    void 없는_판을_물어보면_빈_목록을_준다() {
        // when
        List<VehicleTrajectory> actual = repository.readTrajectories(MISSING_BATCH_ID);

        // then
        assertThat(actual).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 같은_편도에서_82석을_발견하면_앞선_관측도_예측에서_제외하고_다른_차량은_유지한다(boolean configured) {
        // given
        if (configured) {
            qualityPolicy();
        }
        long first = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        long badStart = insertObservation(first, VEHICLE_204000206, 1, 44);
        insertObservation(first, VEHICLE_204003542, 1, 44);
        long later = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);
        insertObservation(later, VEHICLE_204000206, 2, 82);
        insertObservation(later, VEHICLE_204003542, 2, 43);
        clearSyntheticMemberships();
        var quality = new TripQualityRepository(jdbcClient);
        signal(quality, first);
        assertThat(repository.readTrajectories(first)).hasSize(2);

        // when
        signal(quality, later);

        // then
        assertThat(repository.readTrajectories(first)).extracting(VehicleTrajectory::vehicleObservationId)
            .doesNotContain(badStart).hasSize(1);
        assertThat(repository.readTrajectories(later)).hasSize(1);
        assertThat(jdbcClient.sql("SELECT remaining_seats FROM vehicle_observation WHERE id = ?")
            .param(badStart).query(Integer.class).single()).isEqualTo(44);
        assertThat(jdbcClient.sql("SELECT quality_revision FROM route WHERE id=(SELECT route_id FROM route_version WHERE id=?)").param(routeVersionId).query(Long.class).single()).isEqualTo(2);

        // when: 같은 관측 묶음을 다시 판정한다.
        signal(quality, later);

        // then: 계산 자료 버전을 중복 증가시키지 않는다.
        assertThat(jdbcClient.sql("SELECT quality_revision FROM route WHERE id=(SELECT route_id FROM route_version WHERE id=?)").param(routeVersionId).query(Long.class).single()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 잔여석이_82석인_편도_이후_회차지에서_44석으로_출발하면_최대_잔여석은_44다(boolean configured) {
        // given
        if (configured) {
            qualityPolicy();
        }
        jdbcClient.sql("UPDATE route_version SET turn_sequence = 5 WHERE id = ?").param(routeVersionId).update();
        long first = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertObservation(first, VEHICLE_204000206, 1, 82);
        long next = insertBatch(routeVersionId, LATER_POLL, SUCCESS_ROWS, null);
        insertObservation(next, VEHICLE_204000206, 5, 44);
        clearSyntheticMemberships();
        var quality = new TripQualityRepository(jdbcClient);

        // when
        signal(quality, first);
        signal(quality, next);
        quality.investigateLocked(routeVersionId, VEHICLE_204000206);
        quality.investigateLocked(routeVersionId, VEHICLE_204000206);

        // then
        assertThat(repository.readTrajectories(first)).isEmpty();
        assertThat(repository.readTrajectories(next)).singleElement()
            .extracting(VehicleTrajectory::maximumSeatsEverObserved).isEqualTo(44);
    }

    @Test
    void 정상_차량은_관측_간격이_길어도_사전_편도_판정으로_제외하지_않는다() {
        // given
        qualityPolicy();
        long first = insertBatch(routeVersionId, EARLIER_POLL, SUCCESS_ROWS, null);
        insertObservation(first, VEHICLE_204000206, 1, 44);
        long gap = insertBatch(routeVersionId, EARLIER_POLL.plusMinutes(10), SUCCESS_ROWS, null);
        insertObservation(gap, VEHICLE_204000206, 3, 40);
        clearSyntheticMemberships();
        var quality = new TripQualityRepository(jdbcClient);

        // when
        signal(quality, first);
        signal(quality, gap);

        // then
        assertThat(repository.readTrajectories(gap)).hasSize(1);
    }

    private void signal(TripQualityRepository quality, long batch) {
        var at = jdbcClient.sql("SELECT response_received_at FROM observation_batch WHERE id = ?")
            .param(batch).query(OffsetDateTime.class).single();
        var rows = jdbcClient.sql("SELECT id, vehicle_id, remaining_seats FROM vehicle_observation WHERE observation_batch_id = ?")
            .param(batch).query((rs, n) -> new VehicleObservationsStored.Row(rs.getLong(1), rs.getString(2), rs.getObject(3, Integer.class))).list();
        quality.observationsStored(new VehicleObservationsStored(batch, routeVersionId, at.toInstant(), rows));
    }

    private void qualityPolicy() {
        // 합성 입력의 기준이며 운영 설정값이 아니다.
        jdbcClient.sql("UPDATE route_version SET maximum_observation_gap_seconds = 60, observation_gap_evidence = 'synthetic test' WHERE id = ?")
            .param(routeVersionId).update();
    }

    private void clearSyntheticMemberships() {
        jdbcClient.sql("UPDATE vehicle_observation SET vehicle_trip_key = NULL").update();
        jdbcClient.sql("DELETE FROM vehicle_one_way_trip").update();
    }

    private long insertRouteVersion(
        String publicRouteId
    ) {
        final long routeId = jdbcClient.sql("""
                INSERT INTO route (
                    public_route_id, source_id, source_route_id,
                    display_name, start_stop_name, end_stop_name
                ) VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(publicRouteId, SOURCE_ID, publicRouteId, "3330", "범계역", "강남역")
            .query(Long.class)
            .single();

        final long versionId = jdbcClient.sql("""
                INSERT INTO route_version (route_id, content_digest, valid_from)
                VALUES (?, ?, ?)
                RETURNING id
                """)
            .params(routeId, CONTENT_DIGEST, EARLIER_POLL)
            .query(Long.class)
            .single();

        for (int stopOrder = 1; stopOrder <= HIGHEST_STOP_ORDER; stopOrder++) {
            jdbcClient.sql("""
                    INSERT INTO route_stop (
                        route_version_id, stop_order, stop_id, name, direction, boarding_allowed
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """)
                .params(versionId, stopOrder, stopIdOf(stopOrder), "범계역", "UP", true)
                .update();
        }
        return versionId;
    }

    private long insertBatch(
        final long versionId,
        OffsetDateTime responseReceivedAt,
        String outcome,
        OffsetDateTime forecastCompletedAt
    ) {
        return jdbcClient.sql("""
                INSERT INTO observation_batch (
                    route_version_id, scheduled_at, attempt_number, attempt_key, requested_at,
                    response_received_at, forecast_completed_at, outcome, normalization_version,
                    collection_strategy_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(
                versionId, EARLIER_POLL, 1, "%s-%d".formatted(ROUTE_204000057, insertedBatchCount++),
                EARLIER_POLL, responseReceivedAt, forecastCompletedAt, outcome, NORMALIZATION_VERSION,
                STRATEGY_VERSION
            )
            .query(Long.class)
            .single();
    }

    private long insertObservation(
        final long batchId,
        String vehicleId,
        final int stopOrder,
        Integer remainingSeats
    ) {
        return insertObservation(routeVersionId, batchId, vehicleId, stopOrder, remainingSeats, null);
    }

    private void insertObservationWithoutSeats(
        final long batchId,
        String vehicleId,
        final int stopOrder,
        String seatUnknownReason
    ) {
        insertObservation(routeVersionId, batchId, vehicleId, stopOrder, null, seatUnknownReason);
    }

    private long insertObservation(
        final long versionId,
        final long batchId,
        String vehicleId,
        final int stopOrder,
        Integer remainingSeats
    ) {
        return insertObservation(versionId, batchId, vehicleId, stopOrder, remainingSeats, null);
    }

    /** 통과 순번은 지나감(2)이라 상류 순번과 같다. 관측 시각 열은 SAL-84 가 지웠다. */
    private long insertObservation(
        final long versionId,
        final long batchId,
        String vehicleId,
        final int stopOrder,
        Integer remainingSeats,
        String seatUnknownReason
    ) {
        return ConfirmedTripFixture.include(jdbcClient, jdbcClient.sql("""
                INSERT INTO vehicle_observation (
                    observation_batch_id, route_version_id, source_row_number,
                    vehicle_id, stop_order, stop_id, passed_stop_order,
                    running_state, remaining_seats, seat_unknown_reason, crowd_level
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(
                batchId, versionId, nextRowNumber(batchId),
                vehicleId, stopOrder, stopIdOf(stopOrder), stopOrder,
                RUNNING_STATE_DEPARTED, remainingSeats, seatUnknownReason, CROWD_LEVEL_3
            )
            .query(Long.class)
            .single());
    }

    private int nextRowNumber(
        final long batchId
    ) {
        return jdbcClient.sql("SELECT count(*) FROM vehicle_observation WHERE observation_batch_id = ?")
            .param(batchId)
            .query(Integer.class)
            .single();
    }

    private Instant readResponseReceivedAt(
        final long observationBatchId
    ) {
        return jdbcClient.sql("SELECT response_received_at FROM observation_batch WHERE id = ?")
            .param(observationBatchId)
            .query(OffsetDateTime.class)
            .single()
            .toInstant();
    }

    private static String stopIdOf(
        final int stopOrder
    ) {
        return "20500021%d".formatted(stopOrder);
    }
}

package com.gustler.backend.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.collector.dto.BusLocationResponse.BusLocation;
import com.gustler.backend.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class ObservationLoaderTest {

    private static final String SOURCE_ID = "GBIS";
    private static final String ROUTE_204000057 = "204000057";

    private static final String STOP_205000217 = "205000217";
    private static final String STOP_277103149 = "277103149";
    private static final String STOP_208000069 = "208000069";
    private static final int TURN_SEQUENCE = 2;

    private static final String VEHICLE_204000206 = "204000206";
    private static final String VEHICLE_204003542 = "204003542";
    private static final String VEHICLE_204000139 = "204000139";
    private static final String PLATE_NUMBER = "경기70아0001";

    private static final int ROUTE_TYPE_11 = 11;
    private static final int TAGLESS_1 = 1;
    private static final int NORMAL_BUS = 0;
    private static final int SEATS_43 = 43;
    private static final int CROWD_LEVEL_3 = 3;
    private static final int SEATS_REPORTED_UNKNOWN = -1;

    private static final int RUNNING_STATE_MOVING = 0;
    private static final int RUNNING_STATE_ARRIVED = 1;
    private static final int RUNNING_STATE_DEPARTED = 2;
    private static final int RUNNING_STATE_NEVER_OBSERVED = 3;

    private static final OffsetDateTime SCHEDULED_AT = OffsetDateTime.parse("2026-08-19T11:14:00+09:00");
    private static final OffsetDateTime REQUESTED_AT = OffsetDateTime.parse("2026-08-19T11:14:04.800+09:00");
    private static final OffsetDateTime RESPONSE_RECEIVED_AT = OffsetDateTime.parse("2026-08-19T11:14:04.911+09:00");
    private static final String ATTEMPT_KEY = "204000057-2026-08-19T11:14";

    private static final RouteTimetable TIMETABLE_3330 =
        new RouteTimetable("05:00", "22:35", "05:00", "23:55");

    @Autowired
    private ObservationLoader loader;

    @Autowired
    private RouteVersionLoader routeVersionLoader;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private EntityManager entityManager;

    private long routeVersionId;

    @BeforeEach
    void 노선과_판본과_정류소를_먼저_적재한다() {
        final long routeId = insertRoute();
        routeVersionId = routeVersionLoader.load(routeId, threeStops(), TIMETABLE_3330, SCHEDULED_AT);
    }

    @Test
    void 차량이_있는_판은_수집_묶음_한_행을_남긴다() {
        // when
        final long batchId = loader.load(attempt(), List.of(
            busAt(VEHICLE_204000206, 1, STOP_205000217, RUNNING_STATE_DEPARTED)));

        // then
        assertThat(batchCountOf(batchId)).isEqualTo(1);
    }

    @Test
    void 차량이_있는_판은_본_차량만큼_관측을_쌓는다() {
        // when
        final long batchId = loader.load(attempt(), List.of(
            busAt(VEHICLE_204000206, 1, STOP_205000217, RUNNING_STATE_DEPARTED),
            busAt(VEHICLE_204003542, 2, STOP_277103149, RUNNING_STATE_MOVING),
            busAt(VEHICLE_204000139, 3, STOP_208000069, RUNNING_STATE_ARRIVED)));

        // then
        assertThat(observationCountOf(batchId)).isEqualTo(3);
    }

    @Test
    void 차량이_한_대도_없는_판도_묶음_한_행이_남는다() {
        // when
        final long batchId = loader.load(attempt(), List.of());

        // then
        assertThat(batchCountOf(batchId)).isEqualTo(1);
    }

    @Test
    void 차량이_한_대도_없는_판의_결말은_SUCCESS_EMPTY다() {
        // when
        final long batchId = loader.load(attempt(), List.of());

        // then
        assertThat(outcomeOf(batchId)).isEqualTo("SUCCESS_EMPTY");
    }

    @Test
    void 차량이_있는_판의_결말은_SUCCESS_ROWS다() {
        // when
        final long batchId = loader.load(attempt(), List.of(
            busAt(VEHICLE_204000206, 1, STOP_205000217, RUNNING_STATE_DEPARTED)));

        // then
        assertThat(outcomeOf(batchId)).isEqualTo("SUCCESS_ROWS");
    }

    @Test
    void 뜻을_모르는_운행_상태의_차량은_빼고_나머지가_저장된다() {
        // when
        final long batchId = loader.load(attempt(), List.of(
            busAt(VEHICLE_204000206, 1, STOP_205000217, RUNNING_STATE_DEPARTED),
            busAt(VEHICLE_204003542, 2, STOP_277103149, RUNNING_STATE_NEVER_OBSERVED),
            busAt(VEHICLE_204000139, 3, STOP_208000069, RUNNING_STATE_MOVING)));

        // then
        assertThat(vehicleIdsOf(batchId)).containsExactly(VEHICLE_204000206, VEHICLE_204000139);
    }

    @Test
    void 뺀_차량이_있어도_상류가_준_행_수는_그대로_남는다() {
        // when
        final long batchId = loader.load(attempt(), List.of(
            busAt(VEHICLE_204000206, 1, STOP_205000217, RUNNING_STATE_DEPARTED),
            busAt(VEHICLE_204003542, 2, STOP_277103149, RUNNING_STATE_NEVER_OBSERVED)));

        // then
        assertThat(providerRowsOf(batchId)).isEqualTo(2);
    }

    @Test
    void 뺀_차량_수가_묶음에_남는다() {
        // when
        final long batchId = loader.load(attempt(), List.of(
            busAt(VEHICLE_204000206, 1, STOP_205000217, RUNNING_STATE_DEPARTED),
            busAt(VEHICLE_204003542, 2, STOP_277103149, RUNNING_STATE_NEVER_OBSERVED)));

        // then
        assertThat(excludedRowsOf(batchId)).isEqualTo(1);
    }

    @Test
    void 차량을_전부_뺀_판의_결말도_SUCCESS_ROWS다() {
        // when
        final long batchId = loader.load(attempt(), List.of(
            busAt(VEHICLE_204000206, 1, STOP_205000217, RUNNING_STATE_NEVER_OBSERVED)));

        // then
        assertThat(outcomeOf(batchId)).isEqualTo("SUCCESS_ROWS");
    }

    @Test
    void 정류소를_지난_차량의_통과_순번은_그_정류소_순번이다() {
        // when
        final long batchId = loader.load(attempt(), List.of(
            busAt(VEHICLE_204000206, 2, STOP_277103149, RUNNING_STATE_DEPARTED)));

        // then
        assertThat(passedStopOrderOf(batchId)).isEqualTo(2);
    }

    @Test
    void 도착_중인_차량의_통과_순번은_직전_정류소_순번이다() {
        // when
        final long batchId = loader.load(attempt(), List.of(
            busAt(VEHICLE_204000206, 2, STOP_277103149, RUNNING_STATE_ARRIVED)));

        // then
        assertThat(passedStopOrderOf(batchId)).isEqualTo(1);
    }

    @Test
    void 첫_정류소에_도착_중인_차량의_통과_순번은_0이다() {
        // when
        final long batchId = loader.load(attempt(), List.of(
            busAt(VEHICLE_204000206, 1, STOP_205000217, RUNNING_STATE_ARRIVED)));

        // then
        assertThat(passedStopOrderOf(batchId)).isZero();
    }

    @Test
    void 잔여석을_아는_차량은_좌석_수만_저장된다() {
        // when
        final long batchId = loader.load(attempt(), List.of(
            busWithSeats(SEATS_43)));

        // then
        assertThat(storedSeatsOf(batchId)).isEqualTo(new StoredSeats(SEATS_43, null));
    }

    @Test
    void 잔여석을_모른다고_알려준_차량은_사유만_저장된다() {
        // when
        final long batchId = loader.load(attempt(), List.of(
            busWithSeats(SEATS_REPORTED_UNKNOWN)));

        // then
        assertThat(storedSeatsOf(batchId)).isEqualTo(new StoredSeats(null, "REPORTED_UNKNOWN"));
    }

    @Test
    void 잔여석을_안_알려준_차량은_사유만_저장된다() {
        // when
        final long batchId = loader.load(attempt(), List.of(
            busWithSeats(null)));

        // then
        assertThat(storedSeatsOf(batchId)).isEqualTo(new StoredSeats(null, "NOT_REPORTED"));
    }

    @Test
    void 관측은_상류_응답에서_몇_번째_줄이었는지를_그대로_쌓는다() {
        // when
        final long batchId = loader.load(attempt(), List.of(
            busAt(VEHICLE_204000206, 1, STOP_205000217, RUNNING_STATE_NEVER_OBSERVED),
            busAt(VEHICLE_204003542, 2, STOP_277103149, RUNNING_STATE_MOVING)));

        // then
        assertThat(sourceRowNumbersOf(batchId)).containsExactly(1);
    }

    @Test
    void 정규화_판이_묶음에_남는다() {
        // when
        final long batchId = loader.load(attempt(), List.of());

        // then
        assertThat(normalizationVersionOf(batchId))
            .isEqualTo(ObservationBatch.CURRENT_NORMALIZATION_VERSION);
    }

    private record StoredSeats(
        Integer remainingSeats,
        String seatUnknownReason
    ) {
    }

    private ObservationAttempt attempt() {
        return new ObservationAttempt(
            routeVersionId, SCHEDULED_AT, 1, ATTEMPT_KEY, REQUESTED_AT, RESPONSE_RECEIVED_AT);
    }

    private RouteStops threeStops() {
        return RouteStops.from(TURN_SEQUENCE, List.of(
            new UpstreamRouteStop(1, STOP_205000217, "범계역"),
            new UpstreamRouteStop(2, STOP_277103149, "안양대교(경유)"),
            new UpstreamRouteStop(3, STOP_208000069, "안양역")));
    }

    private static BusLocation busAt(
        String vehicleId,
        final int stopSequence,
        String stopId,
        final int runningState
    ) {
        return bus(vehicleId, stopSequence, stopId, runningState, SEATS_43);
    }

    private static BusLocation busWithSeats(
        Integer remainingSeatCount
    ) {
        return bus(VEHICLE_204000206, 1, STOP_205000217, RUNNING_STATE_DEPARTED, remainingSeatCount);
    }

    private static BusLocation bus(
        String vehicleId,
        final int stopSequence,
        String stopId,
        final int runningState,
        Integer remainingSeatCount
    ) {
        return new BusLocation(
            PLATE_NUMBER,
            vehicleId,
            NORMAL_BUS,
            ROUTE_204000057,
            ROUTE_TYPE_11,
            stopId,
            stopSequence,
            runningState,
            remainingSeatCount,
            CROWD_LEVEL_3,
            TAGLESS_1);
    }

    private long insertRoute() {
        return jdbcClient.sql("""
                INSERT INTO route (
                    public_route_id, source_id, source_route_id,
                    display_name, start_stop_name, end_stop_name
                ) VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(ROUTE_204000057, SOURCE_ID, ROUTE_204000057, "3330", "범계역", "강남역")
            .query(Long.class)
            .single();
    }

    private int batchCountOf(
        final long batchId
    ) {
        return queryForInt("SELECT count(*) FROM observation_batch WHERE id = ?", batchId);
    }

    private int observationCountOf(
        final long batchId
    ) {
        return queryForInt(
            "SELECT count(*) FROM vehicle_observation WHERE observation_batch_id = ?", batchId);
    }

    private int providerRowsOf(
        final long batchId
    ) {
        return queryForInt("SELECT provider_rows FROM observation_batch WHERE id = ?", batchId);
    }

    private int excludedRowsOf(
        final long batchId
    ) {
        return queryForInt("SELECT excluded_rows FROM observation_batch WHERE id = ?", batchId);
    }

    private int passedStopOrderOf(
        final long batchId
    ) {
        return queryForInt(
            "SELECT passed_stop_order FROM vehicle_observation WHERE observation_batch_id = ?", batchId);
    }

    private String outcomeOf(
        final long batchId
    ) {
        entityManager.flush();
        return jdbcClient.sql("SELECT outcome FROM observation_batch WHERE id = ?")
            .param(batchId)
            .query(String.class)
            .single();
    }

    private String normalizationVersionOf(
        final long batchId
    ) {
        entityManager.flush();
        return jdbcClient.sql("SELECT normalization_version FROM observation_batch WHERE id = ?")
            .param(batchId)
            .query(String.class)
            .single();
    }

    private List<String> vehicleIdsOf(
        final long batchId
    ) {
        entityManager.flush();
        return jdbcClient.sql("""
                SELECT vehicle_id FROM vehicle_observation
                WHERE observation_batch_id = ?
                ORDER BY source_row_number
                """)
            .param(batchId)
            .query(String.class)
            .list();
    }

    private List<Integer> sourceRowNumbersOf(
        final long batchId
    ) {
        entityManager.flush();
        return jdbcClient.sql("""
                SELECT source_row_number FROM vehicle_observation
                WHERE observation_batch_id = ?
                ORDER BY source_row_number
                """)
            .param(batchId)
            .query(Integer.class)
            .list();
    }

    private StoredSeats storedSeatsOf(
        final long batchId
    ) {
        entityManager.flush();
        return jdbcClient.sql("""
                SELECT remaining_seats, seat_unknown_reason FROM vehicle_observation
                WHERE observation_batch_id = ?
                """)
            .param(batchId)
            .query((rs, rowNumber) -> new StoredSeats(
                rs.getObject("remaining_seats", Integer.class),
                rs.getString("seat_unknown_reason")))
            .single();
    }

    private int queryForInt(
        final String sql,
        final long batchId
    ) {
        entityManager.flush();
        return jdbcClient.sql(sql)
            .param(batchId)
            .query(Integer.class)
            .single();
    }
}

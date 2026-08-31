package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class VehicleTrajectoryAssemblerTest {

    private static final long ROUTE_VERSION_ID = 1L;
    /** 조립은 관측 행 번호를 안 본다. 차량 아이디로 잇는다. */
    private static final long ANY_OBSERVATION_ID = 0L;
    private static final Instant FIRST_RESPONSE_RECEIVED_AT = Instant.parse("2026-08-19T02:14:04.911Z");
    private static final int POLL_INTERVAL_SECONDS = 15;

    private static final String VEHICLE_204000206 = "204000206";
    private static final String VEHICLE_204003542 = "204003542";
    private static final String VEHICLE_204001188 = "204001188";

    private static final String TRIP_MORNING = "204000206-2026-08-19T07:10";
    private static final String TRIP_EVENING = "204000206-2026-08-19T18:40";

    private static final int NO_SEAT_LEFT = 0;
    private static final int STOP_3 = 3;
    private static final int STOP_4 = 4;
    private static final int STOP_5 = 5;
    private static final int STOP_6 = 6;
    private static final int STOP_8 = 8;

    @Test
    void 좌석이_43석에서_40석으로_줄면_기울기는_마이너스_3이다() {
        // given
        ObservationHistory history = history(
            batch(1, observed(VEHICLE_204000206, STOP_5, 43)),
            batch(2, observed(VEHICLE_204000206, STOP_6, 40)));

        // when
        SeatSlope actual = onlyTrajectoryOf(history).seatSlope();

        // then
        assertThat(actual).isEqualTo(new SeatSlope.Known(-3));
    }

    @Test
    void 이력에_판이_하나뿐이면_기울기를_앞_관측_없음으로_준다() {
        // given
        ObservationHistory history = history(
            batch(1, observed(VEHICLE_204000206, STOP_6, 40)));

        // when
        SeatSlope actual = onlyTrajectoryOf(history).seatSlope();

        // then
        assertThat(actual).isEqualTo(new SeatSlope.Unknown(TrajectoryGap.NO_EARLIER_OBSERVATION));
    }

    @Test
    void 직전_판에_그_차가_없으면_기울기를_판_결손으로_준다() {
        // given
        ObservationHistory history = history(
            batch(1, observed(VEHICLE_204000206, STOP_5, 43)),
            batch(2, observed(VEHICLE_204003542, STOP_5, 12)),
            batch(3, observed(VEHICLE_204000206, STOP_6, 40)));

        // when
        SeatSlope actual = trajectoryOf(history, VEHICLE_204000206).seatSlope();

        // then
        assertThat(actual).isEqualTo(new SeatSlope.Unknown(TrajectoryGap.OBSERVATION_BATCH_MISSING));
    }

    @Test
    void 차가_한_대도_없던_판이_사이에_있으면_기울기를_판_결손으로_준다() {
        // given
        ObservationHistory history = history(
            batch(1, observed(VEHICLE_204000206, STOP_5, 43)),
            batch(2),
            batch(3, observed(VEHICLE_204000206, STOP_6, 40)));

        // when
        SeatSlope actual = trajectoryOf(history, VEHICLE_204000206).seatSlope();

        // then
        assertThat(actual).isEqualTo(new SeatSlope.Unknown(TrajectoryGap.OBSERVATION_BATCH_MISSING));
    }

    @Test
    void 통과_순번이_6에서_8로_뛰면_기울기를_순번_건너뜀으로_준다() {
        // given
        ObservationHistory history = history(
            batch(1, observed(VEHICLE_204000206, STOP_6, 43)),
            batch(2, observed(VEHICLE_204000206, STOP_8, 40)));

        // when
        SeatSlope actual = onlyTrajectoryOf(history).seatSlope();

        // then
        assertThat(actual).isEqualTo(new SeatSlope.Unknown(TrajectoryGap.STOP_ORDER_JUMPED));
    }

    @Test
    void 통과_순번이_되돌아가면_기울기를_순번_역행으로_준다() {
        // given
        ObservationHistory history = history(
            batch(1, observed(VEHICLE_204000206, STOP_8, 43)),
            batch(2, observed(VEHICLE_204000206, STOP_3, 40)));

        // when
        SeatSlope actual = onlyTrajectoryOf(history).seatSlope();

        // then
        assertThat(actual).isEqualTo(new SeatSlope.Unknown(TrajectoryGap.STOP_ORDER_WENT_BACK));
    }

    @Test
    void 여정_키가_서로_다르면_기울기를_여정_경계로_준다() {
        // given
        ObservationHistory history = history(
            batch(1, observedInTrip(VEHICLE_204000206, STOP_5, 43, TRIP_MORNING)),
            batch(2, observedInTrip(VEHICLE_204000206, STOP_6, 40, TRIP_EVENING)));

        // when
        SeatSlope actual = onlyTrajectoryOf(history).seatSlope();

        // then
        assertThat(actual).isEqualTo(new SeatSlope.Unknown(TrajectoryGap.TRIP_KEY_CHANGED));
    }

    @Test
    void 여정_키가_같으면_기울기를_잇는다() {
        // given
        ObservationHistory history = history(
            batch(1, observedInTrip(VEHICLE_204000206, STOP_5, 43, TRIP_MORNING)),
            batch(2, observedInTrip(VEHICLE_204000206, STOP_6, 40, TRIP_MORNING)));

        // when
        SeatSlope actual = onlyTrajectoryOf(history).seatSlope();

        // then
        assertThat(actual).isEqualTo(new SeatSlope.Known(-3));
    }

    @Test
    void 좌석을_아는_관측끼리만_기울기를_잰다() {
        // given
        ObservationHistory history = history(
            batch(1, observed(VEHICLE_204000206, STOP_5, null)),
            batch(2, observed(VEHICLE_204000206, STOP_6, 40)));

        // when
        SeatSlope actual = onlyTrajectoryOf(history).seatSlope();

        // then
        assertThat(actual).isEqualTo(new SeatSlope.Unknown(TrajectoryGap.SEATS_UNKNOWN));
    }

    @Test
    void 같은_정류소를_앞서_지난_차의_잔여석을_앞차로_준다() {
        // given
        ObservationHistory history = history(
            batch(1, observed(VEHICLE_204003542, STOP_6, NO_SEAT_LEFT)),
            batch(2, observed(VEHICLE_204000206, STOP_6, 40)));

        // when
        PrecedingVehicle actual = trajectoryOf(history, VEHICLE_204000206).precedingVehicle();

        // then
        assertThat(actual).isEqualTo(new PrecedingVehicle.Known(
            VEHICLE_204003542, NO_SEAT_LEFT, responseReceivedAt(1)));
    }

    @Test
    void 앞차가_여럿이면_가장_최근에_지난_차를_앞차로_준다() {
        // given
        ObservationHistory history = history(
            batch(1, observed(VEHICLE_204003542, STOP_6, 12)),
            batch(2, observed(VEHICLE_204001188, STOP_6, 30)),
            batch(3, observed(VEHICLE_204000206, STOP_6, 40)));

        // when
        PrecedingVehicle actual = trajectoryOf(history, VEHICLE_204000206).precedingVehicle();

        // then
        assertThat(actual).isEqualTo(new PrecedingVehicle.Known(
            VEHICLE_204001188, 30, responseReceivedAt(2)));
    }

    @Test
    void 같은_판에_같은_순번으로_들어온_두_차는_서로를_앞차로_지목하지_않는다() {
        // given
        ObservationHistory history = history(
            batch(1, observed(VEHICLE_204000206, STOP_6, 40), observed(VEHICLE_204003542, STOP_6, 12)),
            batch(2, observed(VEHICLE_204000206, STOP_6, 38), observed(VEHICLE_204003542, STOP_6, 10)));

        // when
        List<PrecedingVehicle> actual = List.of(
            trajectoryOf(history, VEHICLE_204000206).precedingVehicle(),
            trajectoryOf(history, VEHICLE_204003542).precedingVehicle());

        // then
        assertThat(actual).containsExactly(
            new PrecedingVehicle.Unknown(TrajectoryGap.ARRIVAL_ORDER_UNKNOWN),
            new PrecedingVehicle.Unknown(TrajectoryGap.ARRIVAL_ORDER_UNKNOWN));
    }

    @Test
    void 앞서_그_정류소를_지난_차가_없으면_앞차를_없음으로_준다() {
        // given
        ObservationHistory history = history(
            batch(1, observed(VEHICLE_204003542, STOP_3, 12)),
            batch(2, observed(VEHICLE_204000206, STOP_6, 40)));

        // when
        PrecedingVehicle actual = trajectoryOf(history, VEHICLE_204000206).precedingVehicle();

        // then
        assertThat(actual).isEqualTo(new PrecedingVehicle.Unknown(TrajectoryGap.NO_VEHICLE_AHEAD));
    }

    @Test
    void 만석이_3정류장째_이어지면_연속_만석은_3이다() {
        // given
        ObservationHistory history = history(
            batch(1, observed(VEHICLE_204000206, STOP_3, 5)),
            batch(2, observed(VEHICLE_204000206, STOP_4, NO_SEAT_LEFT)),
            batch(3, observed(VEHICLE_204000206, STOP_5, NO_SEAT_LEFT)),
            batch(4, observed(VEHICLE_204000206, STOP_6, NO_SEAT_LEFT)));

        // when
        FullSeatStreak actual = onlyTrajectoryOf(history).fullSeatStreak();

        // then
        assertThat(actual).isEqualTo(new FullSeatStreak.SeenToEnd(3));
    }

    @Test
    void 여유석이_남은_차의_연속_만석은_0이다() {
        // given
        ObservationHistory history = history(
            batch(1, observed(VEHICLE_204000206, STOP_5, NO_SEAT_LEFT)),
            batch(2, observed(VEHICLE_204000206, STOP_6, 3)));

        // when
        FullSeatStreak actual = onlyTrajectoryOf(history).fullSeatStreak();

        // then
        assertThat(actual).isEqualTo(new FullSeatStreak.SeenToEnd(0));
    }

    @Test
    void 이력이_끊긴_자리에서_멈춘_연속_만석은_최소값으로_준다() {
        // given
        ObservationHistory history = history(
            batch(1, observed(VEHICLE_204000206, STOP_5, NO_SEAT_LEFT)),
            batch(2, observed(VEHICLE_204000206, STOP_6, NO_SEAT_LEFT)));

        // when
        FullSeatStreak actual = onlyTrajectoryOf(history).fullSeatStreak();

        // then
        assertThat(actual).isEqualTo(
            new FullSeatStreak.CutByGap(2, TrajectoryGap.NO_EARLIER_OBSERVATION));
    }

    @Test
    void 좌석을_모르는_관측에서_연속_만석이_끊긴다() {
        // given
        ObservationHistory history = history(
            batch(1, observed(VEHICLE_204000206, STOP_5, null)),
            batch(2, observed(VEHICLE_204000206, STOP_6, NO_SEAT_LEFT)));

        // when
        FullSeatStreak actual = onlyTrajectoryOf(history).fullSeatStreak();

        // then
        assertThat(actual).isEqualTo(new FullSeatStreak.CutByGap(1, TrajectoryGap.SEATS_UNKNOWN));
    }

    @Test
    void 같은_정류소를_여러_판에서_봐도_연속_만석은_정류장_하나로_센다() {
        // given
        ObservationHistory history = history(
            batch(1, observed(VEHICLE_204000206, STOP_5, 5)),
            batch(2, observed(VEHICLE_204000206, STOP_6, NO_SEAT_LEFT)),
            batch(3, observed(VEHICLE_204000206, STOP_6, NO_SEAT_LEFT)),
            batch(4, observed(VEHICLE_204000206, STOP_6, NO_SEAT_LEFT)));

        // when
        FullSeatStreak actual = onlyTrajectoryOf(history).fullSeatStreak();

        // then
        assertThat(actual).isEqualTo(new FullSeatStreak.SeenToEnd(1));
    }

    @Test
    void 대상_판에_담긴_차량마다_궤적이_하나씩_나온다() {
        // given
        ObservationHistory history = history(
            batch(1, observed(VEHICLE_204000206, STOP_6, 40), observed(VEHICLE_204003542, STOP_3, 12)));

        // when
        List<VehicleTrajectory> actual = VehicleTrajectoryAssembler.assemble(history);

        // then
        assertThat(actual)
            .extracting(trajectory -> trajectory.observation().vehicleId())
            .containsExactly(VEHICLE_204000206, VEHICLE_204003542);
    }

    private static VehicleTrajectory onlyTrajectoryOf(
        ObservationHistory history
    ) {
        List<VehicleTrajectory> trajectories = VehicleTrajectoryAssembler.assemble(history);
        return trajectories.getFirst();
    }

    private static VehicleTrajectory trajectoryOf(
        ObservationHistory history,
        String vehicleId
    ) {
        return VehicleTrajectoryAssembler.assemble(history).stream()
            .filter(trajectory -> vehicleId.equals(trajectory.observation().vehicleId()))
            .findFirst()
            .orElseThrow();
    }

    private static ObservationHistory history(
        ObservedBatch... batches
    ) {
        return new ObservationHistory(Arrays.asList(batches));
    }

    /** 관측 시각은 판이 준다. 실물에서도 observation_batch.response_received_at 이 그 자리다. */
    private static ObservedBatch batch(
        final long observationBatchId,
        TrajectoryObservation... observations
    ) {
        Instant observedAt = responseReceivedAt(observationBatchId);
        return new ObservedBatch(
            observationBatchId,
            observedAt,
            Arrays.stream(observations)
                .map(observation -> observedAtStampedOn(observation, observedAt))
                .toList());
    }

    private static TrajectoryObservation observedAtStampedOn(
        TrajectoryObservation observation,
        Instant observedAt
    ) {
        ObservedVehicle vehicle = observation.vehicle();
        return new TrajectoryObservation(
            observation.vehicleObservationId(),
            new ObservedVehicle(
                vehicle.vehicleId(),
                vehicle.routeVersionId(),
                vehicle.passedStopOrder(),
                observedAt,
                vehicle.remainingSeats()),
            observation.vehicleTripKey(),
            observation.seats());
    }

    private static TrajectoryObservation observed(
        String vehicleId,
        final int passedStopOrder,
        Integer remainingSeats
    ) {
        return observedInTrip(vehicleId, passedStopOrder, remainingSeats, null);
    }

    private static TrajectoryObservation observedInTrip(
        String vehicleId,
        final int passedStopOrder,
        Integer remainingSeats,
        String vehicleTripKey
    ) {
        return new TrajectoryObservation(
            ANY_OBSERVATION_ID,
            new ObservedVehicle(
                vehicleId,
                ROUTE_VERSION_ID,
                passedStopOrder,
                Instant.EPOCH,
                remainingSeats),
            vehicleTripKey,
            seatsOf(remainingSeats));
    }

    /** 잔여석을 안 주는 픽스처는 상류가 값을 아예 안 준 것으로 둔다. */
    private static ObservedSeats seatsOf(
        Integer remainingSeats
    ) {
        if (remainingSeats == null) {
            return new ObservedSeats.Unknown(SeatUnknownReason.NOT_REPORTED);
        }
        return new ObservedSeats.Known(remainingSeats);
    }

    private static Instant responseReceivedAt(
        final long observationBatchId
    ) {
        return FIRST_RESPONSE_RECEIVED_AT.plusSeconds(observationBatchId * POLL_INTERVAL_SECONDS);
    }
}

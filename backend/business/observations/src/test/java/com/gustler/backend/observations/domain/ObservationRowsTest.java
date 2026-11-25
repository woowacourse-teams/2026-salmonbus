package com.gustler.backend.observations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ObservationRowsTest {
    @ParameterizedTest
    @MethodSource("seatQualityCases")
    void 저장할_수_있는_양수_좌석만_품질_판정_대상이_된다(
        Integer reportedSeats,
        Integer stopSequence,
        final boolean expected
    ) {
        // given
        VehicleObservation observation = new VehicleObservation(
                "vehicle-1",
                null,
                stopSequence,
                "205000217",
                2,
                RemainingSeats.from(reportedSeats),
                null,
                0,
                11,
                1
        );

        // when
        ObservationRows collected = ObservationRows.fromObservations(List.of(observation));

        // then
        assertThat(collected.hasPositiveSeats()).isEqualTo(expected);
    }

    private static Stream<Arguments> seatQualityCases() {
        return Stream.of(
                Arguments.of(null, 1, false),
                Arguments.of(-1, 1, false),
                Arguments.of(0, 1, false),
                Arguments.of(1, 1, true),
                Arguments.of(10, null, false)
        );
    }

    @ParameterizedTest
    @MethodSource("observationCases")
    void 관측_묶음이_저장과_제외_건수를_세고_빈_응답을_전체_제외와_구분한다(
        List<VehicleObservation> observations,
        final int providerRows,
        final int storableRows,
        final int excludedRows,
        final boolean fullyExcluded
    ) {
        // when
        ObservationRows collected = ObservationRows.fromObservations(observations);

        // then
        assertSoftly(checks -> {
            checks.assertThat(collected.providerRows()).isEqualTo(providerRows);
            checks.assertThat(collected.storableRowCount()).isEqualTo(storableRows);
            checks.assertThat(collected.excludedRowCount()).isEqualTo(excludedRows);
            checks.assertThat(collected.isFullyExcluded()).isEqualTo(fullyExcluded);
        });
    }

    private static Stream<Arguments> observationCases() {
        VehicleObservation knownStop = observation(1, "205000217");
        VehicleObservation unknownStop = observation(null, null);

        return Stream.of(
                Arguments.of(
                        List.of(),
                        0,
                        0,
                        0,
                        false
                ),
                Arguments.of(
                        List.of(knownStop),
                        1,
                        1,
                        0,
                        false
                ),
                Arguments.of(
                        List.of(unknownStop),
                        1,
                        0,
                        1,
                        true
                ),
                Arguments.of(
                        List.of(knownStop, unknownStop),
                        2,
                        1,
                        1,
                        false
                )
        );
    }

    private static VehicleObservation observation(Integer stopSequence, String stopId) {
        return new VehicleObservation(
                "vehicle-1",
                null,
                stopSequence,
                stopId,
                2,
                RemainingSeats.from(10),
                null,
                0,
                11,
                1
        );
    }
}

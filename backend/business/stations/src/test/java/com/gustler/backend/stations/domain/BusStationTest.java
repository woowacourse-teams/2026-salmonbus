package com.gustler.backend.stations.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BusStationTest {
    @Test
    void 미정차_지점은_정류장으로_만들지_않는다() {
        // when
        Optional<BusStation> station = BusStation.from(
            "277103149", "판교TG(경유)", 127.1, 37.4, "00000", "성남", "N"
        );

        // then
        assertThat(station).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(value = {"' 07478',N,07478,false", "00000,N,NULL,false", "NULL,Y,NULL,true"}, nullValues = "NULL")
    void 정류소번호를_정리하고_중앙차로_여부를_보존한다(
        String mobileNumber,
        String centerYn,
        String expectedNumber,
        Boolean expectedCenterLane
    ) {
        // when
        Optional<BusStation> station = BusStation.from(
            "206000683", "판교역남편", 127.1115, 37.39385, mobileNumber, "성남", centerYn
        );

        // then
        assertThat(station).isPresent();
        assertSoftly(softly -> {
            softly.assertThat(station.get().mobileNo()).isEqualTo(expectedNumber);
            softly.assertThat(station.get().centerLane()).isEqualTo(expectedCenterLane);
        });
    }

    @Test
    void 서울_정류장의_기존_지역_분류를_보존한다() {
        // when
        Optional<BusStation> station = BusStation.from(
            "121000262", "강남역8번출구", 127.0276, 37.4979, "22339", "서울", "N"
        );

        // then
        assertThat(station).isPresent();
        assertThat(station.get().outsideGyeonggi()).isTrue();
    }
}

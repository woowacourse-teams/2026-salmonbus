package com.gustler.backend.collector.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.collector.dto.BusLocationResponse.BusLocation;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import tools.jackson.databind.ObjectMapper;

@JsonTest
class BusLocationResponseTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 차량이_여러_대면_배열로_응답받고_리스트로_읽는다() {
        // given
        final InputStream twoVehiclesJson = fixture("location-two-vehicles.json");

        // when
        final BusLocationResponse actualResponse =
            objectMapper.readValue(twoVehiclesJson, BusLocationResponse.class);

        // then
        assertThat(actualResponse.response().header().resultCode()).isZero();
        assertThat(actualResponse.response().body().busLocations())
            .extracting(BusLocation::vehicleId)
            .hasSize(2);
    }

    @Test
    void 차량이_한_대면_객체로_응답받고_리스트로_읽는다() {
        // given
        final InputStream singleVehicleJson = fixture("location-single-vehicle.json");

        // when
        final BusLocationResponse actualResponse =
            objectMapper.readValue(singleVehicleJson, BusLocationResponse.class);

        // then
        assertThat(actualResponse.response().body().busLocations())
            .hasSize(1);
    }

    @Test
    void GBIS_필드명을_서버에서_이해하기_쉬운_변수명으로_매핑한다() {
        // given
        final InputStream twoVehiclesJson = fixture("location-two-vehicles.json");

        // when
        final BusLocationResponse actualResponse =
            objectMapper.readValue(twoVehiclesJson, BusLocationResponse.class);

        // then
        final BusLocation actualBus = actualResponse.response().body().busLocations().getFirst();
        assertThat(actualBus.plateNumber()).isEqualTo("경기70아0001");
        assertThat(actualBus.vehicleId()).isEqualTo("204000206");
        assertThat(actualBus.vehicleType()).isEqualTo(2);
        assertThat(actualBus.routeId()).isEqualTo("204000057");
        assertThat(actualBus.routeType()).isEqualTo(11);
        assertThat(actualBus.stopId()).isEqualTo("205000217");
        assertThat(actualBus.stopSequence()).isEqualTo(6);
        assertThat(actualBus.runningStatus()).isEqualTo(2);
        assertThat(actualBus.remainingSeatCount()).isEqualTo(67);
        assertThat(actualBus.crowdLevel()).isZero();
        assertThat(actualBus.taglessCode()).isEqualTo(1);
    }

    private InputStream fixture(
        final String name
    ) {
        return getClass().getClassLoader().getResourceAsStream("gbis/" + name);
    }
}

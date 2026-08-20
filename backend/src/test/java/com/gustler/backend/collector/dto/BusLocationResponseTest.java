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

    private InputStream fixture(
        final String name
    ) {
        return getClass().getClassLoader().getResourceAsStream("gbis/" + name);
    }
}

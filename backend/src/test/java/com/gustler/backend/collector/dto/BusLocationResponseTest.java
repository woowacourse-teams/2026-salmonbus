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
    void 운행_차량이_여러_대면_배열로_읽고_리스트로_파싱한다() {
        // given
        InputStream given = fixture("location-two-vehicles.json");

        // when
        BusLocationResponse actual = objectMapper.readValue(given, BusLocationResponse.class);

        // then
        assertThat(actual.response().header().resultCode()).isZero();
        assertThat(actual.response().body().busLocations())
            .extracting(BusLocation::vehicleId)
            .hasSize(2);
    }

    @Test
    void 운행_차량이_한_대면_객체로_읽고_객체로_파싱한다() {
        // given
        InputStream given = fixture("location-single-vehicle.json");

        // when
        BusLocationResponse actual = objectMapper.readValue(given, BusLocationResponse.class);

        // then
        assertThat(actual.response().body().busLocations())
            .hasSize(1);
    }

    private InputStream fixture(String name) {
        return getClass().getClassLoader().getResourceAsStream("gbis/" + name);
    }
}

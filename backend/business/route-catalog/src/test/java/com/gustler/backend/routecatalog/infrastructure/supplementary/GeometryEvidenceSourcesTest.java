package com.gustler.backend.routecatalog.infrastructure.supplementary;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.time.*;

class GeometryEvidenceSourcesTest {
    private final GbisWebGeometrySource web =
            new GbisWebGeometrySource(
                    new ObjectMapper(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    private String row(int sequence, int type, double x, double y) {
        double lon = Math.toRadians(x) * 6378137,
                lat = Math.log(Math.tan(Math.PI / 4 + Math.toRadians(y) / 2)) * 6378137;
        return "{\"seq\":"
                + sequence
                + ",\"type\":"
                + type
                + ",\"lon\":"
                + lon
                + ",\"lat\":"
                + lat
                + "}";
    }

    private byte[] document(String up) {
        return ("{\"success\":true,\"result\":{\"gg\":{\"upLine\":{\"count\":3,\"list\":["
                        + up
                        + "]},\"downLine\":{\"count\":2,\"list\":["
                        + row(1, 1, 127.01, 37.01)
                        + ","
                        + row(2, 1, 127, 37)
                        + "]}}}}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void 웹_좌표계를_변환하고_분류원본과_공식표시점열을_구분한다() {
        var read =
                web.parse(
                        document(
                                row(1, 1, 127, 37)
                                        + ","
                                        + row(2, 2, 127.005, 37.005)
                                        + ","
                                        + row(3, 3, 127.01, 37.01)));
        assertThat(read.shape().orElseThrow().shape().points()).hasSize(4);
        assertThat(read.webPoints()).hasSize(5);
        assertThat(read.webPoints().getFirst().point().x()).isCloseTo(127, within(1e-9));
        assertThat(read.webPoints().getFirst().point().y()).isCloseTo(37, within(1e-9));
        assertThat(read.source().digest()).hasSize(64);
    }

    @Test
    void 빈응답과_중복순번과_비정상타입을_거부한다() {
        assertThatThrownBy(() -> web.parse("{\"success\":false}".getBytes()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                web.parse(
                                        document(
                                                row(1, 1, 127, 37)
                                                        + ","
                                                        + row(1, 2, 127, 37)
                                                        + ","
                                                        + row(3, 3, 127.01, 37.01))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                web.parse(
                                        document(
                                                row(1, 1, 127, 37)
                                                        + ","
                                                        + row(2, 8, 127, 37)
                                                        + ","
                                                        + row(3, 3, 127.01, 37.01))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void BMS_도분좌표를_소수도로_해석하고_잘못된_분을_거부한다() {
        assertThat(GgBmsNodeGeometrySource.ddmm(3745.674))
                .isCloseTo(37.761233333333, within(1e-10));
        assertThatThrownBy(() -> GgBmsNodeGeometrySource.ddmm(3761))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

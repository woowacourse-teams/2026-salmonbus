package com.gustler.backend.gbis.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GbisRouteLineSourceTest {
    private final GbisApiCaller caller = mock(GbisApiCaller.class);
    private final GbisRouteLineSource source = new GbisRouteLineSource(caller, new ObjectMapper());
    private static final String ROUTE = "204000057";

    private void response(String rows, int status) {
        String body = "{\"response\":{\"msgHeader\":{\"resultCode\":0},\"msgBody\":{\"busRouteLineList\":" + rows + "}}}";
        when(caller.getWithMetadata(GbisRouteLineSource.PATH, ROUTE, "a"))
            .thenReturn(new GbisHttpResult(new GbisRawResponse.Received(body), status, null));
    }

    @Test
    void 경유정류장이_아닌_형상API를_한번_호출하고_순번으로_정렬한다() {
        response("[{\"lineSeq\":2,\"x\":127.2,\"y\":37.2},{\"lineSeq\":1,\"x\":127.1,\"y\":37.1}]", 200);
        var points = source.read(ROUTE).orElseThrow();
        assertThat(points).extracting(GbisRouteLineSource.Point::lineSeq).containsExactly(1, 2);
        assertThat(points.getFirst().x()).isEqualTo(127.1);
        verify(caller).getWithMetadata(GbisRouteLineSource.PATH, ROUTE, "a");
        verifyNoMoreInteractions(caller);
    }

    @Test
    void 빠진_순번이나_중복과_깨진_좌표는_부분형상으로_발행하지_않는다() {
        for (String rows : new String[] {
            "[]", "[{\"lineSeq\":1,\"x\":127,\"y\":37}]",
            "[{\"lineSeq\":1,\"x\":127,\"y\":37},{\"lineSeq\":3,\"x\":128,\"y\":38}]",
            "[{\"lineSeq\":1,\"x\":127,\"y\":37},{\"lineSeq\":1,\"x\":128,\"y\":38}]",
            "[{\"lineSeq\":1,\"x\":\"NaN\",\"y\":37},{\"lineSeq\":2,\"x\":128,\"y\":38}]"
        }) {
            response(rows, 200);
            assertThat(source.read(ROUTE)).as(rows).isEmpty();
        }
    }

    @Test
    void 다른_노선이나_HTTP오류는_기존_형상을_대체하지_않는다() {
        String rows = "[{\"routeId\":\"204000155\",\"lineSeq\":1,\"x\":127,\"y\":37},{\"lineSeq\":2,\"x\":128,\"y\":38}]";
        response(rows, 200);
        assertThat(source.read(ROUTE)).isEmpty();
        response(rows.replace("204000155", ROUTE), 429);
        assertThat(source.read(ROUTE)).isEmpty();
    }
}

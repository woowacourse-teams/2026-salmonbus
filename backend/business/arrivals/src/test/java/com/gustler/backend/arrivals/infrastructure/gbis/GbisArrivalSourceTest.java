package com.gustler.backend.arrivals.infrastructure.gbis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.gustler.backend.arrivals.domain.ArrivalSource;
import com.gustler.backend.gbis.api.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GbisArrivalSourceTest {
    private final GbisApiCaller caller = mock(GbisApiCaller.class);
    private final GbisArrivalSource source = new GbisArrivalSource(caller, new ObjectMapper(),
        Clock.fixed(Instant.parse("2026-10-14T03:00:00Z"), ZoneOffset.UTC));

    private ArrivalSource.Result read(String rows) {
        when(caller.getByStationWithMetadata(anyString(), anyString(), anyString())).thenReturn(new GbisHttpResult(new GbisRawResponse.Received(
            "{\"response\":{\"msgHeader\":{\"resultCode\":0},\"msgBody\":{\"busArrivalList\":" + rows + "}}}"), 200, null));
        return source.fetch("206000637", "a");
    }

    private static String row(String overrides) {
        return "{\"stationId\":206000637,\"routeId\":204000057,\"staOrder\":22,\"routeTypeCd\":12,"
            + "\"vehId1\":\"vehicle-a\",\"predictTimeSec1\":180,\"predictTime1\":3,"
            + "\"remainSeatCnt1\":0,\"predictTimeSec2\":0,\"predictTime2\":0" + overrides + "}";
    }

    @Test
    void 만석0은_유지하고_빈_둘째차량은_생성하지_않는다() {
        var success = (ArrivalSource.Success) read("[" + row("") + "]");
        assertThat(success.routes()).hasSize(1);
        assertThat(success.routes().getFirst().arrivals()).hasSize(1);
        assertThat(success.routes().getFirst().arrivals().getFirst().remainingSeats()).isZero();
        verify(caller).getByStationWithMetadata(GbisArrivalSource.PATH, "206000637", "a");
    }

    @Test
    void 모든_노선을_보존하고_지원하지_않는_좌석0은_알수없음으로_정규화한다() {
        String second = row("").replace("204000057", "204000155").replace("\"routeTypeCd\":12", "\"routeTypeCd\":13");
        var success = (ArrivalSource.Success) read("[" + row("") + "," + second + "]");
        assertThat(success.routes()).hasSize(2);
        var vehicle = success.routes().get(1).arrivals().getFirst();
        assertThat(vehicle.remainingSeats()).isNull();
        assertThat(vehicle.seatUnknownReason()).isEqualTo("UNSUPPORTED_ROUTE_TYPE");
    }

    @Test
    void 분_단위_대체와_미제공_좌석을_구분한다() {
        var success = (ArrivalSource.Success) read(row("").replace("\"predictTimeSec1\":180,", "")
            .replace("\"remainSeatCnt1\":0", "\"remainSeatCnt1\":-1"));
        var vehicle = success.routes().getFirst().arrivals().getFirst();
        assertThat(vehicle.sourceRemainingSeconds()).isNull();
        assertThat(vehicle.sourceRemainingMinutes()).isEqualTo(3);
        assertThat(vehicle.remainingSeats()).isNull();
    }

    @Test
    void 다른_정류장이나_깨진_필드는_빈_목록으로_바꾸지_않는다() {
        assertThat(read(row("").replace("206000637", "206000532")))
            .isEqualTo(new ArrivalSource.Failed(ArrivalSource.Reason.UNREADABLE_RESPONSE));
        assertThat(read(row("").replace("\"predictTimeSec1\":180", "\"predictTimeSec1\":\"broken\"")))
            .isEqualTo(new ArrivalSource.Failed(ArrivalSource.Reason.UNREADABLE_RESPONSE));
        assertThat(read("\"broken\""))
            .isEqualTo(new ArrivalSource.Failed(ArrivalSource.Reason.UNREADABLE_RESPONSE));
    }

    @Test
    void 정상_빈_응답과_상류_거절은_다른_결과다() {
        assertThat(((ArrivalSource.Success) read("[]")).routes()).isEmpty();
        when(caller.getByStationWithMetadata(anyString(), anyString(), anyString())).thenReturn(
            new GbisHttpResult(new GbisRawResponse.PortalRejected(PortalReasonCode.PER_SECOND_QUOTA_EXCEEDED, "23", "", ""), 429, null));
        assertThat(source.fetch("206000637", "a"))
            .isEqualTo(new ArrivalSource.Failed(ArrivalSource.Reason.RATE_LIMITED));
    }

    @Test
    void 같은_노선_같은_방문의_중복행을_허용하지_않는다() {
        assertThat(read("[" + row("") + "," + row("") + "]"))
            .isEqualTo(new ArrivalSource.Failed(ArrivalSource.Reason.UNREADABLE_RESPONSE));
    }

    @Test
    void 과거에_확보한_정류장_27노선_응답을_오프라인으로_해석한다() throws Exception {
        try (var input = getClass().getResourceAsStream("/gbis-arrivals-station.json")) {
            String fixture = new String(java.util.Objects.requireNonNull(input).readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            when(caller.getByStationWithMetadata(anyString(), anyString(), anyString()))
                .thenReturn(new GbisHttpResult(new GbisRawResponse.Received(fixture), 200, null));
            var result = source.fetch("206000637", "a");
            assertThat(result).isInstanceOf(ArrivalSource.Success.class);
            assertThat(((ArrivalSource.Success) result).routes()).hasSize(27);
        }
    }

    @Test
    void 빈_본문의_HTTP429도_재시도_힌트와_함께_해석한다() {
        when(caller.getByStationWithMetadata(anyString(), anyString(), anyString())).thenReturn(
            new GbisHttpResult(new GbisRawResponse.NotReceived("empty"), 429, "120"));
        assertThat(source.fetch("206000637", "a"))
            .isEqualTo(new ArrivalSource.Failed(ArrivalSource.Reason.RATE_LIMITED, Duration.ofSeconds(120)));
    }
}

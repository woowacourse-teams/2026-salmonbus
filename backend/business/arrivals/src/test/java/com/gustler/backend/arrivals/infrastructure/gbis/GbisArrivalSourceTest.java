package com.gustler.backend.arrivals.infrastructure.gbis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gustler.backend.arrival.contract.ArrivalSnapshot.Prediction;
import com.gustler.backend.arrivals.domain.ArrivalSource.Failed;
import com.gustler.backend.arrivals.domain.ArrivalSource.Reason;
import com.gustler.backend.arrivals.domain.ArrivalSource;
import com.gustler.backend.arrivals.domain.ArrivalSource.Success;
import com.gustler.backend.gbis.api.GbisApiCaller;
import com.gustler.backend.gbis.api.GbisHttpResult;
import com.gustler.backend.gbis.api.GbisRawResponse.NotReceived;
import com.gustler.backend.gbis.api.GbisRawResponse.PortalRejected;
import com.gustler.backend.gbis.api.GbisRawResponse.Received;
import com.gustler.backend.gbis.api.PortalReasonCode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GbisArrivalSourceTest {
    private final GbisApiCaller caller = mock(GbisApiCaller.class);
    private final GbisArrivalSource source = new GbisArrivalSource(caller, new ObjectMapper(),
        Clock.fixed(Instant.parse("2026-10-14T03:00:00Z"), ZoneOffset.UTC));

    private ArrivalSource.Result read(String rows) {
        when(caller.getByStationWithMetadata(anyString(), anyString(), anyString())).thenReturn(
            new GbisHttpResult(new Received(
                "{\"response\":{\"msgHeader\":{\"resultCode\":0},\"msgBody\":{\"busArrivalList\":" + rows + "}}}"), 200,
                null));
        return source.fetch("206000637", "a");
    }

    private static String row(String overrides) {
        return "{\"stationId\":206000637,\"routeId\":204000057,\"staOrder\":22,\"routeTypeCd\":12,"
            + "\"vehId1\":\"vehicle-a\",\"predictTimeSec1\":180,\"predictTime1\":3,"
            + "\"remainSeatCnt1\":0,\"predictTimeSec2\":0,\"predictTime2\":0" + overrides + "}";
    }

    @Test
    void 만석0은_유지하고_빈_둘째차량은_생성하지_않는다() {
        // when
        Success success = (Success) read("[" + row("") + "]");

        // then
        assertThat(success.routes()).hasSize(1);
        assertThat(success.routes().getFirst().arrivals()).hasSize(1);
        assertThat(success.routes().getFirst().arrivals().getFirst().remainingSeats()).isZero();
        verify(caller).getByStationWithMetadata(GbisArrivalSource.PATH, "206000637", "a");
    }

    @Test
    void 모든_노선을_보존하고_지원하지_않는_좌석0은_알수없음으로_정규화한다() {
        // when
        String second = row("").replace("204000057", "204000155").replace("\"routeTypeCd\":12", "\"routeTypeCd\":13");
        Success success = (Success) read("[" + row("") + "," + second + "]");

        // then
        assertThat(success.routes()).hasSize(2);
        Prediction vehicle = success.routes().get(1).arrivals().getFirst();
        assertThat(vehicle.remainingSeats()).isNull();
        assertThat(vehicle.seatUnknownReason()).isEqualTo("UNSUPPORTED_ROUTE_TYPE");
    }

    @Test
    void 분_단위_대체와_미제공_좌석을_구분한다() {
        // when
        Success success = (Success) read(row("").replace("\"predictTimeSec1\":180,", "")
                .replace("\"remainSeatCnt1\":0", "\"remainSeatCnt1\":-1"));
        Prediction vehicle = success.routes().getFirst().arrivals().getFirst();

        // then
        assertThat(vehicle.sourceRemainingSeconds()).isNull();
        assertThat(vehicle.sourceRemainingMinutes()).isEqualTo(3);
        assertThat(vehicle.remainingSeats()).isNull();
    }

    @Test
    void 다른_정류장이나_깨진_필드는_빈_목록으로_바꾸지_않는다() {
        // when
        ArrivalSource.Result differentStation = read(row("").replace("206000637", "206000532"));

        // then
        assertThat(differentStation)
            .isEqualTo(new Failed(Reason.UNREADABLE_RESPONSE));

        // when
        ArrivalSource.Result brokenNumber = read(row("").replace("\"predictTimeSec1\":180", "\"predictTimeSec1\":\"broken\""));

        // then
        assertThat(brokenNumber)
            .isEqualTo(new Failed(Reason.UNREADABLE_RESPONSE));

        // when
        ArrivalSource.Result brokenRows = read("\"broken\"");

        // then
        assertThat(brokenRows)
            .isEqualTo(new Failed(Reason.UNREADABLE_RESPONSE));
    }

    @Test
    void 정상_빈_응답과_상류_거절은_다른_결과다() {
        // when
        Success emptyResponse = (Success) read("[]");

        // then
        assertThat(emptyResponse.routes()).isEmpty();

        // given
        when(caller.getByStationWithMetadata(anyString(), anyString(), anyString())).thenReturn(
            new GbisHttpResult(new PortalRejected(PortalReasonCode.PER_SECOND_QUOTA_EXCEEDED, "23", "", ""), 429,
                null));

        // when
        ArrivalSource.Result rejectedResponse = source.fetch("206000637", "a");

        // then
        assertThat(rejectedResponse)
            .isEqualTo(new Failed(Reason.RATE_LIMITED));
    }

    @Test
    void 같은_노선_같은_방문의_중복행을_허용하지_않는다() {
        // when
        ArrivalSource.Result actual = read("[" + row("") + "," + row("") + "]");

        // then
        assertThat(actual)
            .isEqualTo(new Failed(Reason.UNREADABLE_RESPONSE));
    }

    @Test
    void 과거에_확보한_정류장_27노선_응답을_오프라인으로_해석한다() throws Exception {
        // when
        try (InputStream input = getClass().getResourceAsStream("/gbis-arrivals-station.json")) {
            String fixture = new String(Objects.requireNonNull(input).readAllBytes(),
                StandardCharsets.UTF_8);
            when(caller.getByStationWithMetadata(anyString(), anyString(), anyString()))
                .thenReturn(new GbisHttpResult(new Received(fixture), 200, null));
            ArrivalSource.Result result = source.fetch("206000637", "a");

            // then
            assertThat(result).isInstanceOf(Success.class);
            assertThat(((Success) result).routes()).hasSize(27);
        }
    }

    @Test
    void 빈_본문의_HTTP429도_재시도_힌트와_함께_해석한다() {
        // given
        when(caller.getByStationWithMetadata(anyString(), anyString(), anyString())).thenReturn(
            new GbisHttpResult(new NotReceived("empty"), 429, "120"));

        // when
        ArrivalSource.Result actual = source.fetch("206000637", "a");

        // then
        assertThat(actual)
            .isEqualTo(new Failed(Reason.RATE_LIMITED, Duration.ofSeconds(120)));
    }
}

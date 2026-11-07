package com.gustler.backend.routecatalog.infrastructure.gbis;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.gustler.backend.gbis.api.*;
import com.gustler.backend.routecatalog.domain.RouteSourceResult;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt.Status;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class GbisCatalogSourceCaptureTest {
    private static final String ROUTE="204000057",KEY="catalog-secret-do-not-record";
    private static final Instant AT=Instant.parse("2026-11-02T00:00:00Z");
    private static final String INFO="""
        { "response": {"msgHeader":{"resultCode":0},"msgBody":{"busRouteInfoItem":{
          "routeName":"3330", "startStationName":"서울", "endStationName":"종점"}}} }
        """;
    private static final String STOPS="""
        {"response":{"msgHeader":{"resultCode":0},"msgBody":{"busRouteStationList":[
          {"stationId":"206000001","stationName":"서울","stationSeq":1,"x":127.0000,"y":37.0000},
          {"stationId":"206000002","stationName":"종점","stationSeq":2,"x":127.1000,"y":37.1000}]}}}
        """;
    private MockRestServiceServer server;
    private GbisRouteSource source;
    @BeforeEach void setup() {
        var builder=RestClient.builder().baseUrl("https://gbis.test");
        server=MockRestServiceServer.bindTo(builder).build();
        var json=new ObjectMapper();
        source=new GbisRouteSource(new GbisRouteInfoSource(new GbisApiCaller(builder.build(),
            new GbisClientOptions("https://gbis.test",KEY),json),json,Optional.of(Clock.fixed(AT,ZoneOffset.UTC))));
    }
    private void info(String body) {
        server.expect(requestTo(Matchers.containsString("getBusRouteInfoItemv2")))
            .andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
    }
    private void stops(String body) {
        server.expect(requestTo(Matchers.containsString("getBusRouteStationListv2")))
            .andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
    }
    @Test void accepted_catalog_preserves_both_original_utf8_responses_and_metadata() {
        info(INFO);stops(STOPS);
        var capture=source.capture(ROUTE);
        assertThat(capture.result()).isInstanceOf(RouteSourceResult.Success.class);
        assertThat(capture.receipts()).extracting(r -> r.status()).containsExactly(Status.SUCCESS,Status.SUCCESS);
        assertThat(capture.receipts().getFirst().body()).isEqualTo(INFO.getBytes(StandardCharsets.UTF_8));
        assertThat(capture.receipts().getLast().body()).isEqualTo(STOPS.getBytes(StandardCharsets.UTF_8));
        assertThat(capture.receipts()).allSatisfy(r -> {
            assertThat(r.acquiredAt()).isEqualTo(AT);assertThat(r.providerVersion()).isEqualTo("NOT_PROVIDED");
            assertThat(r.toString()).doesNotContain(KEY,"serviceKey",INFO,STOPS);
        });server.verify();
    }
    @Test void station_failure_keeps_successful_info_receipt_and_discards_error_body() {
        info(INFO);
        server.expect(requestTo(Matchers.containsString("getBusRouteStationListv2")))
            .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("https://upstream?serviceKey="+KEY));
        var capture=source.capture(ROUTE);
        assertThat(capture.result()).isInstanceOf(RouteSourceResult.Failed.class);
        assertThat(capture.receipts()).extracting(r -> r.status()).containsExactly(Status.SUCCESS,Status.HTTP_ERROR);
        assertThat(capture.receipts().getLast().body()).isNull();
        assertThat(capture.toString()).doesNotContain(KEY,"serviceKey");server.verify();
    }
    @Test void sensitive_success_never_reaches_catalog_names_or_logs_and_skips_second_request() {
        info(INFO.replace("3330",KEY));
        var capture=source.capture(ROUTE);
        assertThat(capture.result()).isInstanceOf(RouteSourceResult.Failed.class);
        assertThat(capture.receipts()).extracting(r -> r.status()).containsExactly(Status.SENSITIVE_RESPONSE);
        assertThat(capture.receipts().getFirst().body()).isNull();
        assertThat(capture.toString()).doesNotContain(KEY);server.verify();
        server.reset();info(INFO.replace("3330",KEY));
        assertThat(source.read(ROUTE)).isInstanceOf(RouteSourceResult.Failed.class);server.verify();
    }
    @Test void duplicate_visits_are_invalid_and_the_rejected_station_body_is_not_archived() {
        info(INFO);stops(STOPS.replace("\"stationSeq\":2","\"stationSeq\":1"));
        var capture=source.capture(ROUTE);
        assertThat(capture.result()).isInstanceOf(RouteSourceResult.Failed.class);
        assertThat(capture.receipts()).extracting(r -> r.status()).containsExactly(Status.SUCCESS,Status.INVALID_RESPONSE);
        assertThat(capture.receipts().getLast().body()).isNull();server.verify();
    }
    @Test void another_route_response_is_not_bound_to_the_requested_catalog() {
        info(INFO.replace("\"routeName\":", "\"routeId\":\"999999999\",\"routeName\":"));
        var capture=source.capture(ROUTE);
        assertThat(capture.result()).isInstanceOf(RouteSourceResult.Failed.class);
        assertThat(capture.receipts()).extracting(r -> r.status()).containsExactly(Status.INVALID_RESPONSE);
        assertThat(capture.receipts().getFirst().body()).isNull();server.verify();
    }
}

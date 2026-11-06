package com.gustler.backend.gbis.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class GbisRouteLineCaptureTest {
    private static final String ROUTE="204000057";
    private static final String SECRET="ab+cd/ef=";
    private static final ObjectMapper JSON=new ObjectMapper();
    private final RestClient.Builder builder=RestClient.builder().baseUrl("https://gbis.test");
    private final MockRestServiceServer server=MockRestServiceServer.bindTo(builder).build();
    private final GbisApiCaller caller=new GbisApiCaller(builder.build(),new GbisClientOptions("https://gbis.test",SECRET),JSON);
    private final GbisRouteLineSource source=new GbisRouteLineSource(caller,JSON);
    private static String valid(String extra) {
        return " {\n\"response\":{\"msgHeader\":{\"resultCode\":0},\"msgBody\":{\"busRouteLineList\":["
            +"{\"routeId\":\"204000057\",\"lineSeq\":2,\"x\":127.2,\"y\":37.2},"
            +"{\"routeId\":\"204000057\",\"lineSeq\":1,\"x\":127.1,\"y\":37.1}]}},\"note\":\"원본 공백 보존\""+extra+"}\n";
    }
    private void reply(HttpStatus status,byte[] bytes) {
        server.expect(requestTo(containsString("routeId="+ROUTE)))
            .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(bytes));
    }
    @Test void accepted_original_bytes_survive_sorting_and_defensive_copies() {
        byte[] body=valid("").getBytes(StandardCharsets.UTF_8);
        reply(HttpStatus.OK,body);
        var captured=source.capture(ROUTE);
        assertThat(captured.status()).isEqualTo(GbisRouteLineSource.CaptureStatus.SUCCESS);
        assertThat(captured.points().orElseThrow()).extracting(GbisRouteLineSource.Point::lineSeq).containsExactly(1,2);
        assertThat(captured.body()).containsExactly(body);
        captured.body()[0]=0;
        assertThat(captured.body()).containsExactly(body);
        assertThat(captured.toString()).doesNotContain("원본",SECRET);
        server.verify();
    }
    @Test void HTTP_failure_never_retains_even_a_valid_looking_shape_and_error_echo() {
        reply(HttpStatus.INTERNAL_SERVER_ERROR,valid(",\"url\":\"https://gbis.test?serviceKey="+SECRET+"\"").getBytes(StandardCharsets.UTF_8));
        var captured=source.capture(ROUTE);
        assertThat(captured.httpStatus()).isEqualTo(500);
        assertThat(captured.status()).isEqualTo(GbisRouteLineSource.CaptureStatus.HTTP_ERROR);
        assertThat(captured.points()).isEmpty();assertThat(captured.body()).isNull();
        assertThat(captured.toString()).doesNotContain(SECRET,"https://");
        server.verify();
    }
    @Test void portal_rejections_and_invalid_schema_never_become_retained_successes() {
        for(String body:List.of(
                "{\"OpenAPI_ServiceResponse\":{\"cmmMsgHeader\":{\"returnReasonCode\":\"30\",\"returnAuthMsg\":\"serviceKey="+SECRET+"\"}}}",
                "broken-json serviceKey="+SECRET,
                valid("").replace("\"lineSeq\":2","\"lineSeq\":3"),
                valid("").replace("\"routeId\":\"204000057\"","\"routeId\":\"999999999\""),
                valid("").replace("\"resultCode\":0","\"resultCode\":22"))) {
            server.reset();reply(HttpStatus.OK,body.getBytes(StandardCharsets.UTF_8));
            var captured=source.capture(ROUTE);
            assertThat(captured.status()).isEqualTo(GbisRouteLineSource.CaptureStatus.INVALID_RESPONSE);
            assertThat(captured.points()).isEmpty();assertThat(captured.body()).isNull();server.verify();
        }
    }
    @Test void credential_echo_variants_are_withheld_without_losing_valid_normalized_geometry() {
        for(String extra:List.of(
                ",\"trace\":"+JSON.writeValueAsString(SECRET),
                ",\"trace\":\"ab%2Bcd%2Fef%3D\"",
                ",\"trace\":\"ab%252Bcd%252Fef%253D\"",
                ",\"service\\u004bey\":\"another-secret\"",
                ",\"service%4Bey\":\"another-secret\"",
                ",\"access_token\":\"another-secret\"",
                ",\"trace\":\"https://provider.test?serviceKey=another-secret\"",
                ",\"trace\":\"https://user:pass@provider.test/path\"",
                ",\"trace\":\"Bearer opaque-token\"")) {
            server.reset();reply(HttpStatus.OK,valid(extra).getBytes(StandardCharsets.UTF_8));
            var captured=source.capture(ROUTE);
            assertThat(captured.points()).isPresent();
            assertThat(captured.status()).as(extra).isEqualTo(GbisRouteLineSource.CaptureStatus.SENSITIVE_RESPONSE);
            assertThat(captured.body()).isNull();assertThat(captured.toString()).doesNotContain(SECRET,"another-secret","opaque-token");
            server.verify();
        }
    }
    @Test void transport_failure_keeps_only_a_safe_classification() {
        server.expect(requestTo(containsString("routeId="+ROUTE))).andRespond(request -> {
            throw new IOException("https://provider.test?serviceKey="+SECRET);
        });
        var captured=source.capture(ROUTE);
        assertThat(captured.status()).isEqualTo(GbisRouteLineSource.CaptureStatus.TRANSPORT_FAILURE);
        assertThat(captured.body()).isNull();assertThat(captured.httpStatus()).isZero();
        assertThat(captured.toString()).doesNotContain(SECRET,"https://");server.verify();
    }
    @Test void ordinary_non_catalog_calls_do_not_retain_response_bodies() {
        server.expect(requestTo(containsString("routeId="+ROUTE))).andRespond(withSuccess("{}",MediaType.APPLICATION_JSON));
        var result=caller.getWithMetadata("/buslocationservice/v2/getBusLocationListv2",ROUTE,GbisKey.PRIMARY);
        assertThat(result.capture().disposition()).isEqualTo(GbisResponseCapture.Disposition.NOT_RETAINED);
        assertThat(result.capture().body()).isNull();server.verify();
    }
    @Test void HTTP_result_debug_string_does_not_echo_raw_success_or_error_content() {
        var result=new GbisHttpResult(new GbisRawResponse.Received(SECRET),200,"serviceKey="+SECRET);
        assertThat(result.toString()).doesNotContain(SECRET,"serviceKey");
    }
    @Test void already_encoded_configured_credentials_also_block_decoded_bare_echoes() {
        var localBuilder=RestClient.builder().baseUrl("https://gbis.test");
        var localServer=MockRestServiceServer.bindTo(localBuilder).build();
        var encodedCaller=new GbisApiCaller(localBuilder.build(),
            new GbisClientOptions("https://gbis.test","ab%2Bcd%2Fef%3D"),JSON);
        localServer.expect(requestTo(containsString("routeId="+ROUTE)))
            .andRespond(withSuccess(valid(",\"trace\":\"ab+cd/ef=\""),MediaType.APPLICATION_JSON));
        var captured=new GbisRouteLineSource(encodedCaller,JSON).capture(ROUTE);
        assertThat(captured.points()).isPresent();
        assertThat(captured.status()).isEqualTo(GbisRouteLineSource.CaptureStatus.SENSITIVE_RESPONSE);
        assertThat(captured.body()).isNull();
        localServer.verify();
    }

    @Test void oversized_catalog_bodies_are_unavailable_before_decoding_or_normalization() {
        List<String> paths=List.of(GbisRouteLineSource.PATH,
            "/busrouteservice/v2/getBusRouteInfoItemv2", "/busrouteservice/v2/getBusRouteStationListv2");
        for(String path:paths) {
            assertThat(GbisApiCaller.captureBodyTooLarge(path,16_000_000)).isFalse();
            assertThat(GbisApiCaller.captureBodyTooLarge(path,16_000_001)).isTrue();
        }
        assertThat(GbisApiCaller.captureBodyTooLarge("/buslocationservice/v2/getBusLocationListv2",Integer.MAX_VALUE)).isFalse();
        assertThat(GbisApiCaller.captureBodyTooLarge("/busarrivalservice/v2/getBusArrivalListv2",Integer.MAX_VALUE)).isFalse();
        // One shared array exercises the real HTTP boundary without materializing any large String.
        // The small prefix is valid catalog JSON; oversized whitespace must not turn it into a legacy success.
        byte[] prefix=("{\"response\":{\"msgHeader\":{\"resultCode\":0},\"msgBody\":{\"busRouteInfoItem\":{"
            +"\"routeName\":\""+SECRET+"\",\"startStationName\":\"A\",\"endStationName\":\"B\"}}}}")
            .getBytes(StandardCharsets.UTF_8);
        byte[] oversized=new byte[16_000_001];
        java.util.Arrays.fill(oversized,(byte)' ');System.arraycopy(prefix,0,oversized,0,prefix.length);
        for(int i=0;i<4;i++) reply(HttpStatus.OK,oversized);
        for(String path:paths) {
            var result=caller.getWithMetadata(path,ROUTE,GbisKey.PRIMARY);
            // Boolean assertions deliberately avoid rendering a provider body if this protection regresses.
            assertThat(result.response() instanceof GbisRawResponse.NotReceived).isTrue();
            assertThat(((GbisRawResponse.NotReceived)result.response()).message()).isEqualTo("RESPONSE_TOO_LARGE");
            assertThat(result.httpStatus()).isEqualTo(200);
            assertThat(result.capture().body()==null).isTrue();
            assertThat(result.toString()).doesNotContain(SECRET);
        }
        var catalog=new GbisRouteInfoSource(caller,JSON).capture(ROUTE);
        assertThat(catalog.result() instanceof GbisRouteResult.RouteInfoUnavailable).isTrue();
        assertThat(catalog.responses()).hasSize(1);
        assertThat(catalog.responses().getFirst().status()).isEqualTo(GbisRouteInfoSource.CaptureStatus.INVALID_RESPONSE);
        assertThat(catalog.responses().getFirst().body()==null).isTrue();
        server.verify();
    }

    @Test void invalid_UTF8_cannot_bypass_screening_through_replacement_decoding() {
        String prefix="{\"response\":{\"msgHeader\":{\"resultCode\":0,\"resultMessage\":\"";
        String suffix="\"},\"msgBody\":{\"busRouteInfoItem\":{\"routeName\":\"\\u0061b+cd/ef=\","
            +"\"startStationName\":\"A\",\"endStationName\":\"B\"}}}}";
        var bytes=new java.io.ByteArrayOutputStream();
        bytes.writeBytes(prefix.getBytes(StandardCharsets.UTF_8));bytes.write(0xff);bytes.writeBytes(suffix.getBytes(StandardCharsets.UTF_8));
        byte[] response=bytes.toByteArray();
        // String decoding repairs the bad byte, so a second parser could otherwise accept the credential-bearing name.
        assertThat(JSON.readTree(new String(response,StandardCharsets.UTF_8))
            .at("/response/msgBody/busRouteInfoItem/routeName").asString().equals(SECRET)).isTrue();
        reply(HttpStatus.OK,response);reply(HttpStatus.OK,response);
        var result=caller.getWithMetadata("/busrouteservice/v2/getBusRouteInfoItemv2",ROUTE,GbisKey.PRIMARY);
        assertThat(result.response() instanceof GbisRawResponse.NotReceived).isTrue();
        assertThat(((GbisRawResponse.NotReceived)result.response()).message()).isEqualTo("RESPONSE_NOT_RETAINABLE");
        assertThat(result.httpStatus()).isEqualTo(200);assertThat(result.capture().body()==null).isTrue();
        var catalog=new GbisRouteInfoSource(caller,JSON).capture(ROUTE);
        assertThat(catalog.result() instanceof GbisRouteResult.RouteInfoUnavailable).isTrue();
        assertThat(catalog.responses()).hasSize(1);
        assertThat(catalog.responses().getFirst().status()).isEqualTo(GbisRouteInfoSource.CaptureStatus.INVALID_RESPONSE);
        assertThat(catalog.responses().getFirst().body()==null).isTrue();
        server.verify();
    }

}

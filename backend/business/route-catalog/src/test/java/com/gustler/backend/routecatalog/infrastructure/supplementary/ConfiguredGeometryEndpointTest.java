package com.gustler.backend.routecatalog.infrastructure.supplementary;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

class ConfiguredGeometryEndpointTest {
    @Test void web_configured_endpoint_uses_the_same_real_http_capture_parser_and_receipt_path()throws Exception {
        String body="{\"success\":true,\"result\":{\"gg\":{\"upLine\":{\"count\":2,\"list\":[{\"seq\":1,\"type\":1,\"lon\":14100000,\"lat\":4500000},{\"seq\":2,\"type\":3,\"lon\":14100001,\"lat\":4500001}]},\"downLine\":{\"count\":2,\"list\":[{\"seq\":3,\"type\":1,\"lon\":14100002,\"lat\":4500002},{\"seq\":4,\"type\":3,\"lon\":14100003,\"lat\":4500003}]}}}}";
        var query=new AtomicReference<String>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/source/web",request->{query.set(request.getRequestURI().getRawQuery());byte[] bytes=body.getBytes(StandardCharsets.UTF_8);request.sendResponseHeaders(200,bytes.length);request.getResponseBody().write(bytes);request.close();});server.start();
        try {
            var source=new GbisWebGeometrySource(new ObjectMapper(),Clock.systemUTC(),"http://127.0.0.1:"+server.getAddress().getPort()+"/source/web");
            var result=source.read("204000057");assertThat(query.get()).isEqualTo("cmd=searchMapLineJson&routeId=204000057");
            assertThat(result.webPoints()).hasSize(4);assertThat(result.receipts()).hasSize(1);
            assertThat(result.receipts().getFirst().body()).isEqualTo(body.getBytes(StandardCharsets.UTF_8));
        } finally {server.stop(0);}
    }
    @Test void bms_configured_endpoint_selects_json_by_query_without_the_rejected_accept_header()throws Exception {
        String body="{\"TBBMSNODEM\":[{\"head\":[{\"list_total_count\":1},{\"RESULT\":{\"CODE\":\"INFO-000\"}}]},{\"row\":[{\"NODE_ID\":\"one\",\"LAT\":\"3729.0\",\"LOGT\":\"12712.0\"}]}]}";
        var query=new AtomicReference<String>();var requests=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/source/nodes",request->{
            requests.incrementAndGet();query.set(request.getRequestURI().getRawQuery());
            // The real BMS endpoint returns 500 for this header, even with Type=json.
            boolean rejected="application/json".equals(request.getRequestHeaders().getFirst("Accept"));
            byte[] bytes=(rejected?"provider failed":body).getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(rejected?500:200,bytes.length);
            request.getResponseBody().write(bytes);request.close();
        });server.start();
        try {
            var jdbc=mock(JdbcClient.class,RETURNS_DEEP_STUBS);
            when(jdbc.sql(anyString()).params(any(Object[].class)).update()).thenReturn(1);
            var source=new GgBmsNodeGeometrySource(new ObjectMapper(),jdbc,Clock.systemUTC(),"local-only",Optional.empty(),"http://127.0.0.1:"+server.getAddress().getPort()+"/source/nodes");
            source.refresh();assertThat(query.get()).isEqualTo("Type=json&pSize=1000&pIndex=1&KEY=local-only");
            var first=source.read("204000057");assertThat(first.source().status()).isEqualTo("AVAILABLE");
            assertThat(first.nodes()).hasSize(1);
            assertThat(first.nodes().getFirst().x()).isCloseTo(127.2,within(1e-10));
            assertThat(first.nodes().getFirst().y()).isCloseTo(37+29d/60,within(1e-10));
            assertThat(source.read("204000081").nodes()).isEqualTo(first.nodes());
            assertThat(requests.get()).isEqualTo(1);
        } finally {server.stop(0);}
    }
    @Test void invalid_configured_endpoints_fail_closed_without_echoing_uri_credentials() {
        for(String input:new String[]{"ftp://example.invalid/source","https://secret:password@example.invalid/source","https://example.invalid/source?KEY=private","https://example.invalid/source#fragment","not a url"})
            assertThatThrownBy(()->ConfiguredGeometryEndpoint.parse(input)).hasMessage("INVALID_GEOMETRY_SOURCE_ENDPOINT");
    }
}

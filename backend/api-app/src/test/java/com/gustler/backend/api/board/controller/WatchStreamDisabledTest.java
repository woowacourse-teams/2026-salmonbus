package com.gustler.backend.api.board.controller;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.support.PostgresTestContainer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring 시험 컨텍스트는 어노테이션이 같아야 재사용되므로, 실제 포트를 쓰는 다른 시험과 어노테이션을 그대로 맞춘다.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
class WatchStreamDisabledTest {
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 꺼져_있으면_스트림_주소는_없는_경로와_똑같은_404다() throws Exception {
        // when
        HttpResponse<String> stream = get("/api/v1/routes/123456789/watch/events");
        HttpResponse<String> unknown = get("/api/v1/routes/123456789/unknown");

        // then
        JsonNode streamBody = objectMapper.readTree(stream.body());
        JsonNode unknownBody = objectMapper.readTree(unknown.body());
        assertSoftly(softly -> {
            softly.assertThat(stream.statusCode()).isEqualTo(404);
            softly.assertThat(unknown.statusCode()).isEqualTo(404);
            softly.assertThat(streamBody.path("code").asString()).isEqualTo("ENDPOINT_NOT_FOUND");
            softly.assertThat(streamBody.path("code")).isEqualTo(unknownBody.path("code"));
            softly.assertThat(streamBody.path("message")).isEqualTo(unknownBody.path("message"));
            softly.assertThat(stream.headers().allValues("Content-Type"))
                .isEqualTo(unknown.headers().allValues("Content-Type"));
            softly.assertThat(stream.headers().allValues("Cache-Control"))
                .isEqualTo(unknown.headers().allValues("Cache-Control"));
        });
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Accept", "text/event-stream")
            .GET()
            .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}

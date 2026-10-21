package com.gustler.backend.api.account.controller;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.support.PostgresTestContainer;
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
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
class AuthSessionDisabledTest {
    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 로그인_기능을_끄면_로그인_상태_경로가_없다() throws Exception {
        // when
        HttpResponse<String> actual = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/session")).GET().build(),
            HttpResponse.BodyHandlers.ofString());

        // then
        JsonNode body = objectMapper.readTree(actual.body());
        assertSoftly(softly -> {
            softly.assertThat(actual.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value());
            softly.assertThat(body.path("code").asString()).isEqualTo("ENDPOINT_NOT_FOUND");
        });
    }
}

package com.gustler.backend.api.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.support.PostgresTestContainer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Actuator 를 별도 포트로 분리해도 공개 포트의 오류 응답이 바뀌지 않는지 검증한다.
 *
 * <p>배포에서는 {@code MANAGEMENT_SERVER_PORT} 로 관리 포트를 분리한다. 관리 포트는 자식
 * 컨텍스트에서 실행되지만 서비스 오류 처리는 부모 컨텍스트에만 등록된다. 이 분리가 공개 포트의 오류 응답에
 * 영향을 주지 않는지 검증한다.
 *
 * <p>관리 포트가 부트 기본 응답을 반환하는 것도 검증한다. 현재 정한 범위이며,
 * 자식 컨텍스트에 서비스 오류 처리를 등록하면 이 테스트가 실패한다. 범위를 바꾸기로 하면
 * 이 테스트의 기대 결과도 바꾸면 된다.
 *
 * <p>공개 포트에 있는 Actuator 는 {@code add-additional-paths} 로 연 {@code /livez}·{@code /readyz}
 * 뿐이다. 배포 검증({@code validate.sh})은 8080 이 트래픽을 받을 상태인지 {@code /readyz} 로 확인한다.
 * 나머지 Actuator 가 공개 포트에 노출되지 않는 것도 검증한다.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
@Import(PostgresTestContainer.class)
class ManagementPortSeparationTest {
    @LocalServerPort
    private int publicPort;

    @LocalManagementPort
    private int managementPort;

    @Test
    void 관리_포트를_떼도_공개_포트는_우리_오류_응답_형식을_지킨다() throws IOException {
        // when
        String actual = send(publicPort, "TRACE /api/v1/routes HTTP/1.1");

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(actual).contains("\"code\":\"METHOD_NOT_ALLOWED\"");
            softly.assertThat(actual).contains("\"requestId\"");
            softly.assertThat(actual).contains(RequestId.HEADER);
        }
    }

    @Test
    void 관리_포트는_이_오류_응답_형식_밖이다() throws IOException {
        // when
        String actual = send(managementPort, "TRACE /actuator/health HTTP/1.1");

        // then
        // api 문서의 범위는 /api/v1 아래뿐이므로 관리 포트는 부트 기본 응답으로 둔다
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(actual).doesNotContain("\"code\":");
            softly.assertThat(actual).doesNotContain(RequestId.HEADER);
        }
    }

    @Test
    void 두_포트가_서로_다른_포트다() {
        // then
        assertThat(managementPort).isNotEqualTo(publicPort);
    }

    @Test
    void 공개_포트의_readyz_가_UP_이다() throws IOException {
        // when
        // validate.sh 가 8080 이 트래픽을 받을 상태인지 이 응답으로 확인한다
        String actual = send(publicPort, "GET /readyz HTTP/1.1");

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(actual).startsWith("HTTP/1.1 200");
            softly.assertThat(actual).contains("\"status\":\"UP\"");
        }
    }

    @Test
    void 공개_포트에_actuator_경로가_없다() throws IOException {
        // when
        String actual = send(publicPort, "GET /actuator/health HTTP/1.1");

        // then
        // /livez·/readyz 말고는 관리 포트에만 있다
        assertThat(actual).startsWith("HTTP/1.1 404");
    }

    private String send(
        final int port,
        String requestLine
    ) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            String request = requestLine + "\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "Connection: close\r\n\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder received = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                received.append(line).append('\n');
            }

            return received.toString();
        }
    }
}

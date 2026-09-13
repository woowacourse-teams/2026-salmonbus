package com.gustler.backend.api.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.support.PostgresTestContainer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Actuator 를 다른 포트에 떼도 공개 포트의 오류 응답이 안 바뀌는지 붙잡는다.
 *
 * <p>배포에서 {@code MANAGEMENT_SERVER_PORT} 로 관리 포트를 뗀다. 그러면 그 포트가 자식
 * 컨텍스트에서 도는데, 우리 오류 처리는 부모 컨텍스트에만 붙는다. <b>공개 포트가 그 분리에
 * 휩쓸리지 않는지를 여기서 고정한다.</b>
 *
 * <p>관리 포트가 부트 기본 응답을 내는 것도 같이 고정한다. 그것이 <b>지금 정한 범위</b>이고,
 * 누가 자식 컨텍스트에 우리 것을 등록하면 이 테스트가 먼저 알려 준다. 범위를 바꾸기로 하면
 * 이 테스트를 뒤집으면 된다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
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
        assertThat(actual).contains("\"code\":\"METHOD_NOT_ALLOWED\"");
        assertThat(actual).contains("\"requestId\"");
        assertThat(actual).contains(RequestId.HEADER);
    }

    @Test
    void 관리_포트는_이_오류_응답_형식_밖이다() throws IOException {
        // when
        String actual = send(managementPort, "TRACE /actuator/health HTTP/1.1");

        // then api 문서가 정한 것은 /api/v1 아래뿐이라 여기는 부트 기본 응답으로 둔다
        assertThat(actual).doesNotContain("\"code\":");
        assertThat(actual).doesNotContain(RequestId.HEADER);
    }

    @Test
    void 두_포트가_서로_다른_포트다() {
        assertThat(managementPort).isNotEqualTo(publicPort);
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

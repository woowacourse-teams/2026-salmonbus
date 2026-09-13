package com.gustler.backend.api.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.support.PostgresTestContainer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * 컨트롤러까지 안 오는 요청도 오류 응답 형식을 지키는지 실제 톰캣으로 잰다.
 *
 * <p>MockMvc 로는 못 잡는 것들이다. 주소 해석 실패와 요청 헤더 초과는 서블릿이 돌기 전에
 * 톰캣이 거절하고, {@code TRACE} 는 컨테이너가 {@code /error} 로 넘긴다.
 *
 * <p>날 것 소켓으로 보낸다. 자바 HTTP 클라이언트는 주소를 먼저 정규화해서 깨진 퍼센트 인코딩을
 * 서버까지 보내지 않는다.
 *
 * <p>2026-09-02 PR #56 리뷰에서 나온 조합이다. <b>하나씩 치면 통과하고 겹쳐 치면 깨지던</b>
 * 자리라 조합으로 고정한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestContainer.class)
@ExtendWith(OutputCaptureExtension.class)
class ContainerErrorContractTest {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @LocalServerPort
    private int port;

    @Test
    void 주소의_퍼센트_인코딩이_깨져도_오류_코드와_메시지가_실려_나간다() throws IOException {
        // when 톰캣이 주소를 해석하다 거절해서 서블릿이 아예 안 돈다
        Response actual = send("GET /api/v1/routes/%zz/board HTTP/1.1");

        // then
        assertThat(actual.status).isEqualTo(400);
        assertThat(actual.body).contains("\"code\":\"INVALID_REQUEST\"");
        assertThat(actual.header("Content-Type")).contains("application/json");
        assertThat(actual.header("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void 컨테이너가_error_로_넘긴_응답도_오류_코드와_메시지가_실려_나간다() throws IOException {
        // when 스프링 부트가 이 자리에 기본 오류 컨트롤러를 두면 timestamp·status·error·path 가 나간다
        Response actual = send("TRACE /api/v1/routes HTTP/1.1");

        // then
        assertThat(actual.status).isEqualTo(405);
        assertThat(actual.body).contains("\"code\":\"METHOD_NOT_ALLOWED\"");
        assertThat(actual.body).doesNotContain("\"timestamp\"");
        assertThat(actual.header("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void 요청_헤더가_한도를_넘어도_오류_코드와_메시지가_실려_나간다() throws IOException {
        // given
        String cookies = IntStream.range(0, 250)
            .mapToObj(index -> "c%d=v%d".formatted(index, index))
            .reduce((left, right) -> left + ";" + right)
            .orElseThrow();

        // when
        Response actual = send("GET /api/v1/routes HTTP/1.1", "Cookie: " + cookies);

        // then
        assertThat(actual.status).isEqualTo(400);
        assertThat(actual.body).contains("\"code\":\"INVALID_REQUEST\"");
        assertThat(actual.header("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void JSON_을_안_받는_요청이_다른_오류와_겹쳐도_오류_코드와_메시지가_실려_나간다() throws IOException {
        // when 협상에 맡기면 본문을 만들고도 못 써서 500 에 0바이트가 나간다
        Response actual = send("GET /api/v1/routes/abc/board HTTP/1.1", "Accept: text/plain");

        // then
        assertThat(actual.status).isEqualTo(400);
        assertThat(actual.body).contains("\"code\":\"INVALID_ROUTE_ID\"");
        assertThat(actual.body).contains("\"requestId\"");
    }

    @Test
    void 응답의_요청_식별자로_서버_로그를_찾을_수_있다(CapturedOutput 로그) throws IOException {
        // when 컨테이너가 /error 로 넘긴 요청이라 예외 처리기를 안 지난다
        Response actual = send("TRACE /api/v1/routes HTTP/1.1");

        // then 응답에만 있고 로그에 없으면 그 식별자로 찾을 것이 없다
        assertThat(로그.getOut()).contains(bodyRequestId(actual.body));
    }

    @Test
    void 응답_헤더의_요청_식별자가_본문의_것과_같다() throws IOException {
        // when
        Response actual = send("GET /api/v1/routes/%zz/board HTTP/1.1");

        // then 클라이언트가 받은 식별자로 서버 로그를 찾을 수 있어야 한다
        assertThat(actual.header(RequestId.HEADER))
            .isNotNull()
            .isEqualTo(bodyRequestId(actual.body));
    }

    private static String bodyRequestId(
        String body
    ) {
        final int start = body.indexOf("\"requestId\":\"") + "\"requestId\":\"".length();

        return body.substring(start, body.indexOf('"', start));
    }

    private Response send(
        String requestLine,
        String... headers
    ) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout((int) READ_TIMEOUT.toMillis());
            StringBuilder request = new StringBuilder(requestLine).append("\r\n")
                .append("Host: 127.0.0.1:").append(port).append("\r\n");
            for (String header : headers) {
                request.append(header).append("\r\n");
            }
            request.append("Connection: close\r\n\r\n");

            OutputStream output = socket.getOutputStream();
            output.write(request.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();

            return Response.read(socket);
        }
    }

    private record Response(
        int status,
        List<String> headers,
        String body
    ) {

        static Response read(
            Socket socket
        ) throws IOException {
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String statusLine = reader.readLine();
            List<String> headers = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                headers.add(line);
            }
            StringBuilder body = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }

            return new Response(
                Integer.parseInt(statusLine.split(" ")[1]), headers, body.toString());
        }

        String header(
            String name
        ) {
            return headers.stream()
                .filter(header -> header.regionMatches(true, 0, name + ":", 0, name.length() + 1))
                .map(header -> header.substring(name.length() + 1).trim())
                .findFirst()
                .orElse(null);
        }
    }
}

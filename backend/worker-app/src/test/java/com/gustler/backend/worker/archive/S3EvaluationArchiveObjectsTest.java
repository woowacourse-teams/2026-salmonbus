package com.gustler.backend.worker.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** 실제 SDK 직렬화·서명·재시도·연결 풀을 로컬 HTTP 경계에서 검증한다. AWS에는 접속하지 않는다. */
class S3EvaluationArchiveObjectsTest {
    @TempDir
    Path directory;
    private HttpServer server;
    private ExecutorService executor;
    private S3EvaluationArchiveObjects objects;
    private final Map<String, byte[]> stored = new ConcurrentHashMap<>();
    private final Map<String, String> headers = new ConcurrentHashMap<>();
    private final Set<Integer> connections = ConcurrentHashMap.newKeySet();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile boolean unavailable;
    private volatile boolean ignoreRange;
    private volatile boolean chunked;
    private volatile boolean stallBody;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        objects = client(Duration.ofSeconds(45), Duration.ofSeconds(20));
    }

    @AfterEach
    void stop() {
        objects.close();
        server.stop(0);
        executor.shutdownNow();
    }

    @Test
    void 조건부_쓰기와_SHA256과_팀_태그를_보내고_같은_연결을_재사용한다() throws Exception {
        byte[] expected = "검증할 원본".getBytes(StandardCharsets.UTF_8);
        Path source = Files.write(directory.resolve("source"), expected);

        objects.putIfAbsent("bucket", "rows", source, checksum(expected));
        for (int index = 0; index < 6; index++) {
            Path target = directory.resolve("received-" + index);
            objects.download("bucket", "rows", target, expected.length);
            assertThat(Files.readAllBytes(target)).isEqualTo(expected);
        }

        assertThat(headers).containsEntry("if-none-match", "*")
            .containsEntry("x-amz-checksum-sha256",
                Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(expected)))
            .containsEntry("x-amz-tagging", "Service=techcourse&Role=techcourse-etc&ProjectTeam=salmonbus")
            .containsEntry("range", "bytes=0-" + expected.length);
        assertThat(headers.get("authorization")).startsWith("AWS4-HMAC-SHA256 ");
        assertThat(stored.get("/bucket/rows")).isEqualTo(expected);
        assertThat(requests).hasValue(7);
        assertThat(connections).hasSize(1);
    }

    @Test
    void 이미_존재하는_객체는_다른_내용으로_덮어쓰지_않는다() throws Exception {
        stored.put("/bucket/rows", "원본".getBytes(StandardCharsets.UTF_8));
        Path source = Files.writeString(directory.resolve("source"), "변경된 원본");

        String checksum = checksum(Files.readAllBytes(source));
        assertThatThrownBy(() -> objects.putIfAbsent("bucket", "rows", source, checksum))
            .isInstanceOfSatisfying(S3Exception.class, failure -> assertThat(failure.statusCode()).isEqualTo(412));
        assertThat(new String(stored.get("/bucket/rows"), StandardCharsets.UTF_8)).isEqualTo("원본");
        assertThat(requests).hasValue(1);
    }

    @Test
    void 원본의_검증값을_만든_뒤_업로드_파일이_바뀌면_서버가_거절한다() throws Exception {
        byte[] expected = "예약한 원본".getBytes(StandardCharsets.UTF_8);
        String checksum = checksum(expected);
        Path source = Files.writeString(directory.resolve("source"), "업로드 전에 바뀐 내용");

        assertThatThrownBy(() -> objects.putIfAbsent("bucket", "rows", source, checksum))
            .isInstanceOfSatisfying(S3Exception.class, failure -> assertThat(failure.statusCode()).isEqualTo(400));
        assertThat(headers).containsEntry("x-amz-checksum-sha256", checksum);
        assertThat(stored).isEmpty();
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not-a-sha256-checksum"})
    void 원본_검증값이_없거나_잘못되면_SDK의_파일_검증값_자동_계산으로_대체하지_않는다(String checksum) throws Exception {
        Path source = Files.writeString(directory.resolve("source"), "원본");

        assertThatThrownBy(() -> objects.putIfAbsent("bucket", "rows", source, checksum))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).hasValue(0);
        assertThat(stored).isEmpty();
    }

    @Test
    void Range를_무시하는_과대_응답을_거절하고_연결을_반환한다() {
        stored.put("/bucket/large", new byte[8192]);
        stored.put("/bucket/small", new byte[]{1});
        ignoreRange = true;

        for (int index = 0; index < 4; index++) {
            Path target = directory.resolve("too-large-" + index);
            assertThat(catchThrowable(() -> objects.download("bucket", "large", target, 10))).isNotNull();
            assertThat(target).doesNotExist();
            objects.download("bucket", "small", directory.resolve("small-" + index), 10);
        }
        assertThat(requests).hasValue(8);
    }

    @Test
    void 길이를_알수없는_응답도_상한_한바이트까지만_읽고_거절한다() {
        stored.put("/bucket/rows", new byte[8192]);
        ignoreRange = true;
        chunked = true;
        Path target = directory.resolve("received");

        assertThat(catchThrowable(() -> objects.download("bucket", "rows", target, 10))).isNotNull();
        assertThat(target).doesNotExist();
        assertThat(requests).hasValue(1);
    }

    @Test
    void 내려받을_경로가_심볼릭링크면_기존_파일을_덮어쓰지_않는다() throws Exception {
        stored.put("/bucket/rows", new byte[]{1});
        Path protectedFile = Files.writeString(directory.resolve("protected"), "보존");
        Path target = Files.createSymbolicLink(directory.resolve("received"), protectedFile);

        assertThat(catchThrowable(() -> objects.download("bucket", "rows", target, 10))).isNotNull();
        assertThat(Files.readString(protectedFile)).isEqualTo("보존");
    }

    @Test
    void 서버_오류는_최초_요청을_포함해_두번까지만_시도한다() {
        unavailable = true;

        assertThat(catchThrowable(() -> objects.download("bucket", "rows", directory.resolve("received"), 10)))
            .isNotNull();
        assertThat(requests).hasValue(2);
    }

    @Test
    void 응답_헤더_이후에_본문이_멈춰도_전체_호출_시간_제한이_적용된다() {
        objects.close();
        objects = client(Duration.ofMillis(500), Duration.ofMillis(400));
        stored.put("/bucket/rows", new byte[10]);
        stallBody = true;
        long started = System.nanoTime();

        Throwable failure = catchThrowable(() -> objects.download("bucket", "rows", directory.resolve("received"), 10));

        assertThat(failure).isNotNull();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(directory.resolve("received")).doesNotExist();
    }

    private S3EvaluationArchiveObjects client(Duration callTimeout, Duration attemptTimeout) {
        var builder = S3EvaluationArchiveObjects.clientBuilder()
            .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
            .forcePathStyle(true)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
        builder.overrideConfiguration(builder.overrideConfiguration().toBuilder()
            .apiCallTimeout(callTimeout).apiCallAttemptTimeout(attemptTimeout).build());
        return new S3EvaluationArchiveObjects(builder.build());
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            requests.incrementAndGet();
            connections.add(exchange.getRemoteAddress().getPort());
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.getFirst()));
            String key = exchange.getRequestURI().getPath();
            if (unavailable) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            if (exchange.getRequestMethod().equals("PUT")) {
                byte[] body = exchange.getRequestBody().readAllBytes();
                if (!checksum(body).equals(exchange.getRequestHeaders().getFirst("x-amz-checksum-sha256"))) {
                    byte[] error = "<Error><Code>BadDigest</Code></Error>".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/xml");
                    exchange.sendResponseHeaders(400, error.length);
                    exchange.getResponseBody().write(error);
                    return;
                }
                if (stored.putIfAbsent(key, body) != null) {
                    exchange.sendResponseHeaders(412, -1);
                } else {
                    exchange.sendResponseHeaders(200, -1);
                }
                return;
            }
            byte[] bytes = stored.get(key);
            if (bytes == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (!ignoreRange && range != null) {
                int requested = Integer.parseInt(range.substring("bytes=0-".length())) + 1;
                bytes = java.util.Arrays.copyOf(bytes, Math.min(bytes.length, requested));
            }
            exchange.sendResponseHeaders(ignoreRange ? 200 : 206, chunked ? 0 : bytes.length);
            if (stallBody) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            exchange.getResponseBody().write(bytes);
        }
    }

    private String checksum(byte[] bytes) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

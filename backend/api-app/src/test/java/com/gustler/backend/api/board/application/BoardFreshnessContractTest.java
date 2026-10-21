package com.gustler.backend.api.board.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;

/**
 * 보드가 요구하는 예보의 관측 유효 기간을 소비자 쪽에서 검증한다.
 *
 * <p>예보는 오래된 관측에 예보를 붙이지 않고, 보드는 오래된 관측의 판을 보여주지 않는다.
 * 각각 판단하되 예보 창이 보드 창보다 짧아서는 안 된다. 더 짧으면 두 창 사이의 판이
 * 발행되지 않아, 발행 지연이 해소되어도 보드에서 조회할 최신 발행이 없어 503을 반환한다.
 *
 * <p>두 앱은 별도 프로세스라 한쪽 설정만 바뀌어도 각각 기동할 수 있다. 따라서 worker의
 * 실행 JAR에 포함된 설정을 읽어 빌드 시점에 대조한다. 환경변수로 설정하는 경로는
 * worker가 기동 시 차단한다.
 */
class BoardFreshnessContractTest {
    private static final String WORKER_JAR = "worker.boot.jar";
    private static final String WORKER_CONFIG = "BOOT-INF/classes/application.yml";
    private static final String STALENESS = "forecast.staleness";

    @Test
    void 예보_신선도_창은_보드가_약속한_창보다_짧지_않다() {
        // when
        Duration forecastWindow = DurationStyle.detectAndParse(workerStaleness());

        // then
        assertThat(forecastWindow)
            .as("예보 창이 보드 창보다 짧으면 그 사이 판이 발행되지 않는다")
            .isGreaterThanOrEqualTo(BoardFreshnessPolicy.FRESHNESS_WINDOW);
    }

    @Test
    void 예보_신선도_창을_설정_파일_밖에서_정하지_않는다() {
        // when
        String staleness = workerStaleness();

        // then
        assertThat(staleness)
            .as("자리표시자를 두면 환경변수가 이 검사를 지나친다")
            .doesNotContain("${");
    }

    private static String workerStaleness() {
        Path jarPath = Path.of(System.getProperty(WORKER_JAR, ""));
        assertThat(Files.isRegularFile(jarPath))
            .as("worker 의 bootJar 를 못 찾았다(-D%s). 이 테스트는 ./gradlew test 로 돌린다", WORKER_JAR)
            .isTrue();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(WORKER_CONFIG);
            assertThat(entry).as("worker 실행 JAR 에 %s 가 없다", WORKER_CONFIG).isNotNull();
            try (InputStream configuration = jar.getInputStream(entry)) {
                List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                    .load("worker", new ByteArrayResource(configuration.readAllBytes()));
                Object value = loaded.getFirst().getProperty(STALENESS);
                assertThat(value).as("worker 설정에 %s 가 없다", STALENESS).isNotNull();
                return value.toString();
            }
        } catch (IOException e) {
            throw new IllegalStateException("worker 실행 JAR 을 읽지 못했다: " + jarPath, e);
        }
    }
}
